/**
 * Pushed bills (td_receipt): one per trans_id, inserted once, never updated (CLAUDE.md §5).
 *  - no luggage row (or another tenant's)  → BILL_WITHOUT_LUGGAGE / TENANT_MISMATCH, not stored
 *  - same trans_id already billed          → identical re-push: ACCEPTED no-op; else DUPLICATE_BILL
 *  - bill_no already used that day         → DUPLICATE_BILL_NO, not stored
 *  - server recomputation differs          → bill KEPT, AMOUNT_MISMATCH logged (also
 *                                            CHECKOUT_MISSING / NO_RATE when it cannot recompute)
 */
import type { RowDataPacket } from 'mysql2/promise';
import type { RequestContext } from '../../core/context';
import { raw, save, type Executor, type Row } from '../../core/db/dml';
import { toPaise } from '../../core/utils/money';
import { parseDocNo, periodKeyOfDate } from '../../domain/numbering';
import { checkBill } from './billCheck';
import type { CounterCollector } from './counters.collector';
import { checkRefs, type PushRefs } from './sync.refs';
import { receiptRow, type ReceiptRow } from './sync.schema';
import { accepted, dupKeyName, exception, isFatalDbError, type RowResult } from './sync.results';

const TABLE = 'td_receipt';

async function byTransId(table: string, transIds: string[], conn: Executor, lock: boolean) {
  if (!transIds.length) return new Map<string, Row>();
  const rows = await raw<RowDataPacket[]>(
    `SELECT * FROM ${table} WHERE trans_id IN (?)${lock ? ' FOR UPDATE' : ''}`,
    [transIds],
    conn,
  );
  return new Map(rows.map((r) => [String(r.trans_id), r as Row]));
}

/** Same bill pushed again (device retry) — compared on its identity and total. */
function sameBill(stored: Row, row: ReceiptRow): boolean {
  return (
    stored.bill_no === row.bill_no &&
    stored.bill_dt === row.bill_dt &&
    toPaise(String(stored.net_amt)) === toPaise(row.net_amt)
  );
}

export async function mergeReceiptChunk(
  rawRows: Record<string, unknown>[],
  refs: PushRefs,
  counters: CounterCollector,
  ctx: RequestContext,
  conn: Executor,
): Promise<RowResult[]> {
  const results: RowResult[] = [];
  const parsed = rawRows.map((r) => ({ raw: r, row: receiptRow.safeParse(r) }));
  const ids = [...new Set(parsed.flatMap((p) => (p.row.success ? [p.row.data.trans_id] : [])))];
  const luggage = await byTransId('td_luggage_in_out', ids, conn, true);
  const bills = await byTransId(TABLE, ids, conn, true);

  for (const { raw: rawRow, row: result } of parsed) {
    if (!result.success) {
      const detail = result.error.issues.map((i) => `${i.path.join('.')}: ${i.message}`).join('; ');
      results.push(await exception(TABLE, rawRow, 'VALIDATION_ERROR', detail, ctx, conn));
      continue;
    }
    const row = result.data;
    const badRef = checkRefs(refs, { users: [row.user_id], shifts: [row.shift_id] });
    if (badRef) {
      results.push(await exception(TABLE, rawRow, 'INVALID_REFERENCE', badRef, ctx, conn));
      continue;
    }
    try {
      results.push(await mergeOne(rawRow, row, luggage, bills, refs, counters, ctx, conn));
    } catch (err) {
      if (isFatalDbError(err)) throw err;
      const code = (err as { code?: string }).code;
      results.push(await exception(TABLE, rawRow, 'DB_ERROR', String(code), ctx, conn));
    }
  }
  return results;
}

async function mergeOne(
  rawRow: Record<string, unknown>,
  row: ReceiptRow,
  luggage: Map<string, Row>,
  bills: Map<string, Row>,
  refs: PushRefs,
  counters: CounterCollector,
  ctx: RequestContext,
  conn: Executor,
): Promise<RowResult> {
  const bag = luggage.get(row.trans_id);
  if (!bag) {
    return exception(
      TABLE,
      rawRow,
      'BILL_WITHOUT_LUGGAGE',
      'no luggage row for this trans_id',
      ctx,
      conn,
    );
  }
  if (bag.customer_id !== ctx.customerId) {
    return exception(
      TABLE,
      rawRow,
      'TENANT_MISMATCH',
      'trans_id belongs to another customer',
      ctx,
      conn,
    );
  }

  const stored = bills.get(row.trans_id);
  if (stored) {
    return sameBill(stored, row)
      ? accepted(TABLE, rawRow)
      : exception(
          TABLE,
          rawRow,
          'DUPLICATE_BILL',
          `already billed as ${String(stored.bill_no)}`,
          ctx,
          conn,
          {
            existing: { bill_no: stored.bill_no, bill_dt: stored.bill_dt, net_amt: stored.net_amt },
          },
        );
  }

  const { local_version: _lv, ...data } = row;
  try {
    await save(TABLE, data, { flag: 'I' }, ctx, conn);
  } catch (err) {
    const key = dupKeyName(err);
    if (key === 'uq_receipt_bill') {
      return exception(
        TABLE,
        rawRow,
        'DUPLICATE_BILL_NO',
        `${row.bill_no} on ${row.bill_dt} is already used`,
        ctx,
        conn,
      );
    }
    if (key !== 'PRIMARY') throw err;
    // Billed concurrently by another push: treat like an already stored bill.
    const now = (await byTransId(TABLE, [row.trans_id], conn, true)).get(row.trans_id);
    if (now && sameBill(now, row)) return accepted(TABLE, rawRow);
    return exception(
      TABLE,
      rawRow,
      'DUPLICATE_BILL',
      'billed concurrently by another push',
      ctx,
      conn,
    );
  }
  bills.set(row.trans_id, { ...data });

  const doc = parseDocNo(row.bill_no);
  if (doc) {
    counters.add({
      userId: row.user_id,
      series: 'B',
      periodKey: periodKeyOfDate(refs.resetModeFor(row.user_id), row.bill_dt),
      no: doc.no,
    });
  }

  const check = checkBill(row, bag, refs.rates, refs.gst, refs.gstTypeFor(row.user_id));
  if (check.ok) return accepted(TABLE, rawRow);
  // The bill stays (money was collected); the admin reviews the difference.
  return exception(
    TABLE,
    rawRow,
    check.code,
    check.detail,
    ctx,
    conn,
    'expected' in check ? { expected: check.expected } : undefined,
  );
}

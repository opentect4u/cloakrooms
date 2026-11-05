/**
 * Merge of pushed td_luggage_in_out rows (CLAUDE.md §5), one chunk per transaction.
 *
 *  - new trans_id → INSERT (customer from the token, ID masked if the proof says so);
 *  - existing → row locked; check-in fields are write-once (ignored), check-out fields are
 *    fill-only, out_flag never goes back from Y; updated_at moves only on an effective change,
 *    so an identical re-push is a no-op;
 *  - QR check-out first (Case 1): a device that checked a bag out from its QR code does not know
 *    the check-in device, shift or ID proof, so its row may arrive with those NULL. They are the
 *    only check-in fields that are fill-only: the later check-in push fills them once;
 *  - a different check-out for an already checked-out bag → DUPLICATE_CHECKOUT exception.
 *
 * Plain INSERT instead of the §5 `ON DUPLICATE KEY UPDATE`: ODKU also fires on the secondary
 * UNIQUE (customer_id, receipt_no, receipt_dt) and would then write check-out data onto another
 * transaction's row. A PK race (two devices inserting the same new trans_id) is caught and
 * re-run as a merge, which gives the same "one cloud row" result.
 */
import type { RowDataPacket } from 'mysql2/promise';
import type { RequestContext } from '../../core/context';
import { raw, save, type Executor, type Row } from '../../core/db/dml';
import { maskIdNumber } from '../../domain/masking';
import { parseDocNo, periodKeyOfDate } from '../../domain/numbering';
import { checkRefs, type PushRefs } from './sync.refs';
import { luggageRow, type LuggageRow } from './sync.schema';
import { accepted, dupKeyName, exception, isFatalDbError, type RowResult } from './sync.results';
import type { CounterCollector } from './counters.collector';

const TABLE = 'td_luggage_in_out';
const OUT_FIELDS = ['user_id_out', 'device_id_out', 'shift_id_out', 'date_time_out'] as const;
/** Check-in fields missing from a QR check-out row: filled once, while still NULL. */
const IN_FILL_FIELDS = ['device_id_in', 'shift_id_in'] as const;

type Existing = Row & { trans_id: string; customer_id: number; is_deleted: 'Y' | 'N' };

async function lockExisting(transIds: string[], conn: Executor): Promise<Map<string, Existing>> {
  if (!transIds.length) return new Map();
  // Not tenant-scoped on purpose: a trans_id owned by another tenant must be detected, not merged.
  const rows = await raw<(Existing & RowDataPacket)[]>(
    `SELECT * FROM ${TABLE} WHERE trans_id IN (?) FOR UPDATE`,
    [transIds],
    conn,
  );
  return new Map(rows.map((r) => [r.trans_id, r]));
}

/** Check-out fields to fill, or a conflict description. */
export function checkoutPatch(
  existing: Row,
  incoming: LuggageRow,
): { patch: Row } | { conflict: string } {
  if (existing.date_time_out !== null && incoming.date_time_out !== null) {
    const differs = OUT_FIELDS.filter(
      (f) =>
        existing[f] !== null && incoming[f] !== null && String(existing[f]) !== String(incoming[f]),
    );
    if (differs.length) return { conflict: `already checked out (${differs.join(', ')} differ)` };
  }
  const patch: Row = {};
  for (const f of OUT_FIELDS) {
    if (existing[f] === null && incoming[f] !== null) patch[f] = incoming[f];
  }
  if (existing.out_flag !== 'Y' && incoming.out_flag === 'Y') patch.out_flag = 'Y';
  return { patch };
}

/** Check-in details the stored row lacks (QR check-out pushed first) and this push brings. */
export function checkinFillPatch(existing: Row, incoming: LuggageRow, refs: PushRefs): Row {
  const patch: Row = {};
  for (const f of IN_FILL_FIELDS) {
    if (existing[f] === null && incoming[f] !== null) patch[f] = incoming[f];
  }
  if (existing.id_proof_id === null && incoming.id_proof_id !== null) {
    patch.id_proof_id = incoming.id_proof_id;
    patch.id_number = storedIdNumber(incoming, refs);
  }
  return patch;
}

/** ID number as stored: last 4 only when the proof is masked (Aadhaar). */
function storedIdNumber(row: LuggageRow, refs: PushRefs): string | null {
  if (row.id_number === null || row.id_proof_id === null) return row.id_number;
  return refs.idProofs.get(row.id_proof_id)?.mask ? maskIdNumber(row.id_number) : row.id_number;
}

export async function mergeLuggageChunk(
  rawRows: Record<string, unknown>[],
  refs: PushRefs,
  counters: CounterCollector,
  ctx: RequestContext,
  conn: Executor,
): Promise<RowResult[]> {
  const results: RowResult[] = [];
  const parsed = rawRows.map((r) => ({ raw: r, row: luggageRow.safeParse(r) }));
  const existing = await lockExisting(
    [...new Set(parsed.flatMap((p) => (p.row.success ? [p.row.data.trans_id] : [])))],
    conn,
  );

  for (const { raw: rawRow, row: result } of parsed) {
    if (!result.success) {
      const detail = result.error.issues.map((i) => `${i.path.join('.')}: ${i.message}`).join('; ');
      results.push(await exception(TABLE, rawRow, 'VALIDATION_ERROR', detail, ctx, conn));
      continue;
    }
    const row = result.data;
    const badRef = checkRefs(refs, {
      users: [row.user_id_in, row.user_id_out],
      shifts: [row.shift_id_in, row.shift_id_out],
      idProof: row.id_proof_id ?? undefined,
    });
    if (badRef) {
      results.push(await exception(TABLE, rawRow, 'INVALID_REFERENCE', badRef, ctx, conn));
      continue;
    }

    try {
      results.push(await mergeOne(rawRow, row, existing, refs, counters, ctx, conn));
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
  row: LuggageRow,
  existing: Map<string, Existing>,
  refs: PushRefs,
  counters: CounterCollector,
  ctx: RequestContext,
  conn: Executor,
): Promise<RowResult> {
  let current = existing.get(row.trans_id);

  if (!current) {
    const { local_version: _lv, ...rest } = row;
    const data = { ...rest, id_number: storedIdNumber(row, refs) };
    try {
      await save(TABLE, data, { flag: 'I' }, ctx, conn);
    } catch (err) {
      const key = dupKeyName(err);
      if (key === 'uq_luggage_receipt') {
        return exception(
          TABLE,
          rawRow,
          'DUPLICATE_RECEIPT_NO',
          `${row.receipt_no} on ${row.receipt_dt} belongs to another transaction`,
          ctx,
          conn,
        );
      }
      if (key !== 'PRIMARY') throw err;
      // Another device inserted this trans_id meanwhile: merge into its row instead.
      current = (await lockExisting([row.trans_id], conn)).get(row.trans_id);
      if (!current) throw err;
    }
    if (!current) {
      existing.set(row.trans_id, {
        ...data,
        customer_id: ctx.customerId,
        is_deleted: 'N',
      } as Existing);
      collectReceiptNo(row, refs, counters);
      return accepted(TABLE, rawRow);
    }
    existing.set(row.trans_id, current);
  }

  if (current.customer_id !== ctx.customerId) {
    return exception(
      TABLE,
      rawRow,
      'TENANT_MISMATCH',
      'trans_id belongs to another customer',
      ctx,
      conn,
    );
  }

  const outcome = checkoutPatch(current, row);
  if ('conflict' in outcome) {
    return exception(TABLE, rawRow, 'DUPLICATE_CHECKOUT', outcome.conflict, ctx, conn, {
      existing: Object.fromEntries(
        ['user_id_out', 'device_id_out', 'date_time_out'].map((k) => [k, current![k]]),
      ),
    });
  }
  const patch = { ...checkinFillPatch(current, row, refs), ...outcome.patch };
  if (Object.keys(patch).length) {
    await save(
      TABLE,
      patch,
      { flag: 'U', where: { trans_id: row.trans_id, is_deleted: current.is_deleted } },
      ctx,
      conn,
    );
    existing.set(row.trans_id, { ...current, ...patch });
  }
  return accepted(TABLE, rawRow);
}

function collectReceiptNo(row: LuggageRow, refs: PushRefs, counters: CounterCollector) {
  const doc = parseDocNo(row.receipt_no);
  if (!doc) return;
  counters.add({
    userId: row.user_id_in,
    series: 'R',
    periodKey: periodKeyOfDate(refs.resetModeFor(row.user_id_in), row.receipt_dt),
    no: doc.no,
  });
}

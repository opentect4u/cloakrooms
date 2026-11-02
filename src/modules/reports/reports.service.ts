/**
 * Reports (CLAUDE.md §12). Hand-written SQL through raw(): every query is bound to ONE customer
 * explicitly (raw bypasses DML scoping) — the token's, or `customer_id` for a super admin.
 * Date ranges are IST calendar days: DATETIME columns (UTC) use [from 00:00 IST, to+1 00:00 IST);
 * DATE columns (bill_dt, receipt_dt) are already IST dates. Row timestamps also come as *_ist.
 * All SQL fragments below are constants; only values are bound as parameters.
 */
import type { RowDataPacket } from 'mysql2/promise';
import type { RequestContext } from '../../core/context';
import { fetchOne, raw, save } from '../../core/db/dml';
import { ApiError } from '../../core/http/ApiError';
import { addDays, istDayStartUtc, toUtcString } from '../../core/utils/time';
import type { BagsInStoreQuery, ExceptionsQuery, RangeQuery } from './reports.schema';

const IST = (col: string, alias: string) => `${col} + INTERVAL 330 MINUTE AS ${alias}`;
const SUM_INT = (expr: string) => `CAST(COALESCE(SUM(${expr}), 0) AS UNSIGNED)`;
const SUM_MONEY = (expr: string) => `COALESCE(SUM(${expr}), 0.00)`;

class Where {
  readonly parts: string[] = [];
  readonly params: unknown[] = [];
  add(sql: string, ...params: unknown[]): this {
    this.parts.push(sql);
    this.params.push(...params);
    return this;
  }
  /** Adds the condition only when the value is given. */
  opt(sql: string, value: unknown): this {
    return value === undefined ? this : this.add(sql, value);
  }
  get sql(): string {
    return this.parts.join(' AND ');
  }
}

/** The single customer a report covers. */
function customerOf(ctx: RequestContext, requested?: number): number {
  if (ctx.userType !== 'S') return ctx.customerId as number;
  if (!requested) {
    throw ApiError.validation([{ path: 'customer_id', message: 'Required for super admin' }]);
  }
  return requested;
}

/** IST date range → UTC half-open interval for DATETIME columns. */
function utcRange(from: string, to: string): [string, string] {
  return [istDayStartUtc(from), istDayStartUtc(addDays(to, 1))];
}

interface PagedSpec {
  select: string;
  from: string;
  where: Where;
  groupBy?: string;
  orderBy: string;
  totals: string;
  page: number;
  limit: number;
}

async function paged(spec: PagedSpec) {
  const base = `FROM ${spec.from} WHERE ${spec.where.sql}${spec.groupBy ? ` GROUP BY ${spec.groupBy}` : ''}`;
  const params = spec.where.params;
  const [rows, count, totals] = await Promise.all([
    raw<RowDataPacket[]>(
      `SELECT ${spec.select} ${base} ORDER BY ${spec.orderBy} LIMIT ? OFFSET ?`,
      [...params, spec.limit, (spec.page - 1) * spec.limit],
    ),
    raw<RowDataPacket[]>(
      spec.groupBy
        ? `SELECT COUNT(*) AS n FROM (SELECT 1 ${base}) AS grp`
        : `SELECT COUNT(*) AS n ${base}`,
      params,
    ),
    raw<RowDataPacket[]>(`SELECT ${spec.totals} FROM ${spec.from} WHERE ${spec.where.sql}`, params),
  ]);
  return {
    rows,
    meta: {
      page: spec.page,
      limit: spec.limit,
      total: Number(count[0]?.n ?? 0),
      totals: totals[0] ?? {},
    },
  };
}

const RECEIPT_TOTALS = [
  'COUNT(*) AS bills',
  `${SUM_INT('r.luggage_qty')} AS bags`,
  `${SUM_MONEY('r.base_amt')} AS base_amt`,
  `${SUM_MONEY('r.penal_amt')} AS penal_amt`,
  `${SUM_MONEY('r.net_amt')} AS net_amt`,
  `${SUM_MONEY("CASE WHEN r.pay_mode = 'C' THEN r.net_amt ELSE 0 END")} AS cash_amt`,
  `${SUM_MONEY("CASE WHEN r.pay_mode = 'U' THEN r.net_amt ELSE 0 END")} AS upi_amt`,
].join(', ');

/** Bills of the range (bill_dt) with the common user / shift / pay-mode filters. */
function receiptWhere(ctx: RequestContext, q: RangeQuery): Where {
  return new Where()
    .add('r.customer_id = ?', customerOf(ctx, q.customer_id))
    .add("r.is_deleted = 'N'")
    .add('r.bill_dt BETWEEN ? AND ?', q.from, q.to)
    .opt('r.user_id = ?', q.user_id)
    .opt('r.shift_id = ?', q.shift_id)
    .opt('r.pay_mode = ?', q.pay_mode);
}

/** Day-wise collection. TODO(open-point): final summary fields pending business sign-off. */
export function collectionSummary(ctx: RequestContext, q: RangeQuery) {
  return paged({
    select: `r.bill_dt, ${RECEIPT_TOTALS}`,
    from: 'td_receipt r',
    where: receiptWhere(ctx, q),
    groupBy: 'r.bill_dt',
    orderBy: 'r.bill_dt',
    totals: RECEIPT_TOTALS,
    page: q.page,
    limit: q.limit,
  });
}

export function gstReport(ctx: RequestContext, q: RangeQuery) {
  const sums = [
    'COUNT(*) AS bills',
    `${SUM_MONEY('r.taxable_amt')} AS taxable_amt`,
    `${SUM_MONEY('r.cgst')} AS cgst`,
    `${SUM_MONEY('r.sgst')} AS sgst`,
    `${SUM_MONEY('r.igst')} AS igst`,
    `${SUM_MONEY('r.net_amt')} AS net_amt`,
  ].join(', ');
  return paged({
    select: `r.bill_dt, r.gst_pct, ${sums}`,
    from: 'td_receipt r',
    where: receiptWhere(ctx, q),
    groupBy: 'r.bill_dt, r.gst_pct',
    orderBy: 'r.bill_dt, r.gst_pct',
    totals: sums,
    page: q.page,
    limit: q.limit,
  });
}

export function userShiftCollection(ctx: RequestContext, q: RangeQuery) {
  return paged({
    select: `r.user_id, u.user_name, u.user_code, r.shift_id, s.shift_name, ${RECEIPT_TOTALS}`,
    from: 'td_receipt r JOIN md_user u ON u.id = r.user_id LEFT JOIN md_shift s ON s.shift_id = r.shift_id',
    where: receiptWhere(ctx, q),
    groupBy: 'r.user_id, u.user_name, u.user_code, r.shift_id, s.shift_name',
    orderBy: 'u.user_name, r.user_id, s.shift_name',
    totals: RECEIPT_TOTALS,
    page: q.page,
    limit: q.limit,
  });
}

export function unprintedBills(ctx: RequestContext, q: RangeQuery) {
  return paged({
    select: [
      'r.trans_id, r.bill_no, r.bill_dt, r.user_id, u.user_name, r.shift_id, r.device_id',
      'r.luggage_qty, r.net_amt, r.pay_mode, r.upi_ref, l.receipt_no, l.pasn_name, l.pasn_mob_no',
      IST('r.created_at', 'synced_at_ist'),
    ].join(', '),
    from: 'td_receipt r JOIN md_user u ON u.id = r.user_id JOIN td_luggage_in_out l ON l.trans_id = r.trans_id',
    where: receiptWhere(ctx, q).add("r.bill_printed = 'N'"),
    orderBy: 'r.bill_dt, r.bill_no',
    totals: `COUNT(*) AS bills, ${SUM_MONEY('r.net_amt')} AS net_amt`,
    page: q.page,
    limit: q.limit,
  });
}

const LUGGAGE_COLUMNS = [
  'l.trans_id, l.receipt_no, l.receipt_dt',
  `l.date_time_in, ${IST('l.date_time_in', 'date_time_in_ist')}`,
  'l.user_id_in, ui.user_name AS user_name_in, l.shift_id_in, si.shift_name AS shift_name_in',
  'l.pasn_name, l.pasn_mob_no, l.pnr_no, l.luggage_qty, l.id_proof_id, p.id_proof_name, l.id_number',
  'l.out_flag',
].join(', ');
const LUGGAGE_JOINS = `td_luggage_in_out l
  LEFT JOIN md_user ui ON ui.id = l.user_id_in
  LEFT JOIN md_shift si ON si.shift_id = l.shift_id_in
  LEFT JOIN md_id_proof p ON p.id_proof_id = l.id_proof_id`;

export function checkInRegister(ctx: RequestContext, q: RangeQuery) {
  const [fromUtc, toUtc] = utcRange(q.from, q.to);
  return paged({
    select: `${LUGGAGE_COLUMNS}, l.date_time_out, ${IST('l.date_time_out', 'date_time_out_ist')}`,
    from: LUGGAGE_JOINS,
    where: new Where()
      .add('l.customer_id = ?', customerOf(ctx, q.customer_id))
      .add("l.is_deleted = 'N'")
      .add('l.date_time_in >= ? AND l.date_time_in < ?', fromUtc, toUtc)
      .opt('l.user_id_in = ?', q.user_id)
      .opt('l.shift_id_in = ?', q.shift_id),
    orderBy: 'l.date_time_in, l.trans_id',
    totals: `COUNT(*) AS bags_entries, ${SUM_INT('l.luggage_qty')} AS bags`,
    page: q.page,
    limit: q.limit,
  });
}

export function checkOutRegister(ctx: RequestContext, q: RangeQuery) {
  const [fromUtc, toUtc] = utcRange(q.from, q.to);
  return paged({
    select: [
      LUGGAGE_COLUMNS,
      `l.date_time_out, ${IST('l.date_time_out', 'date_time_out_ist')}`,
      'l.user_id_out, uo.user_name AS user_name_out, l.shift_id_out, so.shift_name AS shift_name_out',
      'r.bill_no, r.bill_dt, r.days_charged, r.net_amt, r.pay_mode, r.bill_printed',
    ].join(', '),
    from: `${LUGGAGE_JOINS}
      LEFT JOIN md_user uo ON uo.id = l.user_id_out
      LEFT JOIN md_shift so ON so.shift_id = l.shift_id_out
      LEFT JOIN td_receipt r ON r.trans_id = l.trans_id AND r.is_deleted = 'N'`,
    where: new Where()
      .add('l.customer_id = ?', customerOf(ctx, q.customer_id))
      .add("l.is_deleted = 'N'")
      .add('l.date_time_out >= ? AND l.date_time_out < ?', fromUtc, toUtc)
      .opt('l.user_id_out = ?', q.user_id)
      .opt('l.shift_id_out = ?', q.shift_id)
      .opt('r.pay_mode = ?', q.pay_mode),
    orderBy: 'l.date_time_out, l.trans_id',
    totals: `COUNT(*) AS bags_entries, ${SUM_INT('l.luggage_qty')} AS bags, ${SUM_MONEY('r.net_amt')} AS net_amt`,
    page: q.page,
    limit: q.limit,
  });
}

export function bagsInStore(ctx: RequestContext, q: BagsInStoreQuery) {
  const where = new Where()
    .add('l.customer_id = ?', customerOf(ctx, q.customer_id))
    .add("l.is_deleted = 'N'")
    .add("l.out_flag = 'N'")
    .opt('l.user_id_in = ?', q.user_id)
    .opt('l.shift_id_in = ?', q.shift_id);
  if (q.from) where.add('l.date_time_in >= ?', istDayStartUtc(q.from));
  if (q.to) where.add('l.date_time_in < ?', istDayStartUtc(addDays(q.to, 1)));
  return paged({
    select: `${LUGGAGE_COLUMNS}, TIMESTAMPDIFF(MINUTE, l.date_time_in, UTC_TIMESTAMP()) AS minutes_stored`,
    from: LUGGAGE_JOINS,
    where,
    orderBy: 'l.date_time_in, l.trans_id',
    totals: `COUNT(*) AS bags_entries, ${SUM_INT('l.luggage_qty')} AS bags`,
    page: q.page,
    limit: q.limit,
  });
}

export function exceptionsReport(ctx: RequestContext, q: ExceptionsQuery) {
  const where = new Where()
    .add('e.customer_id = ?', customerOf(ctx, q.customer_id))
    .opt('e.resolved_flag = ?', q.resolved)
    .opt('e.table_name = ?', q.table_name);
  if (q.from) where.add('e.created_at >= ?', istDayStartUtc(q.from));
  if (q.to) where.add('e.created_at < ?', istDayStartUtc(addDays(q.to, 1)));
  return paged({
    select: [
      'e.id, e.trans_id, e.table_name, e.reason, e.payload, e.user_id, u.user_name, e.device_id',
      'e.resolved_flag, e.resolved_by, e.resolved_at, e.created_at',
      IST('e.created_at', 'created_at_ist'),
    ].join(', '),
    from: 'td_sync_exception e LEFT JOIN md_user u ON u.id = e.user_id',
    where,
    orderBy: 'e.created_at DESC, e.id DESC',
    totals: `COUNT(*) AS exceptions, ${SUM_INT("e.resolved_flag = 'N'")} AS unresolved`,
    page: q.page,
    limit: q.limit,
  });
}

/** Marks a sync exception as reviewed (idempotent). */
export async function resolveException(ctx: RequestContext, id: number) {
  const existing = await fetchOne('td_sync_exception', { id }, ctx);
  if (!existing) throw ApiError.notFound('Exception not found');
  if (existing.resolved_flag !== 'Y') {
    await save(
      'td_sync_exception',
      { resolved_flag: 'Y', resolved_by: ctx.loginId, resolved_at: toUtcString() },
      { flag: 'U', where: { id, resolved_flag: 'N' } },
      ctx,
    );
  }
  return fetchOne('td_sync_exception', { id }, ctx);
}

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

/** Stored DATETIMEs are IST (migration 009); an `_ist` alias is returned as stored, unconverted. */
const IST = (col: string, alias: string) => `${col} AS ${alias}`;
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
  /** Parameters of placeholders inside `from` (a derived table), bound before `where`'s. */
  fromParams?: unknown[];
  where: Where;
  groupBy?: string;
  orderBy: string;
  totals: string;
  page: number;
  limit: number;
}

async function paged(spec: PagedSpec) {
  const base = `FROM ${spec.from} WHERE ${spec.where.sql}${spec.groupBy ? ` GROUP BY ${spec.groupBy}` : ''}`;
  const params = [...(spec.fromParams ?? []), ...spec.where.params];
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

/**
 * Money movements of the range, one row per bill (on bill_dt) and per advance (on the check-in
 * date receipt_dt, by the check-in user/shift; Phase 13). An advance is cash taken at check-in;
 * a bill collects bal_amt = max(0, net_amt − adv_amt) in its pay mode. So the cash in hand of a
 * day = advances taken + balances collected that day.
 */
function movements(ctx: RequestContext, q: RangeQuery): { sql: string; params: unknown[] } {
  const bills = receiptWhere(ctx, q);
  const advances = new Where()
    .add('l.customer_id = ?', customerOf(ctx, q.customer_id))
    .add("l.is_deleted = 'N'")
    .add('l.adv_amt > 0')
    .add('l.receipt_dt BETWEEN ? AND ?', q.from, q.to)
    .opt('l.user_id_in = ?', q.user_id)
    .opt('l.shift_id_in = ?', q.shift_id);
  if (q.pay_mode === 'U') advances.add('FALSE');
  const sql = `(
    SELECT r.bill_dt AS day, r.user_id, r.shift_id, 1 AS bills, r.luggage_qty AS bags,
           r.base_amt, r.penal_amt, r.net_amt, r.adv_amt, r.bal_amt, 0.00 AS adv_collected,
           CASE WHEN r.pay_mode = 'C' THEN r.bal_amt ELSE 0.00 END AS cash_amt,
           CASE WHEN r.pay_mode = 'U' THEN r.bal_amt ELSE 0.00 END AS upi_amt
      FROM td_receipt r WHERE ${bills.sql}
    UNION ALL
    SELECT l.receipt_dt, l.user_id_in, l.shift_id_in, 0, 0,
           0.00, 0.00, 0.00, 0.00, 0.00, l.adv_amt, l.adv_amt, 0.00
      FROM td_luggage_in_out l WHERE ${advances.sql}
  ) m`;
  return { sql, params: [...bills.params, ...advances.params] };
}

/**
 * bills/bags/base/penal/net: bills of the day · adv_amt: advances subtracted on those bills ·
 * bal_amt: collected at check-out · adv_collected: advances taken at check-in that day ·
 * cash_amt + upi_amt = collected_amt = adv_collected + bal_amt.
 */
const MOVEMENT_TOTALS = [
  `${SUM_INT('m.bills')} AS bills`,
  `${SUM_INT('m.bags')} AS bags`,
  `${SUM_MONEY('m.base_amt')} AS base_amt`,
  `${SUM_MONEY('m.penal_amt')} AS penal_amt`,
  `${SUM_MONEY('m.net_amt')} AS net_amt`,
  `${SUM_MONEY('m.adv_amt')} AS adv_amt`,
  `${SUM_MONEY('m.bal_amt')} AS bal_amt`,
  `${SUM_MONEY('m.adv_collected')} AS adv_collected`,
  `${SUM_MONEY('m.cash_amt')} AS cash_amt`,
  `${SUM_MONEY('m.upi_amt')} AS upi_amt`,
  `${SUM_MONEY('m.cash_amt + m.upi_amt')} AS collected_amt`,
].join(', ');

/** Day-wise collection. TODO(open-point): final summary fields pending business sign-off. */
export function collectionSummary(ctx: RequestContext, q: RangeQuery) {
  const m = movements(ctx, q);
  return paged({
    select: `m.day AS bill_dt, ${MOVEMENT_TOTALS}`,
    from: m.sql,
    fromParams: m.params,
    where: new Where().add('TRUE'),
    groupBy: 'm.day',
    orderBy: 'm.day',
    totals: MOVEMENT_TOTALS,
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
  const m = movements(ctx, q);
  return paged({
    select: `m.user_id, u.user_name, u.user_code, m.shift_id, s.shift_name, ${MOVEMENT_TOTALS}`,
    from: `${m.sql} JOIN md_user u ON u.id = m.user_id LEFT JOIN md_shift s ON s.shift_id = m.shift_id`,
    fromParams: m.params,
    where: new Where().add('TRUE'),
    groupBy: 'm.user_id, u.user_name, u.user_code, m.shift_id, s.shift_name',
    orderBy: 'u.user_name, m.user_id, s.shift_name',
    totals: MOVEMENT_TOTALS,
    page: q.page,
    limit: q.limit,
  });
}

export function unprintedBills(ctx: RequestContext, q: RangeQuery) {
  return paged({
    select: [
      'r.trans_id, r.bill_no, r.bill_dt, r.user_id, u.user_name, r.shift_id, r.device_id',
      'r.luggage_qty, l.extra_lugage_qty, r.net_amt, r.adv_amt, r.bal_amt, r.pay_mode, r.upi_ref',
      'l.receipt_no, l.pasn_name, l.pasn_mob_no',
      IST('r.created_at', 'synced_at_ist'),
    ].join(', '),
    from: 'td_receipt r JOIN md_user u ON u.id = r.user_id JOIN td_luggage_in_out l ON l.trans_id = r.trans_id',
    where: receiptWhere(ctx, q).add("r.bill_printed = 'N'"),
    orderBy: 'r.bill_dt, r.bill_no',
    totals: `COUNT(*) AS bills, ${SUM_MONEY('r.net_amt')} AS net_amt, ${SUM_MONEY('r.bal_amt')} AS bal_amt`,
    page: q.page,
    limit: q.limit,
  });
}

const LUGGAGE_COLUMNS = [
  'l.trans_id, l.receipt_no, l.receipt_dt',
  `l.date_time_in, ${IST('l.date_time_in', 'date_time_in_ist')}`,
  'l.user_id_in, ui.user_name AS user_name_in, l.shift_id_in, si.shift_name AS shift_name_in',
  'l.pasn_name, l.pasn_mob_no, l.pnr_no, l.luggage_qty, l.extra_lugage_qty',
  'l.adv_amt, l.adv_hours, l.id_proof_id, p.id_proof_name, l.id_number',
  'l.out_flag',
].join(', ');
/** Check-in register / bags in store: entries, bags, free extra bags, advances taken. */
const LUGGAGE_TOTALS = [
  `COUNT(*) AS bags_entries, ${SUM_INT('l.luggage_qty')} AS bags`,
  `${SUM_INT('l.extra_lugage_qty')} AS extra_bags, ${SUM_MONEY('l.adv_amt')} AS adv_amt`,
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
    totals: LUGGAGE_TOTALS,
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
      'r.bill_no, r.bill_dt, r.days_charged, r.net_amt, r.adv_amt AS bill_adv_amt, r.bal_amt',
      'r.pay_mode, r.bill_printed',
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
    totals: [
      `COUNT(*) AS bags_entries, ${SUM_INT('l.luggage_qty')} AS bags`,
      `${SUM_INT('l.extra_lugage_qty')} AS extra_bags, ${SUM_MONEY('r.net_amt')} AS net_amt`,
      `${SUM_MONEY('r.adv_amt')} AS adv_amt, ${SUM_MONEY('r.bal_amt')} AS bal_amt`,
    ].join(', '),
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
    select: `${LUGGAGE_COLUMNS}, TIMESTAMPDIFF(MINUTE, l.date_time_in, NOW()) AS minutes_stored`,
    from: LUGGAGE_JOINS,
    where,
    orderBy: 'l.date_time_in, l.trans_id',
    totals: LUGGAGE_TOTALS,
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

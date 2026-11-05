/**
 * Platform-wide numbers for the super admin dashboard. Read-only, across all customers (S has no
 * tenant), so hand-written SQL via raw() with every value as a placeholder.
 */
import type { RowDataPacket } from 'mysql2/promise';
import { raw } from '../core/db/dml';
import { fromPaise, toPaise } from '../core/utils/money';
import { addDays, daysBetween, toIstDate } from '../core/utils/time';
import { buildColumnChart, type ChartPoint } from './chart';
import { date, inr, inrCompact, plural } from './viewHelpers';

const TREND_DAYS = 14;
const RENEWAL_WINDOW_DAYS = 30;
const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

const n = (v: unknown) => Number(v ?? 0);
/** DECIMAL sums stay strings (exact); views format them. */
const money = (v: unknown) => (v === null || v === undefined ? '0.00' : String(v));

export async function dashboard(today = toIstDate()) {
  const trendFrom = addDays(today, -(TREND_DAYS - 1));
  const monthFrom = addDays(today, -29);
  const renewalUntil = addDays(today, RENEWAL_WINDOW_DAYS);

  const [
    [customers],
    [users],
    [sessions],
    [bags],
    trend,
    [exceptionTotals],
    exceptionsByCustomer,
    renewals,
    topStations,
  ] = await Promise.all([
    raw<RowDataPacket[]>(
      `SELECT COUNT(*) AS total,
              COALESCE(SUM(active_flag = 'Y'), 0) AS active,
              COALESCE(SUM(active_flag = 'Y' AND (renewal_dt IS NULL OR renewal_dt < ?)), 0) AS expired,
              COALESCE(SUM(active_flag = 'Y' AND renewal_dt BETWEEN ? AND ?), 0) AS expiring
         FROM md_customer WHERE is_deleted = 'N'`,
      [today, today, renewalUntil],
    ),
    raw<RowDataPacket[]>(
      `SELECT COUNT(*) AS total, COALESCE(SUM(user_type = 'A'), 0) AS admins
         FROM md_user WHERE is_deleted = 'N' AND allow_flag = 'Y' AND user_type <> 'S'`,
    ),
    raw<RowDataPacket[]>(
      `SELECT COUNT(*) AS active, COUNT(DISTINCT customer_id) AS stations
         FROM td_user_session WHERE status = 'A' AND customer_id IS NOT NULL`,
    ),
    raw<RowDataPacket[]>(
      `SELECT COUNT(*) AS receipts, COALESCE(SUM(luggage_qty), 0) AS bags
         FROM td_luggage_in_out WHERE out_flag = 'N' AND is_deleted = 'N'`,
    ),
    raw<RowDataPacket[]>(
      `SELECT DATE_FORMAT(bill_dt, '%Y-%m-%d') AS day, COUNT(*) AS bills,
              COALESCE(SUM(luggage_qty), 0) AS bags, COALESCE(SUM(net_amt), 0) AS net,
              COALESCE(SUM(CASE WHEN pay_mode = 'C' THEN net_amt END), 0) AS cash,
              COALESCE(SUM(CASE WHEN pay_mode = 'U' THEN net_amt END), 0) AS upi
         FROM td_receipt WHERE is_deleted = 'N' AND bill_dt BETWEEN ? AND ?
        GROUP BY bill_dt`,
      [trendFrom, today],
    ),
    raw<RowDataPacket[]>(
      `SELECT COUNT(*) AS open FROM td_sync_exception WHERE resolved_flag = 'N'`,
    ),
    raw<RowDataPacket[]>(
      `SELECT e.customer_id, c.customer_name, COUNT(*) AS open, MAX(e.created_at) AS latest
         FROM td_sync_exception e JOIN md_customer c ON c.customer_id = e.customer_id
        WHERE e.resolved_flag = 'N'
        GROUP BY e.customer_id, c.customer_name
        ORDER BY open DESC LIMIT 5`,
    ),
    raw<RowDataPacket[]>(
      `SELECT customer_id, customer_name, state, user_limit,
              DATE_FORMAT(renewal_dt, '%Y-%m-%d') AS renewal_dt
         FROM md_customer
        WHERE is_deleted = 'N' AND active_flag = 'Y' AND (renewal_dt IS NULL OR renewal_dt <= ?)
        ORDER BY renewal_dt IS NOT NULL, renewal_dt, customer_name LIMIT 8`,
      [renewalUntil],
    ),
    raw<RowDataPacket[]>(
      `SELECT r.customer_id, c.customer_name, c.state, COUNT(*) AS bills,
              COALESCE(SUM(r.luggage_qty), 0) AS bags, COALESCE(SUM(r.net_amt), 0) AS net
         FROM td_receipt r JOIN md_customer c ON c.customer_id = r.customer_id
        WHERE r.is_deleted = 'N' AND r.bill_dt BETWEEN ? AND ?
        GROUP BY r.customer_id, c.customer_name, c.state
        ORDER BY net DESC LIMIT 5`,
      [monthFrom, today],
    ),
  ]);

  const byDay = new Map(trend.map((r) => [String(r.day), r]));
  const days: string[] = [];
  for (let i = 0; i < TREND_DAYS; i++) days.push(addDays(trendFrom, i));
  const points: ChartPoint[] = days.map((d) => {
    const r = byDay.get(d);
    const weekday = WEEKDAYS[new Date(`${d}T00:00:00Z`).getUTCDay()];
    return {
      label: date(d).slice(0, 6),
      title: `${weekday}, ${date(d)}`,
      value: n(r?.net),
      detail: r ? `${plural(n(r.bills), 'bill')} · ${plural(n(r.bags), 'bag')}` : 'No bills',
    };
  });
  const todayRow = byDay.get(today);
  const yesterdayRow = byDay.get(addDays(today, -1));
  // Money totals in paise (never float sums, CLAUDE.md §3); floats only feed chart geometry.
  const trendTotal = fromPaise(trend.reduce((s, r) => s + toPaise(String(r.net)), 0));
  const topMax = Math.max(1, ...topStations.map((s) => n(s.net)));

  return {
    today,
    customers: {
      total: n(customers?.total),
      active: n(customers?.active),
      inactive: n(customers?.total) - n(customers?.active),
      expired: n(customers?.expired),
      expiring: n(customers?.expiring),
    },
    users: { total: n(users?.total), admins: n(users?.admins) },
    sessions: { active: n(sessions?.active), stations: n(sessions?.stations) },
    bags: { receipts: n(bags?.receipts), bags: n(bags?.bags) },
    collection: {
      today: money(todayRow?.net),
      todayBills: n(todayRow?.bills),
      todayCash: money(todayRow?.cash),
      todayUpi: money(todayRow?.upi),
      yesterday: money(yesterdayRow?.net),
      delta: deltaOf(money(todayRow?.net), money(yesterdayRow?.net)),
      trendTotal,
      trendDays: TREND_DAYS,
    },
    chart: buildColumnChart(points, (v) => inr(v), inrCompact),
    chartRows: days.map((d, i) => ({
      day: d,
      ...pickRow(byDay.get(d)),
      net: points[i]?.value ?? 0,
    })),
    exceptions: {
      open: n(exceptionTotals?.open),
      byCustomer: exceptionsByCustomer.map((r) => ({
        customer_id: n(r.customer_id),
        customer_name: String(r.customer_name),
        open: n(r.open),
        latest: r.latest ? String(r.latest) : null,
      })),
    },
    renewals: renewals.map((r) => {
      const renewal = r.renewal_dt ? String(r.renewal_dt) : null;
      const daysLeft = renewal ? daysBetween(today, renewal) : null;
      return {
        customer_id: n(r.customer_id),
        customer_name: String(r.customer_name),
        state: r.state ? String(r.state) : '',
        renewal_dt: renewal,
        daysLeft,
        status: daysLeft === null || daysLeft < 0 ? 'expired' : daysLeft <= 7 ? 'soon' : 'upcoming',
      };
    }),
    topStations: topStations.map((r) => ({
      customer_id: n(r.customer_id),
      customer_name: String(r.customer_name),
      state: r.state ? String(r.state) : '',
      bills: n(r.bills),
      bags: n(r.bags),
      net: money(r.net),
      share: Math.round((n(r.net) / topMax) * 100),
    })),
    formatted: { trendTotal: inr(trendTotal, true) },
  };
}

/** Today vs yesterday in whole percent (paise arithmetic); null when yesterday had nothing. */
export function deltaOf(today: string, yesterday: string) {
  const t = toPaise(today);
  const y = toPaise(yesterday);
  if (y === 0) return null;
  const pct = Math.round(((t - y) * 100) / y);
  return { pct: Math.abs(pct), dir: pct > 0 ? 'up' : pct < 0 ? 'down' : 'flat' } as const;
}

function pickRow(r: RowDataPacket | undefined) {
  return { bills: n(r?.bills), bags: n(r?.bags), cash: n(r?.cash), upi: n(r?.upi) };
}

export type Dashboard = Awaited<ReturnType<typeof dashboard>>;

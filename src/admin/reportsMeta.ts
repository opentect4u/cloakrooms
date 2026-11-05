/** The panel's view of the API reports (same services, same SQL): titles, columns, filters. */
import type { RequestContext } from '../core/context';
import * as reports from '../modules/reports/reports.service';
import { date, inr, num } from './viewHelpers';

export type ReportCell =
  | 'text'
  | 'strong'
  | 'code'
  | 'int'
  | 'money'
  | 'pct'
  | 'date'
  | 'istTime'
  | 'duration'
  | 'payMode'
  | 'yesNo'
  | 'bagStatus';

export interface ReportColumn {
  key: string;
  label: string;
  kind?: ReportCell;
  /** Second line under the value (e.g. mobile under the name). */
  sub?: string;
  hideBelow?: 'md' | 'lg';
}

export interface ReportDef {
  key: string;
  title: string;
  icon: string;
  description: string;
  /** 'range' needs from/to; 'store' (bags in store) has an optional check-in window. */
  query: 'range' | 'store';
  filters: ('user' | 'shift' | 'payMode')[];
  columns: ReportColumn[];
  totals: { key: string; label: string; kind: 'int' | 'money' }[];
  run: (
    ctx: RequestContext,
    q: never,
  ) => Promise<{
    rows: Record<string, unknown>[];
    meta: { total: number; totals: Record<string, unknown> };
  }>;
}

const MONEY_TOTALS = [
  { key: 'bills', label: 'Bills', kind: 'int' as const },
  { key: 'bags', label: 'Bags', kind: 'int' as const },
  { key: 'cash_amt', label: 'Cash', kind: 'money' as const },
  { key: 'upi_amt', label: 'UPI', kind: 'money' as const },
  { key: 'net_amt', label: 'Net collection', kind: 'money' as const },
];

export const REPORTS: ReportDef[] = [
  {
    key: 'collection-summary',
    title: 'Collection summary',
    icon: 'cash-stack',
    description: 'Bills and money collected per day (bill date), cash and UPI.',
    query: 'range',
    filters: ['user', 'shift', 'payMode'],
    columns: [
      { key: 'bill_dt', label: 'Day', kind: 'date' },
      { key: 'bills', label: 'Bills', kind: 'int' },
      { key: 'bags', label: 'Bags', kind: 'int' },
      { key: 'base_amt', label: 'Rent', kind: 'money', hideBelow: 'lg' },
      { key: 'penal_amt', label: 'Penalty', kind: 'money', hideBelow: 'lg' },
      { key: 'cash_amt', label: 'Cash', kind: 'money', hideBelow: 'md' },
      { key: 'upi_amt', label: 'UPI', kind: 'money', hideBelow: 'md' },
      { key: 'net_amt', label: 'Net', kind: 'money' },
    ],
    totals: MONEY_TOTALS,
    run: reports.collectionSummary as ReportDef['run'],
  },
  {
    key: 'user-shift-collection',
    title: 'User & shift collection',
    icon: 'people',
    description: 'What each counter user collected, per shift.',
    query: 'range',
    filters: ['user', 'shift', 'payMode'],
    columns: [
      { key: 'user_name', label: 'User', kind: 'strong', sub: 'user_code' },
      { key: 'shift_name', label: 'Shift' },
      { key: 'bills', label: 'Bills', kind: 'int' },
      { key: 'bags', label: 'Bags', kind: 'int', hideBelow: 'md' },
      { key: 'cash_amt', label: 'Cash', kind: 'money', hideBelow: 'md' },
      { key: 'upi_amt', label: 'UPI', kind: 'money', hideBelow: 'md' },
      { key: 'net_amt', label: 'Net', kind: 'money' },
    ],
    totals: MONEY_TOTALS,
    run: reports.userShiftCollection as ReportDef['run'],
  },
  {
    key: 'check-in-register',
    title: 'Check-in register',
    icon: 'box-arrow-in-down',
    description: 'Every check-in of the period, with passenger and ID details.',
    query: 'range',
    filters: ['user', 'shift'],
    columns: [
      { key: 'receipt_no', label: 'Receipt', kind: 'code' },
      { key: 'date_time_in_ist', label: 'Checked in', kind: 'istTime' },
      { key: 'pasn_name', label: 'Passenger', sub: 'pasn_mob_no' },
      { key: 'pnr_no', label: 'PNR', hideBelow: 'lg' },
      { key: 'luggage_qty', label: 'Bags', kind: 'int' },
      { key: 'id_proof_name', label: 'ID proof', sub: 'id_number', hideBelow: 'lg' },
      { key: 'user_name_in', label: 'By', sub: 'shift_name_in', hideBelow: 'md' },
      { key: 'out_flag', label: 'Status', kind: 'bagStatus' },
    ],
    totals: [
      { key: 'bags_entries', label: 'Check-ins', kind: 'int' },
      { key: 'bags', label: 'Bags', kind: 'int' },
    ],
    run: reports.checkInRegister as ReportDef['run'],
  },
  {
    key: 'check-out-register',
    title: 'Check-out register',
    icon: 'box-arrow-up',
    description: 'Every check-out of the period with its bill.',
    query: 'range',
    filters: ['user', 'shift', 'payMode'],
    columns: [
      { key: 'receipt_no', label: 'Receipt', kind: 'code', sub: 'bill_no' },
      { key: 'date_time_out_ist', label: 'Checked out', kind: 'istTime' },
      { key: 'pasn_name', label: 'Passenger', sub: 'pasn_mob_no' },
      { key: 'luggage_qty', label: 'Bags', kind: 'int' },
      { key: 'days_charged', label: 'Days', kind: 'int', hideBelow: 'md' },
      { key: 'user_name_out', label: 'By', sub: 'shift_name_out', hideBelow: 'lg' },
      { key: 'pay_mode', label: 'Paid by', kind: 'payMode', hideBelow: 'md' },
      { key: 'net_amt', label: 'Net', kind: 'money' },
    ],
    totals: [
      { key: 'bags_entries', label: 'Check-outs', kind: 'int' },
      { key: 'bags', label: 'Bags', kind: 'int' },
      { key: 'net_amt', label: 'Net billed', kind: 'money' },
    ],
    run: reports.checkOutRegister as ReportDef['run'],
  },
  {
    key: 'bags-in-store',
    title: 'Bags in store',
    icon: 'suitcase2',
    description: 'Bags checked in and not yet out, oldest first. Dates filter the check-in time.',
    query: 'store',
    filters: ['user', 'shift'],
    columns: [
      { key: 'receipt_no', label: 'Receipt', kind: 'code' },
      { key: 'date_time_in_ist', label: 'Checked in', kind: 'istTime' },
      { key: 'minutes_stored', label: 'Stored for', kind: 'duration' },
      { key: 'pasn_name', label: 'Passenger', sub: 'pasn_mob_no' },
      { key: 'pnr_no', label: 'PNR', hideBelow: 'lg' },
      { key: 'luggage_qty', label: 'Bags', kind: 'int' },
      { key: 'user_name_in', label: 'By', hideBelow: 'md' },
    ],
    totals: [
      { key: 'bags_entries', label: 'Receipts', kind: 'int' },
      { key: 'bags', label: 'Bags', kind: 'int' },
    ],
    run: reports.bagsInStore as ReportDef['run'],
  },
  {
    key: 'gst',
    title: 'GST',
    icon: 'percent',
    description: 'Taxable value and GST per day and rate, for returns.',
    query: 'range',
    filters: ['user', 'shift', 'payMode'],
    columns: [
      { key: 'bill_dt', label: 'Day', kind: 'date' },
      { key: 'gst_pct', label: 'Rate', kind: 'pct' },
      { key: 'bills', label: 'Bills', kind: 'int', hideBelow: 'md' },
      { key: 'taxable_amt', label: 'Taxable', kind: 'money' },
      { key: 'cgst', label: 'CGST', kind: 'money', hideBelow: 'md' },
      { key: 'sgst', label: 'SGST', kind: 'money', hideBelow: 'md' },
      { key: 'igst', label: 'IGST', kind: 'money', hideBelow: 'md' },
      { key: 'net_amt', label: 'Total', kind: 'money' },
    ],
    totals: [
      { key: 'taxable_amt', label: 'Taxable', kind: 'money' },
      { key: 'cgst', label: 'CGST', kind: 'money' },
      { key: 'sgst', label: 'SGST', kind: 'money' },
      { key: 'igst', label: 'IGST', kind: 'money' },
      { key: 'net_amt', label: 'Total', kind: 'money' },
    ],
    run: reports.gstReport as ReportDef['run'],
  },
  {
    key: 'unprinted-bills',
    title: 'Unprinted bills',
    icon: 'printer',
    description: 'Check-outs done with “Out without bill print”.',
    query: 'range',
    filters: ['user', 'shift', 'payMode'],
    columns: [
      { key: 'bill_no', label: 'Bill', kind: 'code', sub: 'receipt_no' },
      { key: 'bill_dt', label: 'Day', kind: 'date' },
      { key: 'pasn_name', label: 'Passenger', sub: 'pasn_mob_no' },
      { key: 'luggage_qty', label: 'Bags', kind: 'int' },
      { key: 'user_name', label: 'By', hideBelow: 'md' },
      { key: 'pay_mode', label: 'Paid by', kind: 'payMode', hideBelow: 'md' },
      { key: 'net_amt', label: 'Net', kind: 'money' },
    ],
    totals: [
      { key: 'bills', label: 'Bills', kind: 'int' },
      { key: 'net_amt', label: 'Net', kind: 'money' },
    ],
    run: reports.unprintedBills as ReportDef['run'],
  },
];

export const reportByKey = new Map(REPORTS.map((r) => [r.key, r]));

/** "1 d 4 h", "3 h 05 m", "12 m". */
export function duration(minutes: number): string {
  if (!Number.isFinite(minutes) || minutes < 0) return '—';
  const d = Math.floor(minutes / 1440);
  const h = Math.floor((minutes % 1440) / 60);
  const m = Math.floor(minutes % 60);
  if (d) return `${d} d ${h} h`;
  if (h) return `${h} h ${String(m).padStart(2, '0')} m`;
  return `${m} m`;
}

/** `YYYY-MM-DD HH:mm:ss` already in IST → `05 Nov 2026, 14:05`. */
export function istTime(value: string): string {
  return `${date(value.slice(0, 10))}, ${value.slice(11, 16)}`;
}

export interface Formatted {
  text: string;
  sub?: string;
  tone?: 'success' | 'muted' | 'primary' | 'warning';
  align?: 'end';
}

export function formatCell(col: ReportColumn, row: Record<string, unknown>): Formatted {
  const v = row[col.key];
  const sub = col.sub && row[col.sub] ? String(row[col.sub]) : undefined;
  if (v === null || v === undefined || v === '') return { text: '—', sub };
  const s = String(v);
  switch (col.kind) {
    case 'int':
      return { text: num(Number(v)), sub, align: 'end' };
    case 'money':
      return { text: inr(s), sub, align: 'end' };
    case 'pct':
      return { text: `${Number(v)}%`, sub };
    case 'date':
      return { text: date(s), sub };
    case 'istTime':
      return { text: istTime(s), sub };
    case 'duration':
      return { text: duration(Number(v)), sub, tone: Number(v) > 1440 ? 'warning' : undefined };
    case 'payMode':
      return { text: s === 'U' ? 'UPI' : s === 'C' ? 'Cash' : s, sub };
    case 'yesNo':
      return { text: s === 'Y' ? 'Yes' : 'No', sub };
    case 'bagStatus':
      return s === 'Y'
        ? { text: 'Checked out', tone: 'muted' }
        : { text: 'In store', tone: 'success' };
    default:
      return { text: s, sub };
  }
}

export function formatTotal(kind: 'int' | 'money', v: unknown): string {
  return kind === 'money' ? inr(String(v ?? '0')) : num(Number(v ?? 0));
}

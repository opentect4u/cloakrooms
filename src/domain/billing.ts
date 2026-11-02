/**
 * Check-out bill (CLAUDE.md §3). Pure functions; money in integer paise, returned as 2-decimal
 * strings exactly as stored in td_receipt.
 *
 *   minutesStored = whole minutes between check-in and check-out (seconds are dropped)
 *   days          = max(1, ceil(minutesStored / (block_hours × 60)))   24h00m → 1, 24h01m → 2
 *   base_amt      = qty × (first_block_rate + next_block_rate × (days − 1))
 *   net_amt       = base_amt + penal_amt                                GST-inclusive
 *   taxable/cgst/sgst/igst → domain/gst.ts
 */
import { fromPaise, toPaise } from '../core/utils/money';
import { splitGst, type GstType } from './gst';

export interface Tariff {
  block_hours: number;
  first_block_rate: string;
  next_block_rate: string;
}

export interface BillInput {
  luggage_qty: number;
  /** UTC `YYYY-MM-DD HH:mm:ss` */
  date_time_in: string;
  /** UTC `YYYY-MM-DD HH:mm:ss` */
  date_time_out: string;
  tariff: Tariff;
  /** Operator-typed penalty, GST-inclusive. */
  penal_amt?: string;
  gst_pct: string;
  gst_type: GstType;
}

export interface Bill {
  days_charged: number;
  block_hours: number;
  first_block_rate: string;
  next_block_rate: string;
  base_amt: string;
  penal_amt: string;
  net_amt: string;
  taxable_amt: string;
  gst_pct: string;
  cgst: string;
  sgst: string;
  igst: string;
}

const UTC_DATETIME = /^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})$/;

/** Epoch ms of a UTC `YYYY-MM-DD HH:mm:ss` string. */
export function parseUtc(value: string): number {
  const m = UTC_DATETIME.exec(value);
  if (!m) throw new RangeError(`Invalid UTC datetime: ${value}`);
  const [, y, mo, d, h, mi, s] = m.map(Number) as number[];
  const ms = Date.UTC(y!, mo! - 1, d!, h!, mi!, s!);
  if (new Date(ms).toISOString().slice(0, 19).replace('T', ' ') !== value) {
    throw new RangeError(`Invalid UTC datetime: ${value}`);
  }
  return ms;
}

export function minutesStored(dateTimeIn: string, dateTimeOut: string): number {
  const diff = parseUtc(dateTimeOut) - parseUtc(dateTimeIn);
  if (diff < 0) throw new RangeError('Check-out is before check-in');
  return Math.floor(diff / 60_000);
}

export function daysCharged(minutes: number, blockHours: number): number {
  if (!Number.isInteger(minutes) || minutes < 0)
    throw new RangeError(`Invalid minutes: ${minutes}`);
  if (!Number.isInteger(blockHours) || blockHours < 1) {
    throw new RangeError(`Invalid block_hours: ${blockHours}`);
  }
  return Math.max(1, Math.ceil(minutes / (blockHours * 60)));
}

export function baseAmountPaise(luggageQty: number, tariff: Tariff, days: number): number {
  if (!Number.isInteger(luggageQty) || luggageQty < 1) {
    throw new RangeError(`Invalid luggage_qty: ${luggageQty}`);
  }
  const perBag = toPaise(tariff.first_block_rate) + toPaise(tariff.next_block_rate) * (days - 1);
  return luggageQty * perBag;
}

export function computeBill(input: BillInput): Bill {
  const days = daysCharged(
    minutesStored(input.date_time_in, input.date_time_out),
    input.tariff.block_hours,
  );
  const base = baseAmountPaise(input.luggage_qty, input.tariff, days);
  const penal = toPaise(input.penal_amt ?? '0');
  const net = base + penal;
  const gst = splitGst(net, input.gst_pct, input.gst_type);
  return {
    days_charged: days,
    block_hours: input.tariff.block_hours,
    first_block_rate: fromPaise(toPaise(input.tariff.first_block_rate)),
    next_block_rate: fromPaise(toPaise(input.tariff.next_block_rate)),
    base_amt: fromPaise(base),
    penal_amt: fromPaise(penal),
    net_amt: fromPaise(net),
    taxable_amt: fromPaise(gst.taxable),
    gst_pct: fromPaise(toPaise(input.gst_pct)),
    cgst: fromPaise(gst.cgst),
    sgst: fromPaise(gst.sgst),
    igst: fromPaise(gst.igst),
  };
}

/** Versioned masters: the row with the latest effective_from on or before `onDate` (YYYY-MM-DD). */
export function selectEffective<T extends { effective_from: string }>(
  rows: readonly T[],
  onDate: string,
): T | undefined {
  let best: T | undefined;
  for (const row of rows) {
    if (row.effective_from <= onDate && (!best || row.effective_from > best.effective_from)) {
      best = row;
    }
  }
  return best;
}

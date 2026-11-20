/**
 * Check-out bill (CLAUDE.md §3). Pure functions; money in integer paise, returned as 2-decimal
 * strings exactly as stored in td_receipt.
 *
 *   minutesStored = whole minutes between check-in and check-out (seconds are dropped)
 *   billable      = max(0, minutesStored − grace_minutes)
 *   days, base    = slabs (domain/slabs.ts): every started slab adds its rate per bag, the last
 *                   slab repeats; the two-rate tariff is [0, B) first + [B, 2B) next:
 *                   days = max(1, ceil(billable / (block_hours × 60)))   24h00m → 1, 24h01m → 2
 *                   base_amt = qty × (first_block_rate + next_block_rate × (days − 1))
 *   gst%          = 0 when gst_flag = N, else the GST row's rate
 *   taxed         = base_amt + (penalty_gst_flag = Y ? penal_amt : 0)
 *   GST inclusive (default):  taxable = round2(taxed × 100 / (100 + gst%)), gst = taxed − taxable,
 *                             net_amt = base_amt + penal_amt
 *   GST on top (N):           taxable = taxed, gst = round2(taxed × gst% / 100),
 *                             net_amt = base_amt + penal_amt + gst
 *   taxable_amt = taxable (a penalty without GST is not part of it); split → domain/gst.ts
 *   Defaults (grace 0, inclusive, GST on the penalty, GST on) give the original §3 formula.
 */
import { fromPaise, toPaise } from '../core/utils/money';
import { addGst, splitGst, type GstType } from './gst';
import {
  legacySlabs,
  normaliseSlabs,
  slabCharge,
  slabsToLegacy,
  type RateSlab,
  type Tariff,
} from './slabs';

export type { RateSlab, Tariff } from './slabs';

export interface BillInput {
  luggage_qty: number;
  /** UTC `YYYY-MM-DD HH:mm:ss` */
  date_time_in: string;
  /** UTC `YYYY-MM-DD HH:mm:ss` */
  date_time_out: string;
  /** Two-rate tariff (before Phase 13); used when `slabs` is not given. */
  tariff?: Tariff;
  /** Tariff slabs (Phase 13); win over `tariff`. */
  slabs?: readonly RateSlab[];
  /** Operator-typed penalty, GST-inclusive. */
  penal_amt?: string;
  gst_pct: string;
  gst_type: GstType;
  options?: BillOptions;
}

/** Customer billing options (Phase 11), snapshotted on each bill. */
export interface BillOptions {
  /** GRACE_MINUTES: minutes free before blocks are counted. Default 0. */
  graceMinutes?: number;
  /** GST_INCLUSIVE: rates include GST (true, default) or GST is added on top. */
  gstInclusive?: boolean;
  /** PENALTY_GST_FLAG: GST also on the penalty. Default true. */
  penaltyGst?: boolean;
  /** GST_FLAG: false = 0 % GST (not GST-registered). Default true. */
  gstEnabled?: boolean;
}

export interface Bill {
  days_charged: number;
  block_hours: number;
  first_block_rate: string;
  next_block_rate: string;
  rate_slabs: RateSlab[];
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

/** Minutes that count after the grace period. */
export function billableMinutes(minutes: number, graceMinutes = 0): number {
  if (!Number.isInteger(graceMinutes) || graceMinutes < 0) {
    throw new RangeError(`Invalid grace minutes: ${graceMinutes}`);
  }
  return Math.max(0, minutes - graceMinutes);
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
  const o = input.options ?? {};
  if (!Number.isInteger(input.luggage_qty) || input.luggage_qty < 1) {
    throw new RangeError(`Invalid luggage_qty: ${input.luggage_qty}`);
  }
  if (!input.slabs && !input.tariff) throw new RangeError('A tariff or slabs are needed');
  const slabs = normaliseSlabs(input.slabs ?? legacySlabs(input.tariff!));
  const legacy = input.slabs ? slabsToLegacy(slabs) : input.tariff!;
  const charge = slabCharge(
    billableMinutes(minutesStored(input.date_time_in, input.date_time_out), o.graceMinutes ?? 0),
    slabs,
  );
  const days = charge.blocks;
  const base = input.luggage_qty * charge.perBagPaise;
  const penal = toPaise(input.penal_amt ?? '0');
  const pct = o.gstEnabled === false ? '0' : input.gst_pct;
  const taxed = base + (o.penaltyGst === false ? 0 : penal);
  const inclusive = o.gstInclusive !== false;
  const gst = inclusive ? splitGst(taxed, pct, input.gst_type) : addGst(taxed, pct, input.gst_type);
  const net = base + penal + (inclusive ? 0 : gst.gst);
  return {
    days_charged: days,
    block_hours: legacy.block_hours,
    first_block_rate: fromPaise(toPaise(legacy.first_block_rate)),
    next_block_rate: fromPaise(toPaise(legacy.next_block_rate)),
    rate_slabs: slabs,
    base_amt: fromPaise(base),
    penal_amt: fromPaise(penal),
    net_amt: fromPaise(net),
    taxable_amt: fromPaise(gst.taxable),
    gst_pct: fromPaise(toPaise(pct)),
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

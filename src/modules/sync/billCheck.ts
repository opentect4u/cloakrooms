/**
 * Server-side recomputation of a pushed bill (CLAUDE.md §5) with the domain formulas.
 * Rate = latest md_rate_dtls.effective_from ≤ receipt_dt (its slabs, Phase 13); GST = latest
 * md_gst.effective_from ≤ bill_dt; GST type from the billing user's settings; billing options
 * (Phase 11) as snapshotted on the bill (see billOptionsOf). Advance: the bill's adv_amt must be
 * the bag's, bal_amt = max(0, net_amt − adv_amt).
 */
import type { Row } from '../../core/db/dml';
import { fromPaise, toPaise } from '../../core/utils/money';
import { computeBill, selectEffective, type Bill, type BillOptions } from '../../domain/billing';
import {
  balancePaise,
  isLegacyCompatible,
  legacySlabs,
  sameSlabs,
  type RateSlab,
} from '../../domain/slabs';
import { gstPctFor, type GstType } from '../../domain/gst';
import { settingDefault } from '../settings/settings.schema';
import type { GstRow, PushRefs, RateRow } from './sync.refs';
import type { ReceiptRow } from './sync.schema';

const LEGACY_MONEY = ['first_block_rate', 'next_block_rate'] as const;
const MONEY_FIELDS = [
  'base_amt',
  'penal_amt',
  'net_amt',
  'taxable_amt',
  'gst_pct',
  'cgst',
  'sgst',
  'igst',
  'adv_amt',
  'bal_amt',
] as const;

export type BillCheck =
  | { ok: true }
  | { ok: false; code: 'CHECKOUT_MISSING' | 'NO_RATE'; detail: string }
  | {
      ok: false;
      code: 'AMOUNT_MISMATCH';
      detail: string;
      expected: Expected;
    };

type Expected = Bill & { luggage_qty: number; adv_amt: string; bal_amt: string };

/** The rate row in force on the bag's check-in date. */
export function rateFor(rates: readonly RateRow[], receiptDt: string): RateRow | undefined {
  return selectEffective(rates, receiptDt);
}

/** The rate row's slabs (rows from before migration 008: from the two-rate fields). */
export function slabsOf(rate: RateRow): RateSlab[] {
  return rate.rate_slabs ?? legacySlabs(rate);
}

/**
 * An older app (bill without rate_slabs) cannot bill a tariff the two-rate fields cannot
 * express: such bills are refused rather than stored with wrong amounts.
 */
export function needsNewApp(receipt: ReceiptRow, rate: RateRow | undefined): boolean {
  return !receipt.rate_slabs && rate !== undefined && !isLegacyCompatible(slabsOf(rate));
}

/** bal_amt as stored: the device's value, or max(0, net − advance) from an older app. */
export function balanceOf(receipt: ReceiptRow): string {
  return (
    receipt.bal_amt ?? fromPaise(balancePaise(toPaise(receipt.net_amt), toPaise(receipt.adv_amt)))
  );
}

/** The billing options a bill is stored and checked with (td_receipt snapshot columns). */
export interface BillSnapshot {
  grace_minutes: number;
  gst_inclusive: 'Y' | 'N';
  penalty_gst_flag: 'Y' | 'N';
  gst_flag: 'Y' | 'N';
}

const yn = (v: string | undefined): 'Y' | 'N' | undefined =>
  v === 'Y' || v === 'N' ? v : undefined;
const minutes = (v: string | undefined) =>
  v !== undefined && /^\d{1,4}$/.test(v) && Number(v) <= 1440 ? Number(v) : undefined;

/**
 * Each option: the value the device sent with the bill, else the setting (customer-wide for
 * customer-scoped keys, the billing user's for GST_FLAG), else the default. A device that omits
 * them (older app) is checked against the settings in force when the bill arrives.
 */
export function billOptionsOf(
  row: ReceiptRow,
  refs: Pick<PushRefs, 'setting' | 'customerSetting'>,
): BillSnapshot {
  const customerYn = (name: string) =>
    yn(refs.customerSetting(name)) ?? (settingDefault(name) as 'Y' | 'N');
  return {
    grace_minutes:
      row.grace_minutes ??
      minutes(refs.customerSetting('GRACE_MINUTES')) ??
      Number(settingDefault('GRACE_MINUTES')),
    gst_inclusive: row.gst_inclusive ?? customerYn('GST_INCLUSIVE'),
    penalty_gst_flag: row.penalty_gst_flag ?? customerYn('PENALTY_GST_FLAG'),
    gst_flag: row.gst_flag ?? yn(refs.setting(row.user_id, 'GST_FLAG')) ?? 'Y',
  };
}

/** The customer's own GST % (GST_PCT, 0–28, 2 decimals) or undefined → md_gst applies. */
export function customerGstPct(refs: Pick<PushRefs, 'customerSetting'>): string | undefined {
  const v = refs.customerSetting('GST_PCT');
  return v !== undefined && /^\d{1,2}(\.\d{1,2})?$/.test(v) && Number(v) <= 28 ? v : undefined;
}

export function toBillOptions(s: BillSnapshot): BillOptions {
  return {
    graceMinutes: s.grace_minutes,
    gstInclusive: s.gst_inclusive === 'Y',
    penaltyGst: s.penalty_gst_flag === 'Y',
    gstEnabled: s.gst_flag === 'Y',
  };
}

export function checkBill(
  receipt: ReceiptRow,
  luggage: Row,
  rates: readonly RateRow[],
  gstRows: readonly GstRow[],
  gstType: GstType,
  options: BillOptions = {},
  /** The customer's GST_PCT when set: replaces the md_gst rate (setting at push time). */
  gstPctOverride?: string,
): BillCheck {
  if (!luggage.date_time_out) {
    return { ok: false, code: 'CHECKOUT_MISSING', detail: 'bag has no check-out time' };
  }
  const rate = rateFor(rates, String(luggage.receipt_dt));
  if (!rate)
    return {
      ok: false,
      code: 'NO_RATE',
      detail: `no rate effective on ${String(luggage.receipt_dt)}`,
    };
  const gst = selectEffective(gstRows, receipt.bill_dt);
  const slabs = slabsOf(rate);

  const bill = computeBill({
    luggage_qty: Number(luggage.luggage_qty),
    date_time_in: String(luggage.date_time_in),
    date_time_out: String(luggage.date_time_out),
    slabs,
    penal_amt: receipt.penal_amt,
    gst_pct: gstPctOverride ?? (gst ? gstPctFor(gst, gstType) : '0'),
    gst_type: gstType,
    options,
  });
  const adv = toPaise(String(luggage.adv_amt ?? '0'));
  const expected: Expected = {
    luggage_qty: Number(luggage.luggage_qty),
    ...bill,
    adv_amt: fromPaise(adv),
    bal_amt: fromPaise(balancePaise(toPaise(bill.net_amt), adv)),
  };
  const actual = { ...receipt, bal_amt: balanceOf(receipt) };

  const diffs: string[] = [];
  for (const f of ['luggage_qty', 'days_charged'] as const) {
    if (Number(receipt[f]) !== expected[f]) diffs.push(`${f} ${receipt[f]}≠${expected[f]}`);
  }
  // Snapshot of the tariff: the slabs (new apps) and/or the two-rate fields (whatever was sent).
  if (receipt.rate_slabs && !sameSlabs(receipt.rate_slabs, slabs)) diffs.push('rate_slabs differ');
  if (receipt.block_hours !== undefined && receipt.block_hours !== expected.block_hours) {
    diffs.push(`block_hours ${receipt.block_hours}≠${expected.block_hours}`);
  }
  for (const f of LEGACY_MONEY) {
    const v = receipt[f];
    if (v !== undefined && toPaise(v) !== toPaise(expected[f])) {
      diffs.push(`${f} ${v}≠${expected[f]}`);
    }
  }
  for (const f of MONEY_FIELDS) {
    if (toPaise(actual[f]) !== toPaise(expected[f])) diffs.push(`${f} ${actual[f]}≠${expected[f]}`);
  }
  return diffs.length
    ? { ok: false, code: 'AMOUNT_MISMATCH', detail: diffs.join(', '), expected }
    : { ok: true };
}

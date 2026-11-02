/**
 * Server-side recomputation of a pushed bill (CLAUDE.md §5) with the domain formulas.
 * Rate = latest md_rate_dtls.effective_from ≤ receipt_dt; GST = latest md_gst.effective_from ≤
 * bill_dt; GST type from the billing user's settings.
 */
import type { Row } from '../../core/db/dml';
import { toPaise } from '../../core/utils/money';
import { computeBill, selectEffective, type Bill } from '../../domain/billing';
import { gstPctFor, type GstType } from '../../domain/gst';
import type { GstRow, RateRow } from './sync.refs';
import type { ReceiptRow } from './sync.schema';

const MONEY_FIELDS = [
  'first_block_rate',
  'next_block_rate',
  'base_amt',
  'penal_amt',
  'net_amt',
  'taxable_amt',
  'gst_pct',
  'cgst',
  'sgst',
  'igst',
] as const;

export type BillCheck =
  | { ok: true }
  | { ok: false; code: 'CHECKOUT_MISSING' | 'NO_RATE'; detail: string }
  | {
      ok: false;
      code: 'AMOUNT_MISMATCH';
      detail: string;
      expected: Bill & { luggage_qty: number };
    };

export function checkBill(
  receipt: ReceiptRow,
  luggage: Row,
  rates: readonly RateRow[],
  gstRows: readonly GstRow[],
  gstType: GstType,
): BillCheck {
  if (!luggage.date_time_out) {
    return { ok: false, code: 'CHECKOUT_MISSING', detail: 'bag has no check-out time' };
  }
  const rate = selectEffective(rates, String(luggage.receipt_dt));
  if (!rate)
    return {
      ok: false,
      code: 'NO_RATE',
      detail: `no rate effective on ${String(luggage.receipt_dt)}`,
    };
  const gst = selectEffective(gstRows, receipt.bill_dt);

  const expected = {
    luggage_qty: Number(luggage.luggage_qty),
    ...computeBill({
      luggage_qty: Number(luggage.luggage_qty),
      date_time_in: String(luggage.date_time_in),
      date_time_out: String(luggage.date_time_out),
      tariff: {
        block_hours: Number(rate.block_hours),
        first_block_rate: rate.first_block_rate,
        next_block_rate: rate.next_block_rate,
      },
      penal_amt: receipt.penal_amt,
      gst_pct: gst ? gstPctFor(gst, gstType) : '0',
      gst_type: gstType,
    }),
  };

  const diffs: string[] = [];
  for (const f of ['luggage_qty', 'days_charged', 'block_hours'] as const) {
    if (Number(receipt[f]) !== expected[f]) diffs.push(`${f} ${receipt[f]}≠${expected[f]}`);
  }
  for (const f of MONEY_FIELDS) {
    if (toPaise(receipt[f]) !== toPaise(expected[f]))
      diffs.push(`${f} ${receipt[f]}≠${expected[f]}`);
  }
  return diffs.length
    ? { ok: false, code: 'AMOUNT_MISMATCH', detail: diffs.join(', '), expected }
    : { ok: true };
}

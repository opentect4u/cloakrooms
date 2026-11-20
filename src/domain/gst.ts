/**
 * GST worked backwards from a GST-inclusive amount (CLAUDE.md §3). Pure; integer paise only.
 *
 *   taxable = round2(net × 100 / (100 + gst%))      gst = net − taxable
 *   E (intra-state): cgst = round2(gst / 2), sgst = gst − cgst, igst = 0
 *   I (inter-state): igst = gst, cgst = sgst = 0
 *
 * GST on top (GST_INCLUSIVE = N, Phase 11): gst = round2(taxable × gst% / 100), same split.
 *
 * round2 = half-up to the paisa. Percentages carry ≤ 2 decimals and are handled in hundredths
 * of a percent (18.00 % → 1800), so no step ever uses a float.
 */
import { fromPaise, toPaise } from '../core/utils/money';

export type GstType = 'E' | 'I';

export interface GstRates {
  cgst_pct: string;
  sgst_pct: string;
  igst_pct: string;
}

export interface GstSplit {
  taxable: number;
  gst: number;
  cgst: number;
  sgst: number;
  igst: number;
}

/** Half-up integer division for non-negative operands. */
export function roundDiv(numerator: number, denominator: number): number {
  if (numerator < 0 || denominator <= 0) throw new RangeError('roundDiv expects n ≥ 0, d > 0');
  return Math.floor((2 * numerator + denominator) / (2 * denominator));
}

/** Total GST % applied for the bill's GST type, as a 2-decimal string. */
export function gstPctFor(rates: GstRates, type: GstType): string {
  return type === 'I'
    ? fromPaise(toPaise(rates.igst_pct))
    : fromPaise(toPaise(rates.cgst_pct) + toPaise(rates.sgst_pct));
}

/** Splits a total GST amount by type (E: odd paisa to CGST). */
function byType(taxable: number, gst: number, type: GstType): GstSplit {
  if (type === 'I') return { taxable, gst, cgst: 0, sgst: 0, igst: gst };
  const cgst = roundDiv(gst, 2);
  return { taxable, gst, cgst, sgst: gst - cgst, igst: 0 };
}

/** GST added on top of a taxable amount (GST-exclusive pricing). */
export function addGst(taxablePaise: number, gstPct: string, type: GstType): GstSplit {
  if (!Number.isSafeInteger(taxablePaise) || taxablePaise < 0) {
    throw new RangeError(`Invalid taxable amount (paise): ${taxablePaise}`);
  }
  return byType(taxablePaise, roundDiv(taxablePaise * toPaise(gstPct), 10_000), type);
}

export function splitGst(netPaise: number, gstPct: string, type: GstType): GstSplit {
  if (!Number.isSafeInteger(netPaise) || netPaise < 0) {
    throw new RangeError(`Invalid net amount (paise): ${netPaise}`);
  }
  const pct = toPaise(gstPct); // hundredths of a percent
  const taxable = roundDiv(netPaise * 10_000, 10_000 + pct);
  return byType(taxable, netPaise - taxable, type);
}

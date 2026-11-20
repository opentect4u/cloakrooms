/**
 * Tariff slab editor of the panel (Phase 13). The form posts one "up to hour" and one rate per
 * slab row (`slab_to_<i>`, `slab_rate_<i>`); each slab starts where the previous one ends, so the
 * person only types where a slab ends and its price. Blank rows are ignored.
 */
import type { Row } from '../core/db/dml';
import { fromPaise } from '../core/utils/money';
import { slabCharge, type RateSlab } from '../domain/slabs';
import type { FormValues } from './forms';
import { inr } from './viewHelpers';

/** Rows shown in the editor (JS reveals them one by one with "Add slab"). */
export const SLAB_ROWS = 8;

const isNumber = (v: string | undefined) => v !== undefined && /^\d+$/.test(v.trim());

/** Form values → API input: `rate_slabs` built from the filled rows, slab_* keys removed. */
export function slabFormInput(values: FormValues): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(values)) if (!/^slab_(to|rate)_\d+$/.test(k)) out[k] = v;
  const slabs: Record<string, unknown>[] = [];
  let from = 0;
  for (let i = 1; i <= SLAB_ROWS; i += 1) {
    const to = values[`slab_to_${i}`]?.trim() ?? '';
    const rate = values[`slab_rate_${i}`]?.trim() ?? '';
    if (!to && !rate) continue;
    // Missing halves stay undefined so the API schema reports them.
    slabs.push({ from_hour: from, to_hour: to || undefined, rate: rate || undefined });
    if (isNumber(to)) from = Number(to);
  }
  if (slabs.length) out.rate_slabs = slabs;
  return out;
}

/** Stored row → editor values. */
export function slabFormValues(row: Row | null): FormValues {
  const out: FormValues = {};
  const slabs = (row?.rate_slabs ?? []) as RateSlab[];
  slabs.forEach((s, i) => {
    out[`slab_to_${i + 1}`] = String(s.to_hour);
    out[`slab_rate_${i + 1}`] = String(s.rate);
  });
  return out;
}

/** Editor rows for the view: from-hour (derived), inputs, visibility. */
export function slabEditorRows(values: FormValues) {
  const rows = [];
  let from = 0;
  let lastFilled = 0;
  for (let i = 1; i <= SLAB_ROWS; i += 1) {
    if (values[`slab_to_${i}`] || values[`slab_rate_${i}`]) lastFilled = i;
  }
  for (let i = 1; i <= SLAB_ROWS; i += 1) {
    const to = values[`slab_to_${i}`] ?? '';
    rows.push({
      i,
      from,
      to,
      rate: values[`slab_rate_${i}`] ?? '',
      open: i <= Math.max(1, lastFilled),
    });
    if (isNumber(to)) from = Number(to);
  }
  return rows;
}

const slabsOfRow = (row: Row) =>
  Array.isArray(row.rate_slabs) ? (row.rate_slabs as RateSlab[]) : [];

/** "0–24 h ₹30 · 24–48 h ₹10, then ₹10 every 24 h". */
export function slabSummary(row: Row): string {
  const slabs = slabsOfRow(row);
  if (!slabs.length) return '';
  const last = slabs[slabs.length - 1]!;
  return `${slabs
    .map((s) => `${s.from_hour}–${s.to_hour} h ${inr(s.rate, true)}`)
    .join(' · ')}, then ${inr(last.rate, true)} every ${last.to_hour - last.from_hour} h`;
}

/** Example: one bag over 1, 2 and 3 days. */
export function slabExample(row: Row): string {
  const slabs = slabsOfRow(row);
  if (!slabs.length) return '';
  return [1, 2, 3]
    .map((d) => `${d * 24} h ${inr(fromPaise(slabCharge(d * 24 * 60, slabs).perBagPaise), true)}`)
    .join(' · ')
    .concat(' per bag');
}

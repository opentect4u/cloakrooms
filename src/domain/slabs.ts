/**
 * Tariff slabs ("Price Range", Phase 13). Pure functions, money in integer paise.
 *
 *   slabs = [{from_hour, to_hour, rate}, …]   contiguous, the first starts at hour 0
 *   A bag pays the rate of every slab its stay has started (the first slab always), and after
 *   the last slab ends, the last slab's rate again for every started (to_hour − from_hour).
 *
 *   0–24 h ₹30                       2 bags × 2 days → 2 × (30 + 30)       = ₹120
 *   0–24 h ₹30, 24–48 h ₹10          2 bags × 3 days → 2 × (30 + 10 + 10)  = ₹100
 *
 * The older two-rate tariff (block_hours, first_block_rate, next_block_rate) is the same thing
 * as [0, B) first + [B, 2B) next, so it converts both ways (`legacySlabs`, `slabsToLegacy`).
 */
import { fromPaise, toPaise } from '../core/utils/money';

export interface RateSlab {
  from_hour: number;
  to_hour: number;
  /** Per bag, 2-decimal string. */
  rate: string;
}

/** Two-rate tariff stored in md_rate_dtls / td_receipt before Phase 13. */
export interface Tariff {
  block_hours: number;
  first_block_rate: string;
  next_block_rate: string;
}

export const MAX_SLABS = 20;
export const MAX_SLAB_HOUR = 8760;

/** Why the slab list is not usable, or null. */
export function slabsProblem(slabs: readonly RateSlab[]): string | null {
  if (slabs.length < 1) return 'at least one slab is needed';
  if (slabs.length > MAX_SLABS) return `at most ${MAX_SLABS} slabs`;
  let expectedFrom = 0;
  for (const [i, s] of slabs.entries()) {
    const n = i + 1;
    if (!Number.isInteger(s.from_hour) || !Number.isInteger(s.to_hour)) {
      return `slab ${n}: hours must be whole numbers`;
    }
    if (s.from_hour !== expectedFrom) {
      return i === 0
        ? 'the first slab must start at hour 0'
        : `slab ${n} must start at hour ${expectedFrom}, where slab ${i} ends`;
    }
    if (s.to_hour <= s.from_hour) return `slab ${n}: to_hour must be after from_hour`;
    if (s.to_hour > MAX_SLAB_HOUR) return `slab ${n}: to_hour must be at most ${MAX_SLAB_HOUR}`;
    expectedFrom = s.to_hour;
  }
  return null;
}

function assertSlabs(slabs: readonly RateSlab[]): void {
  const problem = slabsProblem(slabs);
  if (problem) throw new RangeError(`Invalid slabs: ${problem}`);
}

/** Same slabs (amounts compared in paise). */
export function sameSlabs(a: readonly RateSlab[], b: readonly RateSlab[]): boolean {
  return (
    a.length === b.length &&
    a.every(
      (s, i) =>
        s.from_hour === b[i]!.from_hour &&
        s.to_hour === b[i]!.to_hour &&
        toPaise(s.rate) === toPaise(b[i]!.rate),
    )
  );
}

/** Canonical form: rates as 2-decimal strings. */
export function normaliseSlabs(slabs: readonly RateSlab[]): RateSlab[] {
  return slabs.map((s) => ({
    from_hour: s.from_hour,
    to_hour: s.to_hour,
    rate: fromPaise(toPaise(s.rate)),
  }));
}

/** Two-rate tariff → slabs: one slab when both rates are equal, else two. */
export function legacySlabs(t: Tariff): RateSlab[] {
  const first = fromPaise(toPaise(t.first_block_rate));
  const next = fromPaise(toPaise(t.next_block_rate));
  const b = t.block_hours;
  return first === next
    ? [{ from_hour: 0, to_hour: b, rate: first }]
    : [
        { from_hour: 0, to_hour: b, rate: first },
        { from_hour: b, to_hour: 2 * b, rate: next },
      ];
}

/**
 * Closest two-rate tariff (kept in the old columns for older apps): first slab's length and
 * rate, then the second slab's rate (the first's when there is only one).
 */
export function slabsToLegacy(slabs: readonly RateSlab[]): Tariff {
  assertSlabs(slabs);
  const first = slabs[0]!;
  return {
    block_hours: first.to_hour,
    first_block_rate: fromPaise(toPaise(first.rate)),
    next_block_rate: fromPaise(toPaise((slabs[1] ?? first).rate)),
  };
}

/**
 * True when an app that only knows the two-rate tariff bills these slabs correctly: one slab,
 * or two slabs of the same length (first block, then the next rate repeating).
 */
export function isLegacyCompatible(slabs: readonly RateSlab[]): boolean {
  assertSlabs(slabs);
  if (slabs.length === 1) return true;
  if (slabs.length > 2) return false;
  const [a, b] = slabs as [RateSlab, RateSlab];
  return b.to_hour - b.from_hour === a.to_hour - a.from_hour;
}

/** Blocks charged and the amount for ONE bag over `minutes` billable minutes. */
export function slabCharge(
  minutes: number,
  slabs: readonly RateSlab[],
): { blocks: number; perBagPaise: number } {
  if (!Number.isInteger(minutes) || minutes < 0)
    throw new RangeError(`Invalid minutes: ${minutes}`);
  assertSlabs(slabs);
  let blocks = 0;
  let paise = 0;
  for (const [i, s] of slabs.entries()) {
    if (i === 0 || minutes > s.from_hour * 60) {
      blocks += 1;
      paise += toPaise(s.rate);
    }
  }
  const last = slabs[slabs.length - 1]!;
  const end = last.to_hour * 60;
  if (minutes > end) {
    const repeats = Math.ceil((minutes - end) / ((last.to_hour - last.from_hour) * 60));
    blocks += repeats;
    paise += repeats * toPaise(last.rate);
  }
  return { blocks, perBagPaise: paise };
}

/** Balance collected at check-out: the bill minus the advance, never below 0 (no refund). */
export function balancePaise(netPaise: number, advancePaise: number): number {
  return Math.max(0, netPaise - advancePaise);
}

/** Tariff validation and the slabs ↔ two-rate bookkeeping (shared by the API, panel and sync). */
import { z } from 'zod';
import type { Row } from '../../core/db/dml';
import { ApiError } from '../../core/http/ApiError';
import { dateOnly, money } from '../../core/validation/fragments';
import {
  legacySlabs,
  MAX_SLAB_HOUR,
  MAX_SLABS,
  normaliseSlabs,
  slabsProblem,
  slabsToLegacy,
  type RateSlab,
} from '../../domain/slabs';

const blockHours = z.coerce.number().int().min(1).max(720);

/** Tariff slabs (Phase 13): contiguous from hour 0; the last one repeats. */
export const rateSlabs = z
  .array(
    z.object({
      from_hour: z.coerce.number().int().min(0).max(MAX_SLAB_HOUR),
      to_hour: z.coerce.number().int().min(1).max(MAX_SLAB_HOUR),
      rate: money,
    }),
  )
  .min(1)
  .max(MAX_SLABS)
  .superRefine((slabs, ctx) => {
    const problem = slabsProblem(slabs);
    if (problem) ctx.addIssue({ code: 'custom', message: problem });
  });

/**
 * Per-bag tariff, versioned by effective_from (one row per date per customer). Send
 * `rate_slabs` (Phase 13) or the older two-rate fields; the server keeps both in step:
 * slabs → block_hours = first slab's length, first/next = first/second slab's rate;
 * two-rate → [0, B) first + [B, 2B) next (one slab when both rates are equal).
 */
export const rateCreate = z.object({
  rate_slabs: rateSlabs.optional(),
  block_hours: blockHours.optional(),
  first_block_rate: money.optional(),
  next_block_rate: money.optional(),
  effective_from: dateOnly,
});

export const rateUpdate = z
  .object({
    rate_slabs: rateSlabs,
    block_hours: blockHours,
    first_block_rate: money,
    next_block_rate: money,
    effective_from: dateOnly,
  })
  .partial();

const LEGACY = ['block_hours', 'first_block_rate', 'next_block_rate'] as const;

function withSlabs(data: Row, slabs: RateSlab[]): Row {
  const normal = normaliseSlabs(slabs);
  return { ...data, ...slabsToLegacy(normal), rate_slabs: JSON.stringify(normal) };
}

/** Fills the representation that was not sent (slabs ↔ two-rate). */
export function rateCreateData(data: Row): Row {
  if (data.rate_slabs) {
    const given = LEGACY.filter((f) => data[f] !== undefined);
    if (given.length) {
      throw ApiError.validation(
        given.map((f) => ({ path: f, message: 'Send either rate_slabs or the two-rate fields' })),
      );
    }
    return withSlabs(data, data.rate_slabs as RateSlab[]);
  }
  const missing = (['first_block_rate', 'next_block_rate'] as const).filter(
    (f) => data[f] === undefined,
  );
  if (missing.length) {
    throw ApiError.validation(
      missing.map((f) => ({ path: f, message: 'Required when rate_slabs is not given' })),
    );
  }
  return withSlabs(
    data,
    legacySlabs({
      block_hours: Number(data.block_hours ?? 24),
      first_block_rate: String(data.first_block_rate),
      next_block_rate: String(data.next_block_rate),
    }),
  );
}

export function rateUpdateData(data: Row, existing: Row): Row {
  if (data.rate_slabs) {
    if (LEGACY.some((f) => data[f] !== undefined)) {
      throw ApiError.validation([
        { path: 'rate_slabs', message: 'Send either rate_slabs or the two-rate fields' },
      ]);
    }
    return withSlabs(data, data.rate_slabs as RateSlab[]);
  }
  if (!LEGACY.some((f) => data[f] !== undefined)) return data;
  // An older app edits the two-rate tariff: that becomes the slabs.
  return withSlabs(
    data,
    legacySlabs({
      block_hours: Number(data.block_hours ?? existing.block_hours),
      first_block_rate: String(data.first_block_rate ?? existing.first_block_rate),
      next_block_rate: String(data.next_block_rate ?? existing.next_block_rate),
    }),
  );
}

import { z } from 'zod';
import { daysBetween } from '../../core/utils/time';
import { dateOnly, pagination, positiveId, ynEnum } from '../../core/validation/fragments';

export const MAX_RANGE_DAYS = 366;

/** `from`/`to` are IST calendar dates, both inclusive. */
const range = { from: dateOnly, to: dateOnly };

function checkRange(q: { from?: string; to?: string }, ctx: z.RefinementCtx) {
  if (!q.from || !q.to) return;
  const span = daysBetween(q.from, q.to);
  if (span < 0) ctx.addIssue({ code: 'custom', path: ['to'], message: 'must be on or after from' });
  if (span > MAX_RANGE_DAYS) {
    ctx.addIssue({
      code: 'custom',
      path: ['to'],
      message: `range is limited to ${MAX_RANGE_DAYS} days`,
    });
  }
}

const common = {
  /** Super admin: required (reports are per customer). Ignored for A/O. */
  customer_id: positiveId.optional(),
  user_id: positiveId.optional(),
  shift_id: positiveId.optional(),
};

export const rangeQuery = pagination
  .extend({ ...range, ...common, pay_mode: z.enum(['C', 'U']).optional() })
  .superRefine(checkRange);
export type RangeQuery = z.infer<typeof rangeQuery>;

export const bagsInStoreQuery = pagination
  .extend({
    ...common,
    /** Optional check-in date window (IST). */
    from: dateOnly.optional(),
    to: dateOnly.optional(),
  })
  .superRefine(checkRange);
export type BagsInStoreQuery = z.infer<typeof bagsInStoreQuery>;

export const exceptionsQuery = pagination
  .extend({
    customer_id: positiveId.optional(),
    from: dateOnly.optional(),
    to: dateOnly.optional(),
    resolved: ynEnum.optional(),
    table_name: z.enum(['td_luggage_in_out', 'td_receipt']).optional(),
  })
  .superRefine(checkRange);
export type ExceptionsQuery = z.infer<typeof exceptionsQuery>;

export const exceptionIdParams = z.object({ id: positiveId });

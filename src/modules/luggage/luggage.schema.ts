import { z } from 'zod';
import { pagination, uuid } from '../../core/validation/fragments';

export const luggageQuery = pagination.extend({
  /** Matched against PNR, mobile, passenger name and receipt no. */
  search: z.string().trim().min(1).max(100).optional(),
  status: z.enum(['in', 'out']).optional(),
});
export type LuggageQuery = z.infer<typeof luggageQuery>;

export const transIdParams = z.object({ trans_id: uuid });

import { z } from 'zod';
import { sortParam } from '../../core/utils/pagination';
import { idParams, pagination, positiveId, ynEnum } from '../../core/validation/fragments';

export { idParams };

const loginId = z
  .string()
  .trim()
  .min(3)
  .max(100)
  .regex(/^[A-Za-z0-9._@-]+$/, 'letters, digits and . _ @ - only');

/** Receipt/bill number prefix, e.g. U03 → U03-00125. */
const userCode = z
  .string()
  .trim()
  .toUpperCase()
  .regex(/^[A-Z0-9]{1,10}$/, '1–10 letters/digits');

export const password = z
  .string()
  .min(8, 'at least 8 characters')
  .max(128)
  .regex(/[A-Za-z]/, 'must contain a letter')
  .regex(/\d/, 'must contain a digit');

export const createUserBody = z.object({
  user_id: loginId,
  user_name: z.string().trim().min(1).max(100),
  user_code: userCode.optional(),
  password,
  user_type: z.enum(['S', 'A', 'O']),
  allow_flag: ynEnum.default('Y'),
  /** Super admin only; ignored for A (tenant comes from the token). */
  customer_id: positiveId.optional(),
});
export type CreateUserInput = z.infer<typeof createUserBody>;

export const updateUserBody = z
  .object({
    user_name: z.string().trim().min(1).max(100),
    user_code: userCode,
    user_type: z.enum(['A', 'O']),
  })
  .partial();
export type UpdateUserInput = z.infer<typeof updateUserBody>;

export const resetPasswordBody = z.object({ password });
export const allowBody = z.object({ allow_flag: ynEnum });

export const USER_SORTABLE = ['id', 'user_id', 'user_name', 'user_code', 'user_type', 'created_at'];

export const listUsersQuery = pagination.extend({
  sort: sortParam(USER_SORTABLE).optional(),
  search: z.string().trim().max(100).optional(),
  user_type: z.enum(['S', 'A', 'O']).optional(),
  allow_flag: ynEnum.optional(),
  /** Super admin filter; ignored for A. */
  customer_id: positiveId.optional(),
});
export type ListUsersQuery = z.infer<typeof listUsersQuery>;

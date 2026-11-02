import { z } from 'zod';
import { PAGINATION } from '../../config/constants';

/** Shared zod fragments (CLAUDE.md §15). Reuse these instead of re-declaring formats. */

export const ynEnum = z.enum(['Y', 'N']);

function isRealDate(s: string): boolean {
  const d = new Date(`${s.replace(' ', 'T')}Z`);
  return !Number.isNaN(d.getTime()) && d.toISOString().startsWith(s.slice(0, 10));
}

/** `YYYY-MM-DD HH:mm:ss`, UTC — the API wire format for datetimes. */
export const utcDateTime = z
  .string()
  .regex(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/, 'expected YYYY-MM-DD HH:mm:ss (UTC)')
  .refine(isRealDate, 'invalid date/time');

/** `YYYY-MM-DD`. */
export const dateOnly = z
  .string()
  .regex(/^\d{4}-\d{2}-\d{2}$/, 'expected YYYY-MM-DD')
  .refine(isRealDate, 'invalid date');

/** `HH:mm` or `HH:mm:ss` local time (shifts). */
export const timeOfDay = z
  .string()
  .regex(/^([01]\d|2[0-3]):[0-5]\d(:[0-5]\d)?$/, 'expected HH:mm or HH:mm:ss');

export const uuid = z.uuid().transform((v) => v.toLowerCase());

export const positiveId = z.coerce.number().int().positive().max(4_294_967_295);

export const idParams = z.object({ id: positiveId });

/** Money as a non-negative decimal with at most 2 places; kept as a string (no float math). */
export const money = z
  .union([z.string(), z.number()])
  .transform((v) => String(v).trim())
  .pipe(z.string().regex(/^\d{1,8}(\.\d{1,2})?$/, 'expected an amount with up to 2 decimals'));

/** Percentage 0–100 with at most 2 decimals, as a string. */
export const percent = z
  .union([z.string(), z.number()])
  .transform((v) => String(v).trim())
  .pipe(
    z
      .string()
      .regex(/^\d{1,3}(\.\d{1,2})?$/, 'expected a percentage with up to 2 decimals')
      .refine((v) => Number(v) <= 100, 'must be ≤ 100'),
  );

/** Y/N flag that also accepts true/false (handy for multipart form fields). */
export const ynFlag = z
  .union([ynEnum, z.boolean(), z.enum(['true', 'false'])])
  .transform((v) => (v === true || v === 'true' || v === 'Y' ? 'Y' : 'N'));

export const pagination = z.object({
  page: z.coerce.number().int().min(1).default(1),
  limit: z.coerce.number().int().min(1).max(PAGINATION.maxLimit).default(PAGINATION.defaultLimit),
});

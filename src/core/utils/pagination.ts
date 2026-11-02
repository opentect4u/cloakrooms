import { z } from 'zod';
import type { OrderBy } from '../db/dml';

/**
 * `?sort=col:asc,other:desc` validated against a whitelist → DML orderBy.
 * Direction defaults to asc.
 */
export function sortParam(allowed: readonly string[]) {
  const allow = new Set(allowed);
  return z
    .string()
    .max(200)
    .transform((value, ctx): OrderBy[] => {
      const out: OrderBy[] = [];
      for (const part of value.split(',').filter(Boolean)) {
        const [column = '', dir = 'asc'] = part.trim().split(':');
        if (!allow.has(column) || (dir !== 'asc' && dir !== 'desc')) {
          ctx.addIssue({
            code: 'custom',
            message: `sort must be one of ${[...allow].join(', ')} with :asc or :desc`,
          });
          return z.NEVER;
        }
        out.push({ column, dir });
      }
      return out;
    });
}

export function pageMeta(page: number, limit: number, total: number) {
  return { page, limit, total };
}

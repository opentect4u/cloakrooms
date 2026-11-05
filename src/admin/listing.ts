import { z } from 'zod';
import { positiveId } from '../core/validation/fragments';

export const PAGE_SIZE = 25;

/** ?page & ?q of a panel list; bad values fall back to defaults instead of erroring. */
export const listQuery = z.object({
  page: z.coerce.number().int().min(1).max(100_000).catch(1),
  q: z.string().trim().max(100).optional().catch(undefined),
});

export interface Pager {
  page: number;
  pages: number;
  total: number;
  from: number;
  to: number;
  prev: string | null;
  next: string | null;
}

/** Pagination links that keep the other query parameters. */
export function pager(
  path: string,
  query: Record<string, unknown>,
  page: number,
  limit: number,
  total: number,
): Pager {
  const pages = Math.max(1, Math.ceil(total / limit));
  const href = (p: number) => {
    const params = new URLSearchParams();
    for (const [k, v] of Object.entries(query)) {
      if (k !== 'page' && typeof v === 'string' && v !== '') params.set(k, v);
    }
    if (p > 1) params.set('page', String(p));
    const qs = params.toString();
    return qs ? `${path}?${qs}` : path;
  };
  return {
    page,
    pages,
    total,
    from: total === 0 ? 0 : (page - 1) * limit + 1,
    to: Math.min(total, page * limit),
    prev: page > 1 ? href(page - 1) : null,
    next: page < pages ? href(page + 1) : null,
  };
}

/** A positive integer route id, or null (→ 404). */
export function idParam(value: unknown): number | null {
  const parsed = positiveId.safeParse(value);
  return parsed.success ? parsed.data : null;
}

/** Online lookup of bags (e.g. check-out of a bag checked in on a device that has not synced). */
import type { RequestContext } from '../../core/context';
import { fetch, fetchOne, type Where } from '../../core/db/dml';
import { ApiError } from '../../core/http/ApiError';
import { pageMeta } from '../../core/utils/pagination';
import type { LuggageQuery } from './luggage.schema';

const SEARCH_COLUMNS = ['pnr_no', 'pasn_mob_no', 'pasn_name', 'receipt_no'];

export async function searchLuggage(ctx: RequestContext, q: LuggageQuery) {
  const where: Where = {};
  if (q.status) where.out_flag = q.status === 'in' ? 'N' : 'Y';
  const { rows, total } = await fetch(
    'td_luggage_in_out',
    {
      where,
      search: q.search ? { columns: SEARCH_COLUMNS, term: q.search } : undefined,
      orderBy: [{ column: 'date_time_in', dir: 'desc' }],
      page: q.page,
      limit: q.limit,
    },
    ctx,
  );
  return { rows, meta: pageMeta(q.page, q.limit, total) };
}

export async function getLuggage(ctx: RequestContext, transId: string) {
  const bag = await fetchOne('td_luggage_in_out', { trans_id: transId }, ctx);
  if (!bag) throw ApiError.notFound('Luggage not found');
  const receipt = await fetchOne('td_receipt', { trans_id: transId }, ctx);
  return { ...bag, receipt };
}

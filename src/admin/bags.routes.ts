/**
 * /admin/bags — online bag lookup for support: by PNR, mobile, name or receipt no., across all
 * customers or one (luggage.service, which the stations' devices also use). Detail = the luggage
 * row, its bill, and who did what when.
 */
import { Router } from 'express';
import { z } from 'zod';
import { fetch } from '../core/db/dml';
import { ApiError } from '../core/http/ApiError';
import { ctxOf } from '../core/middleware/requireRole';
import { uuid } from '../core/validation/fragments';
import { getLuggage, searchLuggage } from '../modules/luggage/luggage.service';
import { idParam, pager, PAGE_SIZE } from './listing';
import { customerOptions } from './lookups';
import { handle, page } from './render';
import { tenantCtx } from './tenant';

const searchQuery = z.object({
  q: z.string().trim().max(100).optional().catch(undefined),
  customer_id: z.string().optional().catch(undefined),
  status: z.enum(['in', 'out']).optional().catch(undefined),
  page: z.coerce.number().int().min(1).max(10_000).catch(1),
});

/** id → label for a handful of ids of one table (names on the detail page). */
async function names(
  table: string,
  pk: string,
  label: string,
  ids: unknown[],
  ctx: Parameters<typeof fetch>[2],
) {
  const wanted = [...new Set(ids.filter((v) => v !== null && v !== undefined).map(Number))];
  if (!wanted.length) return new Map<number, string>();
  const { rows } = await fetch(
    table,
    { where: { [pk]: { in: wanted } }, columns: [pk, label], includeDeleted: true },
    ctx,
  );
  return new Map(rows.map((r) => [Number(r[pk]), String(r[label])]));
}

export function bagsRoutes(): Router {
  const router = Router();

  router.get(
    '/',
    handle(async (req, res) => {
      const ctx = ctxOf(req);
      const q = searchQuery.parse(req.query);
      const cid = idParam(q.customer_id);
      const customers = await customerOptions(ctx);
      const customerName = new Map(customers.map(([id, name]) => [Number(id), name]));
      // One customer: tenant context (the DML layer scopes the search); none: all customers.
      const scope = cid ? tenantCtx(ctx, cid) : ctx;
      const { rows, meta } = await searchLuggage(scope, {
        search: q.q || undefined,
        status: q.status,
        page: q.page,
        limit: PAGE_SIZE,
      });
      page(res, 'admin/bags', {
        title: 'Bag search',
        active: 'bags',
        customers,
        cid,
        q: q.q ?? '',
        status: q.status ?? '',
        rows: rows.map((r) => ({
          ...r,
          customer_name: customerName.get(Number(r.customer_id)) ?? `#${String(r.customer_id)}`,
        })),
        pager: pager('/admin/bags', req.query, q.page, PAGE_SIZE, meta.total),
      });
    }),
  );

  router.get(
    '/:transId',
    handle(async (req, res) => {
      const parsed = uuid.safeParse(req.params.transId);
      if (!parsed.success) throw ApiError.notFound('Bag not found');
      const ctx = ctxOf(req);
      const bag = (await getLuggage(ctx, parsed.data)) as Record<string, unknown> & {
        receipt: Record<string, unknown> | null;
      };
      const receipt = bag.receipt;
      const [customers, users, shifts, proofs] = await Promise.all([
        names('md_customer', 'customer_id', 'customer_name', [bag.customer_id], ctx),
        names(
          'md_user',
          'id',
          'user_name',
          [bag.user_id_in, bag.user_id_out, receipt?.user_id],
          ctx,
        ),
        names('md_shift', 'shift_id', 'shift_name', [bag.shift_id_in, bag.shift_id_out], ctx),
        names('md_id_proof', 'id_proof_id', 'id_proof_name', [bag.id_proof_id], ctx),
      ]);
      page(res, 'admin/bag', {
        title: `Receipt ${String(bag.receipt_no)}`,
        active: 'bags',
        bag,
        receipt,
        customerName: customers.get(Number(bag.customer_id)) ?? '',
        userName: (id: unknown) => (id ? (users.get(Number(id)) ?? `#${String(id)}`) : '—'),
        shiftName: (id: unknown) => (id ? (shifts.get(Number(id)) ?? `#${String(id)}`) : ''),
        proofName: proofs.get(Number(bag.id_proof_id)) ?? '',
      });
    }),
  );

  return router;
}

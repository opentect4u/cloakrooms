/**
 * Config → list / get / create / update / soft-delete routes for a master table (CLAUDE.md §8,
 * BUILD_PROMPT Phase 2). Mount the returned router behind the auth middleware.
 *
 *   GET    /       ?page&limit&sort=col:asc&search=…&<filterable>=…
 *   GET    /:id
 *   POST   /        body: schema.create
 *   PATCH  /:id     body: schema.update
 *   DELETE /:id     soft delete (is_deleted = 'Y')
 *
 * The data logic lives in `crudService` (shared with the admin panel); this file only does HTTP:
 * roles, validation and the response envelope. Tenant and parent rules: see crudService.
 */
import { Router, type Request, type Response } from 'express';
import { z } from 'zod';
import { asyncHandler } from '../http/asyncHandler';
import { sendSuccess } from '../http/response';
import { ctxOf, requireRole } from '../middleware/requireRole';
import { validate } from '../middleware/validate';
import { pagination, positiveId, uuid } from '../validation/fragments';
import { pageMeta, sortParam } from '../utils/pagination';
import { crudService } from './crudService';
import type { CrudConfig } from './crudTypes';

export type { CrudConfig, WriteEvent } from './crudTypes';
export { serializeRow } from './crudService';

/**
 * The request schemas a crudFactory router validates with. Shared with the OpenAPI document (so
 * the published contract is exactly what the routes enforce) and with the admin panel forms.
 */
export function crudRequestSchemas(config: CrudConfig) {
  const tenant = config.scope === 'tenant';
  const parentShape = config.parent ? { [config.parent.param]: positiveId } : {};
  const filterShape: Record<string, z.ZodOptional<z.ZodString>> = {};
  for (const col of [...(config.filterable ?? []), ...(tenant ? ['customer_id'] : [])]) {
    filterShape[col] = z.string().max(100).optional();
  }
  return {
    collectionParams: z.object(parentShape),
    itemParams: z.object({
      ...parentShape,
      id: config.pkType === 'uuid' ? uuid : positiveId,
    }),
    filterShape,
    listQuery: pagination.extend({
      sort: sortParam(config.sortable ?? [config.pk]).optional(),
      search: z.string().trim().max(100).optional(),
      ...filterShape,
    }),
    createSchema: tenant
      ? config.schema.create.extend({ customer_id: positiveId.optional() })
      : config.schema.create,
    updateSchema: config.schema.update,
  };
}

export function crudFactory(config: CrudConfig): Router {
  const router = Router({ mergeParams: true });
  const service = crudService(config);
  const { collectionParams, itemParams, filterShape, listQuery, createSchema } =
    crudRequestSchemas(config);

  const parentIdOf = (req: Request) =>
    config.parent ? Number(req.params[config.parent.param]) : undefined;

  router.get(
    '/',
    requireRole(...config.roles.read),
    validate({ params: collectionParams, query: listQuery }),
    asyncHandler(async (req: Request, res: Response) => {
      const q = req.query as unknown as z.infer<typeof listQuery> &
        Record<string, string | undefined>;
      const filters: Record<string, string> = {};
      for (const col of Object.keys(filterShape)) {
        const v = q[col];
        if (v !== undefined) filters[col] = v;
      }
      const { rows, total } = await service.list(ctxOf(req), {
        parentId: parentIdOf(req),
        filters,
        page: q.page,
        limit: q.limit,
        sort: q.sort,
        search: q.search,
      });
      sendSuccess(req, res, rows, { meta: pageMeta(q.page, q.limit, total) });
    }),
  );

  router.get(
    '/:id',
    requireRole(...config.roles.read),
    validate({ params: itemParams }),
    asyncHandler(async (req: Request, res: Response) => {
      const row = await service.get(ctxOf(req), req.params.id as string, parentIdOf(req));
      sendSuccess(req, res, row);
    }),
  );

  router.post(
    '/',
    requireRole(...config.roles.write),
    validate({ params: collectionParams, body: createSchema }),
    asyncHandler(async (req: Request, res: Response) => {
      const row = await service.create(ctxOf(req), req.body, parentIdOf(req));
      sendSuccess(req, res, row, { status: 201, message: 'Created' });
    }),
  );

  router.patch(
    '/:id',
    requireRole(...config.roles.write),
    validate({ params: itemParams, body: config.schema.update }),
    asyncHandler(async (req: Request, res: Response) => {
      const row = await service.update(
        ctxOf(req),
        req.params.id as string,
        req.body,
        parentIdOf(req),
      );
      sendSuccess(req, res, row, { message: 'Updated' });
    }),
  );

  router.delete(
    '/:id',
    requireRole(...config.roles.write),
    validate({ params: itemParams }),
    asyncHandler(async (req: Request, res: Response) => {
      await service.remove(ctxOf(req), req.params.id as string, parentIdOf(req));
      sendSuccess(req, res, { [config.pk]: req.params.id }, { message: 'Deleted' });
    }),
  );

  return router;
}

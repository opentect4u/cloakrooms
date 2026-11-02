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
 * Tenant scope: customer_id is forced from the token by the DML layer. A super admin (S) has no
 * tenant, so for S only, `customer_id` is accepted in the create body and as a list filter.
 *
 * Nested resources (`parent`): mount at e.g. `/customers/:customerId/registrations`; the parent
 * row must exist (404 otherwise) and its id is forced into every read and write.
 */
import { Router, type Request, type Response } from 'express';
import { z } from 'zod';
import type { PoolConnection } from 'mysql2/promise';
import type { RequestContext, UserType } from '../context';
import {
  fetch,
  fetchOne,
  save,
  softDelete,
  SENSITIVE_COLUMNS,
  withTransaction,
  type Executor,
  type OrderBy,
  type Row,
  type Where,
} from '../db/dml';
import { getTable } from '../db/registry';
import { ApiError } from '../http/ApiError';
import { asyncHandler } from '../http/asyncHandler';
import { sendSuccess } from '../http/response';
import { ctxOf, requireRole } from '../middleware/requireRole';
import { validate } from '../middleware/validate';
import { pagination, positiveId, uuid } from '../validation/fragments';
import { pageMeta, sortParam } from '../utils/pagination';

type Hook<A extends unknown[]> = (...args: A) => Row | Promise<Row>;

export type WriteEvent = 'create' | 'update' | 'delete';

export interface CrudConfig {
  table: string;
  pk: string;
  pkType?: 'int' | 'uuid';
  scope: 'tenant' | 'global';
  roles: { read: readonly UserType[]; write: readonly UserType[] };
  schema: { create: z.ZodObject; update: z.ZodObject };
  searchable?: readonly string[];
  sortable?: readonly string[];
  filterable?: readonly string[];
  defaultSort?: readonly OrderBy[];
  /** Columns never returned (in addition to the global sensitive ones). */
  hidden?: readonly string[];
  /** Nested under a parent row: `param` is the route param, `column` the FK in this table. */
  parent?: { param: string; column: string; table: string; pk: string };
  /**
   * Natural key columns of a UNIQUE index. Creating a row whose key matches a soft-deleted row
   * revives that row (soft-deleted rows still occupy the unique key).
   */
  reviveDeletedOn?: readonly string[];
  /** Output transform applied after serialisation (e.g. masking secrets). */
  transformRow?: (row: Row) => Row;
  hooks?: {
    /** Transform/validate data before insert (runs in the write transaction). */
    beforeCreate?: Hook<[data: Row, ctx: RequestContext, conn: PoolConnection]>;
    beforeUpdate?: Hook<[data: Row, existing: Row, ctx: RequestContext, conn: PoolConnection]>;
    /** Side effects in the same transaction after a create/update/delete. */
    afterWrite?: (
      event: WriteEvent,
      info: { id: string | number; parentId?: number; row: Row | null },
      ctx: RequestContext,
      conn: PoolConnection,
    ) => void | Promise<void>;
  };
}

/** Buffers (MEDIUMBLOB) go out as base64 strings. */
export function serializeRow(row: Row): Row {
  const out: Row = {};
  for (const [k, v] of Object.entries(row)) out[k] = Buffer.isBuffer(v) ? v.toString('base64') : v;
  return out;
}

/**
 * The request schemas a crudFactory router validates with. Shared with the OpenAPI document so
 * the published contract is exactly what the routes enforce.
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
  const tenant = config.scope === 'tenant';
  const hidden = new Set([...(config.hidden ?? []), ...SENSITIVE_COLUMNS]);
  const { parent, hooks } = config;
  const output = (row: Row) => {
    const out = serializeRow(row);
    return config.transformRow ? config.transformRow(out) : out;
  };

  // Resolved lazily: the registry is loaded at boot, after routes are built.
  const visibleColumns = () =>
    [...getTable(config.table).columns.keys()].filter((c) => !hidden.has(c));

  const { collectionParams, itemParams, filterShape, listQuery, createSchema } =
    crudRequestSchemas(config);

  /** Checks the parent exists (tenant-scoped) and returns where-conditions binding to it. */
  async function parentScope(req: Request, ctx: RequestContext, conn?: Executor) {
    if (!parent) return { where: {} as Where, parentId: undefined };
    const parentId = Number(req.params[parent.param]);
    const found = await fetchOne(parent.table, { [parent.pk]: parentId }, ctx, conn, {
      columns: [parent.pk],
    });
    if (!found) throw ApiError.notFound('Parent record not found');
    return { where: { [parent.column]: parentId } as Where, parentId };
  }

  const getById = (id: string | number, scope: Where, ctx: RequestContext, conn?: Executor) =>
    fetchOne(config.table, { ...scope, [config.pk]: id }, ctx, conn, {
      columns: visibleColumns(),
    });

  /** Id of a soft-deleted row with the same natural key, if any. */
  async function deletedTwin(data: Row, ctx: RequestContext, conn: PoolConnection) {
    if (!config.reviveDeletedOn) return undefined;
    const match: Where = { is_deleted: 'Y' };
    for (const col of config.reviveDeletedOn) match[col] = (data[col] ?? null) as Where[string];
    if (tenant && data.customer_id !== undefined) match.customer_id = data.customer_id as number;
    const twin = await fetchOne(config.table, match, ctx, conn, {
      columns: [config.pk],
      includeDeleted: true,
    });
    return twin?.[config.pk] as string | number | undefined;
  }

  router.get(
    '/',
    requireRole(...config.roles.read),
    validate({ params: collectionParams, query: listQuery }),
    asyncHandler(async (req: Request, res: Response) => {
      const ctx = ctxOf(req);
      const { where } = await parentScope(req, ctx);
      const q = req.query as unknown as z.infer<typeof listQuery> &
        Record<string, string | undefined>;
      for (const col of Object.keys(filterShape)) {
        const v = q[col];
        if (v !== undefined) where[col] = v;
      }
      const { rows, total } = await fetch(
        config.table,
        {
          where,
          columns: visibleColumns(),
          orderBy: q.sort ?? config.defaultSort,
          page: q.page,
          limit: q.limit,
          search: q.search ? { columns: config.searchable ?? [], term: q.search } : undefined,
        },
        ctx,
      );
      sendSuccess(req, res, rows.map(output), { meta: pageMeta(q.page, q.limit, total) });
    }),
  );

  router.get(
    '/:id',
    requireRole(...config.roles.read),
    validate({ params: itemParams }),
    asyncHandler(async (req: Request, res: Response) => {
      const ctx = ctxOf(req);
      const { where } = await parentScope(req, ctx);
      const row = await getById(req.params.id as string, where, ctx);
      if (!row) throw ApiError.notFound();
      sendSuccess(req, res, output(row));
    }),
  );

  router.post(
    '/',
    requireRole(...config.roles.write),
    validate({ params: collectionParams, body: createSchema }),
    asyncHandler(async (req: Request, res: Response) => {
      const ctx = ctxOf(req);
      let data: Row = { ...req.body };
      if (tenant && ctx.userType === 'S' && data.customer_id === undefined) {
        throw ApiError.validation([{ path: 'customer_id', message: 'Required for super admin' }]);
      }
      const row = await withTransaction(async (conn) => {
        const { where, parentId } = await parentScope(req, ctx, conn);
        Object.assign(data, where);
        if (hooks?.beforeCreate) data = await hooks.beforeCreate(data, ctx, conn);

        let id = await deletedTwin(data, ctx, conn);
        if (id !== undefined) {
          await save(
            config.table,
            { ...data, is_deleted: 'N' },
            { flag: 'U', where: { [config.pk]: id, is_deleted: 'Y' } },
            ctx,
            conn,
          );
        } else {
          const { insertId } = await save(config.table, data, { flag: 'I' }, ctx, conn);
          id = insertId || (data[config.pk] as string | number);
        }
        const created = await getById(id, where, ctx, conn);
        await hooks?.afterWrite?.('create', { id, parentId, row: created }, ctx, conn);
        return created;
      });
      sendSuccess(req, res, row && output(row), { status: 201, message: 'Created' });
    }),
  );

  router.patch(
    '/:id',
    requireRole(...config.roles.write),
    validate({ params: itemParams, body: config.schema.update }),
    asyncHandler(async (req: Request, res: Response) => {
      const ctx = ctxOf(req);
      const id = req.params.id as string;
      const row = await withTransaction(async (conn) => {
        const { where, parentId } = await parentScope(req, ctx, conn);
        const existing = await getById(id, where, ctx, conn);
        if (!existing) throw ApiError.notFound();
        let data: Row = { ...req.body };
        if (hooks?.beforeUpdate) data = await hooks.beforeUpdate(data, existing, ctx, conn);
        await save(
          config.table,
          data,
          { flag: 'U', where: { ...where, [config.pk]: id } },
          ctx,
          conn,
        );
        const updated = await getById(id, where, ctx, conn);
        await hooks?.afterWrite?.('update', { id, parentId, row: updated }, ctx, conn);
        return updated;
      });
      sendSuccess(req, res, row && output(row), { message: 'Updated' });
    }),
  );

  router.delete(
    '/:id',
    requireRole(...config.roles.write),
    validate({ params: itemParams }),
    asyncHandler(async (req: Request, res: Response) => {
      const ctx = ctxOf(req);
      const id = req.params.id as string;
      await withTransaction(async (conn) => {
        const { where, parentId } = await parentScope(req, ctx, conn);
        const affected = await softDelete(config.table, { ...where, [config.pk]: id }, ctx, conn);
        if (!affected) throw ApiError.notFound();
        await hooks?.afterWrite?.('delete', { id, parentId, row: null }, ctx, conn);
      });
      sendSuccess(req, res, { [config.pk]: req.params.id }, { message: 'Deleted' });
    }),
  );

  return router;
}

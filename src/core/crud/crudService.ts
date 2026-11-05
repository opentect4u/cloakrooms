/**
 * The data operations behind crudFactory, callable without HTTP (the API router and the admin
 * panel share them). Inputs must already be validated with the schemas of `crudRequestSchemas`.
 *
 * Tenant scope: customer_id is forced from the context by the DML layer. A super admin (S) has no
 * tenant, so for S only, `customer_id` is required in create data and accepted as a list filter.
 *
 * Nested resources (`parent`): pass `parentId`; the parent row must exist (404 otherwise) and its
 * id is forced into every read and write.
 */
import type { PoolConnection } from 'mysql2/promise';
import type { RequestContext } from '../context';
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
import type { CrudConfig } from './crudTypes';

/** Buffers (MEDIUMBLOB) go out as base64 strings. */
export function serializeRow(row: Row): Row {
  const out: Row = {};
  for (const [k, v] of Object.entries(row)) out[k] = Buffer.isBuffer(v) ? v.toString('base64') : v;
  return out;
}

export interface ListParams {
  parentId?: number;
  /** Exact-match filters, already restricted to the config's filterable columns. */
  filters?: Record<string, string | number>;
  page: number;
  limit: number;
  sort?: readonly OrderBy[];
  search?: string;
}

export type CrudService = ReturnType<typeof crudService>;

export function crudService(config: CrudConfig) {
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

  /** Checks the parent exists (tenant-scoped) and returns where-conditions binding to it. */
  async function parentScope(parentId: number | undefined, ctx: RequestContext, conn?: Executor) {
    if (!parent) return {} as Where;
    if (parentId === undefined) throw ApiError.notFound('Parent record not found');
    const found = await fetchOne(parent.table, { [parent.pk]: parentId }, ctx, conn, {
      columns: [parent.pk],
    });
    if (!found) throw ApiError.notFound('Parent record not found');
    return { [parent.column]: parentId } as Where;
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

  return {
    config,

    async list(ctx: RequestContext, p: ListParams) {
      const where = { ...(await parentScope(p.parentId, ctx)), ...(p.filters ?? {}) };
      const { rows, total } = await fetch(
        config.table,
        {
          where,
          columns: visibleColumns(),
          orderBy: p.sort ?? config.defaultSort,
          page: p.page,
          limit: p.limit,
          search: p.search ? { columns: config.searchable ?? [], term: p.search } : undefined,
        },
        ctx,
      );
      return { rows: rows.map(output), total };
    },

    /** One row or 404. */
    async get(ctx: RequestContext, id: string | number, parentId?: number) {
      const row = await getById(id, await parentScope(parentId, ctx), ctx);
      if (!row) throw ApiError.notFound();
      return output(row);
    },

    async create(ctx: RequestContext, input: Row, parentId?: number) {
      let data: Row = { ...input };
      if (tenant && ctx.userType === 'S' && data.customer_id === undefined) {
        throw ApiError.validation([{ path: 'customer_id', message: 'Required for super admin' }]);
      }
      const row = await withTransaction(async (conn) => {
        const where = await parentScope(parentId, ctx, conn);
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
      return row && output(row);
    },

    async update(ctx: RequestContext, id: string | number, input: Row, parentId?: number) {
      const row = await withTransaction(async (conn) => {
        const where = await parentScope(parentId, ctx, conn);
        const existing = await getById(id, where, ctx, conn);
        if (!existing) throw ApiError.notFound();
        let data: Row = { ...input };
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
      return row && output(row);
    },

    /** Soft delete (the only delete, CLAUDE.md §9). */
    async remove(ctx: RequestContext, id: string | number, parentId?: number) {
      await withTransaction(async (conn) => {
        const where = await parentScope(parentId, ctx, conn);
        const affected = await softDelete(config.table, { ...where, [config.pk]: id }, ctx, conn);
        if (!affected) throw ApiError.notFound();
        await hooks?.afterWrite?.('delete', { id, parentId, row: null }, ctx, conn);
      });
    },
  };
}

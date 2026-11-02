/**
 * The only generic data API (CLAUDE.md §9). Services call these functions; nothing else builds
 * SQL from identifiers. Guarantees:
 *  - every table/column is checked against the registry and back-quoted; values are placeholders;
 *  - tables with customer_id are scoped to ctx.customerId unless ctx.userType = 'S';
 *  - reads skip is_deleted = 'Y' rows unless asked; the only delete is softDelete;
 *  - writes stamp audit columns and reset sync_flag = 'N'.
 * The build* functions are pure (no I/O) and exported for unit tests.
 */
import type { Pool, PoolConnection, ResultSetHeader, RowDataPacket } from 'mysql2/promise';
import { PAGINATION } from '../../config/constants';
import type { RequestContext } from '../context';
import { ApiError } from '../http/ApiError';
import { db } from './pool';
import { assertColumn, getTable, quoteId, type TableMeta } from './registry';

export { withTransaction } from './tx';

export type Executor = Pool | PoolConnection;
export type Scalar = string | number | boolean | Buffer;
export type Op = '=' | '<>' | '>' | '>=' | '<' | '<=' | 'like';
export type Condition =
  Scalar | null | { op: Op; value: Scalar | null } | { in: readonly Scalar[] };
export type Where = Record<string, Condition>;
export type Row = Record<string, unknown>;

export interface OrderBy {
  column: string;
  dir?: 'asc' | 'desc';
}

export interface Search {
  columns: readonly string[];
  term: string;
}

export interface FetchOptions {
  where?: Where;
  columns?: readonly string[];
  orderBy?: readonly OrderBy[];
  page?: number;
  limit?: number;
  /** Include soft-deleted rows (e.g. pull, so devices can hide them). */
  includeDeleted?: boolean;
  search?: Search;
}

export interface SaveOptions {
  flag: 'I' | 'U';
  where?: Where;
}

export interface SaveResult {
  insertId: number;
  affectedRows: number;
}

export interface Built {
  sql: string;
  params: unknown[];
}

/** Never selected unless a caller names them explicitly in `columns`. */
export const SENSITIVE_COLUMNS: ReadonlySet<string> = new Set([
  'password_hash',
  'session_token',
  'token_hash',
  'refresh_hash',
]);

const AUDIT_COLUMNS = new Set(['created_by', 'created_at', 'updated_by', 'updated_at']);
const OPS: ReadonlySet<string> = new Set(['=', '<>', '>', '>=', '<', '<=', 'like']);

// ---------------------------------------------------------------- pure builders

function isScalar(v: unknown): v is Scalar {
  return (
    typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean' || Buffer.isBuffer(v)
  );
}

export function escapeLike(term: string): string {
  return term.replace(/[\\%_]/g, '\\$&');
}

/** Adds tenant scoping and (optionally) the not-deleted filter to a caller's where. */
export function scopeWhere(
  meta: TableMeta,
  where: Where | undefined,
  ctx: RequestContext,
  notDeleted: boolean,
): Where {
  const scoped: Where = { ...where };
  if (meta.columns.has('customer_id') && ctx.userType !== 'S') {
    if (ctx.customerId == null) throw ApiError.forbidden('No tenant in token');
    scoped.customer_id = ctx.customerId;
  }
  if (notDeleted && meta.columns.has('is_deleted') && !('is_deleted' in scoped)) {
    scoped.is_deleted = 'N';
  }
  return scoped;
}

export function buildWhere(meta: TableMeta, where: Where, search?: Search): Built {
  const parts: string[] = [];
  const params: unknown[] = [];

  for (const [column, cond] of Object.entries(where)) {
    if (cond === undefined) continue;
    assertColumn(meta, column);
    const col = quoteId(column);

    if (cond === null) {
      parts.push(`${col} IS NULL`);
    } else if (isScalar(cond)) {
      parts.push(`${col} = ?`);
      params.push(cond);
    } else if ('in' in cond) {
      if (!cond.in.length) {
        parts.push('1 = 0');
      } else {
        parts.push(`${col} IN (${cond.in.map(() => '?').join(', ')})`);
        params.push(...cond.in);
      }
    } else if ('op' in cond && OPS.has(cond.op)) {
      if (cond.value === null) {
        if (cond.op === '=') parts.push(`${col} IS NULL`);
        else if (cond.op === '<>') parts.push(`${col} IS NOT NULL`);
        else throw ApiError.badRequest(`Operator ${cond.op} cannot compare with null`);
      } else {
        parts.push(`${col} ${cond.op === 'like' ? 'LIKE' : cond.op} ?`);
        params.push(cond.value);
      }
    } else {
      throw ApiError.badRequest(`Invalid condition for column ${column}`);
    }
  }

  if (search && search.term && search.columns.length) {
    const like = `%${escapeLike(search.term)}%`;
    const ors = search.columns.map((c) => {
      assertColumn(meta, c);
      params.push(like);
      return `${quoteId(c)} LIKE ?`;
    });
    parts.push(`(${ors.join(' OR ')})`);
  }

  return { sql: parts.length ? ` WHERE ${parts.join(' AND ')}` : '', params };
}

export function defaultColumns(meta: TableMeta): string[] {
  return [...meta.columns.keys()].filter((c) => !SENSITIVE_COLUMNS.has(c));
}

export interface BuiltSelect extends Built {
  count?: Built;
  page?: number;
  limit?: number;
}

export function buildSelect(meta: TableMeta, opts: FetchOptions, ctx: RequestContext): BuiltSelect {
  const columns = opts.columns?.length ? opts.columns : defaultColumns(meta);
  const select = columns.map((c) => quoteId(assertColumn(meta, c).name)).join(', ');
  const where = buildWhere(
    meta,
    scopeWhere(meta, opts.where, ctx, !opts.includeDeleted),
    opts.search,
  );
  const from = ` FROM ${quoteId(meta.name)}${where.sql}`;

  const paginated = opts.page !== undefined || opts.limit !== undefined;
  let orderBy = opts.orderBy ?? [];
  // Stable pages need a deterministic order.
  if (paginated && !orderBy.length) orderBy = meta.primaryKey.map((column) => ({ column }));
  const order = orderBy.length
    ? ` ORDER BY ${orderBy
        .map(
          (o) =>
            `${quoteId(assertColumn(meta, o.column).name)} ${o.dir === 'desc' ? 'DESC' : 'ASC'}`,
        )
        .join(', ')}`
    : '';

  if (!paginated) return { sql: `SELECT ${select}${from}${order}`, params: where.params };

  const limit = Math.min(
    Math.max(Math.trunc(opts.limit ?? PAGINATION.defaultLimit), 1),
    PAGINATION.maxLimit,
  );
  const page = Math.max(Math.trunc(opts.page ?? 1), 1);
  return {
    sql: `SELECT ${select}${from}${order} LIMIT ? OFFSET ?`,
    params: [...where.params, limit, (page - 1) * limit],
    count: { sql: `SELECT COUNT(*) AS total${from}`, params: where.params },
    page,
    limit,
  };
}

function writableColumn(meta: TableMeta, column: string) {
  const col = assertColumn(meta, column);
  if (col.generated)
    throw ApiError.badRequest(`Column ${column} is generated and cannot be written`);
  return col;
}

export function buildInsert(meta: TableMeta, data: Row, ctx: RequestContext): Built {
  const cols: string[] = [];
  const values: string[] = [];
  const params: unknown[] = [];
  const add = (column: string, value: unknown) => {
    cols.push(quoteId(column));
    values.push('?');
    params.push(value);
  };
  const stamp = (column: string) => {
    if (!meta.columns.has(column)) return;
    cols.push(quoteId(column));
    values.push('UTC_TIMESTAMP()');
  };

  const tenantForced = meta.columns.has('customer_id') && ctx.userType !== 'S';
  if (tenantForced && ctx.customerId == null) throw ApiError.forbidden('No tenant in token');

  for (const [column, value] of Object.entries(data)) {
    if (value === undefined || AUDIT_COLUMNS.has(column) || column === 'sync_flag') continue;
    if (column === 'customer_id' && tenantForced) continue;
    writableColumn(meta, column);
    add(column, value);
  }
  if (tenantForced) add('customer_id', ctx.customerId);
  if (meta.columns.has('sync_flag')) add('sync_flag', 'N');
  // updated_* are set on insert too: pull selects by updated_at, so new rows must carry it.
  if (meta.columns.has('created_by')) add('created_by', ctx.loginId);
  stamp('created_at');
  if (meta.columns.has('updated_by')) add('updated_by', ctx.loginId);
  stamp('updated_at');

  if (!params.length) throw ApiError.badRequest('Nothing to insert');
  return {
    sql: `INSERT INTO ${quoteId(meta.name)} (${cols.join(', ')}) VALUES (${values.join(', ')})`,
    params,
  };
}

function requireWhere(where: Where | undefined, what: string): Where {
  if (!where || !Object.values(where).some((v) => v !== undefined)) {
    throw new Error(`${what} requires a non-empty where clause`);
  }
  return where;
}

/** SET fragment that stamps updated_by/updated_at and resets sync_flag. */
function auditSet(meta: TableMeta, ctx: RequestContext, sets: string[], params: unknown[]) {
  if (meta.columns.has('sync_flag')) sets.push("`sync_flag` = 'N'");
  if (meta.columns.has('updated_by')) {
    sets.push('`updated_by` = ?');
    params.push(ctx.loginId);
  }
  if (meta.columns.has('updated_at')) sets.push('`updated_at` = UTC_TIMESTAMP()');
}

export function buildUpdate(
  meta: TableMeta,
  data: Row,
  where: Where | undefined,
  ctx: RequestContext,
): Built {
  const callerWhere = requireWhere(where, 'save(U)');
  const sets: string[] = [];
  const params: unknown[] = [];

  for (const [column, value] of Object.entries(data)) {
    if (value === undefined || AUDIT_COLUMNS.has(column) || column === 'sync_flag') continue;
    // Rows never move between tenants except by a super admin.
    if (column === 'customer_id' && ctx.userType !== 'S') continue;
    writableColumn(meta, column);
    if (meta.primaryKey.includes(column)) {
      throw ApiError.badRequest(`Primary key ${column} cannot be updated`);
    }
    sets.push(`${quoteId(column)} = ?`);
    params.push(value);
  }
  if (!sets.length) throw ApiError.badRequest('Nothing to update');
  auditSet(meta, ctx, sets, params);

  const w = buildWhere(meta, scopeWhere(meta, callerWhere, ctx, true));
  return {
    sql: `UPDATE ${quoteId(meta.name)} SET ${sets.join(', ')}${w.sql}`,
    params: [...params, ...w.params],
  };
}

export function buildSoftDelete(
  meta: TableMeta,
  where: Where | undefined,
  ctx: RequestContext,
): Built {
  const callerWhere = requireWhere(where, 'softDelete');
  if (!meta.columns.has('is_deleted')) {
    throw ApiError.badRequest(`Table ${meta.name} does not support delete`);
  }
  const sets = ["`is_deleted` = 'Y'"];
  const params: unknown[] = [];
  auditSet(meta, ctx, sets, params);

  // Only live rows, so affectedRows tells the caller whether anything was deleted.
  const w = buildWhere(meta, scopeWhere(meta, { ...callerWhere, is_deleted: 'N' }, ctx, false));
  return {
    sql: `UPDATE ${quoteId(meta.name)} SET ${sets.join(', ')}${w.sql}`,
    params: [...params, ...w.params],
  };
}

// ---------------------------------------------------------------- execution

export async function fetch<T extends Row = Row>(
  table: string,
  opts: FetchOptions,
  ctx: RequestContext,
  conn: Executor = db,
): Promise<{ rows: T[]; total: number }> {
  const built = buildSelect(getTable(table), opts, ctx);
  const [rows] = await conn.query<RowDataPacket[]>(built.sql, built.params);
  if (!built.count) return { rows: rows as T[], total: rows.length };
  const [countRows] = await conn.query<RowDataPacket[]>(built.count.sql, built.count.params);
  return { rows: rows as T[], total: Number(countRows[0]?.total ?? 0) };
}

export async function fetchOne<T extends Row = Row>(
  table: string,
  where: Where,
  ctx: RequestContext,
  conn: Executor = db,
  opts: Pick<FetchOptions, 'columns' | 'includeDeleted'> = {},
): Promise<T | null> {
  const meta = getTable(table);
  const built = buildSelect(meta, { ...opts, where }, ctx);
  const [rows] = await conn.query<RowDataPacket[]>(`${built.sql} LIMIT 1`, built.params);
  return (rows[0] as T | undefined) ?? null;
}

export async function save(
  table: string,
  data: Row,
  opts: SaveOptions,
  ctx: RequestContext,
  conn: Executor = db,
): Promise<SaveResult> {
  const meta = getTable(table);
  const built =
    opts.flag === 'I' ? buildInsert(meta, data, ctx) : buildUpdate(meta, data, opts.where, ctx);
  const [res] = await conn.query<ResultSetHeader>(built.sql, built.params);
  return { insertId: res.insertId, affectedRows: res.affectedRows };
}

export async function softDelete(
  table: string,
  where: Where,
  ctx: RequestContext,
  conn: Executor = db,
): Promise<number> {
  const built = buildSoftDelete(getTable(table), where, ctx);
  const [res] = await conn.query<ResultSetHeader>(built.sql, built.params);
  return res.affectedRows;
}

const FORBIDDEN_RAW = /^\s*(delete|truncate|drop)\b/i;

/** Parameterised custom SQL (merge upserts, reports). Values must be passed as params. */
export async function raw<T = RowDataPacket[]>(
  sql: string,
  params: unknown[] | Record<string, unknown> = [],
  conn: Executor = db,
): Promise<T> {
  if (FORBIDDEN_RAW.test(sql)) throw new Error('Hard deletes are forbidden; use softDelete');
  // Arrays bind positional `?`, objects bind `:named` placeholders (pool has namedPlaceholders).
  const [result] = await conn.query(sql, params as unknown[]);
  return result as T;
}

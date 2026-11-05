import type { PoolConnection } from 'mysql2/promise';
import type { z } from 'zod';
import type { RequestContext, UserType } from '../context';
import type { OrderBy, Row } from '../db/dml';

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

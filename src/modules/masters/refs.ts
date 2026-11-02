/** Cross-row checks shared by master modules. */
import type { RequestContext } from '../../core/context';
import { fetchOne, type Executor, type Row, type Where } from '../../core/db/dml';
import { ApiError } from '../../core/http/ApiError';

export const MASTER_ROLES = { read: ['A', 'S'], write: ['A', 'S'] } as const satisfies {
  read: RequestContext['userType'][];
  write: RequestContext['userType'][];
};

/** Tenant a write targets: the token's, or the body's customer_id for a super admin. */
export function tenantOf(data: Row, ctx: RequestContext): number {
  const id = ctx.userType === 'S' ? data.customer_id : ctx.customerId;
  if (id === undefined || id === null) {
    throw ApiError.validation([{ path: 'customer_id', message: 'Required for super admin' }]);
  }
  return Number(id);
}

/** A referenced row (user, shift, registration…) must be live and belong to the same tenant. */
export async function assertTenantRef(
  ref: { table: string; pk: string; id: unknown; field: string; where?: Where },
  tenantId: number,
  ctx: RequestContext,
  conn: Executor,
): Promise<void> {
  const row = await fetchOne(
    ref.table,
    { ...ref.where, [ref.pk]: ref.id as number, customer_id: tenantId },
    ctx,
    conn,
    { columns: [ref.pk] },
  );
  if (!row) {
    throw ApiError.validation([
      { path: ref.field, message: `${ref.field} does not exist for this customer` },
    ]);
  }
}

/** 409 if another live row already has these values (tables without a UNIQUE key). */
export async function assertNoLiveDuplicate(
  table: string,
  pk: string,
  match: Where,
  ctx: RequestContext,
  conn: Executor,
  exceptId?: unknown,
): Promise<void> {
  const where: Where = { ...match };
  if (exceptId !== undefined) where[pk] = { op: '<>', value: exceptId as number };
  const dup = await fetchOne(table, where, ctx, conn, { columns: [pk] });
  if (dup) throw ApiError.conflict('Duplicate record', { fields: Object.keys(match) });
}

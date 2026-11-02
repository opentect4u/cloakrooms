/**
 * Users (A: own tenant, S: all). Hashes never leave the server: the DML layer does not select
 * password_hash / session_token unless asked, and this module never asks.
 */
import type { PoolConnection, RowDataPacket } from 'mysql2/promise';
import type { RequestContext } from '../../core/context';
import {
  fetch,
  fetchOne,
  raw,
  save,
  softDelete,
  withTransaction,
  type Where,
} from '../../core/db/dml';
import { ApiError } from '../../core/http/ApiError';
import { hashPassword } from '../../core/security/password';
import { invalidateSession } from '../../core/security/sessionStore';
import { pageMeta } from '../../core/utils/pagination';
import { forceEndUserSessions } from '../auth/session.service';
import type { CreateUserInput, ListUsersQuery, UpdateUserInput } from './users.schema';

const TABLE = 'md_user';
const COLUMNS = [
  'id',
  'customer_id',
  'user_id',
  'user_name',
  'user_code',
  'user_type',
  'allow_flag',
  'device_id',
  'session_started_at',
  'last_login',
  'created_by',
  'created_at',
  'updated_by',
  'updated_at',
];

type UserRow = Record<string, unknown> & {
  id: number;
  customer_id: number | null;
  user_type: 'S' | 'A' | 'O';
  allow_flag: 'Y' | 'N';
};

export async function listUsers(ctx: RequestContext, q: ListUsersQuery) {
  const where: Where = {};
  if (q.user_type) where.user_type = q.user_type;
  if (q.allow_flag) where.allow_flag = q.allow_flag;
  if (q.customer_id && ctx.userType === 'S') where.customer_id = q.customer_id;
  const { rows, total } = await fetch(
    TABLE,
    {
      where,
      columns: COLUMNS,
      orderBy: q.sort,
      page: q.page,
      limit: q.limit,
      search: q.search
        ? { columns: ['user_id', 'user_name', 'user_code'], term: q.search }
        : undefined,
    },
    ctx,
  );
  return { rows, meta: pageMeta(q.page, q.limit, total) };
}

async function getUserOrThrow(ctx: RequestContext, id: number, conn?: PoolConnection) {
  const user = await fetchOne<UserRow>(TABLE, { id }, ctx, conn, { columns: COLUMNS });
  if (!user) throw ApiError.notFound('User not found');
  return user;
}

export const getUser = (ctx: RequestContext, id: number) => getUserOrThrow(ctx, id);

/**
 * Locks the customer row and checks that one more allowed user fits in user_limit
 * (allowed = not deleted, allow_flag Y).
 */
async function assertSeatAvailable(customerId: number, conn: PoolConnection) {
  const rows = await raw<RowDataPacket[]>(
    `SELECT c.user_limit,
            (SELECT COUNT(*) FROM md_user u
              WHERE u.customer_id = c.customer_id AND u.is_deleted = 'N' AND u.allow_flag = 'Y') AS used
       FROM md_customer c
      WHERE c.customer_id = ? AND c.is_deleted = 'N'
      FOR UPDATE`,
    [customerId],
    conn,
  );
  const row = rows[0];
  if (!row) throw ApiError.badRequest('Customer does not exist');
  if (Number(row.used) >= Number(row.user_limit)) {
    throw new ApiError(409, 'USER_LIMIT_REACHED', 'User limit of the subscription reached', {
      user_limit: Number(row.user_limit),
    });
  }
}

export async function createUser(ctx: RequestContext, input: CreateUserInput) {
  const { password, customer_id: bodyCustomerId, ...rest } = input;
  let customerId: number | null;

  if (input.user_type === 'S') {
    if (ctx.userType !== 'S')
      throw ApiError.forbidden('Only a super admin can create super admins');
    if (bodyCustomerId !== undefined || input.user_code !== undefined) {
      throw ApiError.validation([
        { path: 'customer_id', message: 'Super admins have no customer or user_code' },
      ]);
    }
    customerId = null;
  } else {
    customerId = ctx.userType === 'S' ? (bodyCustomerId ?? null) : ctx.customerId;
    if (customerId === null) {
      throw ApiError.validation([{ path: 'customer_id', message: 'Required for super admin' }]);
    }
    if (!input.user_code) {
      throw ApiError.validation([{ path: 'user_code', message: 'Required for customer users' }]);
    }
  }

  const passwordHash = await hashPassword(password);
  return withTransaction(async (conn) => {
    if (customerId !== null && input.allow_flag === 'Y')
      await assertSeatAvailable(customerId, conn);
    const { insertId } = await save(
      TABLE,
      { ...rest, customer_id: customerId, password_hash: passwordHash },
      { flag: 'I' },
      ctx,
      conn,
    );
    return getUserOrThrow(ctx, insertId, conn);
  });
}

export async function updateUser(ctx: RequestContext, id: number, input: UpdateUserInput) {
  return withTransaction(async (conn) => {
    const user = await getUserOrThrow(ctx, id, conn);
    if (user.user_type === 'S' && (input.user_type || input.user_code)) {
      throw ApiError.badRequest('A super admin has no user type change or user_code');
    }
    await save(TABLE, { ...input }, { flag: 'U', where: { id } }, ctx, conn);
    return getUserOrThrow(ctx, id, conn);
  });
}

function assertNotSelf(ctx: RequestContext, id: number, action: string) {
  if (ctx.userId === id) throw ApiError.badRequest(`You cannot ${action} your own account`);
}

/** Runs a user change and force-ends the user's sessions in one transaction. */
async function changeAndForceOut(
  ctx: RequestContext,
  id: number,
  change: (conn: PoolConnection) => Promise<void>,
) {
  const ended = await withTransaction(async (conn) => {
    await getUserOrThrow(ctx, id, conn);
    await change(conn);
    return forceEndUserSessions(id, ctx.loginId, conn);
  });
  invalidateSession(...ended);
  return ended.length;
}

export async function deleteUser(ctx: RequestContext, id: number) {
  assertNotSelf(ctx, id, 'delete');
  await changeAndForceOut(ctx, id, async (conn) => {
    await softDelete(TABLE, { id }, ctx, conn);
  });
}

export async function forceLogout(ctx: RequestContext, id: number) {
  const sessionsEnded = await changeAndForceOut(ctx, id, async () => undefined);
  return { sessions_ended: sessionsEnded };
}

export async function resetPassword(ctx: RequestContext, id: number, password: string) {
  const passwordHash = await hashPassword(password);
  const sessionsEnded = await changeAndForceOut(ctx, id, async (conn) => {
    await save(TABLE, { password_hash: passwordHash }, { flag: 'U', where: { id } }, ctx, conn);
  });
  return { sessions_ended: sessionsEnded };
}

export async function setAllow(ctx: RequestContext, id: number, allowFlag: 'Y' | 'N') {
  if (allowFlag === 'N') {
    assertNotSelf(ctx, id, 'block');
    await changeAndForceOut(ctx, id, async (conn) => {
      await save(TABLE, { allow_flag: 'N' }, { flag: 'U', where: { id } }, ctx, conn);
    });
    return getUser(ctx, id);
  }
  return withTransaction(async (conn) => {
    const user = await getUserOrThrow(ctx, id, conn);
    if (user.allow_flag === 'Y') return user;
    if (user.customer_id !== null) await assertSeatAvailable(user.customer_id, conn);
    await save(TABLE, { allow_flag: 'Y' }, { flag: 'U', where: { id } }, ctx, conn);
    return getUserOrThrow(ctx, id, conn);
  });
}

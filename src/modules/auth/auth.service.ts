/**
 * Login / refresh / logout / me (CLAUDE.md §6).
 * One active session per user; seats per customer limited by md_customer.user_limit.
 */
import { randomUUID } from 'node:crypto';
import type { RowDataPacket } from 'mysql2/promise';
import { SYSTEM_CONTEXT, type RequestContext, type UserType } from '../../core/context';
import { fetchOne, raw, save, withTransaction, type Executor } from '../../core/db/dml';
import { ApiError } from '../../core/http/ApiError';
import { hashPassword, verifyPassword } from '../../core/security/password';
import { invalidateSession } from '../../core/security/sessionStore';
import {
  sha256,
  signAccessToken,
  signRefreshToken,
  verifyRefreshToken,
} from '../../core/security/tokens';
import { toIstDate, toUtcString } from '../../core/utils/time';
import { latestVersion } from '../app-version/app-version.service';
import { getCurrentCounters } from '../counters/counters.service';
import { CUSTOMER_PUBLIC_COLUMNS } from '../customers/customers.schema';
import { loadMasters, loadSettings } from '../masters/masters.service';
import type { ChangePasswordInput, LoginInput } from './auth.schema';
import { assertWithinShift } from './shiftLogin';
import { customerSettingValue } from '../settings/settings.read';
import {
  activeSessionsOfUser,
  countActiveSessionsOfCustomer,
  endSessions,
  expireStaleSessions,
  logoutSession,
} from './session.service';

interface UserRow {
  id: number;
  customer_id: number | null;
  user_id: string;
  user_name: string;
  user_code: string | null;
  user_type: UserType;
  allow_flag: 'Y' | 'N';
  must_change_pwd: 'Y' | 'N';
  password_hash?: string;
  [k: string]: unknown;
}

interface CustomerRow {
  customer_id: number;
  customer_name: string;
  active_flag: 'Y' | 'N';
  renewal_dt: string | null;
  user_limit: number;
  [k: string]: unknown;
}

const USER_PUBLIC = [
  'id',
  'customer_id',
  'user_id',
  'user_name',
  'user_code',
  'user_type',
  'allow_flag',
  'must_change_pwd',
];

const INVALID_LOGIN = 'Invalid user id or password';

let dummyHash: Promise<string> | undefined;
/** Verifying against a throw-away hash keeps unknown-user logins as slow as wrong passwords. */
const getDummyHash = () => (dummyHash ??= hashPassword(randomUUID()));

/** `abcd1234wxyz` → `********wxyz` (shown in the "logged in elsewhere" message). */
export function maskDevice(deviceId: string): string {
  if (deviceId.length <= 4) return '****';
  return '*'.repeat(Math.min(deviceId.length - 4, 8)) + deviceId.slice(-4);
}

function assertCustomerUsable(customer: CustomerRow | null): asserts customer is CustomerRow {
  if (!customer || customer.active_flag !== 'Y') {
    throw ApiError.forbidden('Customer account is inactive');
  }
  if (!customer.renewal_dt || customer.renewal_dt < toIstDate()) {
    throw new ApiError(403, 'SUBSCRIPTION_EXPIRED', 'Subscription has expired', {
      renewal_dt: customer.renewal_dt,
    });
  }
}

function contextOf(
  user: Pick<UserRow, 'id' | 'user_id' | 'customer_id' | 'user_type'>,
): RequestContext {
  return {
    userId: user.id,
    loginId: user.user_id,
    customerId: user.customer_id,
    userType: user.user_type,
  };
}

function issueTokens(user: Pick<UserRow, 'id' | 'customer_id' | 'user_type'>, sessionId: string) {
  const sub = String(user.id);
  const accessToken = signAccessToken({
    sub,
    cid: user.customer_id,
    typ: user.user_type,
    sid: sessionId,
  });
  const refreshToken = signRefreshToken({ sub, sid: sessionId });
  return {
    accessToken,
    refreshToken,
    accessHash: sha256(accessToken),
    refreshHash: sha256(refreshToken),
  };
}

async function getCustomer(customerId: number, conn?: Executor) {
  return fetchOne<CustomerRow>('md_customer', { customer_id: customerId }, SYSTEM_CONTEXT, conn, {
    columns: CUSTOMER_PUBLIC_COLUMNS,
  });
}

/** Everything a device needs to work offline after login. */
async function sessionPayload(user: UserRow, customer: CustomerRow | null) {
  const ctx = contextOf(user);
  const settings = await loadSettings(ctx);
  const isTenantUser = user.user_type !== 'S';
  return {
    user: Object.fromEntries(USER_PUBLIC.map((k) => [k, user[k]])),
    customer,
    masters: await loadMasters(ctx),
    settings,
    counters: isTenantUser ? await getCurrentCounters(ctx, settings.RECEIPT_RESET) : null,
    server_time: toUtcString(),
  };
}

/** 426 when the device runs a versionCode older than a live mandatory release. */
async function assertAppVersionAllowed(versionCode: number | undefined) {
  if (versionCode === undefined) return;
  const v = await latestVersion(versionCode);
  if (v.mandatory) {
    throw new ApiError(426, 'UPDATE_REQUIRED', 'This app version is no longer supported; update', {
      current: versionCode,
      latest: v.latest,
    });
  }
}

export interface LoginOptions {
  /** Only these user types may log in here (admin panel: S); checked before a session exists. */
  userTypes?: readonly UserType[];
  /** Message for a user type outside `userTypes`. */
  wrongTypeMessage?: string;
}

export async function login(input: LoginInput, opts: LoginOptions = {}) {
  await assertAppVersionAllowed(input.version_code);
  const user = await fetchOne<UserRow>(
    'md_user',
    { user_id: input.user_id },
    SYSTEM_CONTEXT,
    undefined,
    {
      columns: [...USER_PUBLIC, 'password_hash'],
    },
  );
  const passwordOk = await verifyPassword(
    user?.password_hash ?? (await getDummyHash()),
    input.password,
  );
  if (!user || !passwordOk) throw ApiError.unauthorized(INVALID_LOGIN);
  delete user.password_hash;

  if (user.allow_flag !== 'Y')
    throw ApiError.forbidden('User is blocked; contact your administrator');
  // After the password check, so a wrong password never reveals the user type.
  if (opts.userTypes && !opts.userTypes.includes(user.user_type)) {
    throw ApiError.forbidden(opts.wrongTypeMessage ?? 'Not allowed to sign in here');
  }

  let customer: CustomerRow | null = null;
  if (user.user_type !== 'S') {
    customer = user.customer_id === null ? null : await getCustomer(user.customer_id);
    assertCustomerUsable(customer);
    await assertWithinShift(user, customer.customer_id);
  }

  const sessionId = randomUUID();
  const tokens = issueTokens(user, sessionId);
  const now = toUtcString();

  const replaced = await withTransaction(async (conn) => {
    // Serialise logins per customer (seat count) and per user (one session).
    if (customer) {
      await raw(
        'SELECT customer_id FROM md_customer WHERE customer_id = ? FOR UPDATE',
        [customer.customer_id],
        conn,
      );
      await expireStaleSessions({ customerId: customer.customer_id }, conn);
    } else {
      await expireStaleSessions({ userId: user.id }, conn);
    }
    await raw('SELECT id FROM md_user WHERE id = ? FOR UPDATE', [user.id], conn);

    const active = await activeSessionsOfUser(user.id, conn);
    // SINGLE_SESSION_FLAG = N (set by Synergic per station): the user may be signed in on
    // several devices; each session still takes a seat of user_limit below.
    const singleSession =
      !customer ||
      (await customerSettingValue(customer.customer_id, 'SINGLE_SESSION_FLAG', conn)) !== 'N';
    const elsewhere = singleSession && active.find((s) => s.device_id !== input.device_id);
    if (elsewhere) {
      throw new ApiError(409, 'SESSION_ACTIVE', 'User is already logged in on another device', {
        session_started_at: elsewhere.started_at,
        device_id: maskDevice(elsewhere.device_id),
      });
    }
    // Same device logging in again (app reinstalled / token lost): the new session replaces it.
    const ended = await endSessions(
      active.filter((s) => s.device_id === input.device_id),
      'L',
      'system:relogin',
      conn,
    );

    if (
      customer &&
      (await countActiveSessionsOfCustomer(customer.customer_id, conn)) >= customer.user_limit
    ) {
      throw new ApiError(409, 'USER_LIMIT_REACHED', 'All licensed users are logged in', {
        user_limit: customer.user_limit,
      });
    }

    await save(
      'td_user_session',
      {
        session_id: sessionId,
        customer_id: user.customer_id,
        user_id: user.id,
        device_id: input.device_id,
        token_hash: tokens.accessHash,
        refresh_hash: tokens.refreshHash,
        status: 'A',
        started_at: now,
        last_seen_at: now,
      },
      { flag: 'I' },
      contextOf(user),
      conn,
    );
    await raw(
      `UPDATE md_user SET device_id = ?, session_token = ?, session_started_at = ?, last_login = ?
        WHERE id = ?`,
      [input.device_id, tokens.accessHash, now, now, user.id],
      conn,
    );
    return ended;
  });
  invalidateSession(...replaced);

  return {
    accessToken: tokens.accessToken,
    refreshToken: tokens.refreshToken,
    ...(await sessionPayload(user, customer)),
  };
}

interface RefreshRow extends RowDataPacket {
  session_id: string;
  user_id: number;
  customer_id: number | null;
  status: 'A' | 'F' | 'L';
  token_hash: string;
  refresh_hash: string | null;
  user_type: UserType;
  allow_flag: 'Y' | 'N';
  is_deleted: 'Y' | 'N';
}

/** Rotates both tokens. Presenting an already-rotated refresh token revokes the session. */
export async function refresh(refreshToken: string) {
  const claims = verifyRefreshToken(refreshToken);
  const presented = sha256(refreshToken);

  const outcome = await withTransaction(async (conn) => {
    const rows = await raw<RefreshRow[]>(
      `SELECT s.session_id, s.user_id, s.customer_id, s.status, s.token_hash, s.refresh_hash,
              u.user_type, u.allow_flag, u.is_deleted
         FROM td_user_session s JOIN md_user u ON u.id = s.user_id
        WHERE s.session_id = ? FOR UPDATE`,
      [claims.sid],
      conn,
    );
    const s = rows[0];
    if (!s || String(s.user_id) !== claims.sub)
      return { error: ApiError.unauthorized('Invalid token') };
    if (s.status !== 'A') return { error: new ApiError(401, 'SESSION_ENDED', 'Session has ended') };
    if (s.refresh_hash !== presented) {
      // Reuse of a rotated token means it may have been stolen: kill the session (committed).
      await endSessions([s], 'L', 'system:refresh-reuse', conn);
      return {
        error: new ApiError(401, 'TOKEN_REUSED', 'Refresh token was already used; session revoked'),
      };
    }
    if (s.allow_flag !== 'Y' || s.is_deleted !== 'N') {
      return { error: ApiError.forbidden('User is blocked; contact your administrator') };
    }
    if (s.user_type !== 'S' && s.customer_id !== null) {
      assertCustomerUsable(await getCustomer(s.customer_id, conn));
    }

    const tokens = issueTokens(
      { id: s.user_id, customer_id: s.customer_id, user_type: s.user_type },
      s.session_id,
    );
    await raw(
      `UPDATE td_user_session SET token_hash = ?, refresh_hash = ?, last_seen_at = NOW()
        WHERE session_id = ?`,
      [tokens.accessHash, tokens.refreshHash, s.session_id],
      conn,
    );
    await raw(
      'UPDATE md_user SET session_token = ? WHERE id = ? AND session_token = ?',
      [tokens.accessHash, s.user_id, s.token_hash],
      conn,
    );
    return { tokens };
  });
  invalidateSession(claims.sid);

  if ('error' in outcome) throw outcome.error;
  return {
    accessToken: outcome.tokens.accessToken,
    refreshToken: outcome.tokens.refreshToken,
    server_time: toUtcString(),
  };
}

export async function logout(ctx: RequestContext) {
  if (!ctx.sessionId) throw ApiError.unauthorized();
  const sessionId = ctx.sessionId;
  const ended = await withTransaction((conn) => logoutSession(sessionId, ctx.loginId, conn));
  invalidateSession(sessionId, ...ended);
}

/** Own password change; also clears must_change_pwd. The current session stays valid. */
export async function changePassword(ctx: RequestContext, input: ChangePasswordInput) {
  const user = await fetchOne<UserRow>('md_user', { id: ctx.userId }, ctx, undefined, {
    columns: ['id', 'password_hash'],
  });
  if (!user?.password_hash) throw ApiError.unauthorized();
  if (!(await verifyPassword(user.password_hash, input.current_password))) {
    throw ApiError.validation([{ path: 'current_password', message: 'is wrong' }]);
  }
  const passwordHash = await hashPassword(input.new_password);
  await save(
    'md_user',
    { password_hash: passwordHash, must_change_pwd: 'N' },
    { flag: 'U', where: { id: ctx.userId } },
    ctx,
  );
  if (ctx.sessionId) invalidateSession(ctx.sessionId);
}

export async function me(ctx: RequestContext) {
  const user = await fetchOne<UserRow>('md_user', { id: ctx.userId }, ctx, undefined, {
    columns: [...USER_PUBLIC, 'device_id', 'session_started_at', 'last_login'],
  });
  if (!user) throw ApiError.unauthorized();
  return {
    user,
    customer: ctx.customerId === null ? null : await getCustomer(ctx.customerId),
    session_id: ctx.sessionId,
    server_time: toUtcString(),
  };
}

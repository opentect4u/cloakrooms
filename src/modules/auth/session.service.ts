/**
 * Session state changes. Callers run these inside a transaction and call invalidateSession() with
 * the returned ids *after* commit, so no other request can re-cache the pre-commit state.
 */
import type { RowDataPacket } from 'mysql2/promise';
import { env } from '../../config/env';
import { raw, type Executor } from '../../core/db/dml';
import type { SessionStatus } from '../../core/security/sessionStore';
import { ttlSeconds } from '../../core/security/tokens';

interface SessionRow extends RowDataPacket {
  session_id: string;
  user_id: number;
  token_hash: string;
  device_id: string;
  started_at: string;
}

const CLEAR_USER_SESSION = `UPDATE md_user
    SET device_id = NULL, session_token = NULL, session_started_at = NULL
  WHERE id = ? AND session_token IN (?)`;

export async function activeSessionsOfUser(userId: number, conn: Executor): Promise<SessionRow[]> {
  return raw<SessionRow[]>(
    `SELECT session_id, user_id, token_hash, device_id, started_at
       FROM td_user_session WHERE user_id = ? AND status = 'A' FOR UPDATE`,
    [userId],
    conn,
  );
}

export async function countActiveSessionsOfCustomer(customerId: number, conn: Executor) {
  const rows = await raw<RowDataPacket[]>(
    `SELECT COUNT(*) AS n FROM td_user_session WHERE customer_id = ? AND status = 'A'`,
    [customerId],
    conn,
  );
  return Number(rows[0]?.n ?? 0);
}

/**
 * Ends the given sessions (status F = forced, still allowed to push + log out; L = logged out)
 * and clears md_user's session columns if they still point at one of them.
 */
export async function endSessions(
  sessions: readonly Pick<SessionRow, 'session_id' | 'user_id' | 'token_hash'>[],
  status: Exclude<SessionStatus, 'A'>,
  endedBy: string,
  conn: Executor,
): Promise<string[]> {
  if (!sessions.length) return [];
  const ids = sessions.map((s) => s.session_id);
  await raw(
    `UPDATE td_user_session SET status = ?, ended_at = UTC_TIMESTAMP(), ended_by = ?
      WHERE session_id IN (?) AND status <> 'L'`,
    [status, endedBy, ids],
    conn,
  );
  const byUser = new Map<number, string[]>();
  for (const s of sessions) byUser.set(s.user_id, [...(byUser.get(s.user_id) ?? []), s.token_hash]);
  for (const [userId, hashes] of byUser) await raw(CLEAR_USER_SESSION, [userId, hashes], conn);
  return ids;
}

/** Force-logout / block / delete / password reset: every live session of the user becomes F. */
export async function forceEndUserSessions(userId: number, endedBy: string, conn: Executor) {
  return endSessions(await activeSessionsOfUser(userId, conn), 'F', endedBy, conn);
}

/** Logout of one session (A or F → L). */
export async function logoutSession(sessionId: string, endedBy: string, conn: Executor) {
  const rows = await raw<SessionRow[]>(
    `SELECT session_id, user_id, token_hash, device_id, started_at
       FROM td_user_session WHERE session_id = ? AND status <> 'L' FOR UPDATE`,
    [sessionId],
    conn,
  );
  return endSessions(rows, 'L', endedBy, conn);
}

/**
 * Sessions whose refresh token can no longer be valid (no activity for the refresh TTL) are
 * closed lazily, so a lost device does not hold a seat or block its user forever.
 */
export async function expireStaleSessions(
  scope: { customerId: number } | { userId: number },
  conn: Executor,
): Promise<void> {
  const [col, value] =
    'customerId' in scope ? ['customer_id', scope.customerId] : ['user_id', scope.userId];
  await raw(
    `UPDATE td_user_session
        SET status = 'L', ended_at = UTC_TIMESTAMP(), ended_by = 'system:expired'
      WHERE ${col} = ? AND status = 'A'
        AND COALESCE(last_seen_at, started_at) < UTC_TIMESTAMP() - INTERVAL ? SECOND`,
    [value, ttlSeconds(env.JWT_REFRESH_TTL)],
    conn,
  );
}

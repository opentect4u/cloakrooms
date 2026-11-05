/**
 * Session lookups for the request path, cached 30 s per worker (CLAUDE.md §6). Writers in this
 * process call invalidateSession(); other cluster workers converge within the TTL, and a token
 * hash mismatch always forces a fresh read (so a just-refreshed token is never rejected).
 */
import type { RowDataPacket } from 'mysql2/promise';
import type { UserType } from '../context';
import { raw, type Executor } from '../db/dml';
import { LruCache } from '../utils/lruCache';

export type SessionStatus = 'A' | 'F' | 'L';

export interface SessionInfo {
  session_id: string;
  user_id: number;
  status: SessionStatus;
  token_hash: string;
  customer_id: number | null;
  device_id: string;
  login_id: string;
  user_type: UserType;
  must_change_pwd: 'Y' | 'N';
}

const CACHE_TTL_MS = 30_000;
const cache = new LruCache<string, SessionInfo>(50_000, CACHE_TTL_MS);

async function loadSession(sessionId: string, conn?: Executor): Promise<SessionInfo | null> {
  const rows = await raw<(SessionInfo & RowDataPacket)[]>(
    `SELECT s.session_id, s.user_id, s.status, s.token_hash, s.customer_id, s.device_id,
            u.user_id AS login_id, u.user_type, u.must_change_pwd
       FROM td_user_session s
       JOIN md_user u ON u.id = s.user_id
      WHERE s.session_id = ?`,
    [sessionId],
    conn,
  );
  return rows[0] ?? null;
}

export async function getSession(
  sessionId: string,
  opts: { fresh?: boolean } = {},
): Promise<SessionInfo | null> {
  if (!opts.fresh) {
    const hit = cache.get(sessionId);
    if (hit) return hit;
  }
  const session = await loadSession(sessionId);
  if (!session) {
    cache.delete(sessionId);
    return null;
  }
  if (session.status === 'A') {
    // At most once per cache period per worker: cheap activity tracking for stale-session expiry.
    await raw('UPDATE td_user_session SET last_seen_at = UTC_TIMESTAMP() WHERE session_id = ?', [
      sessionId,
    ]);
  }
  cache.set(sessionId, session);
  return session;
}

export function invalidateSession(...sessionIds: string[]): void {
  for (const id of sessionIds) cache.delete(id);
}

/** Test helper. */
export function clearSessionCache(): void {
  cache.clear();
}

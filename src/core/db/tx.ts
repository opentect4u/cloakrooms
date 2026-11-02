import type { PoolConnection } from 'mysql2/promise';
import { db } from './pool';

const RETRYABLE = new Set(['ER_LOCK_DEADLOCK']);

/** 10–50 ms × attempt, randomised so colliding transactions do not collide again. */
const backoff = (attempt: number) =>
  new Promise((r) => setTimeout(r, (10 + Math.random() * 40) * (attempt + 1)));

/**
 * Runs `fn` inside BEGIN … COMMIT on one pooled connection (READ COMMITTED, see pool.ts); rolls
 * back on any error. Deadlocks are retried after a short random backoff (the whole callback
 * re-runs), so `fn` must not have side effects outside the database.
 */
export async function withTransaction<T>(
  fn: (conn: PoolConnection) => Promise<T>,
  opts: { retries?: number } = {},
): Promise<T> {
  const retries = opts.retries ?? 3;
  for (let attempt = 0; ; attempt++) {
    const conn = await db.getConnection();
    try {
      await conn.beginTransaction();
      const result = await fn(conn);
      await conn.commit();
      return result;
    } catch (err) {
      await conn.rollback().catch(() => undefined);
      const code = (err as { code?: string }).code;
      if (!(attempt < retries && code && RETRYABLE.has(code))) throw err;
    } finally {
      conn.release();
    }
    await backoff(attempt);
  }
}

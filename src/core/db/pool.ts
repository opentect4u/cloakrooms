import mysql from 'mysql2/promise';
import { env } from '../../config/env';
import { castDbTime, DB_SESSION_TIME_ZONE } from './dbTime';

/**
 * The one connection pool of the process (CLAUDE.md §9). Created at import; connections are
 * opened lazily. DATETIMEs are stored in IST and come back as UTC 'YYYY-MM-DD HH:mm:ss' strings
 * (core/db/dbTime.ts); DECIMALs as strings, so no float or local-time conversion happens in JS.
 */
export const db = mysql.createPool({
  host: env.DB_HOST,
  port: env.DB_PORT,
  user: env.DB_USER,
  password: env.DB_PASSWORD,
  database: env.DB_NAME,
  connectionLimit: env.DB_POOL_LIMIT,
  maxIdle: env.DB_POOL_LIMIT,
  idleTimeout: 60_000,
  waitForConnections: true,
  queueLimit: 0,
  enableKeepAlive: true,
  keepAliveInitialDelay: 10_000,
  namedPlaceholders: true,
  timezone: DB_SESSION_TIME_ZONE,
  dateStrings: true,
  typeCast: castDbTime,
  supportBigNumbers: true,
  bigNumberStrings: false,
  charset: 'utf8mb4',
});

// Every session runs in IST (+05:30) so NOW() and stored DATETIMEs agree (migration 009), and at
// READ COMMITTED: under REPEATABLE READ, `SELECT … FOR UPDATE` on not-yet-existing keys (sync
// push of new trans_ids) takes gap locks, and concurrent inserts into those gaps deadlock.
// Commands queue per connection, so this runs before the connection's first query.
db.on('connection', (conn) => {
  conn.query(
    `SET time_zone = '${DB_SESSION_TIME_ZONE}', SESSION transaction_isolation = 'READ-COMMITTED'`,
  );
});

export async function pingDb(): Promise<void> {
  await db.query('SELECT 1');
}

export async function closeDb(): Promise<void> {
  await db.end();
}

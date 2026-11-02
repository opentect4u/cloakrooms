import mysql from 'mysql2/promise';
import { env } from '../../config/env';

/**
 * The one connection pool of the process (CLAUDE.md §9). Created at import; connections are
 * opened lazily. DATETIMEs come back as 'YYYY-MM-DD HH:mm:ss' strings and DECIMALs as strings,
 * so no float or local-time conversion ever happens in JS.
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
  timezone: 'Z',
  dateStrings: true,
  supportBigNumbers: true,
  bigNumberStrings: false,
  charset: 'utf8mb4',
});

// Every session runs in UTC so UTC_TIMESTAMP(), NOW() and stored DATETIMEs agree, and at
// READ COMMITTED: under REPEATABLE READ, `SELECT … FOR UPDATE` on not-yet-existing keys (sync
// push of new trans_ids) takes gap locks, and concurrent inserts into those gaps deadlock.
// Commands queue per connection, so this runs before the connection's first query.
db.on('connection', (conn) => {
  conn.query("SET time_zone = '+00:00', SESSION transaction_isolation = 'READ-COMMITTED'");
});

export async function pingDb(): Promise<void> {
  await db.query('SELECT 1');
}

export async function closeDb(): Promise<void> {
  await db.end();
}

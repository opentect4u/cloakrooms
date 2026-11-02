import mysql from 'mysql2/promise';
import { env } from '../../src/config/env';

const SAFE_DB_NAME = /^[A-Za-z0-9_]+$/;

export function dbName(): string {
  if (!SAFE_DB_NAME.test(env.DB_NAME)) {
    throw new Error(`DB_NAME "${env.DB_NAME}" must match ${SAFE_DB_NAME}`);
  }
  return env.DB_NAME;
}

/**
 * Single connection for CLI scripts (migrate/seed). Runtime code uses the pool in core/db.
 * Session time zone is forced to UTC so UTC_TIMESTAMP()/NOW() agree with stored values.
 */
export async function openConnection(
  opts: { withDatabase?: boolean; multipleStatements?: boolean } = {},
) {
  const conn = await mysql.createConnection({
    host: env.DB_HOST,
    port: env.DB_PORT,
    user: env.DB_USER,
    password: env.DB_PASSWORD,
    ...(opts.withDatabase !== false && { database: dbName() }),
    multipleStatements: opts.multipleStatements ?? false,
    timezone: 'Z',
    dateStrings: true,
    charset: 'utf8mb4',
  });
  await conn.query("SET time_zone = '+00:00'");
  return conn;
}

import { execFileSync } from 'node:child_process';
import mysql from 'mysql2/promise';

const TEST_DB = 'cloak_room_billing_test';

/**
 * Recreates the disposable test database from scratch (so every run also proves migrations work on
 * a fresh DB), then migrates and seeds it. Guarded to only ever touch a *_test database.
 */
export default async function setup() {
  if (!/_test$/.test(TEST_DB)) throw new Error('refusing to reset a non-test database');
  const conn = await mysql.createConnection({
    host: process.env.DB_HOST ?? 'localhost',
    port: Number(process.env.DB_PORT ?? 3306),
    user: process.env.DB_USER ?? 'root',
    password: process.env.DB_PASSWORD ?? '',
  });
  await conn.query(`DROP DATABASE IF EXISTS \`${TEST_DB}\``);
  await conn.end();

  for (const script of ['scripts/migrate.ts', 'scripts/seed.ts']) {
    execFileSync(process.execPath, ['--import', 'tsx', script], {
      env: { ...process.env, DB_NAME: TEST_DB, NODE_ENV: 'test' },
      stdio: ['ignore', 'ignore', 'inherit'],
    });
  }
}

/**
 * Migration runner (BUILD_PROMPT Phase 1).
 *
 *   npm run migrate            apply pending db/migrations/NNN_*.sql in order
 *   npm run migrate -- --status
 *
 * - Creates the database if missing, tracks applied files in `schema_migrations` with a checksum.
 * - Each file runs in its own transaction. MySQL auto-commits DDL, so DDL files must stay
 *   re-runnable (CREATE TABLE IF NOT EXISTS, guarded ALTERs); DML in a file is rolled back on error.
 * - An applied file whose content changed is an error: write a new migration instead.
 * - A named lock prevents two runners (e.g. two deploy hosts) from migrating at once.
 */
import { createHash } from 'node:crypto';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { RowDataPacket } from 'mysql2/promise';
import { dbName, openConnection } from './lib/connection';

const MIGRATIONS_DIR = join(__dirname, '..', 'db', 'migrations');
const FILE_PATTERN = /^\d{3}_[a-z0-9_]+\.sql$/;
const LOCK_NAME = 'cloak_room_billing:migrate';
const LOCK_TIMEOUT_SEC = 30;

interface MigrationFile {
  version: string;
  sql: string;
  checksum: string;
}

interface AppliedRow extends RowDataPacket {
  version: string;
  checksum: string;
  applied_at: string;
}

function loadFiles(): MigrationFile[] {
  return readdirSync(MIGRATIONS_DIR)
    .filter((f) => FILE_PATTERN.test(f))
    .sort()
    .map((file) => {
      // Normalise line endings so a CRLF checkout does not change the checksum.
      const sql = readFileSync(join(MIGRATIONS_DIR, file), 'utf8').replace(/\r\n/g, '\n');
      return {
        version: file.replace(/\.sql$/, ''),
        sql,
        checksum: createHash('sha256').update(sql).digest('hex'),
      };
    });
}

async function main() {
  const statusOnly = process.argv.includes('--status');
  const database = dbName();

  const bootstrap = await openConnection({ withDatabase: false });
  await bootstrap.query(
    `CREATE DATABASE IF NOT EXISTS \`${database}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci`,
  );
  await bootstrap.end();

  const conn = await openConnection({ multipleStatements: true });
  try {
    await conn.query(`
      CREATE TABLE IF NOT EXISTS schema_migrations (
        version      VARCHAR(100) NOT NULL,
        checksum     CHAR(64)     NOT NULL,
        applied_at   DATETIME     NOT NULL DEFAULT (UTC_TIMESTAMP()),
        execution_ms INT UNSIGNED NOT NULL DEFAULT 0,
        PRIMARY KEY (version)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci`);

    const [lockRows] = await conn.query<RowDataPacket[]>('SELECT GET_LOCK(?, ?) AS got', [
      LOCK_NAME,
      LOCK_TIMEOUT_SEC,
    ]);
    if (lockRows[0]?.got !== 1) throw new Error('Another migration run holds the lock');

    try {
      const [appliedRows] = await conn.query<AppliedRow[]>(
        'SELECT version, checksum, applied_at FROM schema_migrations ORDER BY version',
      );
      const applied = new Map(appliedRows.map((r) => [r.version, r]));
      const files = loadFiles();

      const changed = files.filter(
        (f) => applied.has(f.version) && applied.get(f.version)?.checksum !== f.checksum,
      );
      if (changed.length) {
        throw new Error(
          `Applied migrations were modified (create a new migration instead): ${changed
            .map((f) => f.version)
            .join(', ')}`,
        );
      }

      const pending = files.filter((f) => !applied.has(f.version));

      if (statusOnly) {
        console.log(`Database: ${database}`);
        for (const f of files) {
          const row = applied.get(f.version);
          console.log(
            `  ${row ? `applied ${row.applied_at}` : 'PENDING            '}  ${f.version}`,
          );
        }
        return;
      }

      if (!pending.length) {
        console.log(`Database ${database} is up to date (${files.length} migrations).`);
        return;
      }

      for (const file of pending) {
        const started = Date.now();
        await conn.beginTransaction();
        try {
          await conn.query(file.sql);
          await conn.query(
            'INSERT INTO schema_migrations (version, checksum, execution_ms) VALUES (?, ?, ?)',
            [file.version, file.checksum, Date.now() - started],
          );
          await conn.commit();
        } catch (err) {
          await conn.rollback();
          throw new Error(`Migration ${file.version} failed: ${(err as Error).message}`, {
            cause: err,
          });
        }
        console.log(`  applied ${file.version} (${Date.now() - started} ms)`);
      }
      console.log(`Database ${database}: ${pending.length} migration(s) applied.`);
    } finally {
      await conn.query('SELECT RELEASE_LOCK(?)', [LOCK_NAME]);
    }
  } finally {
    await conn.end();
  }
}

main().catch((err: Error) => {
  console.error(err.message);
  process.exit(1);
});

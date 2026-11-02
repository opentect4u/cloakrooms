/**
 * Load test (BUILD_PROMPT Phase 8): login, sync push and sync pull against the built server.
 *
 *   npm run loadtest            (builds first; env: LOAD_DURATION_SEC, LOAD_CONNECTIONS,
 *                                LOAD_WORKERS, LOAD_USERS, DB_POOL_LIMIT)
 *
 * Uses a disposable database `<DB_NAME>_load` (dropped and recreated — the name must end in
 * `_load`), runs the server with RATE_LIMIT_MULTIPLIER so one load generator is not throttled, and
 * writes docs/load-test-results.md.
 */
import { execFileSync, spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import { availableParallelism, cpus, totalmem } from 'node:os';
import { join } from 'node:path';
import autocannon, { type Result } from 'autocannon';
import mysql from 'mysql2/promise';
import { env } from '../src/config/env';
import { hashPassword } from '../src/core/security/password';

const LOAD_DB = `${env.DB_NAME.replace(/_(test|load)$/, '')}_load`;
const PORT = 4100;
const BASE = `http://127.0.0.1:${PORT}`;
const DURATION = Number(process.env.LOAD_DURATION_SEC ?? 15);
const CONNECTIONS = Number(process.env.LOAD_CONNECTIONS ?? 50);
// Capped so workers × DB_POOL_LIMIT stays under MySQL's default max_connections (151).
const WORKERS = Number(process.env.LOAD_WORKERS ?? Math.min(availableParallelism(), 6));
const USERS = Number(process.env.LOAD_USERS ?? 200);
const CUSTOMERS = Number(process.env.LOAD_CUSTOMERS ?? 20);
const POOL = Number(process.env.DB_POOL_LIMIT ?? env.DB_POOL_LIMIT);
const ROWS_PER_PUSH = 10;
const PASSWORD = 'LoadTest123';

const childEnv = {
  ...process.env,
  NODE_ENV: 'test',
  LOG_LEVEL: 'warn',
  DB_NAME: LOAD_DB,
  DB_POOL_LIMIT: String(POOL),
  PORT: String(PORT),
  CLUSTER_WORKERS: String(WORKERS),
  RATE_LIMIT_MULTIPLIER: '100000',
};

async function resetDatabase() {
  if (!/_load$/.test(LOAD_DB)) throw new Error(`refusing to reset ${LOAD_DB}`);
  const conn = await mysql.createConnection({
    host: env.DB_HOST,
    port: env.DB_PORT,
    user: env.DB_USER,
    password: env.DB_PASSWORD,
  });
  await conn.query(`DROP DATABASE IF EXISTS \`${LOAD_DB}\``);
  await conn.end();
  for (const script of ['scripts/migrate.ts', 'scripts/seed.ts']) {
    execFileSync(process.execPath, ['--import', 'tsx', script], {
      env: childEnv,
      stdio: 'inherit',
    });
  }
}

async function seedTenant() {
  const conn = await mysql.createConnection({
    host: env.DB_HOST,
    port: env.DB_PORT,
    user: env.DB_USER,
    password: env.DB_PASSWORD,
    database: LOAD_DB,
  });
  // Several stations (customers): logins of one customer serialise on its row lock (seat count),
  // so a single-customer run would measure that lock rather than the API.
  const customerIds: number[] = [];
  for (let k = 1; k <= CUSTOMERS; k++) {
    const [c] = await conn.query<mysql.ResultSetHeader>(
      `INSERT INTO md_customer (customer_name, customer_addr, pin_code, mobile_no, renewal_dt, user_limit, created_by, created_at, updated_at)
       VALUES (?, 'Platform 1', '700001', '9000000000', '2099-12-31', ?, 'load', UTC_TIMESTAMP(), UTC_TIMESTAMP())`,
      [`Load Station ${k}`, USERS],
    );
    customerIds.push(c.insertId);
    await conn.query(
      `INSERT INTO md_rate_dtls (customer_id, block_hours, first_block_rate, next_block_rate, effective_from, created_at, updated_at)
       VALUES (?, 24, 50, 20, '2020-01-01', UTC_TIMESTAMP(), UTC_TIMESTAMP())`,
      [c.insertId],
    );
  }
  const hash = await hashPassword(PASSWORD);
  const users: { id: number; userId: string; code: string }[] = [];
  for (let i = 1; i <= USERS; i++) {
    const userId = `load_user_${i}`;
    const code = `L${i}`;
    const [u] = await conn.query<mysql.ResultSetHeader>(
      `INSERT INTO md_user (customer_id, user_id, user_name, user_code, password_hash, user_type, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, 'O', UTC_TIMESTAMP(), UTC_TIMESTAMP())`,
      [customerIds[(i - 1) % CUSTOMERS], userId, `Load ${i}`, code, hash],
    );
    users.push({ id: u.insertId, userId, code });
  }
  await conn.end();
  return { cid: customerIds[0]!, users };
}

async function waitReady(timeoutMs = 60_000) {
  const until = Date.now() + timeoutMs;
  while (Date.now() < until) {
    try {
      if ((await fetch(`${BASE}/api/v1/ready`)).ok) return;
    } catch {
      /* not up yet */
    }
    await new Promise((r) => setTimeout(r, 300));
  }
  throw new Error('server did not become ready');
}

async function loginAll(users: { userId: string }[]) {
  const tokens: string[] = [];
  for (let i = 0; i < users.length; i += 20) {
    const batch = await Promise.all(
      users.slice(i, i + 20).map(async (u) => {
        const res = await fetch(`${BASE}/api/v1/auth/login`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            user_id: u.userId,
            password: PASSWORD,
            device_id: `dev-${u.userId}`,
          }),
        });
        const body = (await res.json()) as { data: { accessToken: string } };
        if (!res.ok) throw new Error(`login failed: ${res.status}`);
        return body.data.accessToken;
      }),
    );
    tokens.push(...batch);
  }
  return tokens;
}

interface LockStats {
  waits: number;
  timeMs: number;
}

/** InnoDB row-lock wait counters (server-wide; diffed around each scenario). */
async function lockStats(): Promise<LockStats> {
  const conn = await mysql.createConnection({
    host: env.DB_HOST,
    port: env.DB_PORT,
    user: env.DB_USER,
    password: env.DB_PASSWORD,
  });
  const [rows] = await conn.query<mysql.RowDataPacket[]>(
    "SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_row_lock_waits', 'Innodb_row_lock_time')",
  );
  await conn.end();
  const get = (n: string) => Number(rows.find((r) => r.Variable_name === n)?.Value ?? 0);
  return { waits: get('Innodb_row_lock_waits'), timeMs: get('Innodb_row_lock_time') };
}

type Scenario = Result & { lockWaits: number; lockMs: number };

async function run(title: string, opts: autocannon.Options): Promise<Scenario> {
  console.log(`\n▶ ${title}`);
  const before = await lockStats();
  const result = await new Promise<Result>((resolve, reject) => {
    const instance = autocannon(
      { connections: CONNECTIONS, duration: DURATION, ...opts },
      (err, res) => (err ? reject(err) : resolve(res)),
    );
    autocannon.track(instance, { renderProgressBar: false, renderResultsTable: true });
  });
  const after = await lockStats();
  return { ...result, lockWaits: after.waits - before.waits, lockMs: after.timeMs - before.timeMs };
}

const utcNow = () => new Date().toISOString().slice(0, 19).replace('T', ' ');

async function explainQueries(cid: number) {
  const conn = await mysql.createConnection({
    host: env.DB_HOST,
    port: env.DB_PORT,
    user: env.DB_USER,
    password: env.DB_PASSWORD,
    database: LOAD_DB,
  });
  const [[session]] = await conn.query<mysql.RowDataPacket[]>(
    'SELECT session_id FROM td_user_session LIMIT 1',
  );
  // Narrow, realistic windows (a 15-min pull, one report day): with ranges covering every row the
  // optimizer rightly prefers the customer_id prefix of another index, which says nothing useful.
  const recent = new Date(Date.now() - 15 * 60_000).toISOString().slice(0, 19).replace('T', ' ');
  const queries: [string, string, unknown[]][] = [
    [
      'pull: luggage changed since (keyset page)',
      'SELECT * FROM td_luggage_in_out WHERE customer_id = ? AND updated_at >= ? ORDER BY updated_at, trans_id LIMIT 1001',
      [cid, recent],
    ],
    [
      'pull (first): open check-ins',
      "SELECT * FROM td_luggage_in_out WHERE customer_id = ? AND out_flag = 'N' AND is_deleted = 'N' AND updated_at >= ? ORDER BY updated_at, trans_id LIMIT 1001",
      [cid, '1970-01-01 00:00:00'],
    ],
    [
      'report: check-in register (one IST day)',
      'SELECT * FROM td_luggage_in_out WHERE customer_id = ? AND date_time_in >= ? AND date_time_in < ?',
      [cid, '2026-01-01 18:30:00', '2026-01-02 18:30:00'],
    ],
    [
      'report: collection by bill_dt',
      'SELECT bill_dt, SUM(net_amt) FROM td_receipt WHERE customer_id = ? AND bill_dt BETWEEN ? AND ? GROUP BY bill_dt',
      [cid, '2026-01-01', '2026-01-31'],
    ],
    [
      'lookup: by PNR',
      'SELECT * FROM td_luggage_in_out WHERE customer_id = ? AND pnr_no = ?',
      [cid, 'PNR1'],
    ],
    [
      'session check (every request, 30 s cache)',
      'SELECT * FROM td_user_session WHERE session_id = ?',
      [session?.session_id],
    ],
  ];
  const out: string[] = [];
  for (const [label, sql, params] of queries) {
    const [rows] = await conn.query<mysql.RowDataPacket[]>(`EXPLAIN ${sql}`, params);
    out.push(`| ${label} | \`${rows[0]?.key ?? 'none'}\` | ${rows[0]?.type} | ${rows[0]?.rows} |`);
  }
  await conn.end();
  return out;
}

function row(name: string, r: Scenario) {
  const codes = Object.entries(r.statusCodeStats ?? {})
    .map(([code, s]) => `${code}×${(s as { count: number }).count}`)
    .join(' ');
  // autocannon's `errors` already includes `timeouts`.
  return `| ${name} | ${r.requests.average.toFixed(0)} req/s | ${r.latency.p50} | ${r.latency.p97_5} | ${r.latency.p99} | ${r.latency.max} | ${codes} | ${r.errors} (${r.timeouts} timeouts) | ${r.lockWaits} / ${(r.lockMs / 1000).toFixed(1)} s |`;
}

async function main() {
  console.log(
    `Load DB ${LOAD_DB}, ${WORKERS} workers, pool ${POOL}/worker, ${CONNECTIONS} connections, ${DURATION}s per scenario`,
  );
  await resetDatabase();
  const { cid, users } = await seedTenant();

  const server = spawn(process.execPath, [join('dist', 'server.js')], {
    env: childEnv,
    stdio: 'inherit',
  });
  try {
    await waitReady();

    let n = 0;
    const login = await run('login (argon2id verify + session)', {
      url: BASE,
      requests: [
        {
          method: 'POST',
          path: '/api/v1/auth/login',
          headers: { 'content-type': 'application/json' },
          setupRequest: (req) => {
            const u = users[n++ % users.length]!;
            // Same device per user: each login replaces that user's session (no 409).
            return {
              ...req,
              body: JSON.stringify({
                user_id: u.userId,
                password: PASSWORD,
                device_id: `dev-${u.userId}`,
              }),
            };
          },
        },
      ],
    });

    const tokens = await loginAll(users);
    const pullScenario = (title: string, since: string) => {
      let q = 0;
      return run(title, {
        url: BASE,
        requests: [
          {
            method: 'GET',
            path: `/api/v1/sync/pull?since=${encodeURIComponent(since)}`,
            setupRequest: (req) => ({
              ...req,
              headers: { authorization: `Bearer ${tokens[q++ % tokens.length]}` },
            }),
          },
        ],
      });
    };
    // Typical 15-minute device pull: masters + settings + counters + few luggage changes.
    const pullIdle = await pullScenario('sync pull (incremental, quiet station)', utcNow());

    const seqs = new Array<number>(users.length).fill(0);
    let p = 0;
    const push = await run(`sync push (${ROWS_PER_PUSH} new bags per request)`, {
      url: BASE,
      requests: [
        {
          method: 'POST',
          path: '/api/v1/sync/push',
          setupRequest: (req) => {
            const i = p++ % users.length;
            const u = users[i]!;
            const now = utcNow();
            const luggage = Array.from({ length: ROWS_PER_PUSH }, () => {
              const no = ++seqs[i]!;
              return {
                trans_id: randomUUID(),
                local_version: no,
                receipt_no: `${u.code}-${String(no).padStart(5, '0')}`,
                receipt_dt: now.slice(0, 10),
                user_id_in: u.id,
                device_id_in: `dev-${u.userId}`,
                date_time_in: now,
                pasn_name: 'Load Passenger',
                pasn_mob_no: '9876543210',
                pnr_no: `PNR${no}`,
                luggage_qty: 2,
                id_proof_id: 3,
                id_number: 'P1234567',
                out_flag: 'N',
              };
            });
            return {
              ...req,
              headers: { 'content-type': 'application/json', authorization: `Bearer ${tokens[i]}` },
              body: JSON.stringify({ luggage, receipts: [] }),
            };
          },
        },
      ],
    });

    // Right after the push burst every pull returns a full page (1000 rows) of other users' bags.
    const pullHeavy = await pullScenario(
      'sync pull (full 1000-row page after the push burst)',
      new Date(Date.now() - 300_000).toISOString().slice(0, 19).replace('T', ' '),
    );

    const explain = await explainQueries(cid);
    const pushedRows = push['2xx'] * ROWS_PER_PUSH;
    const report = `# Load test results

Generated by \`npm run loadtest\` on ${new Date().toISOString().slice(0, 10)}.

## Environment

| Item | Value |
|---|---|
| CPU | ${cpus()[0]?.model.trim()} × ${availableParallelism()} |
| RAM | ${(totalmem() / 2 ** 30).toFixed(1)} GiB |
| Node | ${process.version} |
| API workers (node:cluster) | ${WORKERS} |
| MySQL pool per worker (\`DB_POOL_LIMIT\`) | ${POOL} (max ${WORKERS * POOL} connections) |
| Load | ${CONNECTIONS} concurrent connections, ${DURATION} s per scenario, ${USERS} users over ${CUSTOMERS} customers |
| Generator | autocannon on the same machine (shares CPU with API and MySQL) |

## Throughput and latency (ms)

| Scenario | Throughput | p50 | p97.5 | p99 | max | HTTP status × count | client errors | InnoDB row-lock waits / total wait |
|---|---|---|---|---|---|---|---|---|
${row('POST /auth/login', login)}
${row('GET /sync/pull (quiet, incremental)', pullIdle)}
${row(`POST /sync/push (${ROWS_PER_PUSH} new bags each)`, push)}
${row('GET /sync/pull (1000-row page)', pullHeavy)}

Push stored ≈ ${pushedRows.toLocaleString('en-IN')} luggage rows (${(pushedRows / DURATION).toFixed(0)} rows/s).

## Index usage (EXPLAIN on the load database)

| Query | Index | Access | Rows est. |
|---|---|---|---|
${explain.join('\n')}

## Notes

- Login: argon2id verify costs ~15 ms of CPU; additionally, logins of the **same customer**
  serialise on the customer row lock that makes the seat (\`user_limit\`) check race-free.
  Different customers log in in parallel.
- Pull returns at most 1000 luggage rows per response (\`has_more\` / \`next_cursor\`), so a
  device that was offline for long pages through instead of downloading everything at once.
- All transactions run at READ COMMITTED (set per pool connection): under REPEATABLE READ the
  first load test deadlocked on gap locks of concurrent pushes inserting new trans_ids.
- Run with the API, MySQL and the load generator on separate hosts for production numbers.
- Rate limits were multiplied (\`RATE_LIMIT_MULTIPLIER\`) so one generator IP is not throttled;
  production keeps the configured limits (env validation rejects a multiplier in production).
- Total MySQL connections = workers × \`DB_POOL_LIMIT\`; keep it below MySQL \`max_connections\`
  (default 151) with headroom for admin tools and migrations.
- Row-lock waits are InnoDB's server-wide counters diffed around each scenario. Login waits are
  the per-customer seat lock (expected). Push waits are few and short; the likely source is the
  counter upsert (\`INSERT … ON DUPLICATE KEY UPDATE\` takes next-key locks even at READ COMMITTED)
  — not yet confirmed with performance_schema.
- Results vary ±30 % between runs on a shared laptop; compare trends, not single numbers.

## Production MySQL recommendations

- \`innodb_buffer_pool_size\`: 50–70 % of the DB host's RAM (the 128 MiB default is far too small).
- Commit durability: \`innodb_flush_log_at_trx_commit=1\` + \`sync_binlog=1\` (two fsyncs per commit)
  is the safe default; use a managed MySQL with fast SSD/NVMe storage rather than relaxing them.
- Keep \`binlog_format=ROW\` (required for READ COMMITTED with binary logging; MySQL 8 default).
- Set \`REDIS_URL\` when running more than one API worker or host, so rate limits are shared.
`;
    writeFileSync(join('docs', 'load-test-results.md'), report);
    console.log('\nWrote docs/load-test-results.md');
  } finally {
    // Stop the cluster (primary + workers). Windows has no signal to the process group.
    if (process.platform === 'win32' && server.pid) {
      execFileSync('taskkill', ['/PID', String(server.pid), '/T', '/F'], { stdio: 'ignore' });
    } else {
      server.kill('SIGINT');
    }
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});

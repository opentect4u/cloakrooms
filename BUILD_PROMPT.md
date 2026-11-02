# BUILD_PROMPT.md — Cloak Room Billing SaaS · Backend build plan

**Role:** Senior Node.js backend engineer. Build a production-grade, secure, high-throughput REST API exactly as specified in `CLAUDE.md` (project memory — read it first, follow it over anything else). Backend only.

**Non-negotiables**

1. Node.js 20 + TypeScript strict + Express, layered modules (`routes → controller → service → DML layer`).
2. One global `mysql2/promise` pool; all data access through `core/db/dml.ts` (`fetch`, `fetchOne`, `save` with flag `I`/`U`, `softDelete`, `raw`, `withTransaction`). No hard deletes anywhere.
3. Tenant isolation from the JWT only; identifier whitelist; parameterised values only.
4. Every endpoint validated (zod), authenticated (except health/login), role-guarded, rate-limited, and returns the standard envelope.
5. Reuse: masters via `crudFactory`; shared zod fragments; pure domain functions.
6. Work one phase at a time. After each phase: lint + typecheck + tests green → update `CLAUDE.md §16` → summarise → **stop and wait for "continue"**.

**Local database:** MySQL 8 · `cloak_room_billing` · `localhost:3306` · user `root` · empty password.
`.env.example`:

```
NODE_ENV=development
PORT=4000
DB_HOST=localhost
DB_PORT=3306
DB_USER=root
DB_PASSWORD=
DB_NAME=cloak_room_billing
DB_POOL_LIMIT=20
JWT_ACCESS_SECRET=change_me_min_32_chars
JWT_REFRESH_SECRET=change_me_min_32_chars_too
JWT_ACCESS_TTL=12h
JWT_REFRESH_TTL=30d
CORS_ORIGINS=http://localhost:3000
REDIS_URL=
SUPER_ADMIN_USER_ID=superadmin
SUPER_ADMIN_PASSWORD=ChangeMe@123
LOG_LEVEL=info
```

---

## Phase 0 — Bootstrap

- `npm init`, TypeScript strict, eslint (+ rule banning `DELETE FROM`), prettier, vitest, supertest, tsx, husky optional.
- Scripts: `dev`, `build`, `start`, `lint`, `typecheck`, `test`, `migrate`, `seed`.
- Folder layout per `CLAUDE.md §8`. `config/env.ts` validates env with zod (fail fast).
- `app.ts` (middleware order: requestId → pino-http → helmet → cors → compression → hpp → json limit → rateLimit → routes → notFound → errorHandler) and `server.ts` (cluster workers, graceful shutdown, timeouts).
- `GET /api/v1/health`. **Done when** server boots and health returns the envelope.

## Phase 1 — Database & migrations

- Connect to `cloak_room_billing`; **inspect existing tables via `information_schema` and report** what exists vs `CLAUDE.md §11`.
- Write numbered SQL migrations: CREATE missing tables, ALTER existing ones to match §11 (new columns, renamed rate columns, keys, indexes, `updated_at` indexes). Never drop data; for `md_rate_dtls` migrate old `from_hour/to_hour/rate` values into new columns before removing them, and ask before removing anything.
- `scripts/migrate.ts` runner with `schema_migrations` table (idempotent, transactional per file).
- Seeds per §11. **Done when** `npm run migrate && npm run seed` work on a fresh and on the existing DB.

## Phase 2 — Core layer

- `core/db/pool.ts` singleton + health check; `registry.ts` loads tables/columns at boot.
- `core/db/dml.ts` exactly per §9 (auto audit columns, `sync_flag='N'` on change, tenant scoping, soft delete, pagination with total, where operators, optional `conn`).
- `core/http` (ApiError, response helpers, asyncHandler); middleware: validate(zod for body/query/params), errorHandler (maps mysql errors e.g. `ER_DUP_ENTRY` → 409), notFound, requestId, rateLimit factory (keyed by user id or IP; Redis store when `REDIS_URL`).
- `core/crud/crudFactory.ts`: config `{ table, pk, roles: {read, write}, schema: {create, update}, searchable, sortable, scope: 'tenant'|'global' }` → list/get/create/update/softDelete routes.
- Unit tests for DML SQL building and tenant injection. **Done when** coverage of core ≥ 90 %.

## Phase 3 — Auth & sessions

- `modules/auth`: login (all checks in §6), refresh (rotation + reuse detection), logout, me.
- `core/security`: argon2 hash/verify, JWT sign/verify, SHA-256 token hashing.
- Middleware: `auth` (verify JWT) → `sessionGuard` (session status `A`, or `F` only for `/sync/push` & `/auth/logout`; 30 s LRU cache) → `requireRole(...)`.
- Login response payload: user, customer, masters, settings (customer + user overrides merged), counters (current period), server_time.
- Users module: CRUD with `user_limit` check, `user_code` unique per customer, force-logout, reset-password, allow toggle. Never return hashes.
- Integration tests: wrong password, blocked user, expired customer, second device → 409, force-logout then push allowed then rejected.

## Phase 4 — Masters

- Via crudFactory: rates (no overlap per `effective_from`), shifts, shift-assignments, header-footer (+ multipart logo upload, PNG ≤200 KB, stored in MEDIUMBLOB, served base64), settings (key whitelist + value validation per key, `REPORT_PWD` hashed).
- Global: gst, id-proofs (S write, all read).
- Super admin: customers (on create → default settings + default rate in one transaction), registrations (latest end_date updates `renewal_dt`), customer payments.

## Phase 5 — Domain & numbering

- `domain/billing.ts`, `domain/gst.ts` per §3 with all test vectors; `domain/numbering.ts` (period key D/M/Y with Indian FY, IST, format `U03-00125`).
- Counter service: get current counters, `bumpCounter(user, series, period, no)` using `INSERT … ON DUPLICATE KEY UPDATE last_no = GREATEST(last_no, VALUES(last_no))`.

## Phase 6 — Sync

- `POST /sync/push`: zod schemas for luggage & receipt rows (uuid trans_id, Y/N enums, UTC datetimes, `customer_id` overwritten from token), chunked transactions, merge SQL from §5, receipt insert-once, exception logging, server-side bill recompute, counter bump, per-row result list.
- `GET /sync/pull`: per §5 (masters, settings, global masters, luggage deltas, `server_time`).
- Online lookup: `GET /luggage` search + `GET /luggage/:trans_id`.
- Integration tests: Case 1 both push orders, Case 2, duplicate bill → exception, identical re-push no-op, tenant A cannot touch tenant B's trans_id, 500-row batch performance.

## Phase 7 — Reports & app version

- Reports in §12 with date range (IST input → UTC query), filters (user, shift, pay mode), pagination, totals in `meta`; report password check when `REPORT_PWD_FLAG='Y'`; exceptions resolve endpoint.
- `GET /app/version` (latest, mandatory flag, apk_url), `POST /app/version` (S); static APK hosting from `/downloads` with caching headers.

## Phase 8 — Hardening & tests

- Review every route for validation, role, rate limit, tenant scope; log redaction; security headers; CORS allow-list; no stack traces in prod.
- Load test (autocannon) on login, push, pull; tune pool size and indexes; document results.
- Overall test coverage ≥ 80 %; CI workflow (lint, typecheck, test, banned-DELETE check).

## Phase 9 — Docs & deploy

- Swagger at `/api/docs` from zod schemas; export `openapi.json` for the Android developer.
- `README.md` (setup, scripts, env, architecture diagram, sync contract summary), Postman collection.
- Production notes: PM2 cluster or Docker, Nginx + HTTPS, managed MySQL (Mumbai), daily backups, log rotation.

---

**Start now with Phase 0, then stop.**

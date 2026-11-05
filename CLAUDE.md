# CLAUDE.md — Cloak Room Billing SaaS · Backend (project memory)

> Read this file fully before any work. It is the single source of truth.
> Source documents (if present): `docs/Cloak_Room_Billing_SaaS__Android_Architecture.pdf`, `docs/Cloak_Room_Table_Schema.xlsx`.
> Scope: **backend API only**. The Android app (Kotlin, Room/SQLite, offline-first) is built by another developer. Never write frontend code.

---

## 1. Product in one paragraph

Multi-tenant SaaS for railway/bus cloak rooms. Each **customer** (tenant/operator) has **users** working on Android POS devices that bill **offline** into SQLite and **sync** to one shared MySQL via this API. Tenants are isolated by `customer_id`. Synergic (us) is the platform owner (super admin).

## 2. Confirmed business rules

1. No payment at check-in. Check-in prints a receipt only. One bill per transaction, made at check-out, pay mode Cash (`C`) or UPI (`U`). No advance / pay-later.
2. "Out Without Bill Print" still records the bill → `td_receipt.bill_printed = 'N'`.
3. Rate does not depend on bag size (no luggage type).
4. Tariff per bag: first block ₹50, every further _started_ block ₹20; block = 24 h. Stored per customer, versioned by `effective_from`.
5. GST is **included** in the rate; worked backwards.
6. A user belongs to a customer, not a device. **One active session per user.**

## 3. Domain formulas (pure functions in `src/domain/`, 100% unit-tested)

```
blockMin    = block_hours * 60
days        = max(1, ceil(minutesStored / blockMin))          // exactly 24h00m = 1, 24h01m = 2
base_amt    = luggage_qty * (first_block_rate + next_block_rate * (days - 1))
net_amt     = base_amt + penal_amt                             // GST-inclusive total
taxable_amt = round2(net_amt * 100 / (100 + gst_pct))
gstAmt      = round2(net_amt - taxable_amt)
GST_TYPE=E (intra-state): cgst = round2(gstAmt/2); sgst = gstAmt - cgst; igst = 0
GST_TYPE=I (inter-state): igst = gstAmt; cgst = sgst = 0
```

Test vectors (must pass): 2 min×2 bags→100 · 24h00m×1→50 · 24h01m×1→70 · 50h×3→270 · ₹70 @18% → 59.32 + 5.34 + 5.34.
Rate row = latest `md_rate_dtls.effective_from <= receipt_dt`. GST row = latest `md_gst.effective_from <= bill_dt`. Money: integer paise internally or decimal.js — never raw float math.

## 4. Numbering

- Format `<user_code>-<5-digit no>` e.g. `U03-00125`. Separate series: `R` = receipt (check-in), `B` = bill (check-out).
- Series belongs to the **user** (moves with them across devices). Stored in `md_user_counter`.
- Reset per setting `RECEIPT_RESET`: `D` → period `YYYY-MM-DD`, `M` → `YYYY-MM`, `Y` → Indian FY `FY2026-27` (April–March). Periods computed in **IST**.
- Login returns current-period last numbers; every push raises the counter to `GREATEST(stored, pushed)`.

## 5. Sync contract (core of the backend)

- Device marks unsynced rows `sync_flag='N'`, keeps `local_version` (device only). Server never stores `local_version`, only echoes it back.
- **Push** `POST /api/v1/sync/push` — body `{ luggage: [...], receipts: [...] }`. Process luggage first, then receipts, inside one DB transaction per batch (chunk ≤ 500 rows).
- **Merge rule for `td_luggage_in_out` (key `trans_id`, UUID VARCHAR(36))**:
  - check-in fields are **write-once** (never updated after insert);
  - check-out fields (`user_id_out, device_id_out, shift_id_out, date_time_out`) are **fill-only**: `COALESCE(existing, incoming)`;
  - `out_flag`: once `'Y'` stays `'Y'`;
  - `updated_at = UTC_TIMESTAMP()` on every effective change (drives pull).
  ```sql
  INSERT INTO td_luggage_in_out (...) VALUES (...)
  ON DUPLICATE KEY UPDATE
    user_id_out   = COALESCE(user_id_out,   VALUES(user_id_out)),
    device_id_out = COALESCE(device_id_out, VALUES(device_id_out)),
    shift_id_out  = COALESCE(shift_id_out,  VALUES(shift_id_out)),
    date_time_out = COALESCE(date_time_out, VALUES(date_time_out)),
    out_flag      = IF(out_flag='Y','Y',VALUES(out_flag)),
    updated_at    = UTC_TIMESTAMP();
  ```
- **Receipts (`td_receipt`, PK `trans_id`)**: insert once. Second bill for same `trans_id`, bill without a luggage row, duplicate `receipt_no`/`bill_no` → write to `td_sync_exception` (payload JSON + reason). Server **recomputes** the bill with §3; mismatch → keep bill, log exception `AMOUNT_MISMATCH`.
- Identical re-push changes nothing (idempotent). Retries always safe.
- **Response**: `{ results: [{ table, trans_id, local_version, status: 'ACCEPTED'|'EXCEPTION', reason? }], counters: {...}, server_time }`. Both statuses tell the device to set `sync_flag='Y'` (exceptions are owned by the admin now).
- **Pull** `GET /api/v1/sync/pull?since=<UTC>` → masters + settings changed since `since` (incl. `is_deleted='Y'` rows so device can hide them), global masters (`md_gst`, `md_id_proof`), luggage rows of the same customer changed since `since` (other users' open check-ins **and** bags checked out elsewhere — Case 1). First pull (no `since`): all masters + all open check-ins. Use `updated_at >= since` and return `server_time` as next cursor (client dedupes by PK).
- Case 1 (in on device 1, out on device 2 via QR) and Case 2 (out after in already synced) must both end as **one** cloud row regardless of push order.

## 6. Auth & sessions

- `POST /auth/login {user_id, password, device_id, app_version}` checks: user exists, `is_deleted='N'`, `allow_flag='Y'`; customer `active_flag='Y'`, `is_deleted='N'`, `renewal_dt >= today(IST)`; active sessions of customer < `user_limit`; user has **no active session** → else `409 SESSION_ACTIVE` with `session_started_at` and masked device.
- Success: create `td_user_session` row, store **SHA-256 hash** of token, set `md_user.device_id / session_token(hash) / session_started_at / last_login`. Return `{ accessToken, refreshToken, user, customer, masters, settings, counters, server_time }`.
- JWT (HS256, short access ~12h + refresh ~30d, rotate on refresh). Claims: `sub` (md_user.id), `cid` (customer_id), `typ` (S/A/O), `sid` (session id). Every request validates `sid` is still `A` (active) in DB (cache 30 s in-memory LRU).
- `POST /auth/logout` → session `L`, clear md_user session columns. (Device must push first — client rule.)
- `POST /users/:id/force-logout` (admin A/S) → session `F`. A token with status `F` may call **only** `POST /sync/push` and `POST /auth/logout`, then is dead.
- Optional setting `FORCE_ONLINE_DAYS` returned at login.
- Passwords: argon2id (or bcrypt cost 12). `password_hash`/`session_token` never leave the server.

## 7. Roles

`S` Synergic super admin (customer_id NULL, sees all tenants) · `A` customer admin (own tenant) · `O` operator (own tenant, sync + own data, no masters/reports unless setting allows).
**Tenant rule:** `customer_id` comes **only from the token**, never from body/query. The DML layer injects it automatically.

## 8. Architecture & conventions

- **Stack**: Node.js 20 LTS · TypeScript (strict) · Express 4 · mysql2/promise · zod · jsonwebtoken · argon2 · helmet · cors · express-rate-limit · hpp · compression · pino/pino-http · dotenv · vitest + supertest · eslint + prettier.
- **Layout**
  ```
  src/
    config/        env.ts (zod-validated), constants.ts
    core/db/       pool.ts (singleton), registry.ts (table/column whitelist from information_schema), dml.ts, tx.ts
    core/http/     ApiError.ts, response.ts, asyncHandler.ts
    core/middleware/ requestId, auth, sessionGuard, requireRole, validate, rateLimit, errorHandler, notFound
    core/crud/     crudFactory.ts  (config → list/get/create/update/delete routes)
    core/utils/    time.ts (UTC/IST, FY), money.ts, pagination.ts
    domain/        billing.ts, gst.ts, numbering.ts   (pure, no I/O)
    modules/<name>/ <name>.routes.ts · .controller.ts · .service.ts · .schema.ts
    routes.ts · app.ts · server.ts (cluster + graceful shutdown)
  db/migrations/  NNN_description.sql   db/seeds/   scripts/migrate.ts
  tests/unit · tests/integration
  ```
- Controllers thin → services hold logic → services call **only** the global DML layer. No SQL strings in controllers.
- Routes versioned: `/api/v1/...`. kebab-case paths, plural nouns.
- **Response envelope** (always): `{ success, message, data, meta?, errors?, requestId }`. Errors via `ApiError(status, code, message, details?)`; codes UPPER_SNAKE (`VALIDATION_ERROR`, `UNAUTHORIZED`, `FORBIDDEN`, `NOT_FOUND`, `SESSION_ACTIVE`, `SUBSCRIPTION_EXPIRED`, `RATE_LIMITED`, `CONFLICT`).
- Pagination: `?page=1&limit=50` (max 200), `meta: { page, limit, total }`. Sorting: `?sort=col:asc` validated against whitelist.
- Times: stored UTC `DATETIME`; API I/O `YYYY-MM-DD HH:mm:ss` UTC; reports convert to IST. Pool option `timezone: 'Z'`, `dateStrings: true`.

## 9. Global DB layer (MUST be used everywhere)

- `core/db/pool.ts`: one `mysql2/promise` pool created once at boot (connectionLimit from env, `waitForConnections`, `namedPlaceholders`, keep-alive). Export `db` singleton; health-check on start; close on shutdown.
- `core/db/dml.ts` — the **only** generic data API:
  | Function                                                                             | Purpose                                                                                                                                                                        |
  | ------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
  | `fetch(table, { where, columns, orderBy, page, limit, includeDeleted }, ctx, conn?)` | Select many; returns `{ rows, total }`. Adds `is_deleted='N'` unless `includeDeleted`.                                                                                         |
  | `fetchOne(table, where, ctx, conn?)`                                                 | Select one or `null`.                                                                                                                                                          |
  | `save(table, data, { flag: 'I' \| 'U', where? }, ctx, conn?)`                        | `I` = insert (sets `created_by/created_at`, `sync_flag='N'`); `U` = update (requires `where`, sets `updated_by/updated_at`, `sync_flag='N'`). Returns insertId / affectedRows. |
  | `softDelete(table, where, ctx, conn?)`                                               | `UPDATE … SET is_deleted='Y', updated_by, updated_at`. **The only "delete".**                                                                                                  |
  | `raw(sql, params, conn?)`                                                            | Parameterised custom SQL (merge upserts, reports).                                                                                                                             |
  | `withTransaction(async conn => …)`                                                   | Begin/commit/rollback wrapper.                                                                                                                                                 |
- `where` supports `{ col: v }`, `{ col: { op: '>='|'<='|'<>'|'like', value } }`, `{ col: { in: [...] } }`, `{ col: null }`.
- **Every** table/column identifier is checked against `registry` (loaded from `information_schema` at boot) and back-quoted; values are always placeholders. Unknown identifier → 400.
- Tenant scoping: if table has `customer_id` and `ctx.userType !== 'S'`, the layer forces `customer_id = ctx.customerId` on reads, writes and soft deletes.
- **Hard `DELETE` is forbidden in the codebase** (add an eslint `no-restricted-syntax`/grep check in CI).

## 10. Security & high load

helmet · strict CORS allow-list · JSON body limit 1 MB (sync 5 MB) · hpp · zod validation on body/query/params (strip unknown) · rate limits (express-rate-limit, keyed by **user id when authenticated, IP otherwise** — many counters share one station NAT):
login 10/min per IP+user_id · sync 120/min per user · general 300/min per user · reports 30/min per user. Store: memory by default, Redis when `REDIS_URL` set.
Also: request-id + pino access logs (redact password/token/id_number) · `compression` · server/headers timeouts · pool sizing · `node:cluster` (one worker per CPU) or PM2 cluster · indexes per schema · paginated lists only · `/health` and `/ready` endpoints · graceful shutdown (stop accepting, drain, close pool). No stack traces in production responses. Aadhaar: store/print last 4 digits only when `md_id_proof.mask_flag='Y'`.

## 11. Database

Local dev: MySQL 8 · DB `cloak_room_billing` · host `localhost` · port `3306` · user `root` · **empty password**. InnoDB, utf8mb4.
**The DB may already contain tables.** First inspect `information_schema`; if tables exist write `ALTER` migrations for the deltas below, otherwise `CREATE`. Never drop existing data. Migrations are plain SQL files tracked in `schema_migrations`.

`[STD]` = `is_deleted ENUM('Y','N') DEFAULT 'N'`, `sync_flag ENUM('Y','N') DEFAULT 'N'`, `created_by VARCHAR(100)`, `created_at DATETIME`, `updated_by VARCHAR(100)`, `updated_at DATETIME` (+ index on `updated_at` for pull). All Y/N columns are `ENUM('Y','N')`.

| Table                                   | Columns (PK first)                                                                                                                                                                                                                                                                                                                                                                                                  |
| --------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| md_customer                             | customer_id INT UNSIGNED AI · customer_name V155 · customer_addr V200 · **state V50** · pin_code V10 · mobile_no V20 · email V150 · renewal_dt DATE · gst_no V20 · upi_id V255 · user_limit SMALLINT DEF 1 · **active_flag Y/N DEF Y** · **remarks V255** · [STD]                                                                                                                                                   |
| md_customer_registration _(cloud only)_ | reg_id AI · reg_no V30 UQ · customer_id · start_date · end_date (latest row updates `md_customer.renewal_dt`) · user_count · remarks · [STD minus sync_flag]                                                                                                                                                                                                                                                        |
| td_customer_billing _(cloud only)_      | payment_id BIGINT AI · customer_id · reg_id NULL · payment_dt · base_amt/cgst/sgst/igst/net_amt DEC(10,2) · pay_mode ENUM(C,U,B) · transaction_no V50 · remarks · [STD minus sync_flag]                                                                                                                                                                                                                             |
| md_user                                 | id AI · customer_id NULL(S only) · user_id V100 UQ (login) · user_name · **user_code V10 UQ(customer_id,user_code)** · password_hash · user_type ENUM(S,A,O) · allow_flag · device_id · session_token (hash) · session_started_at · last_login · [STD]                                                                                                                                                              |
| md_id_proof _(global)_                  | id_proof_id AI · id_proof_name V50 · **mask_flag Y/N DEF N** · [STD]                                                                                                                                                                                                                                                                                                                                                |
| md_rate_dtls                            | rate_id AI · customer_id · **block_hours INT DEF 24** · **first_block_rate DEC(10,2)** · **next_block_rate DEC(10,2)** · effective_from DATE · UQ(customer_id,effective_from) · [STD] _(replaces from_hour/to_hour/rate)_                                                                                                                                                                                           |
| md_gst _(global)_                       | gst_id AI · cgst_pct · sgst_pct · igst_pct DEC(5,2) · **effective_from DATE** · [STD]                                                                                                                                                                                                                                                                                                                               |
| md_header_footer                        | id AI · customer_id · line_type ENUM(H,F,L) · **line_order SMALLINT** · narration V100 · image MEDIUMBLOB · in_flag · out_flag · report_flag · [STD]                                                                                                                                                                                                                                                                |
| md_shift                                | shift_id AI · customer_id · shift_name · from_time TIME · to_time TIME (may cross midnight) · [STD]                                                                                                                                                                                                                                                                                                                 |
| td_assign_shift                         | id BIGINT AI · customer_id · user_id→md_user.id · shift_id · effective_from · [STD]                                                                                                                                                                                                                                                                                                                                 |
| md_setting                              | setting_id AI · customer_id · user_id NULL (NULL = whole customer) · setting_name V50 · setting_value V255 · setting_type ENUM(G,B,P) · **UQ(customer_id,user_id,setting_name)** · [STD]. Keys: REPORT_FLAG, REPORT_PWD_FLAG, REPORT_PWD(hash), TOTAL_COLLECTION_FLAG, GST_FLAG, GST_TYPE(E/I), QR_FLAG, DEFAULT_PAY_MODE(C/U), RECEIPT_RESET(D/M/Y), SYNC_INTERVAL_MIN(≥15), PAPER_WIDTH(58/80), FORCE_ONLINE_DAYS |
| td_luggage_in_out                       | trans_id V36 PK · customer_id · receipt_no V20 · receipt_dt DATE · UQ(customer_id,receipt_no,receipt_dt) · user_id_in · device_id_in · shift_id_in · date_time_in (IDX customer_id,date_time_in) · pasn_name V200 · pasn_mob_no V15 (IDX) · pnr_no V20 (IDX) · luggage_qty · id_proof_id · id_number V50 · user_id_out · device_id_out · shift_id_out · date_time_out · out_flag DEF N · [STD]                      |
| td_receipt                              | trans_id V36 PK/FK · customer_id · bill_no V20 · bill_dt · UQ(customer_id,bill_no,bill_dt) · user_id · device_id · shift_id · luggage_qty · **days_charged** · **block_hours · first_block_rate · next_block_rate** (snapshot = "rate_applied") · base_amt · penal_amt · net_amt · taxable_amt · gst_pct · cgst · sgst · igst · gst_flag · pay_mode ENUM(C,U) · upi_ref V50 · bill_printed DEF Y · [STD]            |
| td_sync_exception _(cloud only)_        | id BIGINT AI · customer_id · trans_id (IDX) · table_name · payload JSON · reason V255 · user_id · device_id · resolved_flag DEF N · resolved_by · resolved_at · created_at DEF CURRENT_TIMESTAMP                                                                                                                                                                                                                    |
| **md_user_counter** _(new)_             | id AI · customer_id · user_id · series ENUM('R','B') · period_key V12 · last_no INT · UQ(user_id,series,period_key) · [STD]                                                                                                                                                                                                                                                                                         |
| **td_user_session** _(new, cloud only)_ | session_id V36 PK · customer_id · user_id · device_id · token_hash · refresh_hash · status ENUM('A','F','L') · started_at · last_seen_at · ended_at · ended_by                                                                                                                                                                                                                                                      |
| **md_app_version** _(new, cloud only)_  | id AI · version_code INT UQ · version_name V20 · apk_url V255 · mandatory_flag · release_notes TEXT · [STD]                                                                                                                                                                                                                                                                                                         |

Seeds: super admin (`user_type='S'`, password from env), ID proofs (Aadhaar[mask Y], Driving Licence, Passport, PAN, Ration Card, Voter ID), GST 9+9+0 from 2017-07-01, default settings template applied on customer create, default rate (24h/50/20) on customer create.

## 12. API map (`/api/v1`)

| Area                                                                                                                                                    | Endpoints                                                                                                                                                                                                      | Roles                   |
| ------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------- |
| Health                                                                                                                                                  | `GET /health`, `GET /ready`                                                                                                                                                                                    | public                  |
| Auth                                                                                                                                                    | `POST /auth/login`, `POST /auth/refresh`, `POST /auth/logout`, `GET /auth/me`                                                                                                                                  | public / any            |
| Customers                                                                                                                                               | CRUD `/customers` + `GET /customers/:id/summary`                                                                                                                                                               | S                       |
| Registrations                                                                                                                                           | CRUD `/customers/:id/registrations`                                                                                                                                                                            | S                       |
| Customer billing                                                                                                                                        | CRUD `/customers/:id/payments`                                                                                                                                                                                 | S                       |
| Users                                                                                                                                                   | CRUD `/users`, `POST /users/:id/force-logout`, `POST /users/:id/reset-password`, `PATCH /users/:id/allow`                                                                                                      | A, S                    |
| Masters (crudFactory)                                                                                                                                   | `/rates`, `/shifts`, `/shift-assignments`, `/header-footer` (+ logo upload, multipart, ≤200 KB PNG), `/settings`                                                                                               | A, S                    |
| Global masters                                                                                                                                          | `/gst`, `/id-proofs`                                                                                                                                                                                           | S write, all read       |
| Sync                                                                                                                                                    | `POST /sync/push`, `GET /sync/pull`                                                                                                                                                                            | A, O (S excluded)       |
| Luggage lookup (online)                                                                                                                                 | `GET /luggage?search=pnr                                                                                                                                                                                       | mobile                  | name | receipt_no&status=in | out`,`GET /luggage/:trans_id` | A, O |
| Reports                                                                                                                                                 | `GET /reports/collection-summary`, `/check-in-register`, `/check-out-register`, `/bags-in-store`, `/gst`, `/user-shift-collection`, `/unprinted-bills`, `/exceptions`; `PATCH /reports/exceptions/:id/resolve` | A, S (O if REPORT_FLAG) |
| App                                                                                                                                                     | `GET /app/version`, `POST /app/version` (S)                                                                                                                                                                    | any / S                 |
| Swagger UI at `/api/docs` generated from zod schemas (`zod-to-openapi`). The OpenAPI file is the contract with the Android developer — keep it current. |

## 13. Decisions taken (change here if business says otherwise)

- Rate is **per bag**. Penalty is operator-typed and GST-inclusive (part of `net_amt`).
- Any user of the same customer can check out a bag checked in by another user.
- `md_customer.renewal_dt` is the column name everywhere.
- Server is authoritative for masters; devices never create master rows.
- **2026-11-03 (frontend requests A1–A8, B):** server supports grace minutes, GST-exclusive pricing, penalty without GST, and `GST_FLAG = N` = 0 % GST (Phase 11). Of the 20 new device settings only **shift login** is enforced by the server; the rest are stored/validated/synced only. Station admins (A) may edit their own customer via `/customers/me` (Phase 11). Bills get `gst_no` stamped by the server. No forced-login / session-takeover feature (A7 dropped). Login refuses an app `version_code` older than a live mandatory release (426).

## 14. Open points (do not block; leave `TODO(open-point)` comments)

Summary-report fields · POS printer models (frontend) · Aadhaar legal confirmation · FY numbering format with CA.

## 15. Working rules for Claude Code

- Work **phase by phase** from `BUILD_PROMPT.md`; at the end of each phase run `npm run lint && npm run typecheck && npm test`, update §16, then stop and summarise.
- Reuse before writing: crudFactory for masters, DML layer for data, shared zod fragments (`ynEnum`, `utcDateTime`, `pagination`, `uuid`).
- No secrets in code; everything via `.env` (commit `.env.example` only).
- Small focused commits (Conventional Commits).

## 16. Progress (update as you go)

- [x] Phase 0 Bootstrap · [x] 1 DB & migrations · [x] 2 Core layer · [x] 3 Auth & sessions · [x] 4 Masters · [x] 5 Domain & numbering · [x] 6 Sync · [x] 7 Reports & app version · [x] 8 Hardening & tests · [x] 9 Docs & deploy · [x] 10 Frontend requests I (password change, users list, QR check-out, bill GSTIN, version gate) · [ ] 11 Frontend requests II (settings keys, billing modes, shift login, /customers/me)

### Phase notes
- **Phase 0 (2026-11-02):** Express app + cluster server, zod env (fail-fast), envelope/ApiError, requestId, pino-http (redaction), helmet, CORS allow-list, compression, hpp, 1 MB JSON, IP rate limit (300/min), `GET /api/v1/health`. ESLint bans `DELETE FROM`/`TRUNCATE`/`DROP TABLE` literals; `scripts/check-no-delete.mjs` greps src/scripts/db (runs in `npm run lint`).
  - Password hashing uses **`@node-rs/argon2`** (argon2id): the `argon2` npm package's Windows prebuild crashes (access violation) and node-gyp can't build on this machine.
  - zod is v4 (`z.url()` etc.). Vitest config is `vitest.config.mts`. `.env` has generated JWT secrets (`.env.example` placeholders are < 32 chars and are rejected).
  - Optional env: `CLUSTER_WORKERS` (0 = per-CPU in prod, 1 otherwise), `TRUST_PROXY` (default `loopback`), `SHUTDOWN_TIMEOUT_MS`.
  - `/ready` comes with the DB pool in Phase 2.
- **Phase 1 (2026-11-02):** DB was empty (MySQL 8.4) → all 16 §11 tables created by `db/migrations/001–005` (no ALTERs, no old `md_rate_dtls` columns to migrate). `scripts/migrate.ts`: creates DB if missing, `schema_migrations` with SHA-256 checksum (edited applied file = error), `GET_LOCK`, one transaction per file (MySQL auto-commits DDL, so keep DDL files re-runnable), `--status`. `scripts/seed.ts`: idempotent seeds in one transaction (ID proofs, GST, super admin — never resets an existing password).
  - Schema choices beyond §11: FKs on all references (sync must pre-validate refs and route bad rows to `td_sync_exception`, or a whole batch fails); `md_setting.user_key` = generated `IFNULL(user_id,0)` so UQ(customer_id,user_key,setting_name) really blocks duplicate customer-wide settings; tenant tables index `(customer_id, updated_at)`, global tables `(updated_at)`; extra report/search indexes on luggage/receipt; `md_user.user_code` NULL for S users; `td_sync_exception.created_at DEFAULT (UTC_TIMESTAMP())`; `td_user_session.token_hash/refresh_hash CHAR(64)`.
  - Soft-delete vs UNIQUE: a soft-deleted `md_rate_dtls` row still occupies (customer_id, effective_from) — Phase 4 service must revive it instead of inserting.
  - Session time zone is forced to `+00:00` on script connections; the Phase 2 pool must do the same on every new connection.
  - Customer-create templates live in `src/config/defaults.ts` (settings + 24h/50/20 rate), applied in Phase 4.
  - Tests use DB `cloak_room_billing_test`; `tests/globalSetup.ts` drops (guarded: name must end `_test`), migrates and seeds it before every run.
- **Phase 2 (2026-11-02):** `core/db/pool.ts` (singleton, `SET time_zone='+00:00'` per connection, DECIMAL/DATETIME as strings), `registry.ts` (information_schema whitelist, loaded in `server.ts` boot with retries; `/ready` = ping + registry), `dml.ts` (§9 API; pure `build*` functions unit-tested), `tx.ts` (`withTransaction`, retries `ER_LOCK_DEADLOCK` ×2 — callback must be DB-only). `core/crud/crudFactory.ts`, `core/middleware/{validate,requireRole}.ts`, `core/validation/fragments.ts` (`ynEnum`, `utcDateTime`, `dateOnly`, `timeOfDay`, `uuid`, `positiveId`, `idParams`, `money`, `pagination`), `core/utils/pagination.ts` (`sortParam`), `core/context.ts` (`RequestContext` = `req.auth`), `core/redis.ts`.
  - DML behaviours to rely on: inserts set `updated_by/updated_at` too (pull uses `updated_at`); `customer_id` is forced on insert and ignored on update for non-S; update/softDelete need a non-empty caller `where` and only touch live rows (`is_deleted='N'`); `password_hash/session_token/token_hash/refresh_hash` are never selected unless named in `columns`; `fetch` without page/limit is unpaginated (internal use only — API lists must paginate); `fetchOne(table, where, ctx, conn?, {columns, includeDeleted})`; `raw` refuses statements starting with DELETE/TRUNCATE/DROP.
  - crudFactory: for S on tenant tables `customer_id` is required in the create body and allowed as a list filter (S has no tenant); for A/O it is ignored. Hooks `beforeCreate/beforeUpdate` run inside the write transaction. Buffers are returned as base64. Routers must be mounted behind auth (Phase 3).
  - Rate limiting: global `floodLimiter` per IP (3000/min, before auth); `limiters.{login,general,sync,reports}` are per user (IP+user_id for login) and must be mounted after auth. Redis store when `REDIS_URL`.
  - errorHandler maps MySQL errors: `ER_DUP_ENTRY`→409 (with key name), missing FK→400, referenced row→409, bad value→400, connection/deadlock→503.
  - Coverage threshold `src/core/** ≥ 90 %` enforced in `vitest.config.mts` (`npm run test:coverage`).
- **Phase 3 (2026-11-02):** `core/security/{tokens,sessionStore}.ts`, `core/middleware/{auth,sessionGuard}.ts`, `modules/auth` (login/refresh/logout/me + `session.service.ts`), `modules/users`, `modules/masters/masters.service.ts` (`loadMasters(ctx,{since})`, `loadSettings`), `modules/counters/counters.service.ts` (`getCurrentCounters`), `domain/numbering.ts` (`periodKey`, IST + Indian FY).
  - Protect a router with `...authenticate()` then `limiters.general` (or sync/reports) then `requireRole(...)`. `authenticate({ allowForced: true })` only on `POST /sync/push` and `POST /auth/logout`.
  - JWT: HS256, `iss=cloak-room-billing`, `aud=access|refresh` (separate secrets), unique `jti`. Claims `sub,cid,typ,sid`; identity/tenant used per request come from the DB session row (cached 30 s), not the claims. The presented token's SHA-256 must equal `td_user_session.token_hash`, so rotation kills the previous access token; a hash mismatch forces a fresh DB read (cross-worker safety).
  - Login: password checked first (dummy argon2 verify for unknown users → same 401 + timing); then blocked → 403; customer inactive/deleted → 403; `renewal_dt` NULL or < today IST → 403 `SUBSCRIPTION_EXPIRED`. In a transaction locking md_customer + md_user rows: stale sessions (no activity for refresh TTL) → L `system:expired`; active session on another device → 409 `SESSION_ACTIVE` (`session_started_at`, masked `device_id`); **same device_id re-login replaces its old session** (L `system:relogin`); active sessions of customer ≥ `user_limit` → 409 `USER_LIMIT_REACHED`.
  - Refresh rotates both tokens; presenting an already-rotated refresh token → session L `system:refresh-reuse` + 401 `TOKEN_REUSED`. Refresh re-checks blocked user and subscription.
  - Force-logout / block (allow N) / soft delete / reset-password all set the user's live sessions to **F** and clear md_user session columns, so the user can log in elsewhere while the old device can still push + logout. Self block/delete refused.
  - `user_limit` is also enforced on user create and unblock: allowed (not deleted, allow_flag Y) users of the customer ≤ user_limit, customer row locked.
  - New error codes: `TOKEN_EXPIRED`, `TOKEN_REUSED`, `SESSION_ENDED` (401), `USER_LIMIT_REACHED` (409).
  - Login payload `settings` omits `REPORT_PWD` (hash); super admin login returns `customer:null, settings:{}, counters:null`, global masters only. `app_version` is accepted and logged only (Phase 7 decides on update enforcement).
- **Phase 4 (2026-11-02):** modules `rates`, `shifts`, `shift-assignments`, `header-footer` (+ `POST /header-footer/logo`, multer memory storage, PNG signature checked, ≤200 KB → else 413), `settings`, `gst`, `id-proofs`, `customers` (+ `/:id/summary`, nested `/:id/registrations`, `/:id/payments`). All mounted in `routes.ts` via `secured()` = auth → session → general limiter; roles inside each router. Shared helpers in `modules/masters/refs.ts` (`MASTER_ROLES`, `tenantOf`, `assertTenantRef`, `assertNoLiveDuplicate`); `core/utils/money.ts` (paise); fragments `percent`, `ynFlag`.
  - crudFactory now supports `parent` (nested routes, parent must exist → 404, FK forced), `reviveDeletedOn` (create matching a soft-deleted row's natural key revives that row — used by rates and settings), `transformRow`, `hooks.afterWrite(event, {id,parentId,row})` in the write transaction; delete runs in a transaction. Prefer validation in hooks over `.superRefine` on create schemas (crudFactory `.extend`s them).
  - Settings: key whitelist + per-key value rules + `setting_type` derived from the key (`settings.schema.ts` `SETTING_RULES`); `REPORT_PWD` stored argon2id and returned as `********`; per-user override must reference a user of the tenant; update changes `setting_value` only.
  - Customers: create inserts `DEFAULT_SETTINGS` (customer-wide) + `DEFAULT_RATE` effective today (IST) in the same transaction. `renewal_dt` is not writable; registrations' create/update/delete recompute it as MAX(live end_date) (NULL if none → login `SUBSCRIPTION_EXPIRED`). Registration `user_count` does **not** change `user_limit` (open question). Payments: `net_amt` computed from base+cgst+sgst+igst when omitted, must match when given; `reg_id` must belong to the customer.
  - Header/footer text lines via JSON (H/F only); logo row (L) only via upload, one per customer (re-upload replaces). Monochrome is not enforced (signature check only).
  - GST and ID proofs: S writes, A/O/S read; duplicate live `effective_from` / `id_proof_name` → 409.
- **Phase 5 (2026-11-02):** `domain/billing.ts` (`computeBill`, `minutesStored`, `daysCharged`, `baseAmountPaise`, `selectEffective`, `parseUtc`), `domain/gst.ts` (`splitGst`, `gstPctFor`, `roundDiv`), `domain/numbering.ts` (`periodKeyOfDate`, `periodKey`, `formatDocNo`, `parseDocNo`), `modules/counters` (`bumpCounter`, `getCurrentCounters`, `resetModeOf`). Coverage threshold `src/domain/** = 100 %`.
  - **Exact rules the Android app must mirror** (else `AMOUNT_MISMATCH`): minutes = whole minutes between `date_time_in` and `date_time_out` (seconds dropped, floor); all money in integer paise; round2 = **half-up**; taxable = round(net × 10000 / (10000 + gst% × 100)); E split: cgst = round(gst / 2) (odd paisa goes to CGST), sgst = gst − cgst; gst_pct = cgst_pct + sgst_pct (E) or igst_pct (I).
  - GST is computed whatever `GST_FLAG` says (the flag only controls printing the breakup) — TODO(open-point) if a non-GST-registered operator should bill 0 % GST.
  - Doc numbers: `^[A-Z0-9]{1,10}-\d{5,9}$`, ≥ 1; padded to 5 digits, longer after 99999. Counter period comes from the document's IST date (`receipt_dt` / `bill_dt`) via `periodKeyOfDate`.
  - `bumpCounter`: single `INSERT … AS incoming ON DUPLICATE KEY UPDATE last_no = GREATEST(...)` (row-alias syntax instead of deprecated `VALUES()`); `updated_*`/`sync_flag` change only when the number rises (re-push is a no-op); verifies the series owner belongs to ctx's tenant first (raw bypasses DML scoping).
- **Phase 6 (2026-11-02):** `modules/sync` (`sync.schema`, `sync.refs` = per-push lookups, `luggage.merge`, `receipt.merge`, `billCheck`, `counters.collector`, `sync.results`, `sync.service`, `sync.routes`), `modules/luggage` (lookup), `domain/masking.ts`. `/sync` has a 5 MB JSON parser mounted before the global 1 MB one; `/sync/push` uses `authenticate({allowForced:true})` + `limiters.sync` + role A/O; `/sync/pull` normal auth. `RequestContext.deviceId` now comes from the session.
  - Push envelope `{luggage[], receipts[]}` (≤ 5000 each) → 400 only if the envelope is wrong; **each row is validated separately** and a bad row becomes `EXCEPTION` (`VALIDATION_ERROR`) so one bad row never blocks a device. Exception codes: `VALIDATION_ERROR, INVALID_REFERENCE, TENANT_MISMATCH, DUPLICATE_RECEIPT_NO, DUPLICATE_CHECKOUT, BILL_WITHOUT_LUGGAGE, DUPLICATE_BILL, DUPLICATE_BILL_NO, CHECKOUT_MISSING, NO_RATE, AMOUNT_MISMATCH, DB_ERROR`; `reason` = `CODE: detail`; payload = pushed row (+ `expected` bill / `existing` values).
  - Luggage merge: rows locked `FOR UPDATE`; **plain INSERT, not the §5 ODKU** (ODKU would also fire on UNIQUE(customer_id, receipt_no, receipt_dt) and write onto another transaction's row); PK race → re-read and merge. Check-in fields write-once (silently ignored on later pushes), out fields fill-only, out_flag never reverts, different second check-out → `DUPLICATE_CHECKOUT`; update only when something changes (idempotent, `updated_at` untouched on re-push). Aadhaar-type proofs (`mask_flag=Y`) stored as `XXXXXXXX` + last 4.
  - Receipts: insert once; re-push of the same bill (bill_no, bill_dt, net_amt) = ACCEPTED no-op; recomputed with `checkBill` (rate by luggage `receipt_dt`, GST by `bill_dt`, `GST_TYPE` of the billing user incl. per-user override); mismatch keeps the bill. Counters: R from `receipt_no`/`receipt_dt` of `user_id_in`, B from `bill_no`/`bill_dt` of `user_id`, max per (user, series, period) bumped once per chunk.
  - Chunks of 500 rows per transaction (rows + exceptions + counter bumps commit together); deadlocks retried by `withTransaction`. Measured locally: 500 rows ≈ 0.95 s.
  - Pull: cursor = DB `UTC_TIMESTAMP()` taken before reading; rows read with a **120 s overlap** behind `since` (client dedupes by PK). First pull = live masters + open check-ins (`out_flag N`); later pulls = rows with `updated_at ≥ since − 120 s`, soft-deleted included. Settings always full map; counters for the caller.
  - `GET /luggage?search=&status=in|out` (contains-match on pnr, mobile, name, receipt_no; paginated, newest first) and `GET /luggage/:trans_id` (+ `receipt`), A/O only.
- **Phase 7 (2026-11-02):** `modules/reports` (schema, `reports.gate.ts`, `reports.service.ts`, routes) mounted as `/reports` → auth → `limiters.reports` (30/min) → `reportGate`; `modules/app-version` (`GET /app/version` public, `POST /app/version` S) + `downloads.ts` (`/downloads`, `DOWNLOADS_DIR`, `*.apk` only, `Cache-Control: public, max-age=86400`, ETag). Time helpers `istDayStartUtc`, `addDays`, `daysBetween`.
  - Reports are hand-written SQL via `raw()` and always bound to exactly one customer (token's; **S must pass `customer_id`**, else 400). `from`/`to` = IST dates, inclusive, ≤ 366 days; DATETIME columns use `[from 00:00 IST, to+1 00:00 IST)` in UTC; `bill_dt`/`receipt_dt` compared directly. Rows include `*_ist` timestamps. Response `meta = { page, limit, total, totals, filters }`.
  - Reports: collection-summary (per bill_dt: bills, bags, base, penal, net, cash, upi), check-in-register (date_time_in), check-out-register (date_time_out + bill), bags-in-store (open bags, `minutes_stored`, optional check-in window), gst (per bill_dt × gst_pct), user-shift-collection, unprinted-bills (`bill_printed='N'`), exceptions (filters resolved / table / created date); `PATCH /reports/exceptions/:id/resolve` (A/S, idempotent).
  - Gate: S always; A always; O only if `REPORT_FLAG='Y'`. If `REPORT_PWD_FLAG='Y'` customer users must send `X-Report-Password` (argon2 verify of `REPORT_PWD`) → else 403 `REPORT_PASSWORD_REQUIRED`. Settings = customer-wide overridden by the user's own. `TOTAL_COLLECTION_FLAG` is not applied server-side yet (open).
  - App version: `?current=<versionCode>` → `update_available`, `mandatory` (= any newer live release has `mandatory_flag='Y'`). `apk_url` must be `https://…` or `/downloads/<file>.apk`. Login does not enforce versions (devices call this endpoint).
- **Phase 8 (2026-11-02):** hardening, load test, CI.
  - **All DB sessions run at READ COMMITTED** (set with the time zone on every pool connection). The first load test under REPEATABLE READ deadlocked massively: push's `SELECT … FOR UPDATE` on new trans_ids took gap locks. `withTransaction` retries deadlocks 3× with 10–50 ms × attempt random backoff.
  - **Pull is paginated**: ≤ `limit` (default/max 1000) luggage rows per response, keyset on `(updated_at, trans_id)`; response adds `has_more`, `next_cursor` (opaque base64url JSON `{m: open|changes, s: server_time, u, t}`); continue with `?cursor=` only; continuation pages return luggage only (`masters/settings/counters: null`) and repeat the first page's `server_time` (the `since` to store when `has_more` is false). Luggage page query is raw SQL bound to the token's customer.
  - Logging: request log = id/method/**path only** (query strings carry PII); `REDACT_PATHS` (exported from `core/logger.ts`) also covers `err.sql`, `err.parameters`, `x-report-password`, token/refresh hashes. API responses send `Cache-Control: no-store`. Boot warns when clustered without `REDIS_URL` (per-worker rate limits).
  - `RATE_LIMIT_MULTIPLIER` env (default 1, must be 1 in production) — load testing only.
  - Tests: `tests/integration/security.test.ts` — route audit via `express-list-endpoints` (every non-public `/api/v1` route → 401 anonymous; public list: health, ready, login, refresh, GET app/version), alg-none / HS512 tokens rejected, security headers, log redaction, production error responses without details. Coverage thresholds: overall ≥ 80 %, core ≥ 90 %, domain 100 % (currently ~97.5 % lines).
  - `npm run loadtest` (`scripts/loadtest.ts`): disposable `<db>_load` DB, 200 users over 20 customers, built server as cluster (≤ 6 workers so workers × pool < max_connections 151), autocannon login / quiet pull / push (10 bags) / 1000-row pull, per-scenario InnoDB row-lock waits, EXPLAIN of hot queries → `docs/load-test-results.md`. Latest local run (12-core laptop, everything on one box): login ≈ 60 req/s (per-customer seat lock + argon2), quiet pull ≈ 290 req/s, push ≈ 150 req/s ≈ 1.5k bags/s, 1000-row pull ≈ 100 req/s; 0 errors; all hot queries use the intended indexes. Parallelising the masters loads was tried and reverted (no gain under saturation).
  - CI: `.github/workflows/ci.yml` (MySQL 8.4 service; banned-DELETE check, lint, prettier, typecheck, test:coverage, build, fresh-DB migrate+seed twice).
- **Phase 9 (2026-11-02):** docs & deploy.
  - OpenAPI 3.0.3 built in `src/docs/openapi.ts` with `@asteasolutions/zod-to-openapi` from the **same zod schemas the routes validate with** (crudFactory exposes `crudRequestSchemas(config)`; every master module exports its `*Crud` config). Response shapes in `src/docs/openapi.schemas.ts` (named with `.openapi('Name')` → `$ref` components; `extendZodWithOpenApi` is called at the top of that file and must stay before the schemas). Contract prose (auth flow, sync push/pull, billing arithmetic, numbering, rate limits) in `openapi.description.ts`. `ERROR_CODES` array in `ApiError.ts` feeds the error envelope enum.
  - Swagger UI at `/api/docs`, spec at `/api/docs/openapi.json` (`API_DOCS_ENABLED`, default true; relaxed CSP only on that path). `npm run openapi` writes `docs/openapi.json` + `docs/postman_collection.json` (generated: folders per tag, bearer `{{accessToken}}` set by the Login request's test script).
  - **When you add/change a route:** register it in `src/docs/openapi.ts` and run `npm run openapi` — `tests/integration/openapi.test.ts` fails if a mounted `/api/v1` route is undocumented (or vice versa) or `docs/openapi.json` is stale.
  - `docs/FRONTEND_API_GUIDE.md` — integration guide for the frontend developer's Claude Code (every endpoint, payload rules, real request/response samples, status-code meanings, auth/sync/billing rules, screen → endpoint map). Generated by `npm run docs:frontend` (`tests/docs/frontend-guide.gen.ts` runs a full scenario against the test DB via `vitest.docs.config.mts`); regenerate it with `npm run openapi` whenever the API changes.
  - README.md (setup, scripts, env table, mermaid architecture, ground rules, sync contract, testing/CI, load test, deployment steps, open points). Deploy kit: `Dockerfile` (multi-stage; `--target migrator` runs migrate + seed with tsx; runtime non-root with `/api/v1/ready` healthcheck), `.dockerignore`, `deploy/docker-compose.example.yml` (API + Redis, json-file log rotation, managed MySQL), `deploy/ecosystem.config.cjs` (PM2 cluster, `CLUSTER_WORKERS=1`), `deploy/nginx.conf` (TLS, 6 MB bodies, keep-alive < app 65 s, X-Request-Id), `deploy/backup-mysql.sh` (daily `mysqldump --single-transaction`, 14-day retention).
- **Phase 10 (2026-11-03):** frontend requests A1, A5, A6, A8, B. Migration `006` (guarded ALTERs): `md_user.must_change_pwd`, `td_luggage_in_out.device_id_in / id_proof_id / id_number` NULL-able, `td_receipt.gst_no`.
  - **A1** `POST /auth/change-password {current_password, new_password}` (same password rules as users; must differ; wrong current → 400 `VALIDATION_ERROR` on `current_password`, not 401; own limiter `limiters.password` 10/min per user; session stays valid). `must_change_pwd = 'Y'` on user create and admin reset-password. `sessionGuard` returns 403 `PASSWORD_CHANGE_REQUIRED` for an **A** session with the flag unless the route uses `authenticate({ allowPasswordChange: true })` (change-password, logout, me). F sessions are not held back (old device can still push after a reset). The flag is read via the cached session row (`sessionStore` selects `u.must_change_pwd`); change-password invalidates the cache. Login/me `user` include `must_change_pwd`.
  - **A5** `masters.users` (login + pull): `id, customer_id, user_code, user_name, user_type, allow_flag, is_deleted, updated_at` of the tenant, **deleted users always included** (old rows reference them). `MasterSpec` tuples in `masters.service.ts` take optional columns + withDeleted.
  - **A6** `td_receipt.gst_no` = `md_customer.gst_no` loaded once per push (`refs.customerGstNo`), set on insert; device value is never used (schema strips it).
  - **A8** QR check-out before the check-in synced: luggage rows may omit `device_id_in` (only when `out_flag = 'Y'`), `shift_id_in`, `id_proof_id` + `id_number` (pair: both or neither, on any row — `ID_PROOF_REQUIRED` is device-side). `checkinFillPatch` fills exactly those four check-in fields once while NULL; all other check-in fields stay write-once. QR payload (frontend, v1): `{v,t,r,d,c,u,i,b,n,m,p}` = version, trans_id, receipt_no, receipt_dt, customer_id, user_id_in, date_time_in, luggage_qty, pasn_name, pasn_mob_no, pnr_no.
  - **B** Login body `version_code` (int, optional): newer live `mandatory_flag = 'Y'` release → 426 `UPDATE_REQUIRED` `{current, latest}` (checked before the password). Omitted (web) → no check.
  - New error codes `PASSWORD_CHANGE_REQUIRED` (403), `UPDATE_REQUIRED` (426). Guide scenario now changes passwords after first logins and shows the QR check-out flow.

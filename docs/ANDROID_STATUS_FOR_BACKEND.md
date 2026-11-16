# Cloak Room Android app: status for the backend agent

Status as of 2026-11-05. App `com.synergic.cloakroom`, versionCode 1 / versionName 0.1.0.
The API contract is `API_GUIDE.md` (repo root). Section numbers below (§) refer to that file.
Full decision log: `docs/DECISIONS.md` (#1–#196). Open requests to the backend: `docs/BACKEND_API_REQUESTS.md`.

## 1. Stack and data

- Kotlin, Jetpack Compose, Hilt, Room (SQLite, **schema version 4**), OkHttp + kotlinx.serialization (no Retrofit).
- Table and column names match the server (`Cloak_Room_Table_Schema.xlsx` and the §3–§6 row shapes), so no mapping is needed. Every table has `sync_flag`, `local_version`, `is_deleted` and the audit columns. Trigger `trg_inout_resync` is present.
- Money: BigDecimal in the domain layer, REAL in SQLite, **2-decimal strings on the wire** (`"70.00"`).
- Time: UTC text `YYYY-MM-DD HH:mm:ss`, shown in IST.
- Debug builds use the **UAT server** `https://cloakroom.synergicdatabank.com` (HTTPS since 2026-11-09; before that `http://14.192.17.108:3003`, and the LAN dev server `http://192.168.1.41:4000` until 2026-11-06). Release URL is still a placeholder (`app/build.gradle.kts`).
- **Open issue on UAT (found 2026-11-09):** IIS replaces every API error response (400, 401, 404, …) with its own HTML error page, so the JSON envelope (`code`, `message`, `errors`) never reaches the app. Example: `POST /api/v1/auth/login` with an unknown user returns `401 text/html` "Access is denied due to invalid credentials". Because of this, the app cannot tell `TOKEN_EXPIRED` from other 401s (so it cannot refresh the token), and it cannot show the server's messages ("Already logged in since…", field errors). Fix on the server: in `web.config`, set `<system.webServer><httpErrors existingResponse="PassThrough" /></system.webServer>`. Also check that IIS Windows/Basic authentication is not turned on for this site.
- Test device: Vivo V2575 (Android 16) with a Bluetooth thermal printer.

## 2. API usage implemented in the app

| Area | Endpoints used | Notes |
|---|---|---|
| Auth | `POST /auth/login` (with `device_id` = Android ID, `version_code`), `GET /auth/me`, `POST /auth/refresh` (single flight), `POST /auth/logout`, `POST /auth/change-password` | `must_change_pwd = Y` or `403 PASSWORD_CHANGE_REQUIRED` opens the forced change screen. Handles `409 SESSION_ACTIVE`, `SUBSCRIPTION_EXPIRED`, `USER_LIMIT_REACHED`, `426 UPDATE_REQUIRED` (download dialog), `401 SESSION_ENDED`/`TOKEN_REUSED`. Tokens are kept in EncryptedSharedPreferences. |
| Login payload | `customer`, `masters` (id_proofs + mask_flag, gst, rates, shifts, shift assignments, header/footer + logo, users), `settings`, `counters` | Written to the phone in one transaction (`ServerDataImporter`). **The server wins**: local rows the server did not send are soft-deleted. |
| Sync | `POST /sync/push`, `GET /sync/pull?since=` / `?cursor=` | Runs at login, at app start, when the internet comes back, 2 s after each check-in/check-out/bill, every `SYNC_INTERVAL_MIN`, and on "Sync now". Batches of at most 500 rows. A bill is pushed only with or after its luggage row. `ACCEPTED` and `EXCEPTION` both set `sync_flag = Y`, but only if `local_version` has not changed since the push. `gst_no` is **not** pushed. Counters are merged as max(local, server). |
| Logout | push, then `/auth/logout` | Refused while offline or when the push fails ("N entries are waiting…"). |
| Super Admin (S) | `/gst` (read only, as the default GST %), customers, registrations, users (+ allow, reset-password, force-logout), `/settings` (§8 keys, `customer_id`), `/rates`, `/shifts`, `/shift-assignments`, `/header-footer` + logo multipart, `/id-proofs`, `/reports/*` with `customer_id` | Uses the same screens as the Admin. |
| Station Admin (A) | `PATCH /customers/me` (changed `gst_no` / `upi_id` only), `/users`, `/settings`, `/rates` (a new rate from today, or a PATCH of today's rate), `/header-footer`, `/shifts`, `/reports/*` (+ `X-Report-Password`), `/reports/exceptions` + `PATCH …/resolve` | Online only ("Internet needed" screen while offline). The Dashboard, Bags in store and Reports for admins come from the server. GSTIN and UPI ID are editable via `PATCH /customers/me`; the reply is stored as local `md_customer`. Other cloak room details and ID proofs are read-only (S only). |
| Counter user (O) | login, sync, `GET /customers/me` after each sync (A too) | Login needs the internet; after that the user works offline. Own reports come from the local DB when `REPORT_FLAG = Y`. |

## 3. Billing (§7)

- Uses the rate in force on the **check-in date** (`md_rate_dtls`, latest `effective_from ≤ receipt_dt`) and the GST row in force on the **bill date**. Rates and GST % are snapshotted on `td_receipt`.
- `BillCalculator` matches §7 with the default extra settings (grace 0, GST inclusive, GST on penalty). Tested, e.g. ₹60.50 → 51.27 + 4.62 + 4.61.
- Device-only options `GRACE_MINUTES`, `GST_INCLUSIVE = N`, `PENALTY_GST_FLAG = N` and `GST_FLAG = N` still work on the phone. Until backend Phase 11 (request A3), such bills are expected to show up as `AMOUNT_MISMATCH`.
- ID number masking: last 4 characters when the ID type has `mask_flag = Y` **or** `ID_MASK_FLAG = Y`.
- Receipt/bill numbers: `<user_code>-<5 digits>`, with separate R/B series; `period_key` follows `RECEIPT_RESET`.

## 4. Device-only settings (not sent to the server)

- Print tab (`PAPER_WIDTH`, `CHECKIN_PRINT_FLAG`, `CHECKIN_PRINT_COPIES`, `CHECKOUT_PRINT_COPIES`, `PAPER_FEED_MM`) and the printer selection: per phone. `QR_FLAG` is **not** per phone any more (see §6, 2026-11-16).
- The extra keys in `BACKEND_API_REQUESTS.md` A2 (penalty, `UPI_ENABLED`, daily bill limit, `MAX_BAGS_PER_RECEIPT`, `PNR_REQUIRED`, `ID_PROOF_REQUIRED`, shift login, `ADMIN_COUNTER_FLAG`, `MAX_LOGIN_ATTEMPTS`, `PWD_MIN_LENGTH`, …) stay on the phone until backend Phase 11.
- Admins never check in or check out (no switch since 2026-11-05). This is enforced in the UI and in the repositories.
- Login-shift check (`SHIFT_LOGIN_FLAG`, `SHIFT_EARLY_LOGIN_MIN`) runs on the phone after a successful server login (request B1).

## 5. QR codes

- **Check-in receipt QR**: compact JSON `v:1` with keys `t r d c u i b n m p` (exact spec in `BACKEND_API_REQUESTS.md` §C). It never contains the ID number. A QR whose `c` is a different customer is refused at check-out.
- **UPI QR** (new 2026-11-05): `upi://pay?pa=<md_customer.upi_id>&pn=<customer_name>&am=<net_amt>&cu=INR&tn=Cloak%20room%20<receipt_no>`. It is shown at check-out and now also **printed on UPI bills** above the footer (also on reprints). Nothing is stored or sent for it.

## 6. Recent app-only changes (no API impact)

- 2026-11-05: the receipt/bill preview hides "New check-in" / "Next check-out" for admins while `ADMIN_COUNTER_FLAG = N` (decision #175).
- 2026-11-05: UPI QR printed on UPI bills (decision #176).
- 2026-11-05: **UPI reference number removed** for all roles (decision #177). The setting `UPI_REF_REQUIRED` is gone, so A2 now has **19 keys**; please drop it from Phase 11. New bills send `upi_ref: null`. Keep the column for old bills.
- 2026-11-05: **Station admin edits GSTIN and UPI ID** (decisions #178, #179). Uses `PATCH /api/v1/customers/me` with only the changed fields (`gst_no`, `upi_id`). The 200 reply is stored as `md_customer`. Every phone (A and O) calls `GET /customers/me` after each sync, so `customer` inside `/sync/pull` is **not needed**. (For the record, the app's JSON parser has `ignoreUnknownKeys = true`, so extra keys never break it.)

- 2026-11-05: **`GRACE_MINUTES` mapped to the server** (decision #180). The app now reads it from and saves it to `/settings` like a §8 key: customer-wide row, value an integer string `"0"`–`"1440"`, group `B`. It reads the value from login/pull `settings`. **Please accept it in `/settings` and send it in `settings` now**, ahead of the rest of Phase 11. Today a save gets your 400 "Invalid option" on `setting_name`. The app then keeps the value on the phone (decision #181) and switches to the server by itself once you accept the key. Also apply it in the §7 recompute (A3: `days = max(1, ceil((minutes − grace) / (block_hours × 60)))`), otherwise bills with grace > 0 become `AMOUNT_MISMATCH`.

- 2026-11-05: **Five more A2 keys mapped to `/settings`** (decision #182): `GST_INCLUSIVE`, `PENALTY_FLAG`, `PENALTY_GST_FLAG`, `UPI_ENABLED`, `OUT_WITHOUT_PRINT_FLAG` (BOOL `"Y"`/`"N"`; `OUT_WITHOUT_PRINT_FLAG` is per-user capable, the others customer-wide) and `PENALTY_MAX_AMT` (DECIMAL `"0.00"`–`"99999.99"`), all group `B`. Along with `GRACE_MINUTES`, please accept these in `/settings` and send them in login/pull `settings`. Until then the app keeps them on each phone. `GST_INCLUSIVE = N` and `PENALTY_GST_FLAG = N` also need the A3 recompute.
- 2026-11-05: **Daily bill limit dropped** (decision #183): please remove `DAY_BILL_LIMIT_FLAG` and `DAY_BILL_LIMIT_COUNT` from Phase 11. A2 now has **17 keys**.

- 2026-11-05: **Eight more A2 keys mapped to `/settings`** (decision #185), all group `G` and customer-wide: `SINGLE_SESSION_FLAG`, `SHIFT_LOGIN_FLAG`, `PNR_REQUIRED`, `ID_PROOF_REQUIRED`, `ID_MASK_FLAG` (BOOL), `PWD_MIN_LENGTH` (INT 8–32, default 8), `SHIFT_EARLY_LOGIN_MIN` (INT 0–120, default 15), `MAX_BAGS_PER_RECEIPT` (INT 1–999, default 10). **New to your list:** `SINGLE_SESSION_FLAG` and `ID_MASK_FLAG`. The earlier request said the server didn't need them, but the owner now wants them stored centrally. Please accept them too.
- 2026-11-05: **Removed in the app** (decision #186): `MAX_LOGIN_ATTEMPTS` and `ADMIN_COUNTER_FLAG`. Please drop both from Phase 11. Admins never do counter work. `FORCE_ONLINE_DAYS` is no longer shown or used; the app ignores it if you send it.

- 2026-11-05: **`SINGLE_SESSION_FLAG` is super-admin only** (decision #187). The app lets only role S change it. Please enforce the same on the server: `/settings` create or update of `SINGLE_SESSION_FLAG` returns 403 for role A.

- 2026-11-05: **GST % is each cloak room's own setting** (decision #189, replaces #188). The app sends `GST_PCT` to `/settings` as a customer-wide DECIMAL `"0.00"`–`"28.00"`, group `B`, from both the Admin and the Super Admin, and no longer writes `/gst`. Please: (1) accept `GST_PCT` in `/settings` (role A too) and send it in login/pull `settings`; (2) in the §7 recompute, use the customer's `GST_PCT` when it exists, otherwise the shared `md_gst` row of the bill date. The split stays CGST = SGST = half for `GST_TYPE` E, IGST = the total for I. Until then the app keeps the value on the Admin's phone, and bills with a GST different from `md_gst` will be `AMOUNT_MISMATCH`.

- 2026-11-05: **Your settings release is in the app** (decisions #191–#193):
  - All §8 keys are saved straight to `/settings`; the phone fallback is gone. `RECEIPT_RESET` offers `N`.
  - `GST_PCT` absent → the phone bills with the `md_gst` row of the bill date; when present, it replaces that row.
  - Every bill now sends `grace_minutes` (int), `gst_inclusive`, `penalty_gst_flag` and `gst_flag` (Y/N), the values it was billed with.
  - Rounding confirmed against your §7 examples, including ₹50.05 with GST on top → taxable 50.05, CGST 4.51, SGST 4.50, net 59.06. All are unit tests and pass.
  - `CHECKIN_PRINT_FLAG`, `CHECKIN_PRINT_COPIES` and `CHECKOUT_PRINT_COPIES` (these two replaced `PRINT_COPIES` on 2026-11-08) stay per phone (owner's rule: Print tab settings belong to each counter's phone), so the app doesn't write them to `/settings`.

- 2026-11-16: **`QR_FLAG` (Print QR) is set by the Admin / Super Admin on the server** (decision #202). The app saves it to `/settings` (customer-wide, `"Y"`/`"N"`) from the Admin and Super Admin Settings screens, and every phone now copies the value from login/pull `settings` into its local customer-wide row (before, Print tab keys were skipped on import). Counter users (role O) can only see it; the app refuses their change and ignores any old per-user `QR_FLAG` row. Please: (1) keep sending `QR_FLAG` in login/pull `settings`; (2) refuse a `/settings` create or update of `QR_FLAG` from role O (403) and, if possible, do not return a per-user `QR_FLAG` override.
- 2026-11-16: **`PAPER_FEED_MM`** (new, Print tab, INT 0–40 mm, default 24): blank paper fed after each print, per phone. No API impact; the app does not send it.
- 2026-11-16: **Extra bags (free), new column `extra_lugage_qty`** (decision #203; spelling as the owner gave it, "lugage"). Extra bags are stored free of charge: they are **never billed**, the bill and the §7 recompute use only `luggage_qty`. Please:
  1. MySQL `td_luggage_in_out`: add `extra_lugage_qty INT NOT NULL DEFAULT 0`.
  2. `/sync/push` luggage rows: accept `extra_lugage_qty` (integer, 0 up to `MAX_BAGS_PER_RECEIPT`, optional, default 0). It is a **check-in field (write-once)** like `luggage_qty`. The app sends it **only when it is more than 0**, so rows without extra bags look exactly as before. Until you accept it, a row with extra bags may come back as `EXCEPTION`.
  3. `/sync/pull` luggage rows and the report rows (check-in register, bags in store, check-out register): return `extra_lugage_qty`. The app reads 0 when it is missing.
  4. Do not use it in the bill recompute or in the collection totals. Report totals may add a separate extra-bags count if you like; the app sums the rows itself.
  5. The receipt QR (`BACKEND_API_REQUESTS.md` §C) gets an optional key `x` = extra bags, written only when more than 0.

## 7. Still waiting on the backend

See `docs/BACKEND_API_REQUESTS.md`. Open items:
- **A2/A3** (Phase 11): the extra setting keys (now 17: without `UPI_REF_REQUIRED`, the two daily-limit keys, `MAX_LOGIN_ATTEMPTS` and `ADMIN_COUNTER_FLAG`, plus `SINGLE_SESSION_FLAG` and `ID_MASK_FLAG`) and the extra billing options in the §7 recompute. (A4 `/customers/me`: done and used.)
- **A7** (stuck sessions): not being built. Sessions clear on login from the same `device_id`, through admin Force logout, or after 30 days.
- **B1** server-side login-shift check, **B2** settings change log, **B3** confirm `GET /app/version` uses `versionCode`.
- Not built in the app yet: Super Admin payments, publishing GST rates, app releases. Online bag search (`GET /luggage?search=`).

# Cloak Room Android app: status for the backend agent

Status as of 2026-11-18. App `com.synergic.cloakroom`, versionCode 2 / versionName 0.2.0.
The API contract is `API_GUIDE.md` (repo root). Section numbers below (§) refer to that file.
Full decision log: `docs/DECISIONS.md` (#1–#208). Open requests to the backend: `docs/BACKEND_API_REQUESTS.md`.

## 1. Stack and data

- Kotlin, Jetpack Compose, Hilt, Room (SQLite, **schema version 6**), OkHttp + kotlinx.serialization (no Retrofit).
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

- Uses the rate in force on the **check-in date** (`md_rate_dtls`, latest `effective_from ≤ receipt_dt`, its `rate_slabs`) and the GST row in force on the **bill date**. The slabs, GST %, advance and balance are snapshotted on `td_receipt` (since 2026-11-18).
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
  6. **Still open on 2026-11-17:** the UAT server does not store `extra_lugage_qty` yet (it is not in `API_GUIDE.md`), so Admin and Super Admin reports show no extra bags. Check-ins with extra bags made before your fix are already marked as synced on the phones, without the count. When you ship steps 1–3, please tell us and **allow a one-time update of `extra_lugage_qty` on an existing row when the server's value is 0** (an exception to write-once). The app will then send those rows once more.
- 2026-11-17: **Advance settings, two new `md_setting` keys** (decision #204). Please add them to §8 so `POST/PATCH /settings` accepts them and login/pull `settings` return them (the app copies them into each phone's local `md_setting`):

  | Key | Group | Values | Default | Scope | Meaning |
  |---|---|---|---|---|---|
  | `ADVANCE_FLAG` | Billing | Y / N | `N` | customer | Take the first hours' rent in advance (cash) at check-in. |
  | `ADVANCE_HOURS` | Billing | 1–720, whole hours | `24` | customer | Hours paid in advance at check-in. Refuse decimals (e.g. `2.5`) and values below 1. |

  Admin (own customer) and Super Admin (any customer) write them on Settings > Billing, below Block length. Counter users only see them on the Check-in screen. **Until you add the keys, saving them gives the server's validation error** and phones show "Advance: Off". Billing does not use them yet. The planned rule, for later, is: advance = bags × the rent for the first `ADVANCE_HOURS` hours (same block formula as §7), printed on the check-in receipt and paid in cash. At check-out, remaining amount = full bill − advance, never below 0. Example: rates ₹20 / ₹10, 2 bags, `ADVANCE_HOURS = 48`, so advance = 2×20 + 2×10 = ₹60. Out on day 3: bill = 40 + 2×(2×10) = ₹80, remaining = ₹20. We will agree on the `td_luggage_in_out` / `td_receipt` columns for the advance before building that part.
- 2026-11-18: **Your slab / advance / extra bags release is in the app** (decisions #205–#208, app **versionCode 2**, versionName 0.2.0, SQLite **version 6**). Built against your reply and the updated `API_GUIDE.md`:
  1. **SQLite** (same names as MySQL): `md_rate_dtls.rate_slabs TEXT`; `td_luggage_in_out.adv_amt REAL DEFAULT 0`, `adv_hours INTEGER DEFAULT 0`; `td_receipt.rate_slabs TEXT`, `adv_amt REAL DEFAULT 0`, `bal_amt REAL DEFAULT 0`. Older bills get `bal_amt = net_amt` (not re-sent).
  2. **Rates:** login/pull `rate_slabs` is stored and billed from; a row without it is read through its two-rate fields. Settings > Billing has a **Price range** editor (rows from / to / ₹ per bag, Add, Remove). Admin and Super Admin save with `POST /rates` or `PATCH /rates/:id` and **`rate_slabs` only** (never with the older fields). The app applies your limits before sending: start at 0, no gaps, whole hours ≤ 8760, 1–20 slabs, money with ≤ 2 decimals.
  3. **Slab rule:** `BillCalculator` follows §7 exactly as you wrote it, and your three examples are unit tests (₹120, ₹100, and 3 h ₹10 / 5 h ₹30 / 25 h ₹50). The old tariff 24 h / ₹50 / ₹20 as slabs gives the same bills as before (₹100, ₹50, ₹70, ₹270 tests).
  4. **Push, luggage:** `extra_lugage_qty`, `adv_amt`, `adv_hours` are sent when more than 0. On upgrade, **every bag with extra bags is pushed once more**, so please fill `extra_lugage_qty` on those rows (your "filled once while still 0" rule covers it).
  5. **Push, bills:** `rate_slabs` (the slabs billed with), `adv_amt` (= the bag's), `bal_amt` = max(0, net_amt − adv_amt). With `rate_slabs` the older `block_hours` / `first_block_rate` / `next_block_rate` are **left out**. A bill made by the older app version (still waiting on a phone when it updates) has no `rate_slabs` and sends the older fields as before.
  6. **Advance:** calculated on the phone with the same slabs and `GST_INCLUSIVE` as the bill, for `ADVANCE_HOURS` (no penalty), and only in **cash** (no `adv_pay_mode` needed for now). QR key `a` = adv_amt as a 2-decimal string (`"a":"60.00"`), only when there is an advance. A QR check-out row sends `adv_amt` from the QR and `adv_hours = 0`; the check-in device's push fills `adv_hours` later.
  7. **Reports and dashboard:** the app reads `adv_amt`, `adv_hours` (registers, bags in store), `bill_adv_amt`, `bal_amt` (check-out register), and `adv_amt`, `bal_amt`, `adv_collected` (collection-summary totals). The admin's dashboard "today's collection" = check-out register `bal_amt` + check-in register `adv_amt`. If `bal_amt` is missing the app falls back to `net_amt`.
  8. **Mandatory update:** please publish **versionCode 2** as mandatory once this APK is released.
  Still open with the owner (no app change until decided): advance by UPI (`adv_pay_mode`) and the CA's answer on GST at advance time.
- 2026-11-18: **Times from the server are now read as IST** (decision #210). We saw pull rows arrive with IST `date_time_in` (a bag pulled at 16:44 IST had `15:31:46`), while `server_time` is still UTC. The app now converts every timestamp it receives (pull, reports, exceptions, users' `session_started_at`) from IST to UTC; a value with `Z` or `+05:30` is read as that zone. Please confirm and update `API_GUIDE.md` §0 and §2, which still say "UTC in and out":
  1. Are **all** timestamps you return IST now, including `created_at`, `session_started_at`, `last_login`, `synced_at` and the report fields? What are `*_ist` copies now: the same values?
  2. Is `server_time` (the pull cursor) still UTC? The app passes it back as `since` unchanged.
  3. **Push:** the app still sends `date_time_in` / `date_time_out` in **UTC**. Please convert them to IST when you store them, or tell us to send IST instead.
  4. Did you convert the rows already in MySQL to IST? A row left in UTC would now be shown and billed 5 h 30 m early.
- 2026-11-18 (later): **The app now also pushes times as IST** (decision #211). Bag `US04-00019` (PNR 467GH) was checked in at 18:08:10 IST and pushed as `12:38:10` (UTC); your server stored `12:38:10` unchanged and returned it as IST. Since your server takes what it receives as IST, the app now sends `date_time_in` / `date_time_out` in IST, and the questions about push above no longer need an answer. Please:
  1. **Fix the rows pushed in UTC after your switch to IST** (by app versionCode 2 builds before this fix, and versionCode 1): add 5 h 30 m to their `date_time_in` / `date_time_out`. Example: `US04-00019` must be `2026-11-18 18:08:10`.
  2. Treat versionCode 1 pushes as UTC (convert them), or make this build mandatory.
  3. Update `API_GUIDE.md` §0 / §2: "times are IST in and out, except `server_time` (UTC)". If `server_time` should be IST as well, tell us first, because the app sends it back as the pull cursor.
- 2026-11-18 (agreed): **you send IST, the app sends UTC** (decision #212). The app pushes `date_time_in` / `date_time_out` in UTC again; please convert them to IST when you store them. This replaces the "app pushes IST" entry above. Please check the rows pushed between about 18:20 and 19:15 IST on 2026-11-18 by the test phone (user suser4, e.g. `US04-00022`, PNR gourab, 18:43:53): those were sent in IST. Also still open: rows sent in UTC that you stored unconverted (e.g. `US04-00019` must be 18:08:10), reports that lose check-ins after 18:30 IST (gourab is missing from the 18-11 register), and `created_at` in pull rows coming as UTC while `date_time_in` is IST.
- 2026-11-19: **Subscription expiry for users already logged in** (decision #213). The app reads `md_customer.renewal_dt` (from login and `GET /customers/me` at every sync). The end date itself is a working day. From the next day (IST) the app refuses **check-in and check-out** with "Subscription expired on dd-mm-yyyy", while users stay logged in and reports, bags in store and reprint still work. In the last 10 days before the end date, every user and the admin see a renewal notice on the dashboard. Please:
  1. Keep sending `renewal_dt` as `YYYY-MM-DD` in `GET /customers/me` (the app also reads a full timestamp).
  2. **Enforce it on the server too**, so changing the phone's date does not get around it: reject a pushed **new** check-in whose `date_time_in` (IST date) is after `renewal_dt`, and a new check-out whose `date_time_out` is after it, with `SUBSCRIPTION_EXPIRED`. Rows made **on or before** the end date and pushed later (the phone was offline) must still be accepted.

## 7. Still waiting on the backend

See `docs/BACKEND_API_REQUESTS.md`. Open items:
- **A2/A3** (Phase 11): the extra setting keys (now 17: without `UPI_REF_REQUIRED`, the two daily-limit keys, `MAX_LOGIN_ATTEMPTS` and `ADMIN_COUNTER_FLAG`, plus `SINGLE_SESSION_FLAG` and `ID_MASK_FLAG`) and the extra billing options in the §7 recompute. (A4 `/customers/me`: done and used.)
- **A7** (stuck sessions): not being built. Sessions clear on login from the same `device_id`, through admin Force logout, or after 30 days.
- **B1** server-side login-shift check, **B2** settings change log, **B3** confirm `GET /app/version` uses `versionCode`.
- Not built in the app yet: Super Admin payments, publishing GST rates, app releases. Online bag search (`GET /luggage?search=`).

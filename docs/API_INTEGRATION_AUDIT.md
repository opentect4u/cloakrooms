# API integration — Step 1 audit and plan

Source of truth: `API_GUIDE.md` (section numbers below refer to it). Status: **audit only, no code changed.**
Date: 2026-11-03.

## 1. Screen / feature → today → API → changes

| Screen / feature | What it uses now (file → function) | Replaced by (API_GUIDE) | Required changes |
|---|---|---|---|
| App start / update check | nothing | `GET /app/version?current=<versionCode>` (§4) | New splash check; block the app when `mandatory`, open `apk_url`. |
| Seed data (customer, users, settings, ID proofs, header/footer, shifts) | `data/seed/DemoSeeder.kt` → `seedIfEmpty`, `addMissingSettings`; `data/seed/SeedData.kt` | Login payload `customer`, `masters`, `settings`, `counters` (§3) + `GET /sync/pull` (§6.3) | Stop seeding business data. Keep only the **screen metadata** of settings (label, data type, options, limits) locally. Remove "Reset demo data", or make it "Clear local data". |
| Tenant | `data/Tenant.kt` → `CUSTOMER_ID = 1L` | `user.customer_id` from login | Read customer_id from the session everywhere. |
| Login | `data/repository/AuthRepositoryImpl.kt` → `login` (local PBKDF2 check, failed attempts, block, single session on `md_user.device_id`) | `POST /auth/login` (§3) with `device_id` = Android ID, `app_version` | Server decides password, block, session, subscription, user limit. Map `401 UNAUTHORIZED`, `403 FORBIDDEN`/`SUBSCRIPTION_EXPIRED`, `409 SESSION_ACTIVE` (show `session_started_at`), `409 USER_LIMIT_REACHED`, `429`. Store tokens in EncryptedSharedPreferences. |
| Session restore after restart | `data/session/SessionManager.kt`, `SharedPrefsSessionStore` | tokens + `GET /auth/me` when online (§3) | Keep the user logged in offline with stored tokens; refresh with `POST /auth/refresh` (single flight). |
| Change password / forced change at first login | `AuthRepositoryImpl.changePassword`, `md_user.must_change_pwd` | **No endpoint** | **Gap G1.** |
| Logout | `AuthRepositoryImpl.logout` (clears `md_user.device_id`) | push pending → `POST /auth/logout` (§3) | Push unsynced rows first. Handle `401 SESSION_ENDED` (push, logout, show login). |
| Users (admin) | `AuthRepositoryImpl` → `createUser`, `updateUser`, `setBlocked`, `resetPassword`, `forceLogout`, `observeUsers` | `/users`, `/users/:id`, `/users/:id/allow`, `/reset-password`, `/force-logout` (§5) | Online-only screen with paging/search. Server password rule: 8–128, a letter and a digit. Handle `409 CONFLICT` (user_code) and `USER_LIMIT_REACHED`. |
| Login shift (td_assign_shift) | `AuthRepositoryImpl.setLoginShift`; check at login (`SHIFT_LOGIN_FLAG`, `SHIFT_EARLY_LOGIN_MIN`) | `/shift-assignments` (§5); rows also in login/pull | Admin assigns through the API. **Gap G6:** the server does not enforce login only during the shift. |
| Settings screen | `SettingsRepositoryImpl.updateSettings` / `updatePrintSettings` (local md_setting, 36 keys) | `/settings` list/create/patch (§5); merged values in login/pull (§8) | The server accepts **only the 12 keys in §8**. **Gap G2.** Writes go online via PATCH (customer row) or POST with `user_id` (user override). |
| Rates | `md_setting` `FIRST_BLOCK_RATE`, `NEXT_BLOCK_RATE`, `BLOCK_HOURS` → `BillCalculator` | `md_rate_dtls` rows (`block_hours`, `first_block_rate`, `next_block_rate`, `effective_from`) from login/pull; admin `/rates` (§5) | Rebuild the local rate table (shape differs, see §2). Pick the rate with `effective_from ≤ receipt_dt`. Add a Rates admin screen. |
| GST | `md_setting` `GST_FLAG`, `GST_PCT`, `GST_TYPE`, `GST_INCLUSIVE` | `md_gst` (`cgst_pct`, `sgst_pct`, `igst_pct`, `effective_from`) + `GST_TYPE`, `GST_FLAG` (§7, §8) | `gst_pct` = cgst+sgst (E) or igst (I) from the GST row with `effective_from ≤ bill_dt`. Always GST-inclusive. |
| Bill calculation | `domain/billing/BillCalculator.kt` (BigDecimal; grace minutes; inclusive/exclusive; penalty with or without GST) | §7 formula, integer paise, half-up | Rewrite to §7 exactly. Server rules: no grace, always inclusive, GST on base + penalty. Otherwise the server logs `AMOUNT_MISMATCH`. Tests: all §7 examples, including ₹60.50 → 51.27 / 4.62 / 4.61. |
| Receipt/bill numbers | `domain/numbering/NumberGenerator.kt`, `data/numbering/UserCounterSequence.kt` (local `md_user_counter`) | `counters` from login/pull/push; next = max(local, server) + 1 (§7) | Merge server counters into `md_user_counter`. `RECEIPT_RESET` on the server has only D/M/Y (we also have `N: Never`). |
| ID proofs + masking | `md_id_proof` (no mask column); `ID_MASK_FLAG` setting → `CheckinValidator` | `masters.id_proofs[].mask_flag` (§6.1, §5); create/edit is super-admin only | Add `mask_flag`; mask per ID type (`XXXXXXXX1234`). Masters → ID proofs becomes **read-only** for station admins. |
| Header / footer / logo | `MasterRepositoryImpl` → `saveLine`, `moveLine` (swaps content), `removeLine`; `LogoProcessor` | `/header-footer` (+ `line_order`), `POST /header-footer/logo` multipart ≤ 200 KB (§5) | Add `line_order`; online writes; logo upload as PNG ≤ 200 KB. |
| Shifts | `MasterRepositoryImpl` → `saveShift`, `removeShift` (local, "HH:MM") | `/shifts` (§5), times like `"06:00:00"` | Online writes; convert HH:MM ↔ HH:MM:SS. |
| Cloak room details (md_customer) | `MasterRepositoryImpl.updateCustomer` (admin edits name/address/PIN/GSTIN/UPI) | `PATCH /customers/:id` is **super-admin only** | **Gap G4:** the screen becomes read-only for station admins. Add `state`, `active_flag`. |
| Check-in | `LuggageRepositoryImpl.checkin` (local insert, UUID trans_id, receipt no) | Local insert as today, then push (§6.2, §10) | Keep offline. Use the server user id (`md_user.id` from login) for `user_id_in`; server shift ids. |
| Check-out / QR scan / search | `CheckoutViewModel`, `BillRepositoryImpl` → `checkout`, `searchBags`; Case 1 builds the bag from the QR | Local row by `trans_id`; online fallback `GET /luggage/:trans_id`, `GET /luggage?search=` (§6.4) | Add the online fallback before the QR fallback. Bill per the new §7 calculator. |
| Bags in store, dashboard counts | local DB (`LuggageDao.observeInStore`, `DashboardViewModel`) | local DB, kept fresh by pull (all open bags of the customer) | No API call. Pull keeps other counters' bags. Other users' names are not in pull (**gap G7**). |
| Reports | `ReportRepositoryImpl` (local queries, own vs all users, REPORT_PWD hash check) | `/reports/*` (§9) with `X-Report-Password`; `REPORT_PWD_FLAG` | **Gap G5:** online server reports versus offline local reports. Exceptions report + resolve are new (admin). |
| Sync | none (offline phase); `LuggageDao.markSynced` exists | `POST /sync/push`, `GET /sync/pull` (§6) | New sync engine plus WorkManager every `SYNC_INTERVAL_MIN` (≥ 15). Store last `server_time`. Batches ≤ 500. Mark `Y` for ACCEPTED and EXCEPTION matched by trans_id + local_version. |
| Printing, printer setup | `data/print/*`, `PRINTER_TYPE`, `BT_PRINTER`, `PRINT_COPIES`, `CHECKIN_PRINT_FLAG` | `PAPER_WIDTH`, `QR_FLAG` only (§8) | Device-only settings (**gap G2**). |

## 2. Model mismatches (local ↔ API)

| Table / model | Local now | API | Action |
|---|---|---|---|
| `td_receipt` | `hours_charged` | `days_charged` | Rename (migration v3). |
| `td_receipt` | `gst_no` (local addition, #108) | — | Keep local only; never pushed. A bill pulled from the server prints the customer's current GSTIN. |
| `md_rate_dtls` | `from_hour`, `to_hour`, `rate` (old sheet) | `block_hours`, `first_block_rate`, `next_block_rate`, `effective_from` | Re-create the table. |
| `md_gst` | no `effective_from` | `effective_from` | Add column. |
| `md_id_proof` | no `mask_flag` | `mask_flag` Y/N | Add column. |
| `md_header_footer` | no `line_order` | `line_order` 0–999 | Add column; order by it. |
| `md_customer` | no `state`, `active_flag` | both | Add columns. |
| `md_shift` | `"06:00"` | `"06:00:00"` | Convert at the API edge. |
| `md_user` | local `password_hash`, `must_change_pwd`, `failed_attempts` | password never sent to devices | Stop using them (drop in a later migration). |
| `md_setting` | 36 keys + screen metadata columns | 12 keys (§8), no metadata | Server values for §8 keys; local metadata kept; device-only keys stay local (G2). |
| Money | `BigDecimal` → REAL | decimal **strings**, compute in paise | Parse to paise (Long) at the edge; send `"70.00"`. |
| Times | UTC text, IST display | the same | No change. Reports also send `*_ist`. |
| User ids in rows | local `md_user.id` (seeded 1, 2) | server `md_user.id` (e.g. 3) | Use the server id from login. Local seeded ids are dropped. |
| `RECEIPT_RESET` | D/M/Y/**N** | D/M/Y | Drop `N`. |
| New key | — | `FORCE_ONLINE_DAYS` | Add: block the app after N days offline. |

## 3. Gaps — need your decision (or the backend team's)

- **G1 Own password.** No endpoint to change your own password, and no "must change at first login" flag. Options: (a) remove Change password and the forced first change (admins use reset-password); (b) the backend adds an endpoint.
- **G2 Local-only settings.** The server keeps only the 12 keys in §8. The app also has: penalty (`PENALTY_FLAG`, `PENALTY_MAX_AMT`, `PENALTY_GST_FLAG`), `GRACE_MINUTES`, `GST_INCLUSIVE`, daily bill limit, UPI switches, `OUT_WITHOUT_PRINT_FLAG`, `PNR_REQUIRED`, `ID_PROOF_REQUIRED`, `MAX_BAGS_PER_RECEIPT`, `SINGLE_SESSION_FLAG`, `MAX_LOGIN_ATTEMPTS`, `PWD_MIN_LENGTH`, shift login, and the print/printer keys. Proposal: rates and GST come from the server masters. `GRACE_MINUTES`, `GST_INCLUSIVE = N` and `PENALTY_GST_FLAG = N` are **removed**, because the server would flag every such bill as `AMOUNT_MISMATCH`. The rest stay as **device-only** md_setting rows (never pushed), editable on the device by admins — or the backend adds them to §8.
- **G3 GST_FLAG = N.** §8 says it only means "print the GST breakup"; §7 always splits GST from the inclusive total. Should the bill still store taxable/CGST/SGST when GST_FLAG = N (and just not print them)? Today the app sets them to 0, which the server may flag.
- **G4 Read-only masters for station admins.** ID proof types and cloak room details are super-admin only on the server. These tabs become read-only.
- **G5 Reports.** Proposal: online → server `/reports/*` (complete, all devices); offline → today's local reports with a note "Offline: only this device's data".
- **G6 Login shift.** The server does not limit login to the assigned shift. Keep the local check after a successful server login, or drop it?
- **G7 Other users' names.** Pull has no user list, so bags from other counters show only the user id. Operators cannot call `/users`. Show the user code from `receipt_no` instead, or the backend adds users to pull.
- **G8 Offline login.** Login needs the server. Once logged in, the app works offline with stored tokens until logout. A fresh login without internet is not possible. Confirm this is fine.
- **G9 Customer state for IGST.** Not needed if `GST_TYPE` stays a setting; noted only.

## 4. Proposed implementation order (stop after each)

| Step | Content |
|---|---|
| a | Networking: Retrofit + OkHttp + kotlinx.serialization, base URL in BuildConfig (debug/release), envelope parser, error-code mapping (§2), auth header, single-flight refresh, `X-Request-Id`, timeouts, no token logging. Unit tests for envelope/error mapping. |
| b | Auth: version check, login (tokens in EncryptedSharedPreferences + offline payload), me, refresh, logout with push first, SESSION_ACTIVE / SUBSCRIPTION_EXPIRED / SESSION_ENDED screens. Decide G1, G8. |
| c | Database v3 migration: the table changes in §2, keeping existing local rows. |
| d | Masters and settings from login + pull. Settings screen = §8 keys from the server + device-only keys (G2). |
| e | Check-in/check-out/billing per §7 (paise calculator, §7 examples as tests), counters merge, per-ID masking, online luggage lookup. |
| f | Sync engine + WorkManager schedule; push on logout and on SESSION_ENDED. |
| g | Admin: users, rates (new), shifts, shift assignments, header/footer + logo, settings, server reports with report password, exceptions + resolve. |
| h | Remove demo seeding, local password code and other dead code; update CLAUDE.md (offline-only phase ends); final checklist. |

## 5. Needed before step a

- Dev backend URL (the prompt has the placeholder `BACKEND_HOST`). The phone and the backend must be on the same network, or the URL must be public. Plain `http://` needs a debug-only cleartext exception.
- Test accounts: one station admin and one counter operator (user_id + password).

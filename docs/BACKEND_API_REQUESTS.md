# API requests for the backend — Cloak Room Android app

Status 2026-11-03. Everything below is missing from `API_GUIDE.md`. Each item says what the app needs and why. When an item is added, please also add it to `API_GUIDE.md` (with a real sample).

## A. Needed to finish the app

### A1. Change own password + first-login change
- `POST /api/v1/auth/change-password` — any logged-in user. Body `{ "current_password", "new_password" }` (same password rule as users: 8–128, a letter and a digit). `200` on success; `400 VALIDATION_ERROR` / `401` wrong current password.
- `must_change_pwd` (`Y`/`N`) on `user` in `POST /auth/login` and `GET /auth/me`; set to `Y` by `POST /users` and `POST /users/:id/reset-password`, back to `N` after a change.
- Why: users (and the super admin) cannot change their own password; the app's first-login password change has nothing to call.

### A2. Settings keys to add to §8
The app has these settings on the device only; they do not reach other devices and the super admin cannot set them. Please accept them in `/settings`, validated like the §8 keys, and send them in login / pull `settings` (merged with the user's override, as today).

Values travel as strings like the §8 keys: `BOOL` = `"Y"` / `"N"`, `INT` = whole number, `DECIMAL` = money with 2 decimals (`"500.00"`). Group = `setting_type`. Scope "Per user" = a per-user override row (`user_id` set) is useful; "Customer" = only the customer-wide row is used (the app ignores a user override of these).

| # | Key | Group | Type | Allowed values / range | Default | Scope | Meaning |
|---|---|---|---|---|---|---|---|
| 1 | `PENALTY_FLAG` **(app already uses /settings for it, 2026-11-05: please ship first)** | B | BOOL | Y / N | Y | Customer | Operator may add a penalty at check-out |
| 2 | `PENALTY_MAX_AMT` **(app already uses /settings for it, 2026-11-05: please ship first)** | B | DECIMAL | 0.00 – 99999.99 | 500.00 | Customer | Highest penalty the operator may type |
| 3 | `PENALTY_GST_FLAG` **(app already uses /settings for it, 2026-11-05: please ship first)** | B | BOOL | Y / N | Y | Customer | GST also on the penalty (needs §7, see A3) |
| 4 | `GRACE_MINUTES` **(app already uses /settings for it, 2026-11-05: please ship first)** | B | INT | 0 – 1440 | 0 | Customer | Extra minutes before the next block starts (needs §7, see A3) |
| 5 | `GST_INCLUSIVE` **(app already uses /settings for it, 2026-11-05: please ship first)** | B | BOOL | Y / N | Y | Customer | Rates include GST (Y) or GST added on top (N) (needs §7, see A3) |
| 6 | ~~`DAY_BILL_LIMIT_FLAG`~~ | — | — | — | — | — | **Dropped 2026-11-05**: bills per day are unlimited |
| 7 | ~~`DAY_BILL_LIMIT_COUNT`~~ | — | — | — | — | — | **Dropped 2026-11-05**: bills per day are unlimited |
| 8 | `UPI_ENABLED` **(app already uses /settings for it, 2026-11-05: please ship first)** | B | BOOL | Y / N | Y | Customer | Show the UPI option and UPI QR |
| 9 | ~~`UPI_REF_REQUIRED`~~ | — | — | — | — | — | **Dropped 2026-11-05**: the app no longer asks for a UPI reference |
| 10 | `OUT_WITHOUT_PRINT_FLAG` **(app already uses /settings for it, 2026-11-05: please ship first)** | B | BOOL | Y / N | Y | Per user | Allow "Out Without Bill Print" |
| 11 | `MAX_BAGS_PER_RECEIPT` **(app already uses /settings for it, 2026-11-05: please ship first)** | G | INT | 1 – 999 | 10 | Customer | Max bags in one check-in |
| 12 | `PNR_REQUIRED` **(app already uses /settings for it, 2026-11-05: please ship first)** | G | BOOL | Y / N | Y | Customer | PNR mandatory at check-in |
| 13 | `ID_PROOF_REQUIRED` **(app already uses /settings for it, 2026-11-05: please ship first)** | G | BOOL | Y / N | Y | Customer | ID proof mandatory at check-in |
| 14 | `SHIFT_LOGIN_FLAG` **(app already uses /settings for it, 2026-11-05: please ship first)** | G | BOOL | Y / N | Y | Customer | Counter users with a shift may log in only during it (see B1) |
| 15 | `SHIFT_EARLY_LOGIN_MIN` **(app already uses /settings for it, 2026-11-05: please ship first)** | G | INT | 0 – 120 | 15 | Customer | Minutes before the shift when login opens |
| 16 | ~~`ADMIN_COUNTER_FLAG`~~ | — | — | — | — | — | **Dropped 2026-11-05** |
| 17 | ~~`MAX_LOGIN_ATTEMPTS`~~ | — | — | — | — | — | **Dropped 2026-11-05** |
| 18 | `PWD_MIN_LENGTH` **(app already uses /settings for it, 2026-11-05: please ship first)** | G | INT | 8 – 32 (server password rule is ≥ 8) | 8 | Customer | Shortest password allowed |
| 19 | `CHECKIN_PRINT_FLAG` | P | BOOL | Y / N | Y | Per user | Print a receipt at check-in |
| 20 | `PRINT_COPIES` | P | INT | 1 – 3 | 1 | Per user | Copies per print |
| — | `RECEIPT_RESET` (existing) | B | ENUM | add `N` (never restart) to D / M / Y | Y | Customer | When receipt / bill numbers restart |

**Added 2026-11-05 (owner's request):** `GST_PCT` (B, DECIMAL 0.00–28.00, default: the shared `md_gst` rate, customer; **role A may change it**; the §7 recompute must use it when set). `SINGLE_SESSION_FLAG` (G, BOOL, default Y, customer; **only role S may change it**, 403 for A) and `ID_MASK_FLAG` (G, BOOL, default Y, customer). The app already sends both to `/settings`. Not needed on the server: `PRINTER_TYPE`, `BT_PRINTER` (each phone's own printer), rates and GST % (already `/rates` and `/gst`).

### A3. Billing rules (§7) — options or a decision
The server recomputes every bill. Three app options give a different amount than §7 and would be flagged `AMOUNT_MISMATCH`:
- `GRACE_MINUTES`: subtract grace minutes before counting blocks (`minutes = max(0, minutes − GRACE_MINUTES)`).
- `GST_INCLUSIVE = N`: GST added on top (`gst = base × pct / 100`, `net = base + penalty + gst`).
- `PENALTY_GST_FLAG = N`: GST only on the rent, not on the penalty.

Please either support them in the recompute (and snapshot them on `td_receipt`), or confirm they are dropped — then the app removes them.

Also clarify `GST_FLAG = N`: §8 says it only hides the GST breakup on the bill. Does the server still split `taxable_amt / cgst / sgst` from the inclusive total, or expect them as 0?

### A4. Station admin edits own cloak room details
**Done 2026-11-05** (`GET`/`PATCH /customers/me`, API_GUIDE.md §5). The app uses both. Previous note: The app already sends `PATCH /api/v1/customers/me` `{ "gst_no": "…" | null, "upi_id": "…" | null }` from Admin → Masters → Cloak room. The dev server answers `403 FORBIDDEN "Insufficient role"` because the S-only `PATCH /customers/:id` route catches `me`. Please register `/customers/me` before `/customers/:id`, allow role A, and use the customer from the token.

`PATCH /customers/:id` is super-admin only. The station admin's app has a "Cloak room" screen (name, address, PIN code, GSTIN, UPI ID). Please add e.g. `PATCH /api/v1/customers/me` for role A with those fields — or confirm it stays super-admin only (the app then shows the screen read-only).

### A5. Users of the customer for counter devices
Counter users (O) cannot call `/users`, and login / pull carry no user list, so bags and bills from other counters show only a number. Please add `users` to login `masters` and to `GET /sync/pull` (`id, user_id, user_name, user_code, user_type, allow_flag, is_deleted`).

### A6. `gst_no` on bills (`td_receipt`)
The app stores the GSTIN printed on each bill, so changing the GSTIN later never changes old bills. Please add `gst_no` (nullable, ≤ 15) to `td_receipt`: accept it in `POST /sync/push` and return it in pull, `GET /luggage/:trans_id` and the check-out report.

### A7. Ending a stuck session
Today `superadmin` could not log in from the phone: an earlier session from another tool was still open (`409 SESSION_ACTIVE`) and nobody can force-log-out a super admin. Please add one of: a super admin may force-log-out any user including another super admin; or `POST /auth/login` with `"force": true` that replaces the other session (logged); or an idle timeout for sessions.

### A8. Check-out of a bag that is not synced yet (architecture PDF, Case 1)
Device 2 scans the receipt QR of a bag checked in on device 1 that has not reached the server yet. The QR has `trans_id`, receipt no. and date, user, time, bags, name, mobile, PNR — but never the ID proof. Device 2 then pushes a luggage row without `id_proof_id` / `id_number` plus the bill. Please confirm the push accepts this row (ID fields optional or `0`) and merges it when device 1's full check-in row arrives (check-in fields write-once).

## C. Check-in receipt QR — exact payload

What the app prints in the QR on every check-in receipt (when `QR_FLAG = Y`). Device 2 reads it to check out a bag that has not synced yet (A8).

- Encoding: compact JSON, one flat object, no spaces, UTF-8; QR error correction level M.
- Strings use JSON escaping (`\"`, `\\`, control characters as `\u00XX`); other letters, e.g. Bengali names, are written as they are.
- Keys are one letter to keep the QR small on 58 mm paper; the order is always as below.
- **The ID proof number is never in the QR.**

| Key | Field (`td_luggage_in_out`) | Type | Example |
|---|---|---|---|
| `v` | payload version (`1` today) | number | `1` |
| `t` | `trans_id` (UUID v4) | string | `"5f0c3a52-8a1e-4d43-9a49-2f8e1c7c1b11"` |
| `r` | `receipt_no` | string | `"U02-00001"` |
| `d` | `receipt_dt` (IST date) | string | `"2026-11-02"` |
| `c` | `customer_id` | number | `1` |
| `u` | `user_id_in` (`md_user.id`) | number | `2` |
| `i` | `date_time_in` (UTC `YYYY-MM-DD HH:mm:ss`) | string | `"2026-11-02 06:30:00"` |
| `b` | `luggage_qty` | number | `3` |
| `n` | `pasn_name` | string | `"Ravi Kumar"` |
| `m` | `pasn_mob_no` | string | `"9876543210"` |
| `p` | `pnr_no` (may be `""`) | string | `"4512345678"` |

Example, exactly as printed:

```
{"v":1,"t":"5f0c3a52-8a1e-4d43-9a49-2f8e1c7c1b11","r":"U02-00001","d":"2026-11-02","c":1,"u":2,"i":"2026-11-02 06:30:00","b":3,"n":"Ravi Kumar","m":"9876543210","p":"4512345678"}
```

## B. Good to have

- **B1. Login shift on the server**: enforce `SHIFT_LOGIN_FLAG` / `SHIFT_EARLY_LOGIN_MIN` with `td_assign_shift` at `POST /auth/login` (today the app checks it after the server login).
- **B2. Settings change log**: `GET /api/v1/settings/log?customer_id=` (who changed what, old → new) for the admin screens.
- **B3. App release check**: confirm `GET /app/version` `current` is the Android `versionCode` (the app sends `1` today).

## Already in the API — no request needed
Customers, registrations, payments, users, rates, shifts, shift assignments, header/footer + logo, §8 settings (also for S with `customer_id`), GST, ID proofs, luggage lookup, reports, sync push/pull.

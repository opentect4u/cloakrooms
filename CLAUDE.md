# Cloak Room Billing — Android App (Offline Phase)

You are building a native Android app for cloak-room (luggage storage) billing.
The owner of this project is a **front-end developer with no Kotlin or back-end experience**.
So:

- Write complete, working code. Never leave `TODO` stubs in a finished phase.
- After every phase, run `./gradlew assembleDebug` and the unit tests, fix every error yourself, and only then report back.
- At the end of each phase, explain in plain language what was built, how to run it in Android Studio, and what to test by hand. Then **stop and wait for my OK** before the next phase.
- When you have to make a decision that is not covered here, choose the simplest option, write it in `docs/DECISIONS.md`, and tell me.

## Reference documents (read these first)

- `docs/Cloak_Room_Billing_SaaS__Android_Architecture.pdf`: the architecture, business rules, check-in/check-out flow, bill formula and sync design.
- `docs/Cloak_Room_Table_Schema.xlsx`: the table schema. Use the **SQLite** sheet. Keep the same table names and column names so the future sync to MySQL needs no mapping.

Where this file and the documents disagree, **this file wins**. The main differences are that this phase is offline-only and that `md_setting` is the single rule table.

## Scope of this phase

- **Offline only.** There is no API yet. Do not add Retrofit calls, login to a server or a sync worker.
- **Do** keep everything sync-ready: every table keeps `sync_flag`, `local_version`, `is_deleted` and the audit columns. Every insert or update sets `sync_flag = 'N'` and `local_version = local_version + 1`. Add trigger `trg_inout_resync` exactly as in the PDF.
- Put all data access behind repository interfaces (`AuthRepository`, `SettingsRepository`, `LuggageRepository`, `BillRepository`, `ReportRepository`), so a remote data source can be added later without touching the screens.
- One customer (tenant) is seeded on the device: `customer_id = 1`.

## Tech stack

- Kotlin, single Gradle module, packages `ui`, `domain` and `data` as in the PDF.
- Jetpack Compose + Material 3, Navigation Compose, ViewModel + StateFlow.
- Room (SQLite), with exported schema and proper migrations from version 1 onward.
- Hilt for dependency injection.
- ZXing to generate the receipt QR, and ML Kit barcode scanning (CameraX) to scan it.
- `minSdk 24`, `targetSdk` = latest stable. Many POS devices run old Android versions.
- Money: use `BigDecimal` in the `domain` layer, rounded `HALF_UP` to 2 decimals, and store it as REAL as the schema says.
- Time: store UTC text `'YYYY-MM-DD HH:MM:SS'`, and show it in Asia/Kolkata time.

## Users and roles (offline login)

Seed two users for customer 1:

| user_id | user_name | user_type | user_code | First password |
|---|---|---|---|---|
| `admin` | Admin | `A` (admin) | `U01` | `Admin@123` |
| `user1` | Counter User | `O` (normal user / operator) | `U02` | `User@123` |

- Because there is no server yet, add these local-only columns to `md_user`: `user_code TEXT`, `password_hash TEXT`, `must_change_pwd TEXT CHECK IN ('Y','N') DEFAULT 'Y'`, `failed_attempts INTEGER DEFAULT 0`. Hash passwords with PBKDF2-HMAC-SHA256 and a per-user salt. Never store plain text.
- Force a password change at first login.
- **Single active session per user (my "device limit" rule).** When a user logs in, set `md_user.device_id` (Android ID) and `session_started_at`. While these are set, a second login with the same user ID is refused with the message "Already logged in since <time>. Logout first." Logout clears both fields. An admin can **Force Logout** any user from the Users screen. Control this with the `SINGLE_SESSION_FLAG` setting. The server will enforce the same rule later.
- Block login after `MAX_LOGIN_ATTEMPTS` wrong passwords until an admin unblocks the user (`allow_flag`).
- Keep the user logged in across app restarts until they log out, then go straight to the Dashboard.

### Permissions (enforce in BOTH places)

1. **UI:** hide admin-only menus and buttons from `O` users.
2. **Repository/domain:** every admin-only write checks the logged-in user's `user_type == 'A'` and throws `NotAuthorizedException` otherwise. Hiding a button is not enough.

| Feature | Admin (A) | Normal user (O) |
|---|---|---|
| Check-in, check-out, reprint | ✅ | ✅ |
| Own reports (own check-ins/bills) | ✅ | ✅, only if `REPORT_FLAG = Y` |
| All-users reports, total collection | ✅ | ❌ |
| Settings (`md_setting`) | ✅ view and edit | ❌ cannot open |
| Masters: users, ID proof, header/footer, shifts | ✅ | ❌ |
| Force logout / block user / reset password | ✅ | ❌ |
| Change own password | ✅ | ✅ |

## `md_setting`: the single rule table

**Every** rule, rate, limit, on/off switch and calculation input is read from `md_setting`. **No business number or switch may be hard-coded** in Kotlin. If a rule needs a value, it becomes a setting.

### Table changes

Keep the schema columns (`setting_id`, `customer_id`, `user_id`, `setting_name`, `setting_value`, `setting_type`, plus the sync and audit columns) and add these columns, so the Settings screen can be generated from the table itself:

- `label TEXT`: friendly name shown on screen
- `data_type TEXT CHECK IN ('BOOL','INT','DECIMAL','TEXT','ENUM','PASSWORD')`
- `options TEXT`: for ENUM, e.g. `C:Cash|U:UPI`
- `min_value REAL`, `max_value REAL`: validation limits
- `depends_on TEXT`: name of a BOOL setting that must be `Y` for this one to be shown and editable (e.g. `GST_PCT` depends on `GST_FLAG`)
- `sort_order INTEGER`
- `description TEXT`: one-line help text shown under the field

Lookup rule: a row with `user_id = <logged-in user>` overrides the customer-wide row (`user_id IS NULL`).
Add a unique index on `(customer_id, IFNULL(user_id,0), setting_name)`.

Add an audit table, `td_setting_log` (`id`, `customer_id`, `setting_name`, `old_value`, `new_value`, `changed_by`, `changed_at` + sync columns). Write one row on every change.

### Seed values (customer-wide, `user_id = NULL`)

| setting_name | type | data_type | default | depends_on | Meaning |
|---|---|---|---|---|---|
| **Billing (B)** |||||
| `FIRST_BLOCK_RATE` | B | DECIMAL | 50 | | Rate per bag for the first block (₹) |
| `NEXT_BLOCK_RATE` | B | DECIMAL | 20 | | Rate per bag for each further started block (₹) |
| `BLOCK_HOURS` | B | INT | 24 | | Length of one billing block in hours (min 1) |
| `GRACE_MINUTES` | B | INT | 0 | | Extra minutes allowed before the next block starts |
| `GST_FLAG` | B | BOOL | Y | | Is GST applicable? |
| `GST_PCT` | B | DECIMAL | 18 | GST_FLAG | Total GST % (0–28) |
| `GST_TYPE` | B | ENUM `E:CGST+SGST\|I:IGST` | E | GST_FLAG | How GST is split |
| `GST_INCLUSIVE` | B | BOOL | Y | GST_FLAG | Y = rate already includes GST; N = GST is added on top |
| `PENALTY_FLAG` | B | BOOL | Y | | Can the operator add a penalty at check-out? |
| `PENALTY_MAX_AMT` | B | DECIMAL | 500 | PENALTY_FLAG | Maximum penalty the operator can type (₹) |
| `PENALTY_GST_FLAG` | B | BOOL | Y | PENALTY_FLAG | Y = GST is applied to the penalty as well |
| `DAY_BILL_LIMIT_FLAG` | B | BOOL | N | | Limit the number of bills one user can make per day |
| `DAY_BILL_LIMIT_COUNT` | B | INT | 0 | DAY_BILL_LIMIT_FLAG | Max bills per user per day (0 = no limit) |
| `DEFAULT_PAY_MODE` | B | ENUM `C:Cash\|U:UPI` | C | | Payment mode preselected at check-out |
| `UPI_ENABLED` | B | BOOL | Y | | Show the UPI option and the UPI QR |
| `UPI_REF_REQUIRED` | B | BOOL | N | UPI_ENABLED | UPI reference number is mandatory |
| `OUT_WITHOUT_PRINT_FLAG` | B | BOOL | Y | | Allow the "Out Without Bill Print" button |
| `RECEIPT_RESET` | B | ENUM `D:Daily\|M:Monthly\|Y:Financial year (Apr–Mar)\|N:Never` | Y | | When receipt/bill numbers restart |
| **General (G)** |||||
| `SINGLE_SESSION_FLAG` | G | BOOL | Y | | One active login per user ID |
| `MAX_LOGIN_ATTEMPTS` | G | INT | 5 | | Wrong passwords before the user is blocked |
| `MAX_BAGS_PER_RECEIPT` | G | INT | 10 | | Max bags in one check-in |
| `PNR_REQUIRED` | G | BOOL | Y | | PNR is mandatory at check-in |
| `ID_PROOF_REQUIRED` | G | BOOL | Y | | ID proof is mandatory at check-in |
| `ID_MASK_FLAG` | G | BOOL | Y | | Store and print only the last 4 digits of the ID number |
| `REPORT_FLAG` | G | BOOL | Y | | Normal users can see their own reports |
| `REPORT_PWD_FLAG` | G | BOOL | N | | Ask for a password before opening reports |
| `REPORT_PWD` | G | PASSWORD | (empty) | REPORT_PWD_FLAG | Report password (store a hash) |
| `TOTAL_COLLECTION_FLAG` | G | BOOL | Y | | Show the total collection on the dashboard |
| `SYNC_INTERVAL_MIN` | G | INT | 15 | | For the future sync (min 15). Not used yet |
| **Print (P)** |||||
| `PAPER_WIDTH` | P | ENUM `58:58 mm\|80:80 mm` | 58 | | Printer paper width |
| `QR_FLAG` | P | BOOL | Y | | Print the QR on the check-in receipt |
| `CHECKIN_PRINT_FLAG` | P | BOOL | Y | | Print a receipt at check-in |
| `PRINT_COPIES` | P | INT | 1 | | Copies per print (1–3) |

`md_rate_dtls` and `md_gst` stay in the database for the future sync, but **in this phase all calculations read rates and GST from `md_setting` only.**

### Settings screen (admin only)

- Grouped into the tabs **Billing / General / Print**, built automatically from the `md_setting` rows (label, `data_type`, `options`, `min`/`max`, `depends_on`, `sort_order`). A new setting row must show up with no screen code change.
- BOOL → switch, INT/DECIMAL → number field with min/max validation, ENUM → dropdown, PASSWORD → masked field.
- Fields whose `depends_on` switch is off are greyed out.
- A Save button with a confirmation dialog, then a write to `td_setting_log`.
- Expose settings as a `StateFlow<AppSettings>` (a typed data class), so every screen reacts instantly when the admin changes something.

## Business logic (`domain` package: pure Kotlin, fully unit-tested)

### Bill calculation: `BillCalculator`

The input is the check-in time, the check-out time, the bag count, the penalty typed by the operator and an `AppSettings` snapshot.

```
minutes  = (out − in) in minutes, minus GRACE_MINUTES (not below 0)
blocks   = max(1, ceil(minutes / (BLOCK_HOURS × 60)))
base_amt = bags × (FIRST_BLOCK_RATE + NEXT_BLOCK_RATE × (blocks − 1))
penalty  = 0 if PENALTY_FLAG = N; otherwise 0 ≤ penalty ≤ PENALTY_MAX_AMT
```

GST:

- `GST_FLAG = N` → `gst_pct = 0`, all tax amounts are 0, and `net_amt = base_amt + penalty`.
- `GST_INCLUSIVE = Y` → `net_amt = base_amt + penalty`, and `taxable = gst_base × 100 / (100 + GST_PCT)`, where `gst_base` is `base_amt + penalty`, or only `base_amt` if `PENALTY_GST_FLAG = N`.
- `GST_INCLUSIVE = N` → `gst = gst_base × GST_PCT / 100`, and `net_amt = base_amt + penalty + gst`.
- Split: with `GST_TYPE = E`, `cgst = round(gst / 2)` and `sgst = gst − cgst`, so the two always add up exactly. With `I`, `igst = gst`.

Save a snapshot on `td_receipt`: `hours_charged` = number of blocks charged, `gst_pct`, `gst_flag`, the amounts, and the rates used. **Old bills must never change when settings change.**

Required unit tests: the four rows of the table on PDF page 5 (2 min/2 bags = ₹100; exactly 24 h 00 m = ₹50; 24 h 01 m = ₹70; 50 h/3 bags = ₹270), ₹70 at 18% inclusive = 59.32 + 5.34 + 5.34, GST off, GST exclusive, IGST, grace minutes, penalty above the max is rejected, and penalty with or without GST.

### Validation: `CheckoutValidator` and `CheckinValidator`

- Check-in: required fields per settings, `1 ≤ bags ≤ MAX_BAGS_PER_RECEIPT`, a valid 10-digit mobile number, ID masking per `ID_MASK_FLAG`.
- Check-out: the bag is not already out, the penalty is within limits, the UPI ref is present if required, and if `DAY_BILL_LIMIT_FLAG = Y`, the user's bills today (by `bill_dt`, local date) are below `DAY_BILL_LIMIT_COUNT`. Otherwise show the message "Daily bill limit reached".

### Numbering: `NumberGenerator`

- Add table `md_user_counter` (`customer_id`, `user_id`, `counter_type` R/B, `period_key`, `last_no`, + sync/audit columns).
- Format `<user_code>-<5-digit number>`, e.g. `U02-00001`. Use separate series for receipts (check-in) and bills (check-out).
- `period_key` follows `RECEIPT_RESET` (e.g. `2026-11-02`, `2026-11`, `FY2026-27`, `ALL`).
- Increment inside the same Room `@Transaction` as the insert, so two numbers can never collide.
- `trans_id` = a UUID made at check-in.

## Screens (Compose, Material 3, clean and big touch targets for counter use)

1. **Login**, then **Change password** (first login).
2. **Dashboard**: bags in store now, today's check-ins and bills (and today's collection if `TOTAL_COLLECTION_FLAG = Y`), big **Check-in** / **Check-out** buttons, the logged-in user, and Logout.
3. **Check-in**: PNR, name, mobile, bags, ID type (from `md_id_proof`), ID number. On Save, show a receipt preview with the QR.
4. **Check-out**: search by PNR, mobile, name or receipt no., or **scan the QR**. Show the details and a live bill breakup, a penalty field (only if `PENALTY_FLAG = Y`, max shown), Cash/UPI (UPI shows a UPI QR built from `md_customer.upi_id` and the exact amount), then **Bill Print** and **Out Without Bill Print** (if allowed). Save the bill and the check-out in one transaction.
5. **Bags in store** list with search.
6. **Reports**: date range, check-ins (by `user_id_in`), bills (by `user_id_out`), and collection by pay mode with the GST breakup. Normal users see only their own data; admins can filter by user.
7. **Admin → Settings** (described above).
8. **Admin → Users**: add/edit/block, reset password, Force Logout, and show who is logged in now and since when.
9. **Admin → Masters**: ID proof types, header/footer lines (H/F/Logo with in/out/report flags), shifts.

The QR content follows Case 1 in the PDF: `trans_id`, receipt no. and date, customer_id, user_id_in, check-in time, bags, name, mobile and PNR. **Never the ID number.** Use compact JSON.

## Printing

- Create a `Printer` interface. For now, implement `PreviewPrinter`, which shows the 58/80 mm receipt or bill as an on-screen preview and can share it as a PDF. Header and footer lines come from `md_header_footer`.
- Add a `BluetoothEscPosPrinter` skeleton behind the same interface. The POS model is not decided yet.
- Set `bill_printed` to `Y` or `N` according to the button pressed.

## Seed data (first launch only)

- Customer 1: "Demo Cloak Room", with an address, mobile, a sample GSTIN and a sample UPI ID.
- The 2 users above, all the settings above, and ID proof types (Aadhaar Card, Driving Licence, Passport, PAN Card, Voter ID).
- 2 header lines, 1 footer line, and shifts Morning 06:00–14:00, Evening 14:00–22:00, Night 22:00–06:00.
- A debug-only menu item, "Reset demo data".

## Delivery phases (stop after each one)

1. **Project setup + database**: Gradle, Hilt, all Room entities/DAOs from the SQLite sheet plus the changes above, triggers, seed data, and a DB inspector check. Unit tests for the settings lookup (user row overrides customer row).
2. **Login, roles and single session**: login, change password, logout, force logout, the permission checks, and tests that a normal user cannot write settings.
3. **Settings screen**: generated from `md_setting`, with validation, `depends_on`, and audit logging.
4. **Domain**: `BillCalculator`, the validators and `NumberGenerator`, with all the unit tests above passing.
5. **Check-in + receipt preview + QR**.
6. **Check-out + scan + bill + UPI QR + preview printing**.
7. **Dashboard, bags in store and reports**.
8. **Admin masters + polish**: empty states, error messages, and a final full build plus a manual test checklist in `docs/TEST_CHECKLIST.md`.

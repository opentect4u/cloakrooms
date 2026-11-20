/** Markdown shown at the top of Swagger UI — the human part of the Android contract. */
export const API_DESCRIPTION = `
Backend of the **Cloak Room Billing** SaaS (Synergic). The Android POS app works offline on
SQLite and synchronises with this API. All paths below are under \`/api/v1\` except \`/downloads\`.

## Conventions
- **Envelope** — every JSON response: \`{ success, message, data, meta?, errors?, code?, requestId }\`.
  Errors carry \`success: false\`, an UPPER_SNAKE \`code\` and \`data: null\`.
- **Times** — UTC \`YYYY-MM-DD HH:mm:ss\` in and out. Dates (\`receipt_dt\`, \`bill_dt\`, report
  \`from/to\`) are **IST** calendar dates. Reports add \`*_ist\` copies of timestamps.
- **Money** — decimal strings with 2 places (\`"70.00"\`); never floats.
- **Lists** — \`?page=1&limit=50\` (max 200), \`?sort=col:asc,col2:desc\` (whitelisted), \`?search=\`;
  \`meta: { page, limit, total }\` (+ \`totals\` on reports).
- **Tenant** — the customer always comes from the token. A super admin (S) has no customer and
  passes \`customer_id\` where a write or report needs one.
- **Request id** — send \`X-Request-Id\` to correlate; it is echoed back.

## Auth & sessions
1. \`POST /auth/login\` → \`accessToken\` (~12 h) + \`refreshToken\` (~30 d) + everything needed offline
   (user, customer, masters, settings, counters, server_time).
2. Send \`Authorization: Bearer <accessToken>\`. On \`401 TOKEN_EXPIRED\` call \`POST /auth/refresh\`;
   **both tokens rotate** — store the new pair, the old ones stop working. Re-using an old refresh
   token revokes the session (\`401 TOKEN_REUSED\`).
3. One active session per user: login from another device → \`409 SESSION_ACTIVE\`
   (\`errors.session_started_at\`, masked \`errors.device_id\`). Login again from the **same**
   \`device_id\` replaces the old session.
4. \`401 SESSION_ENDED\` = logged out / replaced / force-logged-out by an admin. A force-logged-out
   device may still call **only** \`POST /sync/push\` and \`POST /auth/logout\`: push pending rows,
   then log out.
5. Other login errors: \`401\` wrong credentials, \`403 FORBIDDEN\` blocked user / inactive customer,
   \`403 SUBSCRIPTION_EXPIRED\`, \`409 USER_LIMIT_REACHED\`, \`426 UPDATE_REQUIRED\` (the sent
   \`version_code\` is older than a live mandatory release; \`errors.latest\` has the APK),
   \`403 OUTSIDE_SHIFT\` (counter user with a shift in the roster signing in outside it while
   \`SHIFT_LOGIN_FLAG = Y\`; \`errors\` = \`{ shift_name, from_time, to_time, opens_at }\`, IST).
6. \`user.must_change_pwd = Y\` (new user / admin reset): every call except \`POST /auth/change-password\`,
   logout and me → \`403 PASSWORD_CHANGE_REQUIRED\` until the password is changed.

## Sync push — \`POST /sync/push\`
Body \`{ luggage: [...], receipts: [...] }\` (≤ 5000 each, ≤ 5 MB). Luggage is processed first, then
receipts, in transactions of 500 rows. Each row is validated on its own: **a bad row never fails the
request** — it comes back as \`EXCEPTION\` and is logged for the admin.

- Every row gets a result \`{ table, trans_id, local_version, status, reason? }\`. **Both ACCEPTED and
  EXCEPTION mean "set sync_flag = 'Y'"** (exceptions are owned by the admin now).
- \`local_version\` is echoed back untouched (never stored).
- Re-pushing the same rows is always safe (idempotent).
- \`customer_id\` / \`sync_flag\` / audit fields in rows are ignored.

**Luggage merge (key \`trans_id\`, UUID created on the check-in device and printed in the QR):**
check-in fields are write-once; check-out fields (\`user_id_out, device_id_out, shift_id_out,
date_time_out\`) only fill empty values; \`out_flag\` never goes back from Y. A bag checked in on device 1
and out on device 2 always ends as one row, whatever the push order. A *different* second check-out →
\`DUPLICATE_CHECKOUT\`. ID numbers of proofs with \`mask_flag = Y\` are stored as \`XXXXXXXX\` + last 4.
A check-out made from the receipt QR before the check-in synced may omit \`device_id_in\`,
\`shift_id_in\`, \`id_proof_id\`, \`id_number\` (the QR doesn't carry them); the later check-in push fills
them once. \`id_proof_id\` / \`id_number\` are optional on any row but go together.
\`extra_lugage_qty\` (extra bags, free), \`adv_amt\` and \`adv_hours\` (advance at check-in) default to 0
and are filled once while still 0 (a QR row carries the advance in key \`a\`, extra bags in \`x\`);
after that they never change.

**Bills** carry \`gst_no\` = the customer's GSTIN, stamped by the server when stored (not sent by devices).

**Receipts:** one bill per \`trans_id\`, stored once. The server recomputes every bill; a difference
keeps the bill and logs \`AMOUNT_MISMATCH\` (with the expected values) for the admin. A bill
**without \`rate_slabs\`** (app older than Phase 13) for a tariff the two-rate fields cannot express
(3+ slabs, or 2 slabs of different lengths) is refused with \`APP_UPDATE_REQUIRED\` (not stored).

**Billing rules the app must mirror exactly:**
- minutes = whole minutes from \`date_time_in\` to \`date_time_out\` (seconds dropped);
  billable = max(0, minutes − grace_minutes)
- tariff = the rate row's \`rate_slabs\` [{from_hour, to_hour, rate}], contiguous from hour 0. Per bag:
  every slab whose start the stay has passed adds its rate (the first slab always:
  billable > from_hour × 60), and after the last slab ends its rate is added again for every started
  (to_hour − from_hour); days_charged = number of rates added
  (0–24 h ₹30 + 24–48 h ₹10: 2 bags × 3 days = 2 × (30 + 10 + 10) = ₹100; 24h00m → 1, 24h01m → 2)
- base = qty × that per-bag amount (extra bags are free). Send \`rate_slabs\` with the bill; the
  two-rate fields \`block_hours/first_block_rate/next_block_rate\` are then optional
- advance: bill \`adv_amt\` = the bag's \`adv_amt\`; \`bal_amt\` = max(0, net − adv_amt) (no refund);
  net_amt stays the full bill
- rate row = latest \`effective_from ≤ receipt_dt\`; GST row = latest \`effective_from ≤ bill_dt\`
- all arithmetic in integer paise; rounding **half-up** to the paisa
- gst% = 0 when \`gst_flag = N\`; else the customer's \`GST_PCT\` when set, else the GST row's rate;
  taxed = base + (penalty_gst_flag = Y ? penal_amt : 0)
- \`gst_inclusive = Y\` (default): taxable = round(taxed × 100 / (100 + gst%)); gst = taxed − taxable;
  net = base + penal_amt
- \`gst_inclusive = N\`: taxable = taxed; gst = round(taxed × gst% / 100); net = base + penal_amt + gst
- taxable_amt = taxable (a penalty without GST is not part of it)
- GST_TYPE E: cgst = round(gst / 2) (odd paisa to CGST), sgst = gst − cgst, gst% = cgst% + sgst%;
  GST_TYPE I: igst = gst, gst% = igst%
- e.g. ₹70 at 18 % → taxable 59.32, CGST 5.34, SGST 5.34
- **Snapshot:** send \`grace_minutes\`, \`gst_inclusive\`, \`penalty_gst_flag\` and \`gst_flag\` with each
  bill (the values you billed with). The server recomputes with them and stores them; omitted
  fields fall back to the customer's settings (\`GRACE_MINUTES\`, \`GST_INCLUSIVE\`,
  \`PENALTY_GST_FLAG\`, the billing user's \`GST_FLAG\`), then to the defaults 0 / Y / Y / Y.

**Numbers:** \`<user_code>-<5 digits>\` (e.g. \`U03-00125\`), series R (receipt) and B (bill) per user,
reset per \`RECEIPT_RESET\` (D day / M month / Y Indian FY \`FY2026-27\` / N never → period \`ALL\`;
IST). The response's
\`counters\` are the server's last numbers for the current period; the server never lowers them.

## Sync pull — \`GET /sync/pull\`
- First pull: no \`since\` → all masters + settings + counters + **open** check-ins of the customer.
- Next pulls: \`since\` = the \`server_time\` of the previous completed pull → rows changed since then
  (soft-deleted rows included, \`is_deleted = 'Y'\` → hide them). The server re-reads a 2-minute
  overlap: **dedupe by primary key**.
- At most \`limit\` (default/max 1000) luggage rows per response. While \`has_more\` is true call again
  with \`?cursor=<next_cursor>\` only; continuation pages carry luggage only. When \`has_more\` is false,
  store \`server_time\` as the next \`since\`.

## Rate limits (per minute)
login 10 per IP + user_id · sync 120 per user · general 300 per user · reports 30 per user ·
3000 per IP overall. Exceeding → \`429 RATE_LIMITED\` with \`RateLimit-*\` headers.
`;

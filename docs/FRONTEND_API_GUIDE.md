# Cloak Room Billing API — Frontend Integration Guide

> **For the frontend developer's AI coding agent (Claude Code).** Paste or place this file in the
> frontend repository (e.g. as `API_GUIDE.md` and reference it from the frontend `CLAUDE.md`).
> It is the complete contract: every endpoint, its payload, a **real response sample** (captured
> from the running API by `npm run docs:frontend` in the backend repo — long tokens/base64 are
> shortened with `…`), status codes, and the rules the app must follow.
> Machine-readable spec: `docs/openapi.json` (OpenAPI 3.0) / Swagger UI at `<baseUrl>/api/docs`.

## 0. Instructions for the agent

1. **Never invent endpoints, fields or values.** Use only what is in this file. If something is
   missing, ask the developer instead of guessing.
2. All JSON endpoints are under `<baseUrl>/api/v1`. Make `baseUrl` configurable
   (dev: `http://localhost:4000`). Send `Content-Type: application/json`.
3. Every response has the **same envelope** (§2). Always read `success` and `code`, never only
   the HTTP status.
4. Implement auth exactly as in §3 (token refresh, one session per user, force-logout).
5. The Android POS app is **offline-first**: implement sync exactly as in §6 and billing
   arithmetic exactly as in §7 — the server recomputes every bill and flags any difference.
6. Times on the wire are **UTC** `YYYY-MM-DD HH:mm:ss`; show users IST (UTC+05:30).
   Money is a decimal **string** (`"70.00"`) — never use floating point for money.
7. Build a typed API client from the shapes below (one function per endpoint, typed request/response).

---

## 1. Who uses what

| Role (`user_type`) | Who | App | Can use |
|---|---|---|---|
| `S` | Synergic super admin (no customer) | admin web | customers, registrations, payments, users of any customer, masters/settings of any customer (§5, pass `customer_id`), GST, ID proofs, app releases, reports (pass `customer_id`) |
| `A` | Customer (station) admin | admin web / app | users, rates, shifts, shift assignments, header/footer + logo, settings, reports, exceptions, luggage lookup, sync |
| `O` | Counter operator | Android POS | login, sync push/pull, luggage lookup, reports only if `REPORT_FLAG = Y` |

**Tenant rule:** the customer is always taken from the token. Never send `customer_id` as a
customer user (it is ignored). A super admin sends `customer_id` where a create or report needs it.

---

## 2. Envelope, status codes and conventions

Success:
```json
{ "success": true, "message": "OK", "data": { }, "meta": { "page": 1, "limit": 50, "total": 3 }, "requestId": "uuid" }
```
Error (`data` is always `null`; `code` tells you what happened; `errors` has details):
```json
{ "success": false, "message": "Validation failed", "code": "VALIDATION_ERROR", "data": null,
  "errors": [ { "location": "body", "path": "password", "message": "Invalid input: expected string, received undefined" } ],
  "requestId": "uuid" }
```

| HTTP | `code` | Meaning → what the app should do |
|---|---|---|
| 200 | — | OK |
| 201 | — | Created (POST create) |
| 400 | `VALIDATION_ERROR` | Body/query/params invalid → show `errors[].path` + `message` next to fields |
| 400 | `BAD_REQUEST` / `INVALID_JSON` | Malformed request / referenced record missing → fix the request |
| 401 | `UNAUTHORIZED` | No/invalid token or wrong login → go to login |
| 401 | `TOKEN_EXPIRED` | Access token expired → call `POST /auth/refresh` once, retry the request |
| 401 | `TOKEN_REUSED` | Old refresh token re-used → session revoked → go to login |
| 401 | `SESSION_ENDED` | Logged out / replaced / **force-logged-out** → POS: push pending rows (allowed), then logout and show login |
| 403 | `FORBIDDEN` | Role not allowed, user blocked or customer inactive → show message, hide the feature |
| 403 | `SUBSCRIPTION_EXPIRED` | Customer subscription ended (`errors.renewal_dt`) → block login, ask to contact Synergic |
| 403 | `REPORT_PASSWORD_REQUIRED` | Report password needed/wrong → prompt, send `X-Report-Password` header |
| 403 | `PASSWORD_CHANGE_REQUIRED` | `user.must_change_pwd = Y` → show the change-password screen (`POST /auth/change-password`) |
| 403 | `OUTSIDE_SHIFT` | login of a counter user outside their shift (`SHIFT_LOGIN_FLAG = Y`); `errors = { shift_name, from_time, to_time, opens_at }` (IST `HH:mm`) → "Sign-in opens at …" |
| 404 | `NOT_FOUND` | Missing, or belongs to another customer |
| 409 | `CONFLICT` | Duplicate unique value (`errors.key`, e.g. `md_user.uq_user_code`) |
| 409 | `SESSION_ACTIVE` | User already logged in on another device (`errors.session_started_at`, masked `errors.device_id`) |
| 409 | `USER_LIMIT_REACHED` | All licensed seats used (`errors.user_limit`) |
| 426 | `UPDATE_REQUIRED` | Login from an app older than a mandatory release → show `errors.latest` (version, `apk_url`) and block login |
| 413 | `PAYLOAD_TOO_LARGE` | Body too big (JSON 1 MB, sync 5 MB, logo 200 KB) → split / shrink |
| 429 | `RATE_LIMITED` | Too many requests → back off (see `RateLimit-*` headers) |
| 500 | `INTERNAL_ERROR` | Server bug → show generic error, log `requestId` |
| 503 | `SERVICE_UNAVAILABLE` | DB busy/unavailable → retry with back-off |

**Lists:** `?page=1&limit=50` (max 200) → `meta: { page, limit, total }`. Sorting `?sort=col:asc`
(only whitelisted columns, else 400). `?search=` is a contains-match. Deleted rows never appear in lists.

**Other conventions**
- Times: UTC `YYYY-MM-DD HH:mm:ss`. Business dates (`receipt_dt`, `bill_dt`, report `from/to`) are IST dates `YYYY-MM-DD`.
- Flags are `"Y"` / `"N"`. Money: 2-decimal strings (send `"50.00"` or `50`; you receive `"50.00"`).
- Optional header `X-Request-Id` (8–64 chars `[A-Za-z0-9_-]`) is echoed back for log correlation.
- Deletes are always **soft** (`is_deleted = "Y"`).
- Rate limits per minute: login 10 per IP+user_id · sync 120 per user · reports 30 per user · everything else 300 per user.

Rate-limit sample (11th login attempt within a minute):
```json
{
  "success": false,
  "message": "Too many requests, please slow down",
  "code": "RATE_LIMITED",
  "data": null,
  "requestId": "decd7ec7-2660-4cb6-b695-24339ec68581"
}
```

---

## 3. Authentication & sessions

**Flow**
1. `POST /auth/login` → store `accessToken` (~12 h) and `refreshToken` (~30 d) securely
   (Android: EncryptedSharedPreferences). Also store the offline payload (`masters`, `settings`,
   `counters`, `server_time`) for the POS.
2. Send `Authorization: Bearer <accessToken>` on every protected call.
3. On `401 TOKEN_EXPIRED`: call `POST /auth/refresh` **once** (single-flight; queue other calls), store
   **both** new tokens, retry. The old pair stops working immediately.
4. `device_id`: a stable per-install id (Android ID). Logging in again from the **same** `device_id`
   replaces the old session; another device gets `409 SESSION_ACTIVE` until the user logs out there
   or an admin force-logs them out.
5. `401 SESSION_ENDED` on a POS: if the device still has unsynced rows, call `POST /sync/push` (still
   allowed after force-logout), then `POST /auth/logout`, clear tokens, show login.
6. Logout: POS must push pending rows first, then `POST /auth/logout`.
7. **First login / after an admin reset:** `user.must_change_pwd = "Y"` (set when an admin creates the
   user or resets the password). Until `POST /auth/change-password` succeeds, every other call returns
   `403 PASSWORD_CHANGE_REQUIRED` (only change-password, logout and me work). The same token keeps
   working after the change. A force-logged-out old device can still push its pending rows.
8. **App version (Android):** send `version_code` (the APK versionCode) at login. If a newer live release
   is mandatory → `426 UPDATE_REQUIRED` with `errors.latest` (download `apk_url`). Web clients omit it.

#### `POST /api/v1/auth/login` — log in (counter device)

**Auth / roles:** public

Response contains everything the POS needs offline. `customer`/`counters` are `null` for a super admin; `settings` is the customer settings merged with the user’s own overrides (see §8).

| Body field | Rules |
|---|---|
| `user_id` | login id, required |
| `password` | required |
| `device_id` | stable device id, 1–100 chars, required |
| `app_version` | optional, ≤ 20 chars (logged) |
| `version_code` | Android versionCode (integer); refused with 426 when a newer mandatory release exists |

Request body:
```json
{
  "user_id": "howrah.op1",
  "password": "Counter@123",
  "device_id": "POS-HWH-01",
  "app_version": "1.0.0"
}
```

Response `200`:
```json
{
  "success": true,
  "message": "Logged in",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiIs…(JWT)",
    "refreshToken": "eyJhbGciOiJIUzI1NiIs…(JWT)",
    "user": {
      "id": 3,
      "customer_id": 1,
      "user_id": "howrah.op1",
      "user_name": "Ravi Kumar Singh",
      "user_code": "U03",
      "user_type": "O",
      "allow_flag": "Y",
      "must_change_pwd": "Y"
    },
    "customer": {
      "customer_id": 1,
      "customer_name": "Howrah Cloak Room",
      "customer_addr": "Howrah Station, Platform 1, Howrah",
      "state": "West Bengal",
      "pin_code": "711101",
      "mobile_no": "9830012345",
      "email": "ops@howrahcloak.in",
      "gst_no": "19AABCS1234C1Z5",
      "upi_id": "howrah.cloak@oksbi",
      "renewal_dt": "2027-11-17",
      "user_limit": 5,
      "active_flag": "Y"
    },
    "masters": {
      "id_proofs": [
        {
          "id_proof_id": 1,
          "id_proof_name": "Aadhaar Card",
          "mask_flag": "Y",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "system",
          "created_at": "2026-11-18 07:16:37",
          "updated_by": null,
          "updated_at": null
        },
        {
          "id_proof_id": 2,
          "id_proof_name": "Driving Licence",
          "mask_flag": "N",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "system",
          "created_at": "2026-11-18 07:16:37",
          "updated_by": null,
          "updated_at": null
        },
        "… 5 more item(s) of the same shape"
      ],
      "gst": [
        {
          "gst_id": 1,
          "cgst_pct": "9.00",
          "sgst_pct": "9.00",
          "igst_pct": "0.00",
          "effective_from": "2017-07-01",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "system",
          "created_at": "2026-11-18 07:16:37",
          "updated_by": null,
          "updated_at": null
        }
      ],
      "rates": [
        {
          "rate_id": 1,
          "customer_id": 1,
          "block_hours": 24,
          "first_block_rate": "50.00",
          "next_block_rate": "20.00",
          "rate_slabs": [
            {
              "rate": "50.00",
              "to_hour": 24,
              "from_hour": 0
            },
            {
              "rate": "20.00",
              "to_hour": 48,
              "from_hour": 24
            }
          ],
          "effective_from": "2026-01-01",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "superadmin",
          "created_at": "2026-11-18 07:16:46",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-18 07:16:47"
        }
      ],
      "shifts": [
        {
          "shift_id": 1,
          "customer_id": 1,
          "shift_name": "Morning",
          "from_time": "06:00:00",
          "to_time": "14:30:00",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "howrah.admin",
          "created_at": "2026-11-18 07:16:47",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-18 07:16:47"
        },
        {
          "shift_id": 2,
          "customer_id": 1,
          "shift_name": "Night",
          "from_time": "22:00:00",
          "to_time": "06:00:00",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "howrah.admin",
          "created_at": "2026-11-18 07:16:47",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-18 07:16:47"
        }
      ],
      "shift_assignments": [
        {
          "id": 1,
          "customer_id": 1,
          "user_id": 3,
          "shift_id": 1,
          "effective_from": "2026-11-19",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "howrah.admin",
          "created_at": "2026-11-18 07:16:47",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-18 07:16:47"
        }
      ],
      "header_footer": [
        {
          "id": 1,
          "customer_id": 1,
          "line_type": "H",
          "line_order": 1,
          "narration": "HOWRAH CLOAK ROOM",
          "image": null,
          "in_flag": "Y",
          "out_flag": "Y",
          "report_flag": "N",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "howrah.admin",
          "created_at": "2026-11-18 07:16:47",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-18 07:16:47"
        },
        {
          "id": 2,
          "customer_id": 1,
          "line_type": "F",
          "line_order": 1,
          "narration": "Thank you. Keep this receipt safe.",
          "image": null,
          "in_flag": "Y",
          "out_flag": "Y",
          "report_flag": "Y",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "howrah.admin",
          "created_at": "2026-11-18 07:16:47",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-18 07:16:47"
        },
        "… 1 more item(s) of the same shape"
      ],
      "users": [
        {
          "id": 2,
          "customer_id": 1,
          "user_code": "A01",
          "user_name": "Station Admin",
          "user_type": "A",
          "allow_flag": "Y",
          "is_deleted": "N",
          "updated_at": "2026-11-18 07:16:47"
        },
        {
          "id": 3,
          "customer_id": 1,
          "user_code": "U03",
          "user_name": "Ravi Kumar Singh",
          "user_type": "O",
          "allow_flag": "Y",
          "is_deleted": "N",
          "updated_at": "2026-11-18 07:16:47"
        },
        "… 1 more item(s) of the same shape"
      ]
    },
    "settings": {
      "REPORT_FLAG": "N",
      "REPORT_PWD_FLAG": "N",
      "TOTAL_COLLECTION_FLAG": "Y",
      "RECEIPT_RESET": "Y",
      "SYNC_INTERVAL_MIN": "30",
      "FORCE_ONLINE_DAYS": "0",
      "GST_FLAG": "Y",
      "GST_TYPE": "E",
      "QR_FLAG": "Y",
      "DEFAULT_PAY_MODE": "C",
      "PAPER_WIDTH": "58",
      "PENALTY_FLAG": "Y",
      "PENALTY_MAX_AMT": "500.00",
      "PENALTY_GST_FLAG": "Y",
      "GRACE_MINUTES": "0",
      "GST_INCLUSIVE": "Y",
      "UPI_ENABLED": "Y",
      "OUT_WITHOUT_PRINT_FLAG": "Y",
      "SINGLE_SESSION_FLAG": "Y",
      "MAX_BAGS_PER_RECEIPT": "10",
      "PNR_REQUIRED": "Y",
      "ID_PROOF_REQUIRED": "Y",
      "ID_MASK_FLAG": "Y",
      "SHIFT_LOGIN_FLAG": "Y",
      "SHIFT_EARLY_LOGIN_MIN": "15",
      "PWD_MIN_LENGTH": "8",
      "ADVANCE_FLAG": "N",
      "ADVANCE_HOURS": "24",
      "CHECKIN_PRINT_FLAG": "Y",
      "PRINT_COPIES": "1"
    },
    "counters": {
      "reset": "Y",
      "period_key": "FY2026-27",
      "receipt_last_no": 0,
      "bill_last_no": 0
    },
    "server_time": "2026-11-18 07:16:48"
  },
  "requestId": "e615cd63-4ba1-4a58-9138-83dd8476f328"
}
```

**Errors:** `400` validation · `401` wrong user/password · `403 FORBIDDEN` blocked user / inactive customer · `403 SUBSCRIPTION_EXPIRED` · `409 SESSION_ACTIVE` · `409 USER_LIMIT_REACHED` · `426 UPDATE_REQUIRED` · `429`

Super admin login (no customer):
Response `200`:
```json
{
  "success": true,
  "message": "Logged in",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiIs…(JWT)",
    "refreshToken": "eyJhbGciOiJIUzI1NiIs…(JWT)",
    "user": {
      "id": 1,
      "customer_id": null,
      "user_id": "superadmin",
      "user_name": "Synergic Super Admin",
      "user_code": null,
      "user_type": "S",
      "allow_flag": "Y",
      "must_change_pwd": "N"
    },
    "customer": null,
    "masters": {
      "id_proofs": [
        {
          "id_proof_id": 1,
          "id_proof_name": "Aadhaar Card",
          "mask_flag": "Y",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "system",
          "created_at": "2026-11-18 07:16:37",
          "updated_by": null,
          "updated_at": null
        },
        {
          "id_proof_id": 2,
          "id_proof_name": "Driving Licence",
          "mask_flag": "N",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "system",
          "created_at": "2026-11-18 07:16:37",
          "updated_by": null,
          "updated_at": null
        },
        "… 4 more item(s) of the same shape"
      ],
      "gst": [
        {
          "gst_id": 1,
          "cgst_pct": "9.00",
          "sgst_pct": "9.00",
          "igst_pct": "0.00",
          "effective_from": "2017-07-01",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "system",
          "created_at": "2026-11-18 07:16:37",
          "updated_by": null,
          "updated_at": null
        }
      ],
      "rates": [],
      "shifts": [],
      "shift_assignments": [],
      "header_footer": [],
      "users": []
    },
    "settings": {},
    "counters": null,
    "server_time": "2026-11-18 07:16:46"
  },
  "requestId": "2d12805f-d4ed-4c30-ba31-4662bdfb51a5"
}
```

`409 SESSION_ACTIVE` (same user, another device):
Response `409`:
```json
{
  "success": false,
  "message": "User is already logged in on another device",
  "code": "SESSION_ACTIVE",
  "data": null,
  "errors": {
    "session_started_at": "2026-11-18 07:16:48",
    "device_id": "******H-01"
  },
  "requestId": "afc22150-10c9-4153-8387-f065d5872b28"
}
```

`401` wrong password:
Response `401`:
```json
{
  "success": false,
  "message": "Invalid user id or password",
  "code": "UNAUTHORIZED",
  "data": null,
  "requestId": "94c0c1a8-f6c6-49b2-a006-640a7b7953dd"
}
```

`400 VALIDATION_ERROR` (missing fields):
Response `400`:
```json
{
  "success": false,
  "message": "Validation failed",
  "code": "VALIDATION_ERROR",
  "data": null,
  "errors": [
    {
      "location": "body",
      "path": "password",
      "message": "Invalid input: expected string, received undefined"
    },
    {
      "location": "body",
      "path": "device_id",
      "message": "Invalid input: expected string, received undefined"
    }
  ],
  "requestId": "df33328c-91ed-4460-9b36-55a32c8b5d99"
}
```

`426 UPDATE_REQUIRED` (versionCode 9, release 10 is mandatory):
Request body:
```json
{
  "user_id": "howrah.op1",
  "password": "Counter@1234",
  "device_id": "POS-HWH-01",
  "app_version": "0.9.0",
  "version_code": 9
}
```

Response `426`:
```json
{
  "success": false,
  "message": "This app version is no longer supported; update",
  "code": "UPDATE_REQUIRED",
  "data": null,
  "errors": {
    "current": 9,
    "latest": {
      "id": 1,
      "version_code": 10,
      "version_name": "1.0.0",
      "apk_url": "/downloads/cloakroom-1.0.0.apk",
      "mandatory_flag": "Y",
      "release_notes": "First release",
      "created_at": "2026-11-18 07:16:49"
    }
  },
  "requestId": "1b3f0f77-4546-4f1b-8c4e-cf2467ff706f"
}
```

#### `POST /api/v1/auth/change-password` — change own password

**Auth / roles:** any logged-in user (also while must_change_pwd = Y)

Clears `must_change_pwd`. The current session and token stay valid. Rate limit 10/min per user.

| Body field | Rules |
|---|---|
| `current_password` | required |
| `new_password` | 8–128 chars, at least one letter and one digit, different from the current one |

Request body:
```json
{
  "current_password": "Admin@2026",
  "new_password": "Admin@2027"
}
```

Response `200`:
```json
{
  "success": true,
  "message": "Password changed",
  "data": {
    "must_change_pwd": "N"
  },
  "requestId": "6b8aaee4-c1e1-4995-bf1a-e617e76ec1e7"
}
```

**Errors:** `400 VALIDATION_ERROR` (`current_password` wrong, weak or unchanged new password) · `401` · `429`

Any other call while `must_change_pwd = Y`:
Response `403`:
```json
{
  "success": false,
  "message": "Change your password first (POST /api/v1/auth/change-password)",
  "code": "PASSWORD_CHANGE_REQUIRED",
  "data": null,
  "requestId": "c5c8e3f1-16e8-4fe1-ad41-9a27fca9dde6"
}
```

Wrong current password:
Response `400`:
```json
{
  "success": false,
  "message": "Validation failed",
  "code": "VALIDATION_ERROR",
  "data": null,
  "errors": [
    {
      "path": "current_password",
      "message": "is wrong"
    }
  ],
  "requestId": "09ffbc94-1864-4420-a9e0-f8b93bb26cb4"
}
```

#### `POST /api/v1/auth/refresh` — rotate tokens

**Auth / roles:** public (send the refresh token in the body)

| Body field | Rules |
|---|---|
| `refreshToken` | required |

Request body:
```json
{
  "refreshToken": "eyJhbGciOiJIUzI1NiIs…(JWT)"
}
```

Response `200`:
```json
{
  "success": true,
  "message": "Token refreshed",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiIs…(JWT)",
    "refreshToken": "eyJhbGciOiJIUzI1NiIs…(JWT)",
    "server_time": "2026-11-18 07:16:48"
  },
  "requestId": "44a1c0d5-d750-4741-b963-c9408250f0db"
}
```

**Errors:** `401 UNAUTHORIZED` invalid · `401 SESSION_ENDED` session over · `401 TOKEN_REUSED` (session revoked) · `403` blocked / `SUBSCRIPTION_EXPIRED`

After a refresh the previous access token is rejected:
Response `401`:
```json
{
  "success": false,
  "message": "Session was renewed or replaced; log in again",
  "code": "SESSION_ENDED",
  "data": null,
  "requestId": "0efab70e-ebb8-49e7-9a03-316ee36af931"
}
```

`401 TOKEN_EXPIRED` (call refresh):
Response `401`:
```json
{
  "success": false,
  "message": "Token expired",
  "code": "TOKEN_EXPIRED",
  "data": null,
  "requestId": "60b635a2-d146-4a59-bd85-830d590d18a4"
}
```

#### `GET /api/v1/auth/me` — current user & session

**Auth / roles:** any logged-in user

Response `200`:
```json
{
  "success": true,
  "message": "OK",
  "data": {
    "user": {
      "id": 3,
      "customer_id": 1,
      "user_id": "howrah.op1",
      "user_name": "Ravi Kumar Singh",
      "user_code": "U03",
      "user_type": "O",
      "allow_flag": "Y",
      "must_change_pwd": "Y",
      "device_id": "POS-HWH-01",
      "session_started_at": "2026-11-18 07:16:48",
      "last_login": "2026-11-18 07:16:48"
    },
    "customer": {
      "customer_id": 1,
      "customer_name": "Howrah Cloak Room",
      "customer_addr": "Howrah Station, Platform 1, Howrah",
      "state": "West Bengal",
      "pin_code": "711101",
      "mobile_no": "9830012345",
      "email": "ops@howrahcloak.in",
      "gst_no": "19AABCS1234C1Z5",
      "upi_id": "howrah.cloak@oksbi",
      "renewal_dt": "2027-11-17",
      "user_limit": 5,
      "active_flag": "Y"
    },
    "session_id": "283898e5-7f83-4cb0-956e-deab6b2c6be7",
    "server_time": "2026-11-18 07:16:48"
  },
  "requestId": "1dadbba7-c6af-4509-aa5d-49fd3b8b5641"
}
```

#### `POST /api/v1/auth/logout` — log out

**Auth / roles:** any logged-in user (also allowed after force-logout)

Response `200`:
```json
{
  "success": true,
  "message": "Logged out",
  "data": null,
  "requestId": "b6c7bfe1-5f4e-4c5e-b1b5-b20aceab7cd4"
}
```

`401 SESSION_ENDED` (e.g. user was blocked / force-logged-out):
Response `401`:
```json
{
  "success": false,
  "message": "Logged out by admin; sync pending data and log out",
  "code": "SESSION_ENDED",
  "data": null,
  "requestId": "fb7ddca7-3e8d-4251-9236-787d3c738f97"
}
```

---

## 4. Public endpoints

#### `GET /api/v1/health` — liveness

**Auth / roles:** public

Response `200`:
```json
{
  "success": true,
  "message": "OK",
  "data": {
    "status": "ok",
    "version": "0.1.0",
    "uptime_sec": 8,
    "server_time": "2026-11-18 07:16:46"
  },
  "requestId": "ce60a55b-a91e-4e59-b42f-af862f8e13a1"
}
```

#### `GET /api/v1/ready` — readiness (DB reachable)

**Auth / roles:** public

Response `200`:
```json
{
  "success": true,
  "message": "OK",
  "data": {
    "status": "ready",
    "db": "ok",
    "server_time": "2026-11-18 07:16:46"
  },
  "requestId": "5776226e-c22f-43ed-a644-f1361ec8cd03"
}
```

**Errors:** `503 SERVICE_UNAVAILABLE`

#### `GET /api/v1/app/version` — update check (call before login)

**Auth / roles:** public

If `mandatory` is true the app must update before use; download from `apk_url` (absolute `https://…` or a path under the API host such as `/downloads/cloakroom-1.0.0.apk`).

| Query param | Meaning |
|---|---|
| `current` | installed versionCode (optional) → enables `update_available` / `mandatory` |

Response `200`:
```json
{
  "success": true,
  "message": "OK",
  "data": {
    "latest": {
      "id": 1,
      "version_code": 10,
      "version_name": "1.0.0",
      "apk_url": "/downloads/cloakroom-1.0.0.apk",
      "mandatory_flag": "Y",
      "release_notes": "First release",
      "created_at": "2026-11-18 07:16:49"
    },
    "update_available": true,
    "mandatory": true
  },
  "requestId": "c7820b2f-fdf6-4c92-b92d-6cb8e2c541f6"
}
```

`GET /downloads/<file>.apk` — public APK download (`application/vnd.android.package-archive`, cached 1 day).

---

## 5. Customer admin (A) and super admin (S) screens

### Users

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/users` | list (query: page, limit, sort = id/user_id/user_name/user_code/user_type/created_at, search on user_id/user_name/user_code, `user_type`, `allow_flag`, `customer_id` (S only)) |
| GET | `/api/v1/users/:id` | one user |
| POST | `/api/v1/users` | create |
| PATCH | `/api/v1/users/:id` | update name / code / type |
| DELETE | `/api/v1/users/:id` | soft delete (also force-logs the user out) |
| POST | `/api/v1/users/:id/force-logout` | end the user’s session (device may still push, then logout) |
| POST | `/api/v1/users/:id/reset-password` | new password (also force-logs out) |
| PATCH | `/api/v1/users/:id/allow` | block (`N`, force-logs out) / allow (`Y`) |

Roles: A (own customer) and S. An admin cannot block or delete their own account (400).

#### `POST /api/v1/users` — create user

**Auth / roles:** A, S

Allowed users (allow_flag Y) of a customer may not exceed its `user_limit` → `409 USER_LIMIT_REACHED`.

| Body field | Rules |
|---|---|
| `user_id` | login id, 3–100 chars `[A-Za-z0-9._@-]`, unique globally |
| `user_name` | 1–100 chars |
| `user_code` | receipt/bill prefix (e.g. `U03`), 1–10 letters/digits, upper-cased, unique per customer; required for A/O, absent for S |
| `password` | 8–128 chars, at least one letter and one digit |
| `user_type` | `A` or `O` (`S` only by a super admin) |
| `allow_flag` | `Y` (default) / `N` |
| `customer_id` | super admin only (required for A/O users) |

Request body:
```json
{
  "user_id": "howrah.op1",
  "user_name": "Ravi Kumar",
  "user_code": "U03",
  "password": "Counter@123",
  "user_type": "O"
}
```

Response `201`:
```json
{
  "success": true,
  "message": "Created",
  "data": {
    "id": 3,
    "customer_id": 1,
    "user_id": "howrah.op1",
    "user_name": "Ravi Kumar",
    "user_code": "U03",
    "user_type": "O",
    "allow_flag": "Y",
    "must_change_pwd": "Y",
    "device_id": null,
    "session_started_at": null,
    "last_login": null,
    "created_by": "howrah.admin",
    "created_at": "2026-11-18 07:16:47",
    "updated_by": "howrah.admin",
    "updated_at": "2026-11-18 07:16:47"
  },
  "requestId": "5f2a90e6-6886-4c9b-81cf-688e5d23991b"
}
```

**Errors:** `400` · `403` (A creating S) · `409 CONFLICT` duplicate user_id / user_code · `409 USER_LIMIT_REACHED`

Duplicate `user_code`:
Response `409`:
```json
{
  "success": false,
  "message": "Duplicate record",
  "code": "CONFLICT",
  "data": null,
  "errors": {
    "key": "md_user.uq_user_code"
  },
  "requestId": "40771a63-1421-4869-af90-ff8b6e235581"
}
```

List:
Response `200`:
```json
{
  "success": true,
  "message": "OK",
  "data": [
    {
      "id": 4,
      "customer_id": 1,
      "user_id": "howrah.op2",
      "user_name": "Anita Das",
      "user_code": "U04",
      "user_type": "O",
      "allow_flag": "Y",
      "must_change_pwd": "Y",
      "device_id": null,
      "session_started_at": null,
      "last_login": null,
      "created_by": "howrah.admin",
      "created_at": "2026-11-18 07:16:47",
      "updated_by": "howrah.admin",
      "updated_at": "2026-11-18 07:16:47"
    },
    {
      "id": 3,
      "customer_id": 1,
      "user_id": "howrah.op1",
      "user_name": "Ravi Kumar",
      "user_code": "U03",
      "user_type": "O",
      "allow_flag": "Y",
      "must_change_pwd": "Y",
      "device_id": null,
      "session_started_at": null,
      "last_login": null,
      "created_by": "howrah.admin",
      "created_at": "2026-11-18 07:16:47",
      "updated_by": "howrah.admin",
      "updated_at": "2026-11-18 07:16:47"
    }
  ],
  "meta": {
    "page": 1,
    "limit": 50,
    "total": 2
  },
  "requestId": "a0bc1377-4df6-4208-bf07-692ddf5d572c"
}
```

Update (`user_name`, `user_code`, `user_type` A/O — all optional):
Request body:
```json
{
  "user_name": "Ravi Kumar Singh"
}
```

Response `200`:
```json
{
  "success": true,
  "message": "Updated",
  "data": {
    "id": 3,
    "customer_id": 1,
    "user_id": "howrah.op1",
    "user_name": "Ravi Kumar Singh",
    "user_code": "U03",
    "user_type": "O",
    "allow_flag": "Y",
    "must_change_pwd": "Y",
    "device_id": null,
    "session_started_at": null,
    "last_login": null,
    "created_by": "howrah.admin",
    "created_at": "2026-11-18 07:16:47",
    "updated_by": "howrah.admin",
    "updated_at": "2026-11-18 07:16:47"
  },
  "requestId": "406103c6-f673-441b-a5bb-6fef98b5e020"
}
```

Block / allow (`{ "allow_flag": "N" | "Y" }`):
Request body:
```json
{
  "allow_flag": "N"
}
```

Response `200`:
```json
{
  "success": true,
  "message": "Updated",
  "data": {
    "id": 4,
    "customer_id": 1,
    "user_id": "howrah.op2",
    "user_name": "Anita Das",
    "user_code": "U04",
    "user_type": "O",
    "allow_flag": "N",
    "must_change_pwd": "N",
    "device_id": null,
    "session_started_at": null,
    "last_login": "2026-11-18 07:16:48",
    "created_by": "howrah.admin",
    "created_at": "2026-11-18 07:16:47",
    "updated_by": "howrah.admin",
    "updated_at": "2026-11-18 07:16:49"
  },
  "requestId": "1c87d33c-b47d-46b6-ab95-546adf469c88"
}
```

Reset password (`{ "password": "…" }`) → number of sessions ended:
Request body:
```json
{
  "password": "NewPass@789"
}
```

Response `200`:
```json
{
  "success": true,
  "message": "Password reset",
  "data": {
    "sessions_ended": 0
  },
  "requestId": "a6f27ca5-4ed4-4303-8e36-214631e8d5dc"
}
```

Force logout:
Response `200`:
```json
{
  "success": true,
  "message": "User logged out",
  "data": {
    "sessions_ended": 1
  },
  "requestId": "cb4617ea-fdbd-4d48-83f2-d8dcc1643d3e"
}
```

Delete:
Response `200`:
```json
{
  "success": true,
  "message": "Deleted",
  "data": {
    "id": 6
  },
  "requestId": "7bb908c1-34d7-4a6b-9088-e14f68501ae1"
}
```

### Rates (tariff per bag)

| Method | Path | Purpose | Roles |
|---|---|---|---|
| GET | `/api/v1/rates` | list (paginated) | A, S |
| GET | `/api/v1/rates/:id` | one row | A, S |
| POST | `/api/v1/rates` | create → `201` | A, S |
| PATCH | `/api/v1/rates/:id` | partial update | A, S |
| DELETE | `/api/v1/rates/:id` | soft delete → `{ "rate_id": id }` | A, S |

Versioned by `effective_from`: the bill uses the latest rate with `effective_from ≤ receipt_dt`. One row per date per customer (`409` on duplicates). A new customer gets 0–24 h ₹50 + 24–48 h ₹20 automatically. `rate_slabs` is the tariff (§7); send it, or the older two-rate fields (then the server builds the slabs). The server keeps both forms in step: `block_hours` = first slab length, `first_block_rate` / `next_block_rate` = first / second slab rate.

#### `POST /api/v1/rates` — create

**Auth / roles:** A, S

| Body field | Rules |
|---|---|
| `rate_slabs` | array of `{from_hour, to_hour, rate}` (1–20): first from 0, each starts where the previous ends, hours whole numbers ≤ 8760, rate money per bag |
| `block_hours, first_block_rate, next_block_rate` | older two-rate form, only without `rate_slabs` (both rates required, block_hours default 24) |
| `effective_from` | `YYYY-MM-DD` |

Request body:
```json
{
  "effective_from": "2027-01-17",
  "rate_slabs": [
    {
      "from_hour": 0,
      "to_hour": 24,
      "rate": "30.00"
    },
    {
      "from_hour": 24,
      "to_hour": 48,
      "rate": "10.00"
    }
  ]
}
```

Response `201`:
```json
{
  "success": true,
  "message": "Created",
  "data": {
    "rate_id": 2,
    "customer_id": 1,
    "block_hours": 24,
    "first_block_rate": "30.00",
    "next_block_rate": "10.00",
    "rate_slabs": [
      {
        "rate": "30.00",
        "to_hour": 24,
        "from_hour": 0
      },
      {
        "rate": "10.00",
        "to_hour": 48,
        "from_hour": 24
      }
    ],
    "effective_from": "2027-01-17",
    "is_deleted": "N",
    "sync_flag": "N",
    "created_by": "howrah.admin",
    "created_at": "2026-11-18 07:16:47",
    "updated_by": "howrah.admin",
    "updated_at": "2026-11-18 07:16:47"
  },
  "requestId": "387bfd6c-36ea-49d0-8a21-a4a713731c99"
}
```

**Errors:** `400` validation · `401` · `403` role · `409` duplicate

#### `GET /api/v1/rates` — list

**Auth / roles:** A, S

| Query param | Meaning |
|---|---|
| `page` | default 1 |
| `limit` | default 50, max 200 |
| `sort` | one of: effective_from, rate_id (default effective_from desc) |
| `search` | — |

Response `200`:
```json
{
  "success": true,
  "message": "OK",
  "data": [
    {
      "rate_id": 1,
      "customer_id": 1,
      "block_hours": 24,
      "first_block_rate": "50.00",
      "next_block_rate": "20.00",
      "rate_slabs": [
        {
          "rate": "50.00",
          "to_hour": 24,
          "from_hour": 0
        },
        {
          "rate": "20.00",
          "to_hour": 48,
          "from_hour": 24
        }
      ],
      "effective_from": "2026-11-18",
      "is_deleted": "N",
      "sync_flag": "N",
      "created_by": "superadmin",
      "created_at": "2026-11-18 07:16:46",
      "updated_by": "superadmin",
      "updated_at": "2026-11-18 07:16:46"
    }
  ],
  "meta": {
    "page": 1,
    "limit": 50,
    "total": 1
  },
  "requestId": "c6df743c-9768-41ed-acec-df8dfc965357"
}
```

`GET /api/v1/rates/:id` returns `data` = one row of the same shape (or `404`).

#### `PATCH /api/v1/rates/:id` — update

**Auth / roles:** A, S

Any of the create fields.

Request body:
```json
{
  "effective_from": "2026-01-01"
}
```

Response `200`:
```json
{
  "success": true,
  "message": "Updated",
  "data": {
    "rate_id": 1,
    "customer_id": 1,
    "block_hours": 24,
    "first_block_rate": "50.00",
    "next_block_rate": "20.00",
    "rate_slabs": [
      {
        "rate": "50.00",
        "to_hour": 24,
        "from_hour": 0
      },
      {
        "rate": "20.00",
        "to_hour": 48,
        "from_hour": 24
      }
    ],
    "effective_from": "2026-01-01",
    "is_deleted": "N",
    "sync_flag": "N",
    "created_by": "superadmin",
    "created_at": "2026-11-18 07:16:46",
    "updated_by": "howrah.admin",
    "updated_at": "2026-11-18 07:16:47"
  },
  "requestId": "c5129abf-11fd-4215-87fb-cba498b9e5e1"
}
```

**Errors:** `400` · `401` · `403` · `404` · `409`

#### `DELETE /api/v1/rates/:id` — soft delete

**Auth / roles:** A, S

Response `200`:
```json
{
  "success": true,
  "message": "Deleted",
  "data": {
    "rate_id": 2
  },
  "requestId": "b13331ab-6739-4341-be05-405b3e15af26"
}
```

### Shifts

| Method | Path | Purpose | Roles |
|---|---|---|---|
| GET | `/api/v1/shifts` | list (paginated) | A, S |
| GET | `/api/v1/shifts/:id` | one row | A, S |
| POST | `/api/v1/shifts` | create → `201` | A, S |
| PATCH | `/api/v1/shifts/:id` | partial update | A, S |
| DELETE | `/api/v1/shifts/:id` | soft delete → `{ "shift_id": id }` | A, S |

Times are local (IST) `HH:mm` or `HH:mm:ss`; `to_time` may be earlier than `from_time` (crosses midnight) but not equal.

#### `POST /api/v1/shifts` — create

**Auth / roles:** A, S

| Body field | Rules |
|---|---|
| `shift_name` | 1–50 chars |
| `from_time` | `HH:mm` |
| `to_time` | `HH:mm`, ≠ from_time |

Request body:
```json
{
  "shift_name": "Morning",
  "from_time": "06:00",
  "to_time": "14:00"
}
```

Response `201`:
```json
{
  "success": true,
  "message": "Created",
  "data": {
    "shift_id": 1,
    "customer_id": 1,
    "shift_name": "Morning",
    "from_time": "06:00:00",
    "to_time": "14:00:00",
    "is_deleted": "N",
    "sync_flag": "N",
    "created_by": "howrah.admin",
    "created_at": "2026-11-18 07:16:47",
    "updated_by": "howrah.admin",
    "updated_at": "2026-11-18 07:16:47"
  },
  "requestId": "90e01e64-938f-4ed2-bc75-bb25f0bb59ac"
}
```

**Errors:** `400` validation · `401` · `403` role · `409` duplicate

#### `GET /api/v1/shifts` — list

**Auth / roles:** A, S

| Query param | Meaning |
|---|---|
| `page` | default 1 |
| `limit` | default 50, max 200 |
| `sort` | one of: shift_name, from_time, shift_id |
| `search` | contains-match on shift_name |

Response `200`:
```json
{
  "success": true,
  "message": "OK",
  "data": [
    {
      "shift_id": 1,
      "customer_id": 1,
      "shift_name": "Morning",
      "from_time": "06:00:00",
      "to_time": "14:00:00",
      "is_deleted": "N",
      "sync_flag": "N",
      "created_by": "howrah.admin",
      "created_at": "2026-11-18 07:16:47",
      "updated_by": "howrah.admin",
      "updated_at": "2026-11-18 07:16:47"
    },
    {
      "shift_id": 2,
      "customer_id": 1,
      "shift_name": "Night",
      "from_time": "22:00:00",
      "to_time": "06:00:00",
      "is_deleted": "N",
      "sync_flag": "N",
      "created_by": "howrah.admin",
      "created_at": "2026-11-18 07:16:47",
      "updated_by": "howrah.admin",
      "updated_at": "2026-11-18 07:16:47"
    }
  ],
  "meta": {
    "page": 1,
    "limit": 50,
    "total": 2
  },
  "requestId": "367e8092-9170-456b-9349-54014ad183b9"
}
```

`GET /api/v1/shifts/:id` returns `data` = one row of the same shape (or `404`).

#### `PATCH /api/v1/shifts/:id` — update

**Auth / roles:** A, S

Any of the create fields.

Request body:
```json
{
  "to_time": "14:30"
}
```

Response `200`:
```json
{
  "success": true,
  "message": "Updated",
  "data": {
    "shift_id": 1,
    "customer_id": 1,
    "shift_name": "Morning",
    "from_time": "06:00:00",
    "to_time": "14:30:00",
    "is_deleted": "N",
    "sync_flag": "N",
    "created_by": "howrah.admin",
    "created_at": "2026-11-18 07:16:47",
    "updated_by": "howrah.admin",
    "updated_at": "2026-11-18 07:16:47"
  },
  "requestId": "4befc189-9715-4bc8-9aa0-b9e70549010a"
}
```

**Errors:** `400` · `401` · `403` · `404` · `409`

### Shift assignments

| Method | Path | Purpose | Roles |
|---|---|---|---|
| GET | `/api/v1/shift-assignments` | list (paginated) | A, S |
| GET | `/api/v1/shift-assignments/:id` | one row | A, S |
| POST | `/api/v1/shift-assignments` | create → `201` | A, S |
| PATCH | `/api/v1/shift-assignments/:id` | partial update | A, S |
| DELETE | `/api/v1/shift-assignments/:id` | soft delete → `{ "id": id }` | A, S |

Assigns a shift to a user from a date. User and shift must belong to the same customer (else `400`).

#### `POST /api/v1/shift-assignments` — create

**Auth / roles:** A, S

| Body field | Rules |
|---|---|
| `user_id` | md_user.id (A/O user of the customer) |
| `shift_id` | shift of the customer |
| `effective_from` | `YYYY-MM-DD` |

Request body:
```json
{
  "user_id": 3,
  "shift_id": 1,
  "effective_from": "2026-11-18"
}
```

Response `201`:
```json
{
  "success": true,
  "message": "Created",
  "data": {
    "id": 1,
    "customer_id": 1,
    "user_id": 3,
    "shift_id": 1,
    "effective_from": "2026-11-18",
    "is_deleted": "N",
    "sync_flag": "N",
    "created_by": "howrah.admin",
    "created_at": "2026-11-18 07:16:47",
    "updated_by": "howrah.admin",
    "updated_at": "2026-11-18 07:16:47"
  },
  "requestId": "25246c19-9da9-42d1-8b69-7c3c2a861a50"
}
```

**Errors:** `400` validation · `401` · `403` role · `409` duplicate

#### `GET /api/v1/shift-assignments` — list

**Auth / roles:** A, S

| Query param | Meaning |
|---|---|
| `page` | default 1 |
| `limit` | default 50, max 200 |
| `sort` | one of: effective_from, user_id, id |
| `search` | — |
| `user_id` | filter |
| `shift_id` | filter |

Response `200`:
```json
{
  "success": true,
  "message": "OK",
  "data": [
    {
      "id": 1,
      "customer_id": 1,
      "user_id": 3,
      "shift_id": 1,
      "effective_from": "2026-11-18",
      "is_deleted": "N",
      "sync_flag": "N",
      "created_by": "howrah.admin",
      "created_at": "2026-11-18 07:16:47",
      "updated_by": "howrah.admin",
      "updated_at": "2026-11-18 07:16:47"
    }
  ],
  "meta": {
    "page": 1,
    "limit": 50,
    "total": 1
  },
  "requestId": "01ea04d9-4a19-4200-8077-7e5ae21dc1d4"
}
```

`GET /api/v1/shift-assignments/:id` returns `data` = one row of the same shape (or `404`).

#### `PATCH /api/v1/shift-assignments/:id` — update

**Auth / roles:** A, S

`shift_id` and/or `effective_from`.

Request body:
```json
{
  "effective_from": "2026-11-19"
}
```

Response `200`:
```json
{
  "success": true,
  "message": "Updated",
  "data": {
    "id": 1,
    "customer_id": 1,
    "user_id": 3,
    "shift_id": 1,
    "effective_from": "2026-11-19",
    "is_deleted": "N",
    "sync_flag": "N",
    "created_by": "howrah.admin",
    "created_at": "2026-11-18 07:16:47",
    "updated_by": "howrah.admin",
    "updated_at": "2026-11-18 07:16:47"
  },
  "requestId": "f37ad6b9-fad7-4964-af52-e6b187e7b73e"
}
```

**Errors:** `400` · `401` · `403` · `404` · `409`

### Receipt header / footer lines and logo

| Method | Path | Purpose | Roles |
|---|---|---|---|
| GET | `/api/v1/header-footer` | list (paginated) | A, S |
| GET | `/api/v1/header-footer/:id` | one row | A, S |
| POST | `/api/v1/header-footer` | create → `201` | A, S |
| PATCH | `/api/v1/header-footer/:id` | partial update | A, S |
| DELETE | `/api/v1/header-footer/:id` | soft delete → `{ "id": id }` | A, S |

Printed lines: `H` header, `F` footer (JSON create). The logo row (`L`) is created only by the upload endpoint below; `image` is returned as base64 PNG.

#### `POST /api/v1/header-footer` — create

**Auth / roles:** A, S

| Body field | Rules |
|---|---|
| `line_type` | `H` or `F` |
| `line_order` | integer 0–999, default 0 |
| `narration` | 1–100 chars |
| `in_flag` | print on check-in receipt, default `Y` |
| `out_flag` | print on check-out bill, default `Y` |
| `report_flag` | print on reports, default `N` |

Request body:
```json
{
  "line_type": "H",
  "line_order": 1,
  "narration": "HOWRAH CLOAK ROOM"
}
```

Response `201`:
```json
{
  "success": true,
  "message": "Created",
  "data": {
    "id": 1,
    "customer_id": 1,
    "line_type": "H",
    "line_order": 1,
    "narration": "HOWRAH CLOAK ROOM",
    "image": null,
    "in_flag": "Y",
    "out_flag": "Y",
    "report_flag": "N",
    "is_deleted": "N",
    "sync_flag": "N",
    "created_by": "howrah.admin",
    "created_at": "2026-11-18 07:16:47",
    "updated_by": "howrah.admin",
    "updated_at": "2026-11-18 07:16:47"
  },
  "requestId": "21468dc7-2a2d-4e55-94fb-c05b746bcb3e"
}
```

**Errors:** `400` validation · `401` · `403` role · `409` duplicate

#### `GET /api/v1/header-footer` — list

**Auth / roles:** A, S

| Query param | Meaning |
|---|---|
| `page` | default 1 |
| `limit` | default 50, max 200 |
| `sort` | one of: line_type, line_order, id |
| `search` | — |
| `line_type` | `H` / `F` / `L` |

Response `200`:
```json
{
  "success": true,
  "message": "OK",
  "data": [
    {
      "id": 1,
      "customer_id": 1,
      "line_type": "H",
      "line_order": 1,
      "narration": "HOWRAH CLOAK ROOM",
      "image": null,
      "in_flag": "Y",
      "out_flag": "Y",
      "report_flag": "N",
      "is_deleted": "N",
      "sync_flag": "N",
      "created_by": "howrah.admin",
      "created_at": "2026-11-18 07:16:47",
      "updated_by": "howrah.admin",
      "updated_at": "2026-11-18 07:16:47"
    },
    {
      "id": 2,
      "customer_id": 1,
      "line_type": "F",
      "line_order": 1,
      "narration": "Thank you. Keep this receipt safe.",
      "image": null,
      "in_flag": "Y",
      "out_flag": "Y",
      "report_flag": "Y",
      "is_deleted": "N",
      "sync_flag": "N",
      "created_by": "howrah.admin",
      "created_at": "2026-11-18 07:16:47",
      "updated_by": "howrah.admin",
      "updated_at": "2026-11-18 07:16:47"
    },
    "… 1 more item(s) of the same shape"
  ],
  "meta": {
    "page": 1,
    "limit": 50,
    "total": 3
  },
  "requestId": "1c1eba5d-b2bd-4ce2-8270-602d5f4f9fd4"
}
```

`GET /api/v1/header-footer/:id` returns `data` = one row of the same shape (or `404`).

#### `PATCH /api/v1/header-footer/:id` — update

**Auth / roles:** A, S

`line_order`, `narration`, `in_flag`, `out_flag`, `report_flag`.

Request body:
```json
{
  "report_flag": "Y"
}
```

Response `200`:
```json
{
  "success": true,
  "message": "Updated",
  "data": {
    "id": 2,
    "customer_id": 1,
    "line_type": "F",
    "line_order": 1,
    "narration": "Thank you. Keep this receipt safe.",
    "image": null,
    "in_flag": "Y",
    "out_flag": "Y",
    "report_flag": "Y",
    "is_deleted": "N",
    "sync_flag": "N",
    "created_by": "howrah.admin",
    "created_at": "2026-11-18 07:16:47",
    "updated_by": "howrah.admin",
    "updated_at": "2026-11-18 07:16:47"
  },
  "requestId": "12ff92a2-fbfd-4503-a463-e767989332ce"
}
```

**Errors:** `400` · `401` · `403` · `404` · `409`

#### `POST /api/v1/header-footer/logo` — upload / replace logo

**Auth / roles:** A, S · **multipart/form-data**: field `logo` = PNG file ≤ 200 KB (bytes must be PNG);
optional text fields `line_order`, `in_flag`, `out_flag`, `report_flag` (`Y`/`N`/`true`/`false`),
`customer_id` (S only). Returns `201` when created, `200` when replacing the existing logo.

Response `201`:
```json
{
  "success": true,
  "message": "Logo uploaded",
  "data": {
    "id": 3,
    "customer_id": 1,
    "line_type": "L",
    "line_order": 0,
    "narration": null,
    "image": "iVBORw0KGgoAAAAN…(base64)",
    "in_flag": "Y",
    "out_flag": "Y",
    "report_flag": "N",
    "is_deleted": "N",
    "sync_flag": "N",
    "created_by": "howrah.admin",
    "created_at": "2026-11-18 07:16:47",
    "updated_by": "howrah.admin",
    "updated_at": "2026-11-18 07:16:47"
  },
  "requestId": "2f9b890d-4932-4df3-979e-17bd1d0ad2b5"
}
```

**Errors:** `400` not a PNG / missing file · `413 PAYLOAD_TOO_LARGE` > 200 KB

### Settings

| Method | Path | Purpose | Roles |
|---|---|---|---|
| GET | `/api/v1/settings` | list (paginated) | A, S |
| GET | `/api/v1/settings/:id` | one row | A, S |
| POST | `/api/v1/settings` | create → `201` | A, S |
| PATCH | `/api/v1/settings/:id` | partial update | A, S |
| DELETE | `/api/v1/settings/:id` | soft delete → `{ "setting_id": id }` | A, S |

Key/value settings for the whole customer (`user_id: null`) or one user (override). Keys and allowed values are listed in §8. A new customer starts with the default set. Updating changes only `setting_value`. `REPORT_PWD` is stored hashed and always returned as `********`.

#### `POST /api/v1/settings` — create

**Auth / roles:** A, S

| Body field | Rules |
|---|---|
| `setting_name` | one of the keys in §8 |
| `setting_value` | string or number, validated per key |
| `user_id` | optional md_user.id for a per-user override (default null = customer-wide) |

Request body:
```json
{
  "setting_name": "QR_FLAG",
  "setting_value": "N",
  "user_id": 4
}
```

Response `201`:
```json
{
  "success": true,
  "message": "Created",
  "data": {
    "setting_id": 11,
    "customer_id": 1,
    "user_id": 4,
    "setting_name": "QR_FLAG",
    "setting_value": "N",
    "setting_type": "B",
    "is_deleted": "N",
    "sync_flag": "N",
    "created_by": "howrah.admin",
    "created_at": "2026-11-18 07:16:47",
    "updated_by": "howrah.admin",
    "updated_at": "2026-11-18 07:16:47"
  },
  "requestId": "98f85e08-b9c7-40c7-8f1f-94e74b43e63e"
}
```

Request body:
```json
{
  "setting_name": "REPORT_PWD",
  "setting_value": "4321"
}
```

Response `201`:
```json
{
  "success": true,
  "message": "Created",
  "data": {
    "setting_id": 12,
    "customer_id": 1,
    "user_id": null,
    "setting_name": "REPORT_PWD",
    "setting_value": "********",
    "setting_type": "G",
    "is_deleted": "N",
    "sync_flag": "N",
    "created_by": "howrah.admin",
    "created_at": "2026-11-18 07:16:47",
    "updated_by": "howrah.admin",
    "updated_at": "2026-11-18 07:16:47"
  },
  "requestId": "fbe9d414-7231-4908-9de4-767c5abb4b97"
}
```

**Errors:** `400` validation · `401` · `403` role · `409` duplicate

#### `GET /api/v1/settings` — list

**Auth / roles:** A, S

| Query param | Meaning |
|---|---|
| `page` | default 1 |
| `limit` | default 50, max 200 |
| `sort` | one of: setting_name, setting_id |
| `search` | — |
| `setting_name` | filter |
| `user_id` | filter |
| `setting_type` | `G`/`B`/`P` |

Response `200`:
```json
{
  "success": true,
  "message": "OK",
  "data": [
    {
      "setting_id": 9,
      "customer_id": 1,
      "user_id": null,
      "setting_name": "DEFAULT_PAY_MODE",
      "setting_value": "C",
      "setting_type": "B",
      "is_deleted": "N",
      "sync_flag": "N",
      "created_by": "superadmin",
      "created_at": "2026-11-18 07:16:46",
      "updated_by": "superadmin",
      "updated_at": "2026-11-18 07:16:46"
    },
    {
      "setting_id": 6,
      "customer_id": 1,
      "user_id": null,
      "setting_name": "GST_FLAG",
      "setting_value": "Y",
      "setting_type": "B",
      "is_deleted": "N",
      "sync_flag": "N",
      "created_by": "superadmin",
      "created_at": "2026-11-18 07:16:46",
      "updated_by": "superadmin",
      "updated_at": "2026-11-18 07:16:46"
    },
    "… 8 more item(s) of the same shape"
  ],
  "meta": {
    "page": 1,
    "limit": 100,
    "total": 10
  },
  "requestId": "c0ff5b0a-d758-4e97-8c5d-2e721a40c58b"
}
```

`GET /api/v1/settings/:id` returns `data` = one row of the same shape (or `404`).

#### `PATCH /api/v1/settings/:id` — update

**Auth / roles:** A, S

`{ "setting_value": "…" }` only (validated against the row’s key).

Request body:
```json
{
  "setting_value": "30"
}
```

Response `200`:
```json
{
  "success": true,
  "message": "Updated",
  "data": {
    "setting_id": 5,
    "customer_id": 1,
    "user_id": null,
    "setting_name": "SYNC_INTERVAL_MIN",
    "setting_value": "30",
    "setting_type": "G",
    "is_deleted": "N",
    "sync_flag": "N",
    "created_by": "superadmin",
    "created_at": "2026-11-18 07:16:46",
    "updated_by": "howrah.admin",
    "updated_at": "2026-11-18 07:16:47"
  },
  "requestId": "4b288fe8-d9c3-44b1-bacd-c229507df9f4"
}
```

**Errors:** `400` · `401` · `403` · `404` · `409`

#### `DELETE /api/v1/settings/:id` — soft delete

**Auth / roles:** A, S

Response `200`:
```json
{
  "success": true,
  "message": "Deleted",
  "data": {
    "setting_id": 11
  },
  "requestId": "ed5dd933-8a13-41ed-ba8d-cb6d11009467"
}
```

Invalid value for a key:
Request body:
```json
{
  "setting_name": "SYNC_INTERVAL_MIN",
  "setting_value": "5"
}
```

Response `400`:
```json
{
  "success": false,
  "message": "Validation failed",
  "code": "VALIDATION_ERROR",
  "data": null,
  "errors": [
    {
      "path": "setting_value",
      "message": "SYNC_INTERVAL_MIN: must be between 15 and 1440"
    }
  ],
  "requestId": "3bd65c85-7c47-4932-b9a0-76bf2a776a90"
}
```

### Customer masters as super admin (S)

Applies to `/rates`, `/shifts`, `/shift-assignments`, `/header-footer` (+ `/logo`) and `/settings`.
An S token has no customer, so S picks the customer explicitly. A/O never send `customer_id` (ignored, the token's customer is used).

| Call | S behaviour |
|---|---|
| `GET /<master>?customer_id=<id>` | Lists that customer's rows only. **Without `customer_id` S gets the rows of all customers** (each row carries `customer_id`), so always pass it on a per-customer screen. Combines with the other filters, `page`, `limit`, `sort`. |
| `GET /<master>/:id` | Works for a row of any customer; no `customer_id` needed. |
| `POST /<master>` | `customer_id` is **required in the body** → else `400 VALIDATION_ERROR` (`customer_id: Required for super admin`). References in the body (`user_id`, `shift_id`) must belong to that customer (`400` otherwise). Logo upload: `customer_id` as a multipart text field. |
| `PATCH /<master>/:id` | Row addressed by id; **do not send `customer_id`** — it is not an update field and is silently dropped (a row can't be moved to another customer). |
| `DELETE /<master>/:id` | Row addressed by id; no `customer_id` needed. |

Example — S adds a rate for customer 1:

```http
POST /api/v1/rates
Authorization: Bearer <S access token>

{ "customer_id": 1, "first_block_rate": 60, "next_block_rate": 25, "effective_from": "2027-04-01" }
```

### GST rates (global)

| Method | Path | Purpose | Roles |
|---|---|---|---|
| GET | `/api/v1/gst` | list (paginated) | S, A, O |
| GET | `/api/v1/gst/:id` | one row | S, A, O |
| POST | `/api/v1/gst` | create → `201` | S |
| PATCH | `/api/v1/gst/:id` | partial update | S |
| DELETE | `/api/v1/gst/:id` | soft delete → `{ "gst_id": id }` | S |

Versioned by `effective_from` (bill uses the latest row with `effective_from ≤ bill_dt`). One live row per date.

#### `POST /api/v1/gst` — create

**Auth / roles:** S

| Body field | Rules |
|---|---|
| `cgst_pct` | percent 0–100, default 0 |
| `sgst_pct` | percent 0–100, default 0 |
| `igst_pct` | percent 0–100, default 0 |
| `effective_from` | `YYYY-MM-DD` |

Request body:
```json
{
  "cgst_pct": "9",
  "sgst_pct": "9",
  "igst_pct": "18",
  "effective_from": "2030-04-01"
}
```

Response `201`:
```json
{
  "success": true,
  "message": "Created",
  "data": {
    "gst_id": 2,
    "cgst_pct": "9.00",
    "sgst_pct": "9.00",
    "igst_pct": "18.00",
    "effective_from": "2030-04-01",
    "is_deleted": "N",
    "sync_flag": "N",
    "created_by": "superadmin",
    "created_at": "2026-11-18 07:16:47",
    "updated_by": "superadmin",
    "updated_at": "2026-11-18 07:16:47"
  },
  "requestId": "c45dfd4b-5f08-4293-b873-66c3f913fe4f"
}
```

**Errors:** `400` validation · `401` · `403` role · `409` duplicate

#### `GET /api/v1/gst` — list

**Auth / roles:** S, A, O

| Query param | Meaning |
|---|---|
| `page` | default 1 |
| `limit` | default 50, max 200 |
| `sort` | one of: effective_from, gst_id |
| `search` | — |

Response `200`:
```json
{
  "success": true,
  "message": "OK",
  "data": [
    {
      "gst_id": 1,
      "cgst_pct": "9.00",
      "sgst_pct": "9.00",
      "igst_pct": "0.00",
      "effective_from": "2017-07-01",
      "is_deleted": "N",
      "sync_flag": "N",
      "created_by": "system",
      "created_at": "2026-11-18 07:16:37",
      "updated_by": null,
      "updated_at": null
    }
  ],
  "meta": {
    "page": 1,
    "limit": 50,
    "total": 1
  },
  "requestId": "9da27545-3c4b-40ef-828b-e65bae2df557"
}
```

`GET /api/v1/gst/:id` returns `data` = one row of the same shape (or `404`).

#### `PATCH /api/v1/gst/:id` — update

**Auth / roles:** S

Any of the create fields.

**Errors:** `400` · `401` · `403` · `404` · `409`

#### `DELETE /api/v1/gst/:id` — soft delete

**Auth / roles:** S

Response `200`:
```json
{
  "success": true,
  "message": "Deleted",
  "data": {
    "gst_id": 2
  },
  "requestId": "6241826f-86d1-4a22-9bc4-8bb5ddce2586"
}
```

### ID proof types (global)

| Method | Path | Purpose | Roles |
|---|---|---|---|
| GET | `/api/v1/id-proofs` | list (paginated) | S, A, O |
| GET | `/api/v1/id-proofs/:id` | one row | S, A, O |
| POST | `/api/v1/id-proofs` | create → `201` | S |
| PATCH | `/api/v1/id-proofs/:id` | partial update | S |
| DELETE | `/api/v1/id-proofs/:id` | soft delete → `{ "id_proof_id": id }` | S |

`mask_flag = Y` (Aadhaar) → only the last 4 characters of the ID number may be stored/printed; the server masks them as `XXXXXXXX1234`.

#### `POST /api/v1/id-proofs` — create

**Auth / roles:** S

| Body field | Rules |
|---|---|
| `id_proof_name` | 1–50 chars, unique among live rows |
| `mask_flag` | `Y` / `N` (default) |

Request body:
```json
{
  "id_proof_name": "Senior Citizen Card",
  "mask_flag": "N"
}
```

Response `201`:
```json
{
  "success": true,
  "message": "Created",
  "data": {
    "id_proof_id": 7,
    "id_proof_name": "Senior Citizen Card",
    "mask_flag": "N",
    "is_deleted": "N",
    "sync_flag": "N",
    "created_by": "superadmin",
    "created_at": "2026-11-18 07:16:47",
    "updated_by": "superadmin",
    "updated_at": "2026-11-18 07:16:47"
  },
  "requestId": "bb202775-f5b3-4a06-ab41-a39dcba90449"
}
```

**Errors:** `400` validation · `401` · `403` role · `409` duplicate

#### `GET /api/v1/id-proofs` — list

**Auth / roles:** S, A, O

| Query param | Meaning |
|---|---|
| `page` | default 1 |
| `limit` | default 50, max 200 |
| `sort` | one of: id_proof_name, id_proof_id |
| `search` | contains-match on id_proof_name |

Response `200`:
```json
{
  "success": true,
  "message": "OK",
  "data": [
    {
      "id_proof_id": 1,
      "id_proof_name": "Aadhaar Card",
      "mask_flag": "Y",
      "is_deleted": "N",
      "sync_flag": "N",
      "created_by": "system",
      "created_at": "2026-11-18 07:16:37",
      "updated_by": null,
      "updated_at": null
    },
    {
      "id_proof_id": 2,
      "id_proof_name": "Driving Licence",
      "mask_flag": "N",
      "is_deleted": "N",
      "sync_flag": "N",
      "created_by": "system",
      "created_at": "2026-11-18 07:16:37",
      "updated_by": null,
      "updated_at": null
    },
    "… 4 more item(s) of the same shape"
  ],
  "meta": {
    "page": 1,
    "limit": 50,
    "total": 6
  },
  "requestId": "10b75536-b2f1-45f1-84ae-ac280cb84837"
}
```

`GET /api/v1/id-proofs/:id` returns `data` = one row of the same shape (or `404`).

#### `PATCH /api/v1/id-proofs/:id` — update

**Auth / roles:** S

`id_proof_name`, `mask_flag`.

**Errors:** `400` · `401` · `403` · `404` · `409`

### Own cloak room details (A edits, A and O read)

#### `GET /api/v1/customers/me` — own cloak room details

**Auth / roles:** A, O (S → 403: it has no own customer, use `/customers/:id`)

Same shape as `customer` in the login payload (`GET /auth/me` returns the same object). Use it to refresh the Cloak room screen.

Response `200`:
```json
{
  "success": true,
  "message": "OK",
  "data": {
    "customer_id": 1,
    "customer_name": "Howrah Cloak Room",
    "customer_addr": "Howrah Station, Platform 1",
    "state": "West Bengal",
    "pin_code": "711101",
    "mobile_no": "9830012345",
    "email": "ops@howrahcloak.in",
    "gst_no": "19ABCDE1234F1Z5",
    "upi_id": "howrahcloak@okaxis",
    "renewal_dt": "2027-11-17",
    "user_limit": 5,
    "active_flag": "Y"
  },
  "requestId": "01a3eee3-13ee-4671-9f41-df94c7a76565"
}
```

#### `PATCH /api/v1/customers/me` — station admin edits own cloak room details

**Auth / roles:** A only (O and S → 403)

Partial update: send the changed fields (at least one). Same rules as the super-admin customer update. `user_limit`, `active_flag`, `renewal_dt`, `remarks` and `customer_id` are licence/platform fields and are silently ignored here (a body with only those → 400). Returns the updated customer: store it as the local `md_customer` row. A new `gst_no` is stamped by the server on bills pushed **after** the change; bills already stored keep theirs. Other devices of the customer get the new values at their next login or `GET /auth/me` / `GET /customers/me` (pull does not carry the customer).

| Body field | Rules |
|---|---|
| `customer_name` | 1–155 chars |
| `customer_addr` | 1–200 chars |
| `state` | ≤ 50 chars |
| `pin_code` | 6 digits |
| `mobile_no` | 7–20 chars of digits, `+`, `-`, space |
| `email` | email, ≤ 150 chars |
| `gst_no` | 15-char GSTIN (upper-cased by the server) |
| `upi_id` | `name@bank` (used for the UPI QR) |

Request body:
```json
{
  "customer_addr": "Howrah Station, Platform 1, Howrah",
  "gst_no": "19aabcs1234c1z5",
  "upi_id": "howrah.cloak@oksbi"
}
```

Response `200`:
```json
{
  "success": true,
  "message": "Customer updated",
  "data": {
    "customer_id": 1,
    "customer_name": "Howrah Cloak Room",
    "customer_addr": "Howrah Station, Platform 1, Howrah",
    "state": "West Bengal",
    "pin_code": "711101",
    "mobile_no": "9830012345",
    "email": "ops@howrahcloak.in",
    "gst_no": "19AABCS1234C1Z5",
    "upi_id": "howrah.cloak@oksbi",
    "renewal_dt": "2027-11-17",
    "user_limit": 5,
    "active_flag": "Y"
  },
  "requestId": "c4fe84ae-8267-400e-a277-4a6c165415e6"
}
```

Request body:
```json
{
  "user_limit": 50
}
```

Response `400`:
```json
{
  "success": false,
  "message": "Validation failed",
  "code": "VALIDATION_ERROR",
  "data": null,
  "errors": [
    {
      "location": "body",
      "path": "",
      "message": "Send at least one of customer_name, customer_addr, state, pin_code, mobile_no, email, gst_no, upi_id"
    }
  ],
  "requestId": "e3e857f6-7ebe-4d93-8ee1-823b78bb1bcf"
}
```

**Errors:** 400 `VALIDATION_ERROR` (bad field, or nothing to change), 403 `FORBIDDEN` (not a station admin), 403 `PASSWORD_CHANGE_REQUIRED`.

### Super admin only (S): customers, registrations, payments, app releases

### Customers (stations)

| Method | Path | Purpose | Roles |
|---|---|---|---|
| GET | `/api/v1/customers` | list (paginated) | S |
| GET | `/api/v1/customers/:id` | one row | S |
| POST | `/api/v1/customers` | create → `201` | S |
| PATCH | `/api/v1/customers/:id` | partial update | S |
| DELETE | `/api/v1/customers/:id` | soft delete → `{ "customer_id": id }` | S |

Creating a customer also creates its default settings and default rate. `renewal_dt` is not writable: it is the latest registration `end_date` (login is refused after it).

#### `POST /api/v1/customers` — create

**Auth / roles:** S

| Body field | Rules |
|---|---|
| `customer_name` | 1–155 chars |
| `customer_addr` | 1–200 chars |
| `state` | optional, ≤ 50 |
| `pin_code` | 6 digits |
| `mobile_no` | 7–20 chars of digits, `+`, `-`, space |
| `email` | optional email |
| `gst_no` | optional 15-char GSTIN (upper-cased) |
| `upi_id` | optional `name@bank` (used for the payment QR) |
| `user_limit` | licensed users 1–1000, default 1 |
| `active_flag` | default `Y` |
| `remarks` | optional, ≤ 255 |

Request body:
```json
{
  "customer_name": "Howrah Cloak Room",
  "customer_addr": "Howrah Station, Platform 1",
  "state": "West Bengal",
  "pin_code": "711101",
  "mobile_no": "9830012345",
  "email": "ops@howrahcloak.in",
  "gst_no": "19ABCDE1234F1Z5",
  "upi_id": "howrahcloak@okaxis",
  "user_limit": 5
}
```

Response `201`:
```json
{
  "success": true,
  "message": "Created",
  "data": {
    "customer_id": 1,
    "customer_name": "Howrah Cloak Room",
    "customer_addr": "Howrah Station, Platform 1",
    "state": "West Bengal",
    "pin_code": "711101",
    "mobile_no": "9830012345",
    "email": "ops@howrahcloak.in",
    "renewal_dt": null,
    "gst_no": "19ABCDE1234F1Z5",
    "upi_id": "howrahcloak@okaxis",
    "user_limit": 5,
    "active_flag": "Y",
    "remarks": null,
    "is_deleted": "N",
    "sync_flag": "N",
    "created_by": "superadmin",
    "created_at": "2026-11-18 07:16:46",
    "updated_by": "superadmin",
    "updated_at": "2026-11-18 07:16:46"
  },
  "requestId": "034bccff-3014-4b35-aeec-651158df91f5"
}
```

**Errors:** `400` validation · `401` · `403` role · `409` duplicate

#### `GET /api/v1/customers` — list

**Auth / roles:** S

| Query param | Meaning |
|---|---|
| `page` | default 1 |
| `limit` | default 50, max 200 |
| `sort` | one of: customer_name, renewal_dt, customer_id, created_at |
| `search` | contains-match on customer_name, mobile_no, gst_no |
| `active_flag` | `Y`/`N` |
| `state` | exact |

Response `200`:
```json
{
  "success": true,
  "message": "OK",
  "data": [
    {
      "customer_id": 1,
      "customer_name": "Howrah Cloak Room",
      "customer_addr": "Howrah Station, Platform 1",
      "state": "West Bengal",
      "pin_code": "711101",
      "mobile_no": "9830012345",
      "email": "ops@howrahcloak.in",
      "renewal_dt": null,
      "gst_no": "19ABCDE1234F1Z5",
      "upi_id": "howrahcloak@okaxis",
      "user_limit": 5,
      "active_flag": "Y",
      "remarks": null,
      "is_deleted": "N",
      "sync_flag": "N",
      "created_by": "superadmin",
      "created_at": "2026-11-18 07:16:46",
      "updated_by": "superadmin",
      "updated_at": "2026-11-18 07:16:46"
    }
  ],
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 1
  },
  "requestId": "b3a8093f-ac7a-409b-b9c9-a6c9508c62c4"
}
```

`GET /api/v1/customers/:id` returns `data` = one row of the same shape (or `404`).

#### `PATCH /api/v1/customers/:id` — update

**Auth / roles:** S

Any of the create fields.

Request body:
```json
{
  "remarks": "Pilot station"
}
```

Response `200`:
```json
{
  "success": true,
  "message": "Updated",
  "data": {
    "customer_id": 1,
    "customer_name": "Howrah Cloak Room",
    "customer_addr": "Howrah Station, Platform 1",
    "state": "West Bengal",
    "pin_code": "711101",
    "mobile_no": "9830012345",
    "email": "ops@howrahcloak.in",
    "renewal_dt": null,
    "gst_no": "19ABCDE1234F1Z5",
    "upi_id": "howrahcloak@okaxis",
    "user_limit": 5,
    "active_flag": "Y",
    "remarks": "Pilot station",
    "is_deleted": "N",
    "sync_flag": "N",
    "created_by": "superadmin",
    "created_at": "2026-11-18 07:16:46",
    "updated_by": "superadmin",
    "updated_at": "2026-11-18 07:16:46"
  },
  "requestId": "cabe12c1-509d-4804-8d7d-e3baefc1975e"
}
```

**Errors:** `400` · `401` · `403` · `404` · `409`

#### `GET /api/v1/customers/:id/summary` — customer summary

**Auth / roles:** S

Response `200`:
```json
{
  "success": true,
  "message": "OK",
  "data": {
    "customer": {
      "customer_id": 1,
      "customer_name": "Howrah Cloak Room",
      "customer_addr": "Howrah Station, Platform 1, Howrah",
      "state": "West Bengal",
      "pin_code": "711101",
      "mobile_no": "9830012345",
      "email": "ops@howrahcloak.in",
      "renewal_dt": "2027-11-17",
      "gst_no": "19AABCS1234C1Z5",
      "upi_id": "howrah.cloak@oksbi",
      "user_limit": 5,
      "active_flag": "Y",
      "remarks": "Pilot station",
      "is_deleted": "N",
      "sync_flag": "N",
      "created_by": "superadmin",
      "created_at": "2026-11-18 07:16:46",
      "updated_by": "howrah.admin",
      "updated_at": "2026-11-18 07:16:47"
    },
    "users": {
      "total": 3,
      "allowed": 3,
      "admins": 1,
      "operators": 2,
      "active_sessions": 1
    },
    "latest_registration": {
      "reg_id": 1,
      "reg_no": "REG-2026-0001",
      "customer_id": 1,
      "start_date": "2026-11-18",
      "end_date": "2027-11-17",
      "user_count": 5,
      "remarks": "Annual plan",
      "is_deleted": "N",
      "created_by": "superadmin",
      "created_at": "2026-11-18 07:16:46",
      "updated_by": "superadmin",
      "updated_at": "2026-11-18 07:16:46"
    },
    "payments": {
      "count": 1,
      "total_net": "5900.00",
      "last_payment_dt": "2026-11-18"
    },
    "luggage": {
      "total": 3,
      "in_store": 1
    }
  },
  "requestId": "53c72d40-efac-4629-ad6a-99e5f733a8ae"
}
```

### Registrations (subscriptions) of a customer

| Method | Path | Purpose | Roles |
|---|---|---|---|
| GET | `/api/v1/customers/:customerId/registrations` | list (paginated) | S |
| GET | `/api/v1/customers/:customerId/registrations/:id` | one row | S |
| POST | `/api/v1/customers/:customerId/registrations` | create → `201` | S |
| PATCH | `/api/v1/customers/:customerId/registrations/:id` | partial update | S |
| DELETE | `/api/v1/customers/:customerId/registrations/:id` | soft delete → `{ "reg_id": id }` | S |

Create/update/delete recompute the customer’s `renewal_dt` = latest live `end_date`. `reg_no` is unique.

#### `POST /api/v1/customers/:customerId/registrations` — create

**Auth / roles:** S

| Body field | Rules |
|---|---|
| `reg_no` | 1–30 chars, unique |
| `start_date` | `YYYY-MM-DD` |
| `end_date` | `YYYY-MM-DD`, ≥ start_date |
| `user_count` | integer 1–1000 |
| `remarks` | optional |

Request body:
```json
{
  "reg_no": "REG-2026-0001",
  "start_date": "2026-11-18",
  "end_date": "2027-11-17",
  "user_count": 5,
  "remarks": "Annual plan"
}
```

Response `201`:
```json
{
  "success": true,
  "message": "Created",
  "data": {
    "reg_id": 1,
    "reg_no": "REG-2026-0001",
    "customer_id": 1,
    "start_date": "2026-11-18",
    "end_date": "2027-11-17",
    "user_count": 5,
    "remarks": "Annual plan",
    "is_deleted": "N",
    "created_by": "superadmin",
    "created_at": "2026-11-18 07:16:46",
    "updated_by": "superadmin",
    "updated_at": "2026-11-18 07:16:46"
  },
  "requestId": "a16205c1-cf01-4398-ad25-b599055097a8"
}
```

**Errors:** `400` validation · `401` · `403` role · `409` duplicate

#### `GET /api/v1/customers/:customerId/registrations` — list

**Auth / roles:** S

| Query param | Meaning |
|---|---|
| `page` | default 1 |
| `limit` | default 50, max 200 |
| `sort` | one of: end_date, start_date, reg_no, reg_id |
| `search` | contains-match on reg_no |

Response `200`:
```json
{
  "success": true,
  "message": "OK",
  "data": [
    {
      "reg_id": 1,
      "reg_no": "REG-2026-0001",
      "customer_id": 1,
      "start_date": "2026-11-18",
      "end_date": "2027-11-17",
      "user_count": 5,
      "remarks": "Annual plan",
      "is_deleted": "N",
      "created_by": "superadmin",
      "created_at": "2026-11-18 07:16:46",
      "updated_by": "superadmin",
      "updated_at": "2026-11-18 07:16:46"
    }
  ],
  "meta": {
    "page": 1,
    "limit": 50,
    "total": 1
  },
  "requestId": "cd5dbb54-fd8f-40eb-9b78-d0b3d780a02e"
}
```

`GET /api/v1/customers/:customerId/registrations/:id` returns `data` = one row of the same shape (or `404`).

#### `PATCH /api/v1/customers/:customerId/registrations/:id` — update

**Auth / roles:** S

Any of the create fields.

**Errors:** `400` · `401` · `403` · `404` · `409`

### Payments received from a customer

| Method | Path | Purpose | Roles |
|---|---|---|---|
| GET | `/api/v1/customers/:customerId/payments` | list (paginated) | S |
| GET | `/api/v1/customers/:customerId/payments/:id` | one row | S |
| POST | `/api/v1/customers/:customerId/payments` | create → `201` | S |
| PATCH | `/api/v1/customers/:customerId/payments/:id` | partial update | S |
| DELETE | `/api/v1/customers/:customerId/payments/:id` | soft delete → `{ "payment_id": id }` | S |

`net_amt` is computed as base + cgst + sgst + igst when omitted; when sent it must match (else `400`). `reg_id` must belong to the same customer.

#### `POST /api/v1/customers/:customerId/payments` — create

**Auth / roles:** S

| Body field | Rules |
|---|---|
| `reg_id` | optional registration |
| `payment_dt` | `YYYY-MM-DD` |
| `base_amt` | money |
| `cgst / sgst / igst` | money, default 0 |
| `net_amt` | optional money (must equal the sum) |
| `pay_mode` | `C` cash · `U` UPI · `B` bank |
| `transaction_no` | optional, ≤ 50 |
| `remarks` | optional |

Request body:
```json
{
  "reg_id": 1,
  "payment_dt": "2026-11-18",
  "base_amt": "5000.00",
  "cgst": "450.00",
  "sgst": "450.00",
  "pay_mode": "B",
  "transaction_no": "UTR123456789"
}
```

Response `201`:
```json
{
  "success": true,
  "message": "Created",
  "data": {
    "payment_id": 1,
    "customer_id": 1,
    "reg_id": 1,
    "payment_dt": "2026-11-18",
    "base_amt": "5000.00",
    "cgst": "450.00",
    "sgst": "450.00",
    "igst": "0.00",
    "net_amt": "5900.00",
    "pay_mode": "B",
    "transaction_no": "UTR123456789",
    "remarks": null,
    "is_deleted": "N",
    "created_by": "superadmin",
    "created_at": "2026-11-18 07:16:46",
    "updated_by": "superadmin",
    "updated_at": "2026-11-18 07:16:46"
  },
  "requestId": "f8e298d5-4f44-4cca-8ea7-f5b44331ad73"
}
```

**Errors:** `400` validation · `401` · `403` role · `409` duplicate

#### `GET /api/v1/customers/:customerId/payments` — list

**Auth / roles:** S

| Query param | Meaning |
|---|---|
| `page` | default 1 |
| `limit` | default 50, max 200 |
| `sort` | one of: payment_dt, payment_id, net_amt |
| `search` | contains-match on transaction_no |
| `pay_mode` | `C`/`U`/`B` |
| `reg_id` | filter |

Response `200`:
```json
{
  "success": true,
  "message": "OK",
  "data": [
    {
      "payment_id": 1,
      "customer_id": 1,
      "reg_id": 1,
      "payment_dt": "2026-11-18",
      "base_amt": "5000.00",
      "cgst": "450.00",
      "sgst": "450.00",
      "igst": "0.00",
      "net_amt": "5900.00",
      "pay_mode": "B",
      "transaction_no": "UTR123456789",
      "remarks": null,
      "is_deleted": "N",
      "created_by": "superadmin",
      "created_at": "2026-11-18 07:16:46",
      "updated_by": "superadmin",
      "updated_at": "2026-11-18 07:16:46"
    }
  ],
  "meta": {
    "page": 1,
    "limit": 50,
    "total": 1
  },
  "requestId": "324bf612-28d1-4f7d-86e2-2411a4339af7"
}
```

`GET /api/v1/customers/:customerId/payments/:id` returns `data` = one row of the same shape (or `404`).

#### `PATCH /api/v1/customers/:customerId/payments/:id` — update

**Auth / roles:** S

Any of the create fields (amounts re-checked).

**Errors:** `400` · `401` · `403` · `404` · `409`

#### `POST /api/v1/app/version` — publish an app release

**Auth / roles:** S

| Body field | Rules |
|---|---|
| `version_code` | integer ≥ 1, unique |
| `version_name` | 1–20 chars |
| `apk_url` | `https://…` or `/downloads/<file>.apk` |
| `mandatory_flag` | `Y` forces older apps to update (default `N`) |
| `release_notes` | optional, ≤ 5000 |

Request body:
```json
{
  "version_code": 10,
  "version_name": "1.0.0",
  "apk_url": "/downloads/cloakroom-1.0.0.apk",
  "mandatory_flag": "Y",
  "release_notes": "First release"
}
```

Response `201`:
```json
{
  "success": true,
  "message": "Version published",
  "data": {
    "id": 1,
    "version_code": 10,
    "version_name": "1.0.0",
    "apk_url": "/downloads/cloakroom-1.0.0.apk",
    "mandatory_flag": "Y",
    "release_notes": "First release",
    "created_at": "2026-11-18 07:16:49"
  },
  "requestId": "f35008df-a594-4268-9d4d-05866d0e377b"
}
```

---

## 6. Offline sync (Android POS) — the core contract

### 6.1 Local data model on the device
Mirror the server rows of `td_luggage_in_out` (check-in/out) and `td_receipt` (bill) in SQLite, plus:
- `sync_flag` (`N` = not yet pushed, `Y` = pushed) — set `N` on every local insert/change.
- `local_version` — your own counter/marker per row; the server **echoes it back, never stores it**,
  so you can match results to the exact local version you sent.
- `trans_id` — a UUID v4 created **on the check-in device** and printed in the receipt QR. It is the
  same on every device and in the cloud. A bag checked in on device 1 can be checked out on device 2
  by scanning the QR (create/update the local row with the same `trans_id`).
- Masters (rates, shifts, ID proofs, GST, header/footer, **users**), settings and counters come from
  login/pull. Devices never create master rows. `masters.users` lists the customer's users (deleted ones
  too, `is_deleted = Y`) with `id, user_code, user_name, user_type, allow_flag` — use it to show who
  checked a bag in/out or billed it on rows from other counters.

### 6.2 Push — `POST /api/v1/sync/push`
Roles A, O (also allowed for a force-logged-out session). Body `{ "luggage": [...], "receipts": [...] }`,
max 5000 rows each, 5 MB. Send luggage rows with `sync_flag = N` and receipts with `sync_flag = N`
(send batches of ≤ 500 rows per request for best results).

Luggage row fields (`customer_id`, `sync_flag`, audit fields are ignored if sent):

| Field | Rules |
|---|---|
| `trans_id` | UUID (required) |
| `local_version` | number or string (echoed back) |
| `receipt_no` | `<user_code>-<5 digits>`, ≤ 20 chars, unique per customer per `receipt_dt` |
| `receipt_dt` | IST check-in date `YYYY-MM-DD` |
| `user_id_in`, `date_time_in` (UTC) | check-in (required) |
| `device_id_in` | required when `out_flag = N`; may be omitted/null on a QR check-out row |
| `shift_id_in` | nullable |
| `pasn_name` (≤ 200), `pasn_mob_no` (≤ 15), `pnr_no` (≤ 20, may be empty), `luggage_qty` (1–999) | passenger / bags |
| `extra_lugage_qty` (0–999, default 0) | extra bags stored **free**, never billed (QR key `x`) |
| `adv_amt` (money, default 0), `adv_hours` (0–720, default 0) | advance taken at check-in and the `ADVANCE_HOURS` it covered (QR key `a` = adv_amt) |
| `id_proof_id`, `id_number` (≤ 50) | ID proof — both or neither (null when none was taken, or on a QR check-out row); masked by the server when `mask_flag = Y` |
| `user_id_out`, `device_id_out`, `shift_id_out`, `date_time_out` | check-out (null while in store) |
| `out_flag` | `N` in store / `Y` checked out (then the 3 out fields + date_time_out are required) |

Receipt (bill) row fields: `trans_id`, `local_version`, `bill_no` (bill series), `bill_dt` (IST),
`user_id`, `device_id`, `shift_id`, `luggage_qty`, `days_charged`, `rate_slabs` (the slabs used; then
`block_hours`, `first_block_rate`, `next_block_rate` are optional), `base_amt`, `penal_amt`, `net_amt`
(full bill), `adv_amt` (the bag's advance, default 0), `bal_amt` (max(0, net_amt − adv_amt)), `taxable_amt`, `gst_pct`,
`cgst`, `sgst`, `igst`, `gst_flag`, `pay_mode` (`C`/`U`), `upi_ref` (nullable), `bill_printed`
(`N` = "Out Without Bill Print", the bill is still recorded). Do not send `gst_no`: the server stamps the
customer's current GSTIN on the bill when it stores it (old bills keep the GSTIN they were made with).

**Rules the server applies**
- Each row is validated separately: a bad row returns `EXCEPTION` and never fails the request.
- **Both `ACCEPTED` and `EXCEPTION` mean: set `sync_flag = 'Y'`** for that row/local_version
  (exceptions are now owned by the admin, visible in the exceptions report).
- Luggage merge: check-in fields are write-once; check-out fields only fill empty values; `out_flag`
  never goes back from `Y`. Push order between devices does not matter. Re-pushing is safe.
- Bills are stored once per `trans_id`; the server recomputes the bill (§7). A difference keeps the bill
  and logs `AMOUNT_MISMATCH`. A bill without `rate_slabs` (app older than the slab release) for a tariff
  the two-rate fields cannot express is refused with `APP_UPDATE_REQUIRED` (not stored).
- `extra_lugage_qty`, `adv_amt`, `adv_hours` are filled once while still 0 (QR row first, or a row
  synced before the app sent them), then never change.
- The response `counters` are the server’s last receipt/bill numbers of the current period for this user.

`reason` codes on `EXCEPTION`: `VALIDATION_ERROR`, `INVALID_REFERENCE` (user/shift/ID proof not of this
customer), `TENANT_MISMATCH`, `DUPLICATE_RECEIPT_NO`, `DUPLICATE_CHECKOUT` (a different second check-out),
`BILL_WITHOUT_LUGGAGE`, `DUPLICATE_BILL`, `DUPLICATE_BILL_NO`, `CHECKOUT_MISSING`, `NO_RATE`, `APP_UPDATE_REQUIRED`,
`AMOUNT_MISMATCH`, `DB_ERROR`.

Sample (bag 1 still in store with an Aadhaar number, bag 2 checked out and billed, plus a bill for an
unknown `trans_id`):
Request body:
```json
{
  "luggage": [
    {
      "trans_id": "22f0781e-4366-4ac6-aa22-94f2e9f9adb9",
      "local_version": 1,
      "receipt_no": "U03-00001",
      "receipt_dt": "2026-11-18",
      "user_id_in": 3,
      "device_id_in": "POS-HWH-01",
      "shift_id_in": 1,
      "date_time_in": "2026-11-18 05:16:48",
      "pasn_name": "Suresh Patel",
      "pasn_mob_no": "9876543210",
      "pnr_no": "4521367890",
      "luggage_qty": 2,
      "id_proof_id": 1,
      "id_number": "1234 5678 9012",
      "out_flag": "N"
    },
    {
      "trans_id": "4d7b8931-3a81-48a3-aeb5-d92d46e185ff",
      "local_version": 3,
      "receipt_no": "U03-00002",
      "receipt_dt": "2026-11-17",
      "user_id_in": 3,
      "device_id_in": "POS-HWH-01",
      "shift_id_in": 1,
      "date_time_in": "2026-11-17 05:16:48",
      "pasn_name": "Meera Iyer",
      "pasn_mob_no": "9123456780",
      "pnr_no": "8812345670",
      "luggage_qty": 1,
      "extra_lugage_qty": 1,
      "adv_amt": "50.00",
      "adv_hours": 24,
      "id_proof_id": 3,
      "id_number": "P1234567",
      "user_id_out": 3,
      "device_id_out": "POS-HWH-01",
      "shift_id_out": 1,
      "date_time_out": "2026-11-18 06:16:48",
      "out_flag": "Y"
    }
  ],
  "receipts": [
    {
      "trans_id": "4d7b8931-3a81-48a3-aeb5-d92d46e185ff",
      "local_version": 1,
      "bill_no": "U03-00001",
      "bill_dt": "2026-11-18",
      "user_id": 3,
      "device_id": "POS-HWH-01",
      "shift_id": 1,
      "luggage_qty": 1,
      "days_charged": 2,
      "block_hours": 24,
      "first_block_rate": "50.00",
      "next_block_rate": "20.00",
      "rate_slabs": [
        {
          "from_hour": 0,
          "to_hour": 24,
          "rate": "50.00"
        },
        {
          "from_hour": 24,
          "to_hour": 48,
          "rate": "20.00"
        }
      ],
      "base_amt": "70.00",
      "penal_amt": "0.00",
      "net_amt": "70.00",
      "taxable_amt": "59.32",
      "gst_pct": "18.00",
      "cgst": "5.34",
      "sgst": "5.34",
      "igst": "0.00",
      "adv_amt": "50.00",
      "bal_amt": "20.00",
      "gst_flag": "Y",
      "grace_minutes": 0,
      "gst_inclusive": "Y",
      "penalty_gst_flag": "Y",
      "pay_mode": "U",
      "upi_ref": "432198765012",
      "bill_printed": "Y"
    },
    {
      "trans_id": "4a1f4596-83cc-4081-8232-2578532f4c82",
      "local_version": 1,
      "bill_no": "U03-00002",
      "bill_dt": "2026-11-18",
      "user_id": 3,
      "device_id": "POS-HWH-01",
      "shift_id": 1,
      "luggage_qty": 1,
      "days_charged": 2,
      "block_hours": 24,
      "first_block_rate": "50.00",
      "next_block_rate": "20.00",
      "rate_slabs": [
        {
          "from_hour": 0,
          "to_hour": 24,
          "rate": "50.00"
        },
        {
          "from_hour": 24,
          "to_hour": 48,
          "rate": "20.00"
        }
      ],
      "base_amt": "70.00",
      "penal_amt": "0.00",
      "net_amt": "70.00",
      "taxable_amt": "59.32",
      "gst_pct": "18.00",
      "cgst": "5.34",
      "sgst": "5.34",
      "igst": "0.00",
      "adv_amt": "50.00",
      "bal_amt": "20.00",
      "gst_flag": "Y",
      "grace_minutes": 0,
      "gst_inclusive": "Y",
      "penalty_gst_flag": "Y",
      "pay_mode": "U",
      "upi_ref": "432198765012",
      "bill_printed": "Y"
    }
  ]
}
```

Response `200`:
```json
{
  "success": true,
  "message": "Synced with 1 exception(s)",
  "data": {
    "results": [
      {
        "table": "td_luggage_in_out",
        "trans_id": "22f0781e-4366-4ac6-aa22-94f2e9f9adb9",
        "local_version": 1,
        "status": "ACCEPTED"
      },
      {
        "table": "td_luggage_in_out",
        "trans_id": "4d7b8931-3a81-48a3-aeb5-d92d46e185ff",
        "local_version": 3,
        "status": "ACCEPTED"
      },
      {
        "table": "td_receipt",
        "trans_id": "4d7b8931-3a81-48a3-aeb5-d92d46e185ff",
        "local_version": 1,
        "status": "ACCEPTED"
      },
      {
        "table": "td_receipt",
        "trans_id": "4a1f4596-83cc-4081-8232-2578532f4c82",
        "local_version": 1,
        "status": "EXCEPTION",
        "reason": "BILL_WITHOUT_LUGGAGE: no luggage row for this trans_id"
      }
    ],
    "counters": {
      "reset": "Y",
      "period_key": "FY2026-27",
      "receipt_last_no": 2,
      "bill_last_no": 1
    },
    "server_time": "2026-11-18 07:16:48"
  },
  "requestId": "dfcb3e09-548c-4065-ba49-6381fed680f1"
}
```

The same rows pushed again (retry) — nothing changes, all `ACCEPTED`:
Response `200`:
```json
{
  "success": true,
  "message": "Synced",
  "data": {
    "results": [
      {
        "table": "td_luggage_in_out",
        "trans_id": "22f0781e-4366-4ac6-aa22-94f2e9f9adb9",
        "local_version": 1,
        "status": "ACCEPTED"
      },
      {
        "table": "td_luggage_in_out",
        "trans_id": "4d7b8931-3a81-48a3-aeb5-d92d46e185ff",
        "local_version": 3,
        "status": "ACCEPTED"
      },
      {
        "table": "td_receipt",
        "trans_id": "4d7b8931-3a81-48a3-aeb5-d92d46e185ff",
        "local_version": 1,
        "status": "ACCEPTED"
      }
    ],
    "counters": {
      "reset": "Y",
      "period_key": "FY2026-27",
      "receipt_last_no": 2,
      "bill_last_no": 1
    },
    "server_time": "2026-11-18 07:16:48"
  },
  "requestId": "5f84caeb-68a5-4a48-9ee8-666e672d4694"
}
```

Push from a force-logged-out session is still accepted:
Response `200`:
```json
{
  "success": true,
  "message": "Synced",
  "data": {
    "results": [],
    "counters": {
      "reset": "Y",
      "period_key": "FY2026-27",
      "receipt_last_no": 3,
      "bill_last_no": 1
    },
    "server_time": "2026-11-18 07:16:49"
  },
  "requestId": "529998c8-7fae-4eb2-8608-667dcad13241"
}
```

**Check-out of a bag that is not synced yet (Case 1, receipt QR).** The QR carries
`t r d c u i b n m p` = `trans_id, receipt_no, receipt_dt, customer_id, user_id_in, date_time_in,
luggage_qty, pasn_name, pasn_mob_no, pnr_no` — no check-in device, shift or ID proof. Device 2 creates the
local row from the QR (never send `customer_id`; it comes from the token), checks it out, bills it and
pushes the row with `device_id_in`, `shift_id_in`, `id_proof_id`, `id_number` left out/null. The server
stores it and accepts the bill (it is recomputed from `receipt_dt`, `date_time_in`, `luggage_qty` and the
check-out time). When device 1 later pushes its check-in, those four fields are filled **once** (only while
still empty); everything else stays as stored. Both orders end as one row.

Device 2 pushes the QR check-out and the bill first:
Request body:
```json
{
  "luggage": [
    {
      "trans_id": "dfaafe0e-6c85-4fbe-ac8a-f3dc5bf89434",
      "local_version": 1,
      "receipt_no": "U03-00003",
      "receipt_dt": "2026-11-18",
      "user_id_in": 3,
      "date_time_in": "2026-11-18 04:16:48",
      "luggage_qty": 1,
      "pasn_name": "Arjun Sen",
      "pasn_mob_no": "9000011111",
      "pnr_no": "",
      "user_id_out": 4,
      "device_id_out": "POS-HWH-02",
      "shift_id_out": null,
      "date_time_out": "2026-11-18 06:46:48",
      "out_flag": "Y"
    }
  ],
  "receipts": [
    {
      "trans_id": "dfaafe0e-6c85-4fbe-ac8a-f3dc5bf89434",
      "local_version": 1,
      "bill_no": "U04-00001",
      "bill_dt": "2026-11-18",
      "user_id": 4,
      "device_id": "POS-HWH-02",
      "shift_id": null,
      "luggage_qty": 1,
      "days_charged": 1,
      "block_hours": 24,
      "first_block_rate": "50.00",
      "next_block_rate": "20.00",
      "rate_slabs": [
        {
          "from_hour": 0,
          "to_hour": 24,
          "rate": "50.00"
        },
        {
          "from_hour": 24,
          "to_hour": 48,
          "rate": "20.00"
        }
      ],
      "base_amt": "50.00",
      "penal_amt": "0.00",
      "net_amt": "50.00",
      "taxable_amt": "42.37",
      "gst_pct": "18.00",
      "cgst": "3.82",
      "sgst": "3.81",
      "igst": "0.00",
      "gst_flag": "Y",
      "grace_minutes": 0,
      "gst_inclusive": "Y",
      "penalty_gst_flag": "Y",
      "pay_mode": "C",
      "upi_ref": null,
      "bill_printed": "Y"
    }
  ]
}
```

Response `200`:
```json
{
  "success": true,
  "message": "Synced",
  "data": {
    "results": [
      {
        "table": "td_luggage_in_out",
        "trans_id": "dfaafe0e-6c85-4fbe-ac8a-f3dc5bf89434",
        "local_version": 1,
        "status": "ACCEPTED"
      },
      {
        "table": "td_receipt",
        "trans_id": "dfaafe0e-6c85-4fbe-ac8a-f3dc5bf89434",
        "local_version": 1,
        "status": "ACCEPTED"
      }
    ],
    "counters": {
      "reset": "Y",
      "period_key": "FY2026-27",
      "receipt_last_no": 0,
      "bill_last_no": 1
    },
    "server_time": "2026-11-18 07:16:48"
  },
  "requestId": "899db442-f8d5-4d74-941a-39973d0ce3a8"
}
```

Device 1 pushes its check-in later — accepted; device, shift and ID proof are filled:
Response `200`:
```json
{
  "success": true,
  "message": "Synced",
  "data": {
    "results": [
      {
        "table": "td_luggage_in_out",
        "trans_id": "dfaafe0e-6c85-4fbe-ac8a-f3dc5bf89434",
        "local_version": 1,
        "status": "ACCEPTED"
      }
    ],
    "counters": {
      "reset": "Y",
      "period_key": "FY2026-27",
      "receipt_last_no": 3,
      "bill_last_no": 1
    },
    "server_time": "2026-11-18 07:16:48"
  },
  "requestId": "9d24a7d7-0941-4d73-b8a0-47998dd4899f"
}
```

The stored row afterwards (with the bill and its stamped `gst_no`):
Response `200`:
```json
{
  "success": true,
  "message": "OK",
  "data": {
    "trans_id": "dfaafe0e-6c85-4fbe-ac8a-f3dc5bf89434",
    "customer_id": 1,
    "receipt_no": "U03-00003",
    "receipt_dt": "2026-11-18",
    "user_id_in": 3,
    "device_id_in": "POS-HWH-01",
    "shift_id_in": 1,
    "date_time_in": "2026-11-18 04:16:48",
    "pasn_name": "Arjun Sen",
    "pasn_mob_no": "9000011111",
    "pnr_no": "",
    "luggage_qty": 1,
    "extra_lugage_qty": 0,
    "adv_amt": "0.00",
    "adv_hours": 0,
    "id_proof_id": 3,
    "id_number": "Z7654321",
    "user_id_out": 4,
    "device_id_out": "POS-HWH-02",
    "shift_id_out": null,
    "date_time_out": "2026-11-18 06:46:48",
    "out_flag": "Y",
    "is_deleted": "N",
    "sync_flag": "N",
    "created_by": "howrah.op2",
    "created_at": "2026-11-18 07:16:48",
    "updated_by": "howrah.op1",
    "updated_at": "2026-11-18 07:16:48",
    "receipt": {
      "trans_id": "dfaafe0e-6c85-4fbe-ac8a-f3dc5bf89434",
      "customer_id": 1,
      "bill_no": "U04-00001",
      "bill_dt": "2026-11-18",
      "user_id": 4,
      "device_id": "POS-HWH-02",
      "shift_id": null,
      "luggage_qty": 1,
      "days_charged": 1,
      "block_hours": 24,
      "first_block_rate": "50.00",
      "next_block_rate": "20.00",
      "rate_slabs": [
        {
          "rate": "50.00",
          "to_hour": 24,
          "from_hour": 0
        },
        {
          "rate": "20.00",
          "to_hour": 48,
          "from_hour": 24
        }
      ],
      "grace_minutes": 0,
      "base_amt": "50.00",
      "penal_amt": "0.00",
      "net_amt": "50.00",
      "adv_amt": "0.00",
      "bal_amt": "50.00",
      "taxable_amt": "42.37",
      "gst_pct": "18.00",
      "cgst": "3.82",
      "sgst": "3.81",
      "igst": "0.00",
      "gst_flag": "Y",
      "gst_inclusive": "Y",
      "penalty_gst_flag": "Y",
      "gst_no": "19AABCS1234C1Z5",
      "pay_mode": "C",
      "upi_ref": null,
      "bill_printed": "Y",
      "is_deleted": "N",
      "sync_flag": "N",
      "created_by": "howrah.op2",
      "created_at": "2026-11-18 07:16:48",
      "updated_by": "howrah.op2",
      "updated_at": "2026-11-18 07:16:48"
    }
  },
  "requestId": "e05831ba-5eb5-4f1b-8813-fb1ec2deb729"
}
```

**Errors:** `400` only if the envelope itself is wrong (e.g. `luggage` not an array) · `401` · `403` (super admin) · `413` · `429`

### 6.3 Pull — `GET /api/v1/sync/pull`
Roles A, O.

| Query | Meaning |
|---|---|
| `since` | `server_time` of the previous **completed** pull (omit for the first pull) |
| `cursor` | `next_cursor` of the previous page while `has_more` was true (send without `since`) |
| `limit` | luggage rows per page, 1–1000 (default 1000) |

**Algorithm**
1. First pull (after login / fresh install): no `since` → all masters, settings, counters and all **open**
   check-ins of the customer (other users’ bags too, so any counter can check them out).
2. Every `SYNC_INTERVAL_MIN` minutes (setting, ≥ 15) and after a push: `GET /sync/pull?since=<stored server_time>`
   → rows changed since then, including **soft-deleted** rows (`is_deleted = "Y"` → hide/delete locally) and
   bags checked out on other devices (`out_flag = "Y"` → mark out locally).
3. **Upsert by primary key** (`trans_id`, `rate_id`, …): the server re-sends a 2-minute overlap, so duplicates are normal.
4. While `has_more` is `true`: `GET /sync/pull?cursor=<next_cursor>` (continuation pages carry only `luggage`;
   `masters/settings/counters` are `null`). When `has_more` is `false`, store `server_time` as the next `since`.

First pull (operator 2 sees operator 1’s bag still in store):
Response `200`:
```json
{
  "success": true,
  "message": "OK",
  "data": {
    "masters": {
      "id_proofs": [
        {
          "id_proof_id": 1,
          "id_proof_name": "Aadhaar Card",
          "mask_flag": "Y",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "system",
          "created_at": "2026-11-18 07:16:37",
          "updated_by": null,
          "updated_at": null
        },
        {
          "id_proof_id": 2,
          "id_proof_name": "Driving Licence",
          "mask_flag": "N",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "system",
          "created_at": "2026-11-18 07:16:37",
          "updated_by": null,
          "updated_at": null
        },
        "… 5 more item(s) of the same shape"
      ],
      "gst": [
        {
          "gst_id": 1,
          "cgst_pct": "9.00",
          "sgst_pct": "9.00",
          "igst_pct": "0.00",
          "effective_from": "2017-07-01",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "system",
          "created_at": "2026-11-18 07:16:37",
          "updated_by": null,
          "updated_at": null
        }
      ],
      "rates": [
        {
          "rate_id": 1,
          "customer_id": 1,
          "block_hours": 24,
          "first_block_rate": "50.00",
          "next_block_rate": "20.00",
          "rate_slabs": [
            {
              "rate": "50.00",
              "to_hour": 24,
              "from_hour": 0
            },
            {
              "rate": "20.00",
              "to_hour": 48,
              "from_hour": 24
            }
          ],
          "effective_from": "2026-01-01",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "superadmin",
          "created_at": "2026-11-18 07:16:46",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-18 07:16:47"
        }
      ],
      "shifts": [
        {
          "shift_id": 1,
          "customer_id": 1,
          "shift_name": "Morning",
          "from_time": "06:00:00",
          "to_time": "14:30:00",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "howrah.admin",
          "created_at": "2026-11-18 07:16:47",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-18 07:16:47"
        },
        {
          "shift_id": 2,
          "customer_id": 1,
          "shift_name": "Night",
          "from_time": "22:00:00",
          "to_time": "06:00:00",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "howrah.admin",
          "created_at": "2026-11-18 07:16:47",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-18 07:16:47"
        }
      ],
      "shift_assignments": [
        {
          "id": 1,
          "customer_id": 1,
          "user_id": 3,
          "shift_id": 1,
          "effective_from": "2026-11-19",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "howrah.admin",
          "created_at": "2026-11-18 07:16:47",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-18 07:16:47"
        }
      ],
      "header_footer": [
        {
          "id": 1,
          "customer_id": 1,
          "line_type": "H",
          "line_order": 1,
          "narration": "HOWRAH CLOAK ROOM",
          "image": null,
          "in_flag": "Y",
          "out_flag": "Y",
          "report_flag": "N",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "howrah.admin",
          "created_at": "2026-11-18 07:16:47",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-18 07:16:47"
        },
        {
          "id": 2,
          "customer_id": 1,
          "line_type": "F",
          "line_order": 1,
          "narration": "Thank you. Keep this receipt safe.",
          "image": null,
          "in_flag": "Y",
          "out_flag": "Y",
          "report_flag": "Y",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "howrah.admin",
          "created_at": "2026-11-18 07:16:47",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-18 07:16:47"
        },
        "… 1 more item(s) of the same shape"
      ],
      "users": [
        {
          "id": 2,
          "customer_id": 1,
          "user_code": "A01",
          "user_name": "Station Admin",
          "user_type": "A",
          "allow_flag": "Y",
          "is_deleted": "N",
          "updated_at": "2026-11-18 07:16:47"
        },
        {
          "id": 3,
          "customer_id": 1,
          "user_code": "U03",
          "user_name": "Ravi Kumar Singh",
          "user_type": "O",
          "allow_flag": "Y",
          "is_deleted": "N",
          "updated_at": "2026-11-18 07:16:48"
        },
        "… 1 more item(s) of the same shape"
      ]
    },
    "settings": {
      "REPORT_FLAG": "N",
      "REPORT_PWD_FLAG": "N",
      "TOTAL_COLLECTION_FLAG": "Y",
      "RECEIPT_RESET": "Y",
      "SYNC_INTERVAL_MIN": "30",
      "FORCE_ONLINE_DAYS": "0",
      "GST_FLAG": "Y",
      "GST_TYPE": "E",
      "QR_FLAG": "Y",
      "DEFAULT_PAY_MODE": "C",
      "PAPER_WIDTH": "58",
      "PENALTY_FLAG": "Y",
      "PENALTY_MAX_AMT": "500.00",
      "PENALTY_GST_FLAG": "Y",
      "GRACE_MINUTES": "0",
      "GST_INCLUSIVE": "Y",
      "UPI_ENABLED": "Y",
      "OUT_WITHOUT_PRINT_FLAG": "Y",
      "SINGLE_SESSION_FLAG": "Y",
      "MAX_BAGS_PER_RECEIPT": "10",
      "PNR_REQUIRED": "Y",
      "ID_PROOF_REQUIRED": "Y",
      "ID_MASK_FLAG": "Y",
      "SHIFT_LOGIN_FLAG": "Y",
      "SHIFT_EARLY_LOGIN_MIN": "15",
      "PWD_MIN_LENGTH": "8",
      "ADVANCE_FLAG": "N",
      "ADVANCE_HOURS": "24",
      "CHECKIN_PRINT_FLAG": "Y",
      "PRINT_COPIES": "1"
    },
    "counters": {
      "reset": "Y",
      "period_key": "FY2026-27",
      "receipt_last_no": 0,
      "bill_last_no": 0
    },
    "luggage": [
      {
        "trans_id": "22f0781e-4366-4ac6-aa22-94f2e9f9adb9",
        "customer_id": 1,
        "receipt_no": "U03-00001",
        "receipt_dt": "2026-11-18",
        "user_id_in": 3,
        "device_id_in": "POS-HWH-01",
        "shift_id_in": 1,
        "date_time_in": "2026-11-18 05:16:48",
        "pasn_name": "Suresh Patel",
        "pasn_mob_no": "9876543210",
        "pnr_no": "4521367890",
        "luggage_qty": 2,
        "extra_lugage_qty": 0,
        "adv_amt": "0.00",
        "adv_hours": 0,
        "id_proof_id": 1,
        "id_number": "XXXXXXXX9012",
        "user_id_out": null,
        "device_id_out": null,
        "shift_id_out": null,
        "date_time_out": null,
        "out_flag": "N",
        "is_deleted": "N",
        "sync_flag": "N",
        "created_by": "howrah.op1",
        "created_at": "2026-11-18 07:16:48",
        "updated_by": "howrah.op1",
        "updated_at": "2026-11-18 07:16:48"
      }
    ],
    "has_more": false,
    "next_cursor": null,
    "server_time": "2026-11-18 07:16:48"
  },
  "requestId": "3b30b976-324c-4c53-838f-73668f9989b0"
}
```

Next pull with `since=2026-11-18 07:16:48`:
Response `200`:
```json
{
  "success": true,
  "message": "OK",
  "data": {
    "masters": {
      "id_proofs": [
        {
          "id_proof_id": 7,
          "id_proof_name": "Senior Citizen Card",
          "mask_flag": "N",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "superadmin",
          "created_at": "2026-11-18 07:16:47",
          "updated_by": "superadmin",
          "updated_at": "2026-11-18 07:16:47"
        }
      ],
      "gst": [
        {
          "gst_id": 2,
          "cgst_pct": "9.00",
          "sgst_pct": "9.00",
          "igst_pct": "18.00",
          "effective_from": "2030-04-01",
          "is_deleted": "Y",
          "sync_flag": "N",
          "created_by": "superadmin",
          "created_at": "2026-11-18 07:16:47",
          "updated_by": "superadmin",
          "updated_at": "2026-11-18 07:16:47"
        }
      ],
      "rates": [
        {
          "rate_id": 1,
          "customer_id": 1,
          "block_hours": 24,
          "first_block_rate": "50.00",
          "next_block_rate": "20.00",
          "rate_slabs": [
            {
              "rate": "50.00",
              "to_hour": 24,
              "from_hour": 0
            },
            {
              "rate": "20.00",
              "to_hour": 48,
              "from_hour": 24
            }
          ],
          "effective_from": "2026-01-01",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "superadmin",
          "created_at": "2026-11-18 07:16:46",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-18 07:16:47"
        },
        {
          "rate_id": 2,
          "customer_id": 1,
          "block_hours": 24,
          "first_block_rate": "30.00",
          "next_block_rate": "10.00",
          "rate_slabs": [
            {
              "rate": "30.00",
              "to_hour": 24,
              "from_hour": 0
            },
            {
              "rate": "10.00",
              "to_hour": 48,
              "from_hour": 24
            }
          ],
          "effective_from": "2027-01-17",
          "is_deleted": "Y",
          "sync_flag": "N",
          "created_by": "howrah.admin",
          "created_at": "2026-11-18 07:16:47",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-18 07:16:47"
        }
      ],
      "shifts": [
        {
          "shift_id": 1,
          "customer_id": 1,
          "shift_name": "Morning",
          "from_time": "06:00:00",
          "to_time": "14:30:00",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "howrah.admin",
          "created_at": "2026-11-18 07:16:47",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-18 07:16:47"
        },
        {
          "shift_id": 2,
          "customer_id": 1,
          "shift_name": "Night",
          "from_time": "22:00:00",
          "to_time": "06:00:00",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "howrah.admin",
          "created_at": "2026-11-18 07:16:47",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-18 07:16:47"
        }
      ],
      "shift_assignments": [
        {
          "id": 1,
          "customer_id": 1,
          "user_id": 3,
          "shift_id": 1,
          "effective_from": "2026-11-19",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "howrah.admin",
          "created_at": "2026-11-18 07:16:47",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-18 07:16:47"
        }
      ],
      "header_footer": [
        {
          "id": 1,
          "customer_id": 1,
          "line_type": "H",
          "line_order": 1,
          "narration": "HOWRAH CLOAK ROOM",
          "image": null,
          "in_flag": "Y",
          "out_flag": "Y",
          "report_flag": "N",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "howrah.admin",
          "created_at": "2026-11-18 07:16:47",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-18 07:16:47"
        },
        {
          "id": 2,
          "customer_id": 1,
          "line_type": "F",
          "line_order": 1,
          "narration": "Thank you. Keep this receipt safe.",
          "image": null,
          "in_flag": "Y",
          "out_flag": "Y",
          "report_flag": "Y",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "howrah.admin",
          "created_at": "2026-11-18 07:16:47",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-18 07:16:47"
        },
        "… 1 more item(s) of the same shape"
      ],
      "users": [
        {
          "id": 2,
          "customer_id": 1,
          "user_code": "A01",
          "user_name": "Station Admin",
          "user_type": "A",
          "allow_flag": "Y",
          "is_deleted": "N",
          "updated_at": "2026-11-18 07:16:47"
        },
        {
          "id": 3,
          "customer_id": 1,
          "user_code": "U03",
          "user_name": "Ravi Kumar Singh",
          "user_type": "O",
          "allow_flag": "Y",
          "is_deleted": "N",
          "updated_at": "2026-11-18 07:16:48"
        },
        "… 1 more item(s) of the same shape"
      ]
    },
    "settings": {
      "REPORT_FLAG": "N",
      "REPORT_PWD_FLAG": "N",
      "TOTAL_COLLECTION_FLAG": "Y",
      "RECEIPT_RESET": "Y",
      "SYNC_INTERVAL_MIN": "30",
      "FORCE_ONLINE_DAYS": "0",
      "GST_FLAG": "Y",
      "GST_TYPE": "E",
      "QR_FLAG": "Y",
      "DEFAULT_PAY_MODE": "C",
      "PAPER_WIDTH": "58",
      "PENALTY_FLAG": "Y",
      "PENALTY_MAX_AMT": "500.00",
      "PENALTY_GST_FLAG": "Y",
      "GRACE_MINUTES": "0",
      "GST_INCLUSIVE": "Y",
      "UPI_ENABLED": "Y",
      "OUT_WITHOUT_PRINT_FLAG": "Y",
      "SINGLE_SESSION_FLAG": "Y",
      "MAX_BAGS_PER_RECEIPT": "10",
      "PNR_REQUIRED": "Y",
      "ID_PROOF_REQUIRED": "Y",
      "ID_MASK_FLAG": "Y",
      "SHIFT_LOGIN_FLAG": "Y",
      "SHIFT_EARLY_LOGIN_MIN": "15",
      "PWD_MIN_LENGTH": "8",
      "ADVANCE_FLAG": "N",
      "ADVANCE_HOURS": "24",
      "CHECKIN_PRINT_FLAG": "Y",
      "PRINT_COPIES": "1"
    },
    "counters": {
      "reset": "Y",
      "period_key": "FY2026-27",
      "receipt_last_no": 0,
      "bill_last_no": 0
    },
    "luggage": [
      {
        "trans_id": "22f0781e-4366-4ac6-aa22-94f2e9f9adb9",
        "customer_id": 1,
        "receipt_no": "U03-00001",
        "receipt_dt": "2026-11-18",
        "user_id_in": 3,
        "device_id_in": "POS-HWH-01",
        "shift_id_in": 1,
        "date_time_in": "2026-11-18 05:16:48",
        "pasn_name": "Suresh Patel",
        "pasn_mob_no": "9876543210",
        "pnr_no": "4521367890",
        "luggage_qty": 2,
        "extra_lugage_qty": 0,
        "adv_amt": "0.00",
        "adv_hours": 0,
        "id_proof_id": 1,
        "id_number": "XXXXXXXX9012",
        "user_id_out": null,
        "device_id_out": null,
        "shift_id_out": null,
        "date_time_out": null,
        "out_flag": "N",
        "is_deleted": "N",
        "sync_flag": "N",
        "created_by": "howrah.op1",
        "created_at": "2026-11-18 07:16:48",
        "updated_by": "howrah.op1",
        "updated_at": "2026-11-18 07:16:48"
      },
      {
        "trans_id": "4d7b8931-3a81-48a3-aeb5-d92d46e185ff",
        "customer_id": 1,
        "receipt_no": "U03-00002",
        "receipt_dt": "2026-11-17",
        "user_id_in": 3,
        "device_id_in": "POS-HWH-01",
        "shift_id_in": 1,
        "date_time_in": "2026-11-17 05:16:48",
        "pasn_name": "Meera Iyer",
        "pasn_mob_no": "9123456780",
        "pnr_no": "8812345670",
        "luggage_qty": 1,
        "extra_lugage_qty": 1,
        "adv_amt": "50.00",
        "adv_hours": 24,
        "id_proof_id": 3,
        "id_number": "P1234567",
        "user_id_out": 3,
        "device_id_out": "POS-HWH-01",
        "shift_id_out": 1,
        "date_time_out": "2026-11-18 06:16:48",
        "out_flag": "Y",
        "is_deleted": "N",
        "sync_flag": "N",
        "created_by": "howrah.op1",
        "created_at": "2026-11-18 07:16:48",
        "updated_by": "howrah.op1",
        "updated_at": "2026-11-18 07:16:48"
      }
    ],
    "has_more": false,
    "next_cursor": null,
    "server_time": "2026-11-18 07:16:48"
  },
  "requestId": "b31d5e71-9394-46a8-b57a-af7eb48ae9c2"
}
```

Paged pull (`limit=1`) → `has_more: true` + `next_cursor`:
Response `200`:
```json
{
  "success": true,
  "message": "OK",
  "data": {
    "masters": {
      "id_proofs": [
        {
          "id_proof_id": 7,
          "id_proof_name": "Senior Citizen Card",
          "mask_flag": "N",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "superadmin",
          "created_at": "2026-11-18 07:16:47",
          "updated_by": "superadmin",
          "updated_at": "2026-11-18 07:16:47"
        }
      ],
      "gst": [
        {
          "gst_id": 2,
          "cgst_pct": "9.00",
          "sgst_pct": "9.00",
          "igst_pct": "18.00",
          "effective_from": "2030-04-01",
          "is_deleted": "Y",
          "sync_flag": "N",
          "created_by": "superadmin",
          "created_at": "2026-11-18 07:16:47",
          "updated_by": "superadmin",
          "updated_at": "2026-11-18 07:16:47"
        }
      ],
      "rates": [
        {
          "rate_id": 1,
          "customer_id": 1,
          "block_hours": 24,
          "first_block_rate": "50.00",
          "next_block_rate": "20.00",
          "rate_slabs": [
            {
              "rate": "50.00",
              "to_hour": 24,
              "from_hour": 0
            },
            {
              "rate": "20.00",
              "to_hour": 48,
              "from_hour": 24
            }
          ],
          "effective_from": "2026-01-01",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "superadmin",
          "created_at": "2026-11-18 07:16:46",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-18 07:16:47"
        },
        {
          "rate_id": 2,
          "customer_id": 1,
          "block_hours": 24,
          "first_block_rate": "30.00",
          "next_block_rate": "10.00",
          "rate_slabs": [
            {
              "rate": "30.00",
              "to_hour": 24,
              "from_hour": 0
            },
            {
              "rate": "10.00",
              "to_hour": 48,
              "from_hour": 24
            }
          ],
          "effective_from": "2027-01-17",
          "is_deleted": "Y",
          "sync_flag": "N",
          "created_by": "howrah.admin",
          "created_at": "2026-11-18 07:16:47",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-18 07:16:47"
        }
      ],
      "shifts": [
        {
          "shift_id": 1,
          "customer_id": 1,
          "shift_name": "Morning",
          "from_time": "06:00:00",
          "to_time": "14:30:00",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "howrah.admin",
          "created_at": "2026-11-18 07:16:47",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-18 07:16:47"
        },
        {
          "shift_id": 2,
          "customer_id": 1,
          "shift_name": "Night",
          "from_time": "22:00:00",
          "to_time": "06:00:00",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "howrah.admin",
          "created_at": "2026-11-18 07:16:47",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-18 07:16:47"
        }
      ],
      "shift_assignments": [
        {
          "id": 1,
          "customer_id": 1,
          "user_id": 3,
          "shift_id": 1,
          "effective_from": "2026-11-19",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "howrah.admin",
          "created_at": "2026-11-18 07:16:47",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-18 07:16:47"
        }
      ],
      "header_footer": [
        {
          "id": 1,
          "customer_id": 1,
          "line_type": "H",
          "line_order": 1,
          "narration": "HOWRAH CLOAK ROOM",
          "image": null,
          "in_flag": "Y",
          "out_flag": "Y",
          "report_flag": "N",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "howrah.admin",
          "created_at": "2026-11-18 07:16:47",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-18 07:16:47"
        },
        {
          "id": 2,
          "customer_id": 1,
          "line_type": "F",
          "line_order": 1,
          "narration": "Thank you. Keep this receipt safe.",
          "image": null,
          "in_flag": "Y",
          "out_flag": "Y",
          "report_flag": "Y",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "howrah.admin",
          "created_at": "2026-11-18 07:16:47",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-18 07:16:47"
        },
        "… 1 more item(s) of the same shape"
      ],
      "users": [
        {
          "id": 2,
          "customer_id": 1,
          "user_code": "A01",
          "user_name": "Station Admin",
          "user_type": "A",
          "allow_flag": "Y",
          "is_deleted": "N",
          "updated_at": "2026-11-18 07:16:47"
        },
        {
          "id": 3,
          "customer_id": 1,
          "user_code": "U03",
          "user_name": "Ravi Kumar Singh",
          "user_type": "O",
          "allow_flag": "Y",
          "is_deleted": "N",
          "updated_at": "2026-11-18 07:16:48"
        },
        "… 1 more item(s) of the same shape"
      ]
    },
    "settings": {
      "REPORT_FLAG": "N",
      "REPORT_PWD_FLAG": "N",
      "TOTAL_COLLECTION_FLAG": "Y",
      "RECEIPT_RESET": "Y",
      "SYNC_INTERVAL_MIN": "30",
      "FORCE_ONLINE_DAYS": "0",
      "GST_FLAG": "Y",
      "GST_TYPE": "E",
      "QR_FLAG": "Y",
      "DEFAULT_PAY_MODE": "C",
      "PAPER_WIDTH": "58",
      "PENALTY_FLAG": "Y",
      "PENALTY_MAX_AMT": "500.00",
      "PENALTY_GST_FLAG": "Y",
      "GRACE_MINUTES": "0",
      "GST_INCLUSIVE": "Y",
      "UPI_ENABLED": "Y",
      "OUT_WITHOUT_PRINT_FLAG": "Y",
      "SINGLE_SESSION_FLAG": "Y",
      "MAX_BAGS_PER_RECEIPT": "10",
      "PNR_REQUIRED": "Y",
      "ID_PROOF_REQUIRED": "Y",
      "ID_MASK_FLAG": "Y",
      "SHIFT_LOGIN_FLAG": "Y",
      "SHIFT_EARLY_LOGIN_MIN": "15",
      "PWD_MIN_LENGTH": "8",
      "ADVANCE_FLAG": "N",
      "ADVANCE_HOURS": "24",
      "CHECKIN_PRINT_FLAG": "Y",
      "PRINT_COPIES": "1"
    },
    "counters": {
      "reset": "Y",
      "period_key": "FY2026-27",
      "receipt_last_no": 0,
      "bill_last_no": 0
    },
    "luggage": [
      {
        "trans_id": "22f0781e-4366-4ac6-aa22-94f2e9f9adb9",
        "customer_id": 1,
        "receipt_no": "U03-00001",
        "receipt_dt": "2026-11-18",
        "user_id_in": 3,
        "device_id_in": "POS-HWH-01",
        "shift_id_in": 1,
        "date_time_in": "2026-11-18 05:16:48",
        "pasn_name": "Suresh Patel",
        "pasn_mob_no": "9876543210",
        "pnr_no": "4521367890",
        "luggage_qty": 2,
        "extra_lugage_qty": 0,
        "adv_amt": "0.00",
        "adv_hours": 0,
        "id_proof_id": 1,
        "id_number": "XXXXXXXX9012",
        "user_id_out": null,
        "device_id_out": null,
        "shift_id_out": null,
        "date_time_out": null,
        "out_flag": "N",
        "is_deleted": "N",
        "sync_flag": "N",
        "created_by": "howrah.op1",
        "created_at": "2026-11-18 07:16:48",
        "updated_by": "howrah.op1",
        "updated_at": "2026-11-18 07:16:48"
      }
    ],
    "has_more": true,
    "next_cursor": "eyJtIjoiY2hhbmdl…(base64)",
    "server_time": "2026-11-18 07:16:48"
  },
  "requestId": "bb150eb8-62bf-4acc-8228-57ccb2940b09"
}
```

Continuation page (`?cursor=…`):
Response `200`:
```json
{
  "success": true,
  "message": "OK",
  "data": {
    "masters": null,
    "settings": null,
    "counters": null,
    "luggage": [
      {
        "trans_id": "4d7b8931-3a81-48a3-aeb5-d92d46e185ff",
        "customer_id": 1,
        "receipt_no": "U03-00002",
        "receipt_dt": "2026-11-17",
        "user_id_in": 3,
        "device_id_in": "POS-HWH-01",
        "shift_id_in": 1,
        "date_time_in": "2026-11-17 05:16:48",
        "pasn_name": "Meera Iyer",
        "pasn_mob_no": "9123456780",
        "pnr_no": "8812345670",
        "luggage_qty": 1,
        "extra_lugage_qty": 1,
        "adv_amt": "50.00",
        "adv_hours": 24,
        "id_proof_id": 3,
        "id_number": "P1234567",
        "user_id_out": 3,
        "device_id_out": "POS-HWH-01",
        "shift_id_out": 1,
        "date_time_out": "2026-11-18 06:16:48",
        "out_flag": "Y",
        "is_deleted": "N",
        "sync_flag": "N",
        "created_by": "howrah.op1",
        "created_at": "2026-11-18 07:16:48",
        "updated_by": "howrah.op1",
        "updated_at": "2026-11-18 07:16:48"
      }
    ],
    "has_more": false,
    "next_cursor": null,
    "server_time": "2026-11-18 07:16:48"
  },
  "requestId": "7684ddff-797a-40c1-be36-93d7cbb8987d"
}
```

### 6.4 Online luggage lookup

#### `GET /api/v1/luggage` — search bags

**Auth / roles:** A, O

| Query param | Meaning |
|---|---|
| `search` | contains-match on PNR, mobile, passenger name, receipt no |
| `status` | `in` (in store) / `out` (checked out) |
| `page, limit` | pagination (newest check-in first) |

Response `200`:
```json
{
  "success": true,
  "message": "OK",
  "data": [
    {
      "trans_id": "22f0781e-4366-4ac6-aa22-94f2e9f9adb9",
      "customer_id": 1,
      "receipt_no": "U03-00001",
      "receipt_dt": "2026-11-18",
      "user_id_in": 3,
      "device_id_in": "POS-HWH-01",
      "shift_id_in": 1,
      "date_time_in": "2026-11-18 05:16:48",
      "pasn_name": "Suresh Patel",
      "pasn_mob_no": "9876543210",
      "pnr_no": "4521367890",
      "luggage_qty": 2,
      "extra_lugage_qty": 0,
      "adv_amt": "0.00",
      "adv_hours": 0,
      "id_proof_id": 1,
      "id_number": "XXXXXXXX9012",
      "user_id_out": null,
      "device_id_out": null,
      "shift_id_out": null,
      "date_time_out": null,
      "out_flag": "N",
      "is_deleted": "N",
      "sync_flag": "N",
      "created_by": "howrah.op1",
      "created_at": "2026-11-18 07:16:48",
      "updated_by": "howrah.op1",
      "updated_at": "2026-11-18 07:16:48"
    }
  ],
  "meta": {
    "page": 1,
    "limit": 50,
    "total": 1
  },
  "requestId": "49378d92-74d4-48e4-ac2f-f7b71d5690dc"
}
```

#### `GET /api/v1/luggage/:trans_id` — one bag with its bill (`receipt` or null)

**Auth / roles:** A, O

Response `200`:
```json
{
  "success": true,
  "message": "OK",
  "data": {
    "trans_id": "4d7b8931-3a81-48a3-aeb5-d92d46e185ff",
    "customer_id": 1,
    "receipt_no": "U03-00002",
    "receipt_dt": "2026-11-17",
    "user_id_in": 3,
    "device_id_in": "POS-HWH-01",
    "shift_id_in": 1,
    "date_time_in": "2026-11-17 05:16:48",
    "pasn_name": "Meera Iyer",
    "pasn_mob_no": "9123456780",
    "pnr_no": "8812345670",
    "luggage_qty": 1,
    "extra_lugage_qty": 1,
    "adv_amt": "50.00",
    "adv_hours": 24,
    "id_proof_id": 3,
    "id_number": "P1234567",
    "user_id_out": 3,
    "device_id_out": "POS-HWH-01",
    "shift_id_out": 1,
    "date_time_out": "2026-11-18 06:16:48",
    "out_flag": "Y",
    "is_deleted": "N",
    "sync_flag": "N",
    "created_by": "howrah.op1",
    "created_at": "2026-11-18 07:16:48",
    "updated_by": "howrah.op1",
    "updated_at": "2026-11-18 07:16:48",
    "receipt": {
      "trans_id": "4d7b8931-3a81-48a3-aeb5-d92d46e185ff",
      "customer_id": 1,
      "bill_no": "U03-00001",
      "bill_dt": "2026-11-18",
      "user_id": 3,
      "device_id": "POS-HWH-01",
      "shift_id": 1,
      "luggage_qty": 1,
      "days_charged": 2,
      "block_hours": 24,
      "first_block_rate": "50.00",
      "next_block_rate": "20.00",
      "rate_slabs": [
        {
          "rate": "50.00",
          "to_hour": 24,
          "from_hour": 0
        },
        {
          "rate": "20.00",
          "to_hour": 48,
          "from_hour": 24
        }
      ],
      "grace_minutes": 0,
      "base_amt": "70.00",
      "penal_amt": "0.00",
      "net_amt": "70.00",
      "adv_amt": "50.00",
      "bal_amt": "20.00",
      "taxable_amt": "59.32",
      "gst_pct": "18.00",
      "cgst": "5.34",
      "sgst": "5.34",
      "igst": "0.00",
      "gst_flag": "Y",
      "gst_inclusive": "Y",
      "penalty_gst_flag": "Y",
      "gst_no": "19AABCS1234C1Z5",
      "pay_mode": "U",
      "upi_ref": "432198765012",
      "bill_printed": "Y",
      "is_deleted": "N",
      "sync_flag": "N",
      "created_by": "howrah.op1",
      "created_at": "2026-11-18 07:16:48",
      "updated_by": "howrah.op1",
      "updated_at": "2026-11-18 07:16:48"
    }
  },
  "requestId": "bb0b262c-be5f-41aa-ab3a-70d90a12d2c4"
}
```

**Errors:** `400` bad UUID · `404`

`404 NOT_FOUND`:
Response `404`:
```json
{
  "success": false,
  "message": "Luggage not found",
  "code": "NOT_FOUND",
  "data": null,
  "requestId": "ca381a07-7c4c-4168-935b-03230b40e591"
}
```

---

## 7. Billing rules (must match the server exactly)

```
minutes      = whole minutes from date_time_in to date_time_out (seconds dropped)
billable     = max(0, minutes − grace_minutes)                      GRACE_MINUTES, default 0
slabs        = rate_slabs of the rate row: [{from_hour, to_hour, rate}], contiguous from hour 0
per bag      = Σ rate of every slab with i = 0 or billable > from_hour × 60     (started slabs)
             + last.rate × ceil((billable − last.to_hour × 60) / ((last.to_hour − last.from_hour) × 60))
                                                                    when billable > last.to_hour × 60
days_charged = number of rates added (slabs started + repeats of the last slab)
base_amt     = luggage_qty × per bag                                 extra bags are free
adv_amt      = the bag's advance (0 when none); bal_amt = max(0, net_amt − adv_amt)   no refund
rate row     = latest rate with effective_from ≤ receipt_dt
GST row      = latest GST with effective_from ≤ bill_dt
gst_pct      = 0 when gst_flag = N, else the customer's GST_PCT when set,
               else cgst_pct + sgst_pct (GST_TYPE = E) | igst_pct (GST_TYPE = I) of the GST row
taxed        = base_amt + (penalty_gst_flag = Y ? penal_amt : 0)    PENALTY_GST_FLAG, default Y

gst_inclusive = Y (GST_INCLUSIVE, default):
  taxable_amt = round(taxed × 100 / (100 + gst_pct))
  gst         = taxed − taxable_amt
  net_amt     = base_amt + penal_amt                                 total collected
gst_inclusive = N (GST added on top):
  taxable_amt = taxed
  gst         = round(taxed × gst_pct / 100)
  net_amt     = base_amt + penal_amt + gst

GST_TYPE = E: cgst = round(gst / 2), sgst = gst − cgst, igst = 0   (odd paisa goes to CGST)
GST_TYPE = I: igst = gst, cgst = sgst = 0
```
- Do all arithmetic in **integer paise** (₹1 = 100). `round` = **half-up** to the paisa.
- A penalty without GST is **not** part of `taxable_amt`: net = taxable + GST + untaxed penalty.
- Examples (defaults): 2 min × 2 bags → ₹100 · 24h00m × 1 → ₹50 · 24h01m × 1 → ₹70 · 50 h × 3 → ₹270 ·
  ₹70 at 18 % → taxable 59.32, CGST 5.34, SGST 5.34 · ₹60.50 at 18 % → 51.27, CGST 4.62, SGST 4.61.
- Examples (options): 24h10m with 15 grace minutes → 1 block (₹50) · GST on top, ₹50 at 18 % → taxable 50.00,
  CGST 4.50, SGST 4.50, net 59.00 · on top with ₹10 penalty → taxable 60.00, GST 10.80, net 70.80 · on top,
  penalty without GST → taxable 50.00, GST 9.00, net 69.00 · inclusive ₹70 + ₹10 penalty without GST →
  taxable 59.32, CGST 5.34, SGST 5.34, net 80.00 · `gst_flag = N` → 0 % GST, taxable = net.
- **Send the options you billed with on every bill**: `grace_minutes`, `gst_inclusive`, `penalty_gst_flag`,
  `gst_flag`. The server recomputes with them and stores them, so a later settings change never flags old
  offline bills. Omitted → the customer's settings at the time the bill arrives, then the defaults.
- Slab examples: 0–24 h ₹30 only → 2 bags × 2 days = 2 × (30 + 30) = ₹120 · 0–24 h ₹30 + 24–48 h ₹10 →
  2 bags × 3 days = 2 × (30 + 10 + 10) = ₹100 · 0–4 h ₹10 + 4–24 h ₹20 → 3 h ₹10, 5 h ₹30, 25 h ₹50 ·
  the two-rate tariff 24 h / ₹50 / ₹20 is the slabs 0–24 h ₹50 + 24–48 h ₹20 (same bills as before).
- Store the snapshot on the bill: `rate_slabs` (plus the two-rate fields if you have them) and `gst_pct`.
- **Advance** (`ADVANCE_FLAG = Y`): at check-in take `adv_amt` = the bill for `ADVANCE_HOURS` with the same
  rate and GST setting and save `adv_hours`; print it on the receipt. At check-out `net_amt` is the full bill,
  `adv_amt` is subtracted and `bal_amt` is collected (Cash or UPI). The GST breakup is printed on the bill
  only. Reports count the advance on the check-in date and `bal_amt` on the bill date.
- One bill per bag transaction at check-out; pay mode Cash or UPI.

### Receipt / bill numbers
- Format `<user_code>-<5 digits>` (e.g. `U03-00125`; more digits after 99999). Two series per user:
  receipts (check-in) and bills (check-out). The series belongs to the **user**, not the device.
- Reset by `RECEIPT_RESET`: `D` daily (period `YYYY-MM-DD`), `M` monthly (`YYYY-MM`), `Y` Indian
  financial year (`FY2026-27`, April–March), `N` never (one period `ALL`); periods use the IST date.
- Next number = max(local last number, server `counters.*_last_no` for the current `period_key`) + 1.
  Login, pull and push return `counters`; the server never lowers them.

---

## 8. Settings keys

Values travel as strings. **Scope** "customer" = only the customer-wide row exists (a per-user value is
refused with `400`); "per user" = a user's own row overrides the customer-wide one. Login and pull
send `settings` for the signed-in user with **every key**: the stored value, else the default. Keys
without a default are absent until set: `GST_PCT` (absent = use the `md_gst` rate of the bill date)
and `REPORT_PWD` (never sent to devices). "Server" = the server itself uses the value (everything else
is stored, validated and synced for the app). Roles A and S write settings with `POST/PATCH /settings`;
`SINGLE_SESSION_FLAG` only S (`403` for A). `SINGLE_SESSION_FLAG = N` lets a user of that station be
signed in on several devices at once (no `409 SESSION_ACTIVE`); every session still takes a seat of
`user_limit`. `GST_PCT` set → bills are checked with that rate instead of `md_gst` (the value in force
when the bill reaches the server).

| Key | Group | Values | Default | Scope | Server | Meaning |
|---|---|---|---|---|---|---|
| `REPORT_FLAG` | Reports | Y / N | `N` | per user |  | Counter users can open reports. Admins always can. Off: operators see no reports. |
| `REPORT_PWD_FLAG` | Reports | Y / N | `N` | per user |  | Ask for a report password. Reports then need the password set below. |
| `REPORT_PWD` | Reports | 4–64 characters | — | per user |  | Report password. 4–64 characters. Stored hashed; leave empty to keep the current one. |
| `TOTAL_COLLECTION_FLAG` | Reports | Y / N | `Y` | per user |  | Show total collection. Show the day’s total collection on the counter. |
| `RECEIPT_RESET` | Numbers & sync | `D` Every day, `M` Every month, `Y` Every financial year (April), `N` Never | `Y` | per user |  | Receipt and bill numbers restart. Each user has their own series (e.g. U03-00125). |
| `SYNC_INTERVAL_MIN` | Numbers & sync | 15–1440 minutes | `15` | per user |  | Background sync every. Devices also sync after each check-in, check-out and when the internet returns. |
| `FORCE_ONLINE_DAYS` | Sign-in & security | 0–365 days | `0` | per user |  | Require going online after. 0 = never. Devices offline for longer must connect before billing. |
| `GST_FLAG` | Billing | Y / N | `Y` | per user | yes | Charge GST. Off: the station is not GST-registered; bills carry 0 % GST. |
| `GST_TYPE` | Billing | `E` Intra-state (CGST + SGST), `I` Inter-state (IGST) | `E` | per user | yes | GST type. Intra-state splits GST into CGST + SGST; inter-state charges IGST. |
| `QR_FLAG` | Check-in | Y / N | `Y` | per user |  | QR code on check-in receipts. Lets any counter check the bag out by scanning, even before it syncs. |
| `DEFAULT_PAY_MODE` | Billing | `C` Cash, `U` UPI | `C` | per user |  | Default payment mode. Pre-selected at check-out. |
| `PAPER_WIDTH` | Printing | `58` 58 mm, `80` 80 mm | `58` | per user |  | Printer paper width. Thermal paper roll used by the counters. |
| `GST_PCT` | Billing | 0.00–28.00 (%) | — | customer | yes | Station GST rate. Only if this station uses its own GST %; replaces the platform GST rate on its bills. |
| `PENALTY_FLAG` | Penalty & limits | Y / N | `Y` | customer |  | Allow a penalty at check-out. The counter may add a penalty (e.g. for a damaged receipt). |
| `PENALTY_MAX_AMT` | Penalty & limits | 0.00–99999.99 | `500.00` | customer |  | Highest penalty. Largest penalty the counter may type. |
| `PENALTY_GST_FLAG` | Penalty & limits | Y / N | `Y` | customer | yes | GST on the penalty. Off: GST applies to the rent only; the penalty is outside the taxable value. |
| `GRACE_MINUTES` | Billing | 0–1440 minutes | `0` | customer | yes | Grace period. Minutes free before the next block is charged (e.g. 24 h 10 min with 15 min grace = 1 day). |
| `GST_INCLUSIVE` | Billing | Y / N | `Y` | customer | yes | Rates include GST. On: GST is inside the tariff. Off: GST is added on top of the tariff. |
| `UPI_ENABLED` | Billing | Y / N | `Y` | customer |  | Accept UPI. Show the UPI option and the payment QR at check-out. |
| `OUT_WITHOUT_PRINT_FLAG` | Penalty & limits | Y / N | `Y` | customer |  | Allow “Out without bill print”. The counter may close a bag without printing the bill. |
| `SINGLE_SESSION_FLAG` | Sign-in & security | Y / N | `Y` | customer | yes | One device per user at a time. Off: a user of this station may be signed in on several devices; each still takes a licensed seat. Only Synergic can change this. |
| `MAX_BAGS_PER_RECEIPT` | Check-in | 1–999 bags | `10` | customer |  | Most bags on one receipt. Largest number of bags in a single check-in. |
| `PNR_REQUIRED` | Check-in | Y / N | `Y` | customer |  | PNR is mandatory. The counter must enter the passenger’s PNR. |
| `ID_PROOF_REQUIRED` | Check-in | Y / N | `Y` | customer |  | ID proof is mandatory. The counter must record an ID document and number. |
| `ID_MASK_FLAG` | Check-in | Y / N | `Y` | customer |  | Mask every ID number. Keep only the last 4 characters of any ID number (not only Aadhaar). |
| `SHIFT_LOGIN_FLAG` | Sign-in & security | Y / N | `Y` | customer | yes | Counter users sign in only during their shift. Applies to counter users who have a shift in the roster; admins are not limited. |
| `SHIFT_EARLY_LOGIN_MIN` | Sign-in & security | 0–120 minutes | `15` | customer | yes | Sign-in opens before the shift. Minutes before the shift starts when counter users may sign in. |
| `PWD_MIN_LENGTH` | Sign-in & security | 8–32 characters | `8` | customer |  | Shortest password on the device. The server always requires at least 8 characters with a letter and a digit. |
| `ADVANCE_FLAG` | Check-in | Y / N | `N` | customer |  | Take an advance at check-in. The counter collects the rent for the advance hours at check-in; the bill then asks only for the rest (no refund when the bill is smaller). |
| `ADVANCE_HOURS` | Check-in | 1–720 hours | `24` | customer |  | Advance covers. Hours of rent collected as the advance. |
| `CHECKIN_PRINT_FLAG` | Printing | Y / N | `Y` | per user |  | Print a receipt at check-in. Can be set per user below. |
| `PRINT_COPIES` | Printing | 1–3 copies | `1` | per user |  | Copies per print. Can be set per user below. |

---

## 9. Reports (A, S; O only when `REPORT_FLAG = Y`)

Common query: `from`, `to` (IST dates, inclusive, ≤ 366 days, required except bags-in-store/exceptions),
`user_id`, `shift_id`, `pay_mode` (`C`/`U`), `page`, `limit`, `customer_id` (**required for S**).
`meta` has `page, limit, total, totals, filters`. Timestamps come with `*_ist` copies for display.
When `REPORT_PWD_FLAG = Y` send header `X-Report-Password: <password>` (else `403 REPORT_PASSWORD_REQUIRED`).

#### `GET /api/v1/reports/collection-summary` — day-wise collection

**Auth / roles:** A, S, (O)

Response `200`:
```json
{
  "success": true,
  "message": "OK",
  "data": [
    {
      "bill_dt": "2026-11-17",
      "bills": 0,
      "bags": 0,
      "base_amt": "0.00",
      "penal_amt": "0.00",
      "net_amt": "0.00",
      "adv_amt": "0.00",
      "bal_amt": "0.00",
      "adv_collected": "50.00",
      "cash_amt": "50.00",
      "upi_amt": "0.00",
      "collected_amt": "50.00"
    },
    {
      "bill_dt": "2026-11-18",
      "bills": 2,
      "bags": 2,
      "base_amt": "120.00",
      "penal_amt": "0.00",
      "net_amt": "120.00",
      "adv_amt": "50.00",
      "bal_amt": "70.00",
      "adv_collected": "0.00",
      "cash_amt": "50.00",
      "upi_amt": "20.00",
      "collected_amt": "70.00"
    }
  ],
  "meta": {
    "page": 1,
    "limit": 50,
    "total": 2,
    "totals": {
      "bills": 2,
      "bags": 2,
      "base_amt": "120.00",
      "penal_amt": "0.00",
      "net_amt": "120.00",
      "adv_amt": "50.00",
      "bal_amt": "70.00",
      "adv_collected": "50.00",
      "cash_amt": "100.00",
      "upi_amt": "20.00",
      "collected_amt": "120.00"
    },
    "filters": {
      "page": 1,
      "limit": 50,
      "from": "2026-11-17",
      "to": "2026-11-18"
    }
  },
  "requestId": "f6b01b19-a64d-49b4-b3b2-2d96ea32730b"
}
```

#### `GET /api/v1/reports/check-in-register` — check-ins in the range

**Auth / roles:** A, S, (O)

Response `200`:
```json
{
  "success": true,
  "message": "OK",
  "data": [
    {
      "trans_id": "4d7b8931-3a81-48a3-aeb5-d92d46e185ff",
      "receipt_no": "U03-00002",
      "receipt_dt": "2026-11-17",
      "date_time_in": "2026-11-17 05:16:48",
      "date_time_in_ist": "2026-11-17 10:46:48",
      "user_id_in": 3,
      "user_name_in": "Ravi Kumar Singh",
      "shift_id_in": 1,
      "shift_name_in": "Morning",
      "pasn_name": "Meera Iyer",
      "pasn_mob_no": "9123456780",
      "pnr_no": "8812345670",
      "luggage_qty": 1,
      "extra_lugage_qty": 1,
      "adv_amt": "50.00",
      "adv_hours": 24,
      "id_proof_id": 3,
      "id_proof_name": "Passport",
      "id_number": "P1234567",
      "out_flag": "Y",
      "date_time_out": "2026-11-18 06:16:48",
      "date_time_out_ist": "2026-11-18 11:46:48"
    },
    {
      "trans_id": "dfaafe0e-6c85-4fbe-ac8a-f3dc5bf89434",
      "receipt_no": "U03-00003",
      "receipt_dt": "2026-11-18",
      "date_time_in": "2026-11-18 04:16:48",
      "date_time_in_ist": "2026-11-18 09:46:48",
      "user_id_in": 3,
      "user_name_in": "Ravi Kumar Singh",
      "shift_id_in": 1,
      "shift_name_in": "Morning",
      "pasn_name": "Arjun Sen",
      "pasn_mob_no": "9000011111",
      "pnr_no": "",
      "luggage_qty": 1,
      "extra_lugage_qty": 0,
      "adv_amt": "0.00",
      "adv_hours": 0,
      "id_proof_id": 3,
      "id_proof_name": "Passport",
      "id_number": "Z7654321",
      "out_flag": "Y",
      "date_time_out": "2026-11-18 06:46:48",
      "date_time_out_ist": "2026-11-18 12:16:48"
    },
    "… 1 more item(s) of the same shape"
  ],
  "meta": {
    "page": 1,
    "limit": 50,
    "total": 3,
    "totals": {
      "bags_entries": 3,
      "bags": 4,
      "extra_bags": 1,
      "adv_amt": "50.00"
    },
    "filters": {
      "page": 1,
      "limit": 50,
      "from": "2026-11-17",
      "to": "2026-11-18"
    }
  },
  "requestId": "220894ff-b5bc-4799-a00a-6cfa4222c1b6"
}
```

#### `GET /api/v1/reports/check-out-register` — check-outs with bills

**Auth / roles:** A, S, (O)

Response `200`:
```json
{
  "success": true,
  "message": "OK",
  "data": [
    {
      "trans_id": "4d7b8931-3a81-48a3-aeb5-d92d46e185ff",
      "receipt_no": "U03-00002",
      "receipt_dt": "2026-11-17",
      "date_time_in": "2026-11-17 05:16:48",
      "date_time_in_ist": "2026-11-17 10:46:48",
      "user_id_in": 3,
      "user_name_in": "Ravi Kumar Singh",
      "shift_id_in": 1,
      "shift_name_in": "Morning",
      "pasn_name": "Meera Iyer",
      "pasn_mob_no": "9123456780",
      "pnr_no": "8812345670",
      "luggage_qty": 1,
      "extra_lugage_qty": 1,
      "adv_amt": "50.00",
      "adv_hours": 24,
      "id_proof_id": 3,
      "id_proof_name": "Passport",
      "id_number": "P1234567",
      "out_flag": "Y",
      "date_time_out": "2026-11-18 06:16:48",
      "date_time_out_ist": "2026-11-18 11:46:48",
      "user_id_out": 3,
      "user_name_out": "Ravi Kumar Singh",
      "shift_id_out": 1,
      "shift_name_out": "Morning",
      "bill_no": "U03-00001",
      "bill_dt": "2026-11-18",
      "days_charged": 2,
      "net_amt": "70.00",
      "bill_adv_amt": "50.00",
      "bal_amt": "20.00",
      "pay_mode": "U",
      "bill_printed": "Y"
    },
    {
      "trans_id": "dfaafe0e-6c85-4fbe-ac8a-f3dc5bf89434",
      "receipt_no": "U03-00003",
      "receipt_dt": "2026-11-18",
      "date_time_in": "2026-11-18 04:16:48",
      "date_time_in_ist": "2026-11-18 09:46:48",
      "user_id_in": 3,
      "user_name_in": "Ravi Kumar Singh",
      "shift_id_in": 1,
      "shift_name_in": "Morning",
      "pasn_name": "Arjun Sen",
      "pasn_mob_no": "9000011111",
      "pnr_no": "",
      "luggage_qty": 1,
      "extra_lugage_qty": 0,
      "adv_amt": "0.00",
      "adv_hours": 0,
      "id_proof_id": 3,
      "id_proof_name": "Passport",
      "id_number": "Z7654321",
      "out_flag": "Y",
      "date_time_out": "2026-11-18 06:46:48",
      "date_time_out_ist": "2026-11-18 12:16:48",
      "user_id_out": 4,
      "user_name_out": "Anita Das",
      "shift_id_out": null,
      "shift_name_out": null,
      "bill_no": "U04-00001",
      "bill_dt": "2026-11-18",
      "days_charged": 1,
      "net_amt": "50.00",
      "bill_adv_amt": "0.00",
      "bal_amt": "50.00",
      "pay_mode": "C",
      "bill_printed": "Y"
    }
  ],
  "meta": {
    "page": 1,
    "limit": 50,
    "total": 2,
    "totals": {
      "bags_entries": 2,
      "bags": 2,
      "extra_bags": 1,
      "net_amt": "120.00",
      "adv_amt": "50.00",
      "bal_amt": "70.00"
    },
    "filters": {
      "page": 1,
      "limit": 50,
      "from": "2026-11-17",
      "to": "2026-11-18"
    }
  },
  "requestId": "86f49b8d-5f10-460f-afce-cf7a4caf6d0d"
}
```

#### `GET /api/v1/reports/bags-in-store` — bags currently in store

**Auth / roles:** A, S, (O)

`from`/`to` optional (check-in date window).

Response `200`:
```json
{
  "success": true,
  "message": "OK",
  "data": [
    {
      "trans_id": "22f0781e-4366-4ac6-aa22-94f2e9f9adb9",
      "receipt_no": "U03-00001",
      "receipt_dt": "2026-11-18",
      "date_time_in": "2026-11-18 05:16:48",
      "date_time_in_ist": "2026-11-18 10:46:48",
      "user_id_in": 3,
      "user_name_in": "Ravi Kumar Singh",
      "shift_id_in": 1,
      "shift_name_in": "Morning",
      "pasn_name": "Suresh Patel",
      "pasn_mob_no": "9876543210",
      "pnr_no": "4521367890",
      "luggage_qty": 2,
      "extra_lugage_qty": 0,
      "adv_amt": "0.00",
      "adv_hours": 0,
      "id_proof_id": 1,
      "id_proof_name": "Aadhaar Card",
      "id_number": "XXXXXXXX9012",
      "out_flag": "N",
      "minutes_stored": 120
    }
  ],
  "meta": {
    "page": 1,
    "limit": 50,
    "total": 1,
    "totals": {
      "bags_entries": 1,
      "bags": 2,
      "extra_bags": 0,
      "adv_amt": "0.00"
    },
    "filters": {
      "page": 1,
      "limit": 50
    }
  },
  "requestId": "bbb34dfa-a91c-4427-814a-164f48afe4dc"
}
```

#### `GET /api/v1/reports/gst` — GST by day and rate

**Auth / roles:** A, S, (O)

Response `200`:
```json
{
  "success": true,
  "message": "OK",
  "data": [
    {
      "bill_dt": "2026-11-18",
      "gst_pct": "18.00",
      "bills": 2,
      "taxable_amt": "101.69",
      "cgst": "9.16",
      "sgst": "9.15",
      "igst": "0.00",
      "net_amt": "120.00"
    }
  ],
  "meta": {
    "page": 1,
    "limit": 50,
    "total": 1,
    "totals": {
      "bills": 2,
      "taxable_amt": "101.69",
      "cgst": "9.16",
      "sgst": "9.15",
      "igst": "0.00",
      "net_amt": "120.00"
    },
    "filters": {
      "page": 1,
      "limit": 50,
      "from": "2026-11-17",
      "to": "2026-11-18"
    }
  },
  "requestId": "96a47a27-982b-4bc7-ab9c-b6a6db4a55cb"
}
```

#### `GET /api/v1/reports/user-shift-collection` — collection per user and shift

**Auth / roles:** A, S, (O)

Response `200`:
```json
{
  "success": true,
  "message": "OK",
  "data": [
    {
      "user_id": 4,
      "user_name": "Anita Das",
      "user_code": "U04",
      "shift_id": null,
      "shift_name": null,
      "bills": 1,
      "bags": 1,
      "base_amt": "50.00",
      "penal_amt": "0.00",
      "net_amt": "50.00",
      "adv_amt": "0.00",
      "bal_amt": "50.00",
      "adv_collected": "0.00",
      "cash_amt": "50.00",
      "upi_amt": "0.00",
      "collected_amt": "50.00"
    },
    {
      "user_id": 3,
      "user_name": "Ravi Kumar Singh",
      "user_code": "U03",
      "shift_id": 1,
      "shift_name": "Morning",
      "bills": 1,
      "bags": 1,
      "base_amt": "70.00",
      "penal_amt": "0.00",
      "net_amt": "70.00",
      "adv_amt": "50.00",
      "bal_amt": "20.00",
      "adv_collected": "50.00",
      "cash_amt": "50.00",
      "upi_amt": "20.00",
      "collected_amt": "70.00"
    }
  ],
  "meta": {
    "page": 1,
    "limit": 50,
    "total": 2,
    "totals": {
      "bills": 2,
      "bags": 2,
      "base_amt": "120.00",
      "penal_amt": "0.00",
      "net_amt": "120.00",
      "adv_amt": "50.00",
      "bal_amt": "70.00",
      "adv_collected": "50.00",
      "cash_amt": "100.00",
      "upi_amt": "20.00",
      "collected_amt": "120.00"
    },
    "filters": {
      "page": 1,
      "limit": 50,
      "from": "2026-11-17",
      "to": "2026-11-18"
    }
  },
  "requestId": "764ed147-1547-4447-ade6-2e560ba93dd5"
}
```

#### `GET /api/v1/reports/unprinted-bills` — "Out Without Bill Print" bills

**Auth / roles:** A, S, (O)

Response `200`:
```json
{
  "success": true,
  "message": "OK",
  "data": [],
  "meta": {
    "page": 1,
    "limit": 50,
    "total": 0,
    "totals": {
      "bills": 0,
      "net_amt": "0.00",
      "bal_amt": "0.00"
    },
    "filters": {
      "page": 1,
      "limit": 50,
      "from": "2026-11-17",
      "to": "2026-11-18"
    }
  },
  "requestId": "6336456c-e4c0-4078-bf66-b10d8d358385"
}
```

#### `GET /api/v1/reports/exceptions` — sync exceptions to review

**Auth / roles:** A, S, (O)

| Query param | Meaning |
|---|---|
| `resolved` | `Y` / `N` |
| `table_name` | `td_luggage_in_out` / `td_receipt` |
| `from, to` | optional (created date, IST) |

Response `200`:
```json
{
  "success": true,
  "message": "OK",
  "data": [
    {
      "id": 1,
      "trans_id": "4a1f4596-83cc-4081-8232-2578532f4c82",
      "table_name": "td_receipt",
      "reason": "BILL_WITHOUT_LUGGAGE: no luggage row for this trans_id",
      "payload": {
        "cgst": "5.34",
        "igst": "0.00",
        "sgst": "5.34",
        "adv_amt": "50.00",
        "bal_amt": "20.00",
        "bill_dt": "2026-11-18",
        "bill_no": "U03-00002",
        "gst_pct": "18.00",
        "net_amt": "70.00",
        "upi_ref": "432198765012",
        "user_id": 3,
        "base_amt": "70.00",
        "gst_flag": "Y",
        "pay_mode": "U",
        "shift_id": 1,
        "trans_id": "4a1f4596-83cc-4081-8232-2578532f4c82",
        "device_id": "POS-HWH-01",
        "penal_amt": "0.00",
        "rate_slabs": [
          {
            "rate": "50.00",
            "to_hour": 24,
            "from_hour": 0
          },
          {
            "rate": "20.00",
            "to_hour": 48,
            "from_hour": 24
          }
        ],
        "block_hours": 24,
        "luggage_qty": 1,
        "taxable_amt": "59.32",
        "bill_printed": "Y",
        "days_charged": 2,
        "grace_minutes": 0,
        "gst_inclusive": "Y",
        "local_version": 1,
        "next_block_rate": "20.00",
        "first_block_rate": "50.00",
        "penalty_gst_flag": "Y"
      },
      "user_id": 3,
      "user_name": "Ravi Kumar Singh",
      "device_id": "POS-HWH-01",
      "resolved_flag": "N",
      "resolved_by": null,
      "resolved_at": null,
      "created_at": "2026-11-18 07:16:48",
      "created_at_ist": "2026-11-18 12:46:48"
    }
  ],
  "meta": {
    "page": 1,
    "limit": 50,
    "total": 1,
    "totals": {
      "exceptions": 1,
      "unresolved": 1
    },
    "filters": {
      "page": 1,
      "limit": 50,
      "resolved": "N"
    }
  },
  "requestId": "3d37f51d-35ba-4058-98f8-db7423e43862"
}
```

#### `PATCH /api/v1/reports/exceptions/:id/resolve` — mark an exception resolved (idempotent)

**Auth / roles:** A, S

Response `200`:
```json
{
  "success": true,
  "message": "Exception resolved",
  "data": {
    "id": 1,
    "customer_id": 1,
    "trans_id": "4a1f4596-83cc-4081-8232-2578532f4c82",
    "table_name": "td_receipt",
    "payload": {
      "cgst": "5.34",
      "igst": "0.00",
      "sgst": "5.34",
      "adv_amt": "50.00",
      "bal_amt": "20.00",
      "bill_dt": "2026-11-18",
      "bill_no": "U03-00002",
      "gst_pct": "18.00",
      "net_amt": "70.00",
      "upi_ref": "432198765012",
      "user_id": 3,
      "base_amt": "70.00",
      "gst_flag": "Y",
      "pay_mode": "U",
      "shift_id": 1,
      "trans_id": "4a1f4596-83cc-4081-8232-2578532f4c82",
      "device_id": "POS-HWH-01",
      "penal_amt": "0.00",
      "rate_slabs": [
        {
          "rate": "50.00",
          "to_hour": 24,
          "from_hour": 0
        },
        {
          "rate": "20.00",
          "to_hour": 48,
          "from_hour": 24
        }
      ],
      "block_hours": 24,
      "luggage_qty": 1,
      "taxable_amt": "59.32",
      "bill_printed": "Y",
      "days_charged": 2,
      "grace_minutes": 0,
      "gst_inclusive": "Y",
      "local_version": 1,
      "next_block_rate": "20.00",
      "first_block_rate": "50.00",
      "penalty_gst_flag": "Y"
    },
    "reason": "BILL_WITHOUT_LUGGAGE: no luggage row for this trans_id",
    "user_id": 3,
    "device_id": "POS-HWH-01",
    "resolved_flag": "Y",
    "resolved_by": "howrah.admin",
    "resolved_at": "2026-11-18 07:16:48",
    "created_at": "2026-11-18 07:16:48"
  },
  "requestId": "0f7813d0-d1df-4537-aa69-ff0581aaf823"
}
```

Operator without `REPORT_FLAG`:
Response `403`:
```json
{
  "success": false,
  "message": "Reports are not enabled for operators",
  "code": "FORBIDDEN",
  "data": null,
  "requestId": "d6a13e6f-25a5-4120-bc66-38650d23ef2f"
}
```

Role denied example (operator calling `GET /users`):
Response `403`:
```json
{
  "success": false,
  "message": "Insufficient role",
  "code": "FORBIDDEN",
  "data": null,
  "requestId": "f07b3f22-5a39-4a4b-80c1-c0d4a9d51a93"
}
```

---

## 10. Suggested screens → endpoints

| Screen | Endpoints |
|---|---|
| Splash / update check | `GET /app/version?current=<code>` |
| Login | `POST /auth/login` (handle 401/403/409 per §2) |
| POS home (offline) | local DB; background `POST /sync/push` then `GET /sync/pull` every `SYNC_INTERVAL_MIN` |
| Check-in | local insert (new `trans_id`, next receipt no), print receipt with QR, push later |
| Check-out (scan QR / search) | local row by `trans_id`; if missing and online `GET /luggage/:trans_id` or `GET /luggage?search=`; compute bill (§7); print; push |
| Admin: cloak room details | `GET /customers/me`, `PATCH /customers/me` |
| Admin: users | `/users` (+ allow, reset-password, force-logout) |
| Admin: masters | `/rates`, `/shifts`, `/shift-assignments`, `/header-footer` (+ `/logo`), `/settings` |
| Admin: reports | `/reports/*`, exceptions + resolve |
| Super admin | `/customers` (+ summary, registrations, payments), `/gst`, `/id-proofs`, `POST /app/version` |
| Logout | push pending → `POST /auth/logout` |

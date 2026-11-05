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
  "requestId": "44320dbb-333e-473e-ac58-f3fe9707ca87"
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
      "customer_addr": "Howrah Station, Platform 1",
      "state": "West Bengal",
      "pin_code": "711101",
      "mobile_no": "9830012345",
      "email": "ops@howrahcloak.in",
      "gst_no": "19ABCDE1234F1Z5",
      "upi_id": "howrahcloak@okaxis",
      "renewal_dt": "2027-11-02",
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
          "created_at": "2026-11-03 14:05:22",
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
          "created_at": "2026-11-03 14:05:22",
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
          "created_at": "2026-11-03 14:05:22",
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
          "effective_from": "2026-01-01",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "superadmin",
          "created_at": "2026-11-03 14:05:31",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-03 14:05:32"
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
          "created_at": "2026-11-03 14:05:32",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-03 14:05:32"
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
          "created_at": "2026-11-03 14:05:32",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-03 14:05:32"
        }
      ],
      "shift_assignments": [
        {
          "id": 1,
          "customer_id": 1,
          "user_id": 3,
          "shift_id": 1,
          "effective_from": "2026-11-04",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "howrah.admin",
          "created_at": "2026-11-03 14:05:32",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-03 14:05:32"
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
          "created_at": "2026-11-03 14:05:32",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-03 14:05:32"
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
          "created_at": "2026-11-03 14:05:32",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-03 14:05:32"
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
          "updated_at": "2026-11-03 14:05:31"
        },
        {
          "id": 3,
          "customer_id": 1,
          "user_code": "U03",
          "user_name": "Ravi Kumar Singh",
          "user_type": "O",
          "allow_flag": "Y",
          "is_deleted": "N",
          "updated_at": "2026-11-03 14:05:32"
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
      "GST_FLAG": "Y",
      "GST_TYPE": "E",
      "QR_FLAG": "Y",
      "DEFAULT_PAY_MODE": "C",
      "PAPER_WIDTH": "58"
    },
    "counters": {
      "reset": "Y",
      "period_key": "FY2026-27",
      "receipt_last_no": 0,
      "bill_last_no": 0
    },
    "server_time": "2026-11-03 14:05:32"
  },
  "requestId": "642b577c-8646-4019-a39d-2136501104b7"
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
          "created_at": "2026-11-03 14:05:22",
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
          "created_at": "2026-11-03 14:05:22",
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
          "created_at": "2026-11-03 14:05:22",
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
    "server_time": "2026-11-03 14:05:31"
  },
  "requestId": "0f14d4cd-4d08-44ac-a858-374e17a966c0"
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
    "session_started_at": "2026-11-03 14:05:32",
    "device_id": "******H-01"
  },
  "requestId": "de861542-abf2-42e9-9680-b6d3a2e6daf5"
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
  "requestId": "4b17f520-495b-48a6-875e-e0ab079af9b0"
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
  "requestId": "aa6b154d-5ce0-4546-8c0c-f81c16645975"
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
      "created_at": "2026-11-03 14:05:33"
    }
  },
  "requestId": "bc6e0522-e3c8-47f6-a699-e1b1577a62ad"
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
  "requestId": "7d58c66a-89d4-4bf4-9980-e65e288a6afa"
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
  "requestId": "fa46cf7c-e884-4476-bf97-adeb893d30f8"
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
  "requestId": "eeb00451-2df8-4256-9975-666d9c1c6b43"
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
    "server_time": "2026-11-03 14:05:33"
  },
  "requestId": "b5aba4b6-82b6-441a-8684-9b6c17ccab26"
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
  "requestId": "1513eb97-f44b-4def-abbc-ef12af45d9a3"
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
  "requestId": "1f095248-9570-4534-a4a0-a92e29349e13"
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
      "session_started_at": "2026-11-03 14:05:32",
      "last_login": "2026-11-03 14:05:32"
    },
    "customer": {
      "customer_id": 1,
      "customer_name": "Howrah Cloak Room",
      "customer_addr": "Howrah Station, Platform 1",
      "state": "West Bengal",
      "pin_code": "711101",
      "mobile_no": "9830012345",
      "email": "ops@howrahcloak.in",
      "gst_no": "19ABCDE1234F1Z5",
      "upi_id": "howrahcloak@okaxis",
      "renewal_dt": "2027-11-02",
      "user_limit": 5,
      "active_flag": "Y"
    },
    "session_id": "c7898688-29ff-44cb-b7b8-f9d9afbfb229",
    "server_time": "2026-11-03 14:05:32"
  },
  "requestId": "f6c3a5b5-ab3c-41df-a12f-6a7595296073"
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
  "requestId": "6b3459ba-7e6c-4d32-a043-63be94e8a4c1"
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
  "requestId": "08f512b5-1258-40ed-94cd-060bcb80af6a"
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
    "server_time": "2026-11-03 14:05:31"
  },
  "requestId": "77208a0f-d176-44f2-8973-9c10becec19f"
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
    "server_time": "2026-11-03 14:05:31"
  },
  "requestId": "0bcceb4c-2981-4168-b423-5442b850d88a"
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
      "created_at": "2026-11-03 14:05:33"
    },
    "update_available": true,
    "mandatory": true
  },
  "requestId": "dba50b75-5c04-475a-bdfd-ef4e5c7f015c"
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
    "created_at": "2026-11-03 14:05:32",
    "updated_by": "howrah.admin",
    "updated_at": "2026-11-03 14:05:32"
  },
  "requestId": "ba63ac30-f7b7-4924-bc37-d8f3350efcf9"
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
  "requestId": "3e7cfed2-a138-4d56-85c9-3473d22d4642"
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
      "created_at": "2026-11-03 14:05:32",
      "updated_by": "howrah.admin",
      "updated_at": "2026-11-03 14:05:32"
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
      "created_at": "2026-11-03 14:05:32",
      "updated_by": "howrah.admin",
      "updated_at": "2026-11-03 14:05:32"
    }
  ],
  "meta": {
    "page": 1,
    "limit": 50,
    "total": 2
  },
  "requestId": "ba94b5cc-ce61-4383-b2d9-8d2af6add4e6"
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
    "created_at": "2026-11-03 14:05:32",
    "updated_by": "howrah.admin",
    "updated_at": "2026-11-03 14:05:32"
  },
  "requestId": "26492b56-6e6f-4069-8dc0-58609b0c180b"
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
    "last_login": "2026-11-03 14:05:33",
    "created_by": "howrah.admin",
    "created_at": "2026-11-03 14:05:32",
    "updated_by": "howrah.admin",
    "updated_at": "2026-11-03 14:05:33"
  },
  "requestId": "23c7537f-059a-4963-9adb-e5e4018d7010"
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
  "requestId": "065a1ac2-a069-4769-9a7b-9847a7dad419"
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
  "requestId": "213e0f93-1c3d-4655-b803-5bd2d4c8c22f"
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
  "requestId": "eeb8b10c-7628-4565-b38d-b16a8743bbb6"
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

Versioned by `effective_from`: the bill uses the latest rate with `effective_from ≤ receipt_dt`. One row per date per customer (`409` on duplicates). A new customer gets 24 h / ₹50 / ₹20 automatically.

#### `POST /api/v1/rates` — create

**Auth / roles:** A, S

| Body field | Rules |
|---|---|
| `block_hours` | integer 1–720, default 24 |
| `first_block_rate` | money, per bag for the first block |
| `next_block_rate` | money, per bag for each further started block |
| `effective_from` | `YYYY-MM-DD` |

Request body:
```json
{
  "block_hours": 24,
  "first_block_rate": "60.00",
  "next_block_rate": "25.00",
  "effective_from": "2027-01-02"
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
    "first_block_rate": "60.00",
    "next_block_rate": "25.00",
    "effective_from": "2027-01-02",
    "is_deleted": "N",
    "sync_flag": "N",
    "created_by": "howrah.admin",
    "created_at": "2026-11-03 14:05:32",
    "updated_by": "howrah.admin",
    "updated_at": "2026-11-03 14:05:32"
  },
  "requestId": "0d0b5440-c585-41c1-b158-c1aec95947e4"
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
      "effective_from": "2026-11-03",
      "is_deleted": "N",
      "sync_flag": "N",
      "created_by": "superadmin",
      "created_at": "2026-11-03 14:05:31",
      "updated_by": "superadmin",
      "updated_at": "2026-11-03 14:05:31"
    }
  ],
  "meta": {
    "page": 1,
    "limit": 50,
    "total": 1
  },
  "requestId": "9f605b21-d2d2-4815-af79-eabf2f244050"
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
    "effective_from": "2026-01-01",
    "is_deleted": "N",
    "sync_flag": "N",
    "created_by": "superadmin",
    "created_at": "2026-11-03 14:05:31",
    "updated_by": "howrah.admin",
    "updated_at": "2026-11-03 14:05:32"
  },
  "requestId": "5078b031-e3be-46de-8cdd-6de073f43033"
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
  "requestId": "64b59df7-a05e-40e4-b2da-af956889311c"
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
    "created_at": "2026-11-03 14:05:32",
    "updated_by": "howrah.admin",
    "updated_at": "2026-11-03 14:05:32"
  },
  "requestId": "d60b61a5-2526-41e6-8547-cde8a05f5d33"
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
      "created_at": "2026-11-03 14:05:32",
      "updated_by": "howrah.admin",
      "updated_at": "2026-11-03 14:05:32"
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
      "created_at": "2026-11-03 14:05:32",
      "updated_by": "howrah.admin",
      "updated_at": "2026-11-03 14:05:32"
    }
  ],
  "meta": {
    "page": 1,
    "limit": 50,
    "total": 2
  },
  "requestId": "d9677172-0fb7-4ac5-a11f-91756466967b"
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
    "created_at": "2026-11-03 14:05:32",
    "updated_by": "howrah.admin",
    "updated_at": "2026-11-03 14:05:32"
  },
  "requestId": "d0f5a472-525b-4b2f-a51a-46cbbaf11240"
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
  "effective_from": "2026-11-03"
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
    "effective_from": "2026-11-03",
    "is_deleted": "N",
    "sync_flag": "N",
    "created_by": "howrah.admin",
    "created_at": "2026-11-03 14:05:32",
    "updated_by": "howrah.admin",
    "updated_at": "2026-11-03 14:05:32"
  },
  "requestId": "eb348c51-e54d-4582-8700-49a1a76237e5"
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
      "effective_from": "2026-11-03",
      "is_deleted": "N",
      "sync_flag": "N",
      "created_by": "howrah.admin",
      "created_at": "2026-11-03 14:05:32",
      "updated_by": "howrah.admin",
      "updated_at": "2026-11-03 14:05:32"
    }
  ],
  "meta": {
    "page": 1,
    "limit": 50,
    "total": 1
  },
  "requestId": "ac4ce955-5547-46c6-ad93-94c91c013658"
}
```

`GET /api/v1/shift-assignments/:id` returns `data` = one row of the same shape (or `404`).

#### `PATCH /api/v1/shift-assignments/:id` — update

**Auth / roles:** A, S

`shift_id` and/or `effective_from`.

Request body:
```json
{
  "effective_from": "2026-11-04"
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
    "effective_from": "2026-11-04",
    "is_deleted": "N",
    "sync_flag": "N",
    "created_by": "howrah.admin",
    "created_at": "2026-11-03 14:05:32",
    "updated_by": "howrah.admin",
    "updated_at": "2026-11-03 14:05:32"
  },
  "requestId": "3b7d2d96-9d8b-4fb3-9f06-6347e880ccc8"
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
    "created_at": "2026-11-03 14:05:32",
    "updated_by": "howrah.admin",
    "updated_at": "2026-11-03 14:05:32"
  },
  "requestId": "22d3ade3-1259-4db1-abc7-b44d8a0001e5"
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
      "created_at": "2026-11-03 14:05:32",
      "updated_by": "howrah.admin",
      "updated_at": "2026-11-03 14:05:32"
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
      "created_at": "2026-11-03 14:05:32",
      "updated_by": "howrah.admin",
      "updated_at": "2026-11-03 14:05:32"
    },
    "… 1 more item(s) of the same shape"
  ],
  "meta": {
    "page": 1,
    "limit": 50,
    "total": 3
  },
  "requestId": "9bcaa8d3-0e91-4ef7-b2e0-0561cd9790aa"
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
    "created_at": "2026-11-03 14:05:32",
    "updated_by": "howrah.admin",
    "updated_at": "2026-11-03 14:05:32"
  },
  "requestId": "176aeaf7-d73a-4465-ad3f-c210a728ffb8"
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
    "created_at": "2026-11-03 14:05:32",
    "updated_by": "howrah.admin",
    "updated_at": "2026-11-03 14:05:32"
  },
  "requestId": "94888b33-a85f-477c-99ba-9646d0724ea9"
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
    "created_at": "2026-11-03 14:05:32",
    "updated_by": "howrah.admin",
    "updated_at": "2026-11-03 14:05:32"
  },
  "requestId": "108bfe01-181e-4223-8175-ae3c4be63e85"
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
    "created_at": "2026-11-03 14:05:32",
    "updated_by": "howrah.admin",
    "updated_at": "2026-11-03 14:05:32"
  },
  "requestId": "726927e7-57f5-4d22-8fae-8a3b544bea0e"
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
      "created_at": "2026-11-03 14:05:31",
      "updated_by": "superadmin",
      "updated_at": "2026-11-03 14:05:31"
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
      "created_at": "2026-11-03 14:05:31",
      "updated_by": "superadmin",
      "updated_at": "2026-11-03 14:05:31"
    },
    "… 8 more item(s) of the same shape"
  ],
  "meta": {
    "page": 1,
    "limit": 100,
    "total": 10
  },
  "requestId": "60b62e39-b49e-4354-9f8f-aa693c333e6f"
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
    "created_at": "2026-11-03 14:05:31",
    "updated_by": "howrah.admin",
    "updated_at": "2026-11-03 14:05:32"
  },
  "requestId": "29a8d279-5694-417e-ac54-24f8f7d5f20e"
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
  "requestId": "cbc46303-bd50-43ce-aa62-d1080d47b3ef"
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
  "requestId": "484903cf-aa0a-4f70-8550-a9bef6b24594"
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
    "created_at": "2026-11-03 14:05:32",
    "updated_by": "superadmin",
    "updated_at": "2026-11-03 14:05:32"
  },
  "requestId": "d217f149-c8fe-4c4a-9dac-25770fcc4a0b"
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
      "created_at": "2026-11-03 14:05:22",
      "updated_by": null,
      "updated_at": null
    }
  ],
  "meta": {
    "page": 1,
    "limit": 50,
    "total": 1
  },
  "requestId": "e1565a34-8638-4814-ac0e-67695e7b0e8d"
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
  "requestId": "c0717a9f-c5e3-407c-9672-e8628df4c25b"
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
    "created_at": "2026-11-03 14:05:32",
    "updated_by": "superadmin",
    "updated_at": "2026-11-03 14:05:32"
  },
  "requestId": "32ba9726-4a1b-44a2-8d39-94faab914d70"
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
      "created_at": "2026-11-03 14:05:22",
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
      "created_at": "2026-11-03 14:05:22",
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
  "requestId": "181b5d5e-869a-4468-b51d-0e8b7c844620"
}
```

`GET /api/v1/id-proofs/:id` returns `data` = one row of the same shape (or `404`).

#### `PATCH /api/v1/id-proofs/:id` — update

**Auth / roles:** S

`id_proof_name`, `mask_flag`.

**Errors:** `400` · `401` · `403` · `404` · `409`

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
    "created_at": "2026-11-03 14:05:31",
    "updated_by": "superadmin",
    "updated_at": "2026-11-03 14:05:31"
  },
  "requestId": "29b4fb44-cf78-4781-a1ae-14b6e257425b"
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
      "created_at": "2026-11-03 14:05:31",
      "updated_by": "superadmin",
      "updated_at": "2026-11-03 14:05:31"
    }
  ],
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 1
  },
  "requestId": "2037f7d6-eb6c-48ce-99f4-3885d79d3ea0"
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
    "created_at": "2026-11-03 14:05:31",
    "updated_by": "superadmin",
    "updated_at": "2026-11-03 14:05:31"
  },
  "requestId": "3bbedaaa-e16b-4316-90c7-7bae2f5ecb9a"
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
      "customer_addr": "Howrah Station, Platform 1",
      "state": "West Bengal",
      "pin_code": "711101",
      "mobile_no": "9830012345",
      "email": "ops@howrahcloak.in",
      "renewal_dt": "2027-11-02",
      "gst_no": "19ABCDE1234F1Z5",
      "upi_id": "howrahcloak@okaxis",
      "user_limit": 5,
      "active_flag": "Y",
      "remarks": "Pilot station",
      "is_deleted": "N",
      "sync_flag": "N",
      "created_by": "superadmin",
      "created_at": "2026-11-03 14:05:31",
      "updated_by": "superadmin",
      "updated_at": "2026-11-03 14:05:31"
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
      "start_date": "2026-11-03",
      "end_date": "2027-11-02",
      "user_count": 5,
      "remarks": "Annual plan",
      "is_deleted": "N",
      "created_by": "superadmin",
      "created_at": "2026-11-03 14:05:31",
      "updated_by": "superadmin",
      "updated_at": "2026-11-03 14:05:31"
    },
    "payments": {
      "count": 1,
      "total_net": "5900.00",
      "last_payment_dt": "2026-11-03"
    },
    "luggage": {
      "total": 3,
      "in_store": 1
    }
  },
  "requestId": "3e3ef5d9-7626-42ae-9303-f3b2249d8b4a"
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
  "start_date": "2026-11-03",
  "end_date": "2027-11-02",
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
    "start_date": "2026-11-03",
    "end_date": "2027-11-02",
    "user_count": 5,
    "remarks": "Annual plan",
    "is_deleted": "N",
    "created_by": "superadmin",
    "created_at": "2026-11-03 14:05:31",
    "updated_by": "superadmin",
    "updated_at": "2026-11-03 14:05:31"
  },
  "requestId": "7104fa39-b442-4cfd-afe8-cc21dba67bbe"
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
      "start_date": "2026-11-03",
      "end_date": "2027-11-02",
      "user_count": 5,
      "remarks": "Annual plan",
      "is_deleted": "N",
      "created_by": "superadmin",
      "created_at": "2026-11-03 14:05:31",
      "updated_by": "superadmin",
      "updated_at": "2026-11-03 14:05:31"
    }
  ],
  "meta": {
    "page": 1,
    "limit": 50,
    "total": 1
  },
  "requestId": "3bf4b44e-80af-411c-be22-0b1141b77b49"
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
  "payment_dt": "2026-11-03",
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
    "payment_dt": "2026-11-03",
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
    "created_at": "2026-11-03 14:05:31",
    "updated_by": "superadmin",
    "updated_at": "2026-11-03 14:05:31"
  },
  "requestId": "73480a85-85ce-4768-adc2-ff8a7d66b854"
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
      "payment_dt": "2026-11-03",
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
      "created_at": "2026-11-03 14:05:31",
      "updated_by": "superadmin",
      "updated_at": "2026-11-03 14:05:31"
    }
  ],
  "meta": {
    "page": 1,
    "limit": 50,
    "total": 1
  },
  "requestId": "cae91df0-8ab3-4627-8594-66fdf97fee37"
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
    "created_at": "2026-11-03 14:05:33"
  },
  "requestId": "c5536e54-8207-48df-8001-71f24650c228"
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
| `id_proof_id`, `id_number` (≤ 50) | ID proof — both or neither (null when none was taken, or on a QR check-out row); masked by the server when `mask_flag = Y` |
| `user_id_out`, `device_id_out`, `shift_id_out`, `date_time_out` | check-out (null while in store) |
| `out_flag` | `N` in store / `Y` checked out (then the 3 out fields + date_time_out are required) |

Receipt (bill) row fields: `trans_id`, `local_version`, `bill_no` (bill series), `bill_dt` (IST),
`user_id`, `device_id`, `shift_id`, `luggage_qty`, `days_charged`, `block_hours`, `first_block_rate`,
`next_block_rate` (the rate snapshot used), `base_amt`, `penal_amt`, `net_amt`, `taxable_amt`, `gst_pct`,
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
  and logs `AMOUNT_MISMATCH`.
- The response `counters` are the server’s last receipt/bill numbers of the current period for this user.

`reason` codes on `EXCEPTION`: `VALIDATION_ERROR`, `INVALID_REFERENCE` (user/shift/ID proof not of this
customer), `TENANT_MISMATCH`, `DUPLICATE_RECEIPT_NO`, `DUPLICATE_CHECKOUT` (a different second check-out),
`BILL_WITHOUT_LUGGAGE`, `DUPLICATE_BILL`, `DUPLICATE_BILL_NO`, `CHECKOUT_MISSING`, `NO_RATE`,
`AMOUNT_MISMATCH`, `DB_ERROR`.

Sample (bag 1 still in store with an Aadhaar number, bag 2 checked out and billed, plus a bill for an
unknown `trans_id`):
Request body:
```json
{
  "luggage": [
    {
      "trans_id": "c360891f-4be8-4083-879b-f97cecc49184",
      "local_version": 1,
      "receipt_no": "U03-00001",
      "receipt_dt": "2026-11-03",
      "user_id_in": 3,
      "device_id_in": "POS-HWH-01",
      "shift_id_in": 1,
      "date_time_in": "2026-11-03 12:05:33",
      "pasn_name": "Suresh Patel",
      "pasn_mob_no": "9876543210",
      "pnr_no": "4521367890",
      "luggage_qty": 2,
      "id_proof_id": 1,
      "id_number": "1234 5678 9012",
      "out_flag": "N"
    },
    {
      "trans_id": "61de0b51-e483-421d-b4c0-b881dc42f2cc",
      "local_version": 3,
      "receipt_no": "U03-00002",
      "receipt_dt": "2026-11-02",
      "user_id_in": 3,
      "device_id_in": "POS-HWH-01",
      "shift_id_in": 1,
      "date_time_in": "2026-11-02 12:05:33",
      "pasn_name": "Meera Iyer",
      "pasn_mob_no": "9123456780",
      "pnr_no": "8812345670",
      "luggage_qty": 1,
      "id_proof_id": 3,
      "id_number": "P1234567",
      "user_id_out": 3,
      "device_id_out": "POS-HWH-01",
      "shift_id_out": 1,
      "date_time_out": "2026-11-03 13:05:33",
      "out_flag": "Y"
    }
  ],
  "receipts": [
    {
      "trans_id": "61de0b51-e483-421d-b4c0-b881dc42f2cc",
      "local_version": 1,
      "bill_no": "U03-00001",
      "bill_dt": "2026-11-03",
      "user_id": 3,
      "device_id": "POS-HWH-01",
      "shift_id": 1,
      "luggage_qty": 1,
      "days_charged": 2,
      "block_hours": 24,
      "first_block_rate": "50.00",
      "next_block_rate": "20.00",
      "base_amt": "70.00",
      "penal_amt": "0.00",
      "net_amt": "70.00",
      "taxable_amt": "59.32",
      "gst_pct": "18.00",
      "cgst": "5.34",
      "sgst": "5.34",
      "igst": "0.00",
      "gst_flag": "Y",
      "pay_mode": "U",
      "upi_ref": "432198765012",
      "bill_printed": "Y"
    },
    {
      "trans_id": "c05ab850-3556-402a-8268-6a8fc2438639",
      "local_version": 1,
      "bill_no": "U03-00002",
      "bill_dt": "2026-11-03",
      "user_id": 3,
      "device_id": "POS-HWH-01",
      "shift_id": 1,
      "luggage_qty": 1,
      "days_charged": 2,
      "block_hours": 24,
      "first_block_rate": "50.00",
      "next_block_rate": "20.00",
      "base_amt": "70.00",
      "penal_amt": "0.00",
      "net_amt": "70.00",
      "taxable_amt": "59.32",
      "gst_pct": "18.00",
      "cgst": "5.34",
      "sgst": "5.34",
      "igst": "0.00",
      "gst_flag": "Y",
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
        "trans_id": "c360891f-4be8-4083-879b-f97cecc49184",
        "local_version": 1,
        "status": "ACCEPTED"
      },
      {
        "table": "td_luggage_in_out",
        "trans_id": "61de0b51-e483-421d-b4c0-b881dc42f2cc",
        "local_version": 3,
        "status": "ACCEPTED"
      },
      {
        "table": "td_receipt",
        "trans_id": "61de0b51-e483-421d-b4c0-b881dc42f2cc",
        "local_version": 1,
        "status": "ACCEPTED"
      },
      {
        "table": "td_receipt",
        "trans_id": "c05ab850-3556-402a-8268-6a8fc2438639",
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
    "server_time": "2026-11-03 14:05:33"
  },
  "requestId": "812da9ed-599c-43d4-b40e-0638a8b47b3d"
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
        "trans_id": "c360891f-4be8-4083-879b-f97cecc49184",
        "local_version": 1,
        "status": "ACCEPTED"
      },
      {
        "table": "td_luggage_in_out",
        "trans_id": "61de0b51-e483-421d-b4c0-b881dc42f2cc",
        "local_version": 3,
        "status": "ACCEPTED"
      },
      {
        "table": "td_receipt",
        "trans_id": "61de0b51-e483-421d-b4c0-b881dc42f2cc",
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
    "server_time": "2026-11-03 14:05:33"
  },
  "requestId": "a015e99d-71c4-42bf-8fe4-58458fb7209a"
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
    "server_time": "2026-11-03 14:05:34"
  },
  "requestId": "df6599ae-41cc-4e1b-a2f1-207507864c2d"
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
      "trans_id": "dcc9385f-f69f-4771-a039-dcacdafd3fab",
      "local_version": 1,
      "receipt_no": "U03-00003",
      "receipt_dt": "2026-11-03",
      "user_id_in": 3,
      "date_time_in": "2026-11-03 11:05:33",
      "luggage_qty": 1,
      "pasn_name": "Arjun Sen",
      "pasn_mob_no": "9000011111",
      "pnr_no": "",
      "user_id_out": 4,
      "device_id_out": "POS-HWH-02",
      "shift_id_out": null,
      "date_time_out": "2026-11-03 13:35:33",
      "out_flag": "Y"
    }
  ],
  "receipts": [
    {
      "trans_id": "dcc9385f-f69f-4771-a039-dcacdafd3fab",
      "local_version": 1,
      "bill_no": "U04-00001",
      "bill_dt": "2026-11-03",
      "user_id": 4,
      "device_id": "POS-HWH-02",
      "shift_id": null,
      "luggage_qty": 1,
      "days_charged": 1,
      "block_hours": 24,
      "first_block_rate": "50.00",
      "next_block_rate": "20.00",
      "base_amt": "50.00",
      "penal_amt": "0.00",
      "net_amt": "50.00",
      "taxable_amt": "42.37",
      "gst_pct": "18.00",
      "cgst": "3.82",
      "sgst": "3.81",
      "igst": "0.00",
      "gst_flag": "Y",
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
        "trans_id": "dcc9385f-f69f-4771-a039-dcacdafd3fab",
        "local_version": 1,
        "status": "ACCEPTED"
      },
      {
        "table": "td_receipt",
        "trans_id": "dcc9385f-f69f-4771-a039-dcacdafd3fab",
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
    "server_time": "2026-11-03 14:05:33"
  },
  "requestId": "f02f39df-5af9-4ef3-9700-7495ebd74f2f"
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
        "trans_id": "dcc9385f-f69f-4771-a039-dcacdafd3fab",
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
    "server_time": "2026-11-03 14:05:33"
  },
  "requestId": "352bd1f1-16f0-439c-acc0-d04f7cae14c6"
}
```

The stored row afterwards (with the bill and its stamped `gst_no`):
Response `200`:
```json
{
  "success": true,
  "message": "OK",
  "data": {
    "trans_id": "dcc9385f-f69f-4771-a039-dcacdafd3fab",
    "customer_id": 1,
    "receipt_no": "U03-00003",
    "receipt_dt": "2026-11-03",
    "user_id_in": 3,
    "device_id_in": "POS-HWH-01",
    "shift_id_in": 1,
    "date_time_in": "2026-11-03 11:05:33",
    "pasn_name": "Arjun Sen",
    "pasn_mob_no": "9000011111",
    "pnr_no": "",
    "luggage_qty": 1,
    "id_proof_id": 3,
    "id_number": "Z7654321",
    "user_id_out": 4,
    "device_id_out": "POS-HWH-02",
    "shift_id_out": null,
    "date_time_out": "2026-11-03 13:35:33",
    "out_flag": "Y",
    "is_deleted": "N",
    "sync_flag": "N",
    "created_by": "howrah.op2",
    "created_at": "2026-11-03 14:05:33",
    "updated_by": "howrah.op1",
    "updated_at": "2026-11-03 14:05:33",
    "receipt": {
      "trans_id": "dcc9385f-f69f-4771-a039-dcacdafd3fab",
      "customer_id": 1,
      "bill_no": "U04-00001",
      "bill_dt": "2026-11-03",
      "user_id": 4,
      "device_id": "POS-HWH-02",
      "shift_id": null,
      "luggage_qty": 1,
      "days_charged": 1,
      "block_hours": 24,
      "first_block_rate": "50.00",
      "next_block_rate": "20.00",
      "base_amt": "50.00",
      "penal_amt": "0.00",
      "net_amt": "50.00",
      "taxable_amt": "42.37",
      "gst_pct": "18.00",
      "cgst": "3.82",
      "sgst": "3.81",
      "igst": "0.00",
      "gst_flag": "Y",
      "gst_no": "19ABCDE1234F1Z5",
      "pay_mode": "C",
      "upi_ref": null,
      "bill_printed": "Y",
      "is_deleted": "N",
      "sync_flag": "N",
      "created_by": "howrah.op2",
      "created_at": "2026-11-03 14:05:33",
      "updated_by": "howrah.op2",
      "updated_at": "2026-11-03 14:05:33"
    }
  },
  "requestId": "9454a63e-934f-40ea-938c-0140e910520c"
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
          "created_at": "2026-11-03 14:05:22",
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
          "created_at": "2026-11-03 14:05:22",
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
          "created_at": "2026-11-03 14:05:22",
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
          "effective_from": "2026-01-01",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "superadmin",
          "created_at": "2026-11-03 14:05:31",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-03 14:05:32"
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
          "created_at": "2026-11-03 14:05:32",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-03 14:05:32"
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
          "created_at": "2026-11-03 14:05:32",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-03 14:05:32"
        }
      ],
      "shift_assignments": [
        {
          "id": 1,
          "customer_id": 1,
          "user_id": 3,
          "shift_id": 1,
          "effective_from": "2026-11-04",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "howrah.admin",
          "created_at": "2026-11-03 14:05:32",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-03 14:05:32"
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
          "created_at": "2026-11-03 14:05:32",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-03 14:05:32"
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
          "created_at": "2026-11-03 14:05:32",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-03 14:05:32"
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
          "updated_at": "2026-11-03 14:05:31"
        },
        {
          "id": 3,
          "customer_id": 1,
          "user_code": "U03",
          "user_name": "Ravi Kumar Singh",
          "user_type": "O",
          "allow_flag": "Y",
          "is_deleted": "N",
          "updated_at": "2026-11-03 14:05:33"
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
      "GST_FLAG": "Y",
      "GST_TYPE": "E",
      "QR_FLAG": "Y",
      "DEFAULT_PAY_MODE": "C",
      "PAPER_WIDTH": "58"
    },
    "counters": {
      "reset": "Y",
      "period_key": "FY2026-27",
      "receipt_last_no": 0,
      "bill_last_no": 0
    },
    "luggage": [
      {
        "trans_id": "c360891f-4be8-4083-879b-f97cecc49184",
        "customer_id": 1,
        "receipt_no": "U03-00001",
        "receipt_dt": "2026-11-03",
        "user_id_in": 3,
        "device_id_in": "POS-HWH-01",
        "shift_id_in": 1,
        "date_time_in": "2026-11-03 12:05:33",
        "pasn_name": "Suresh Patel",
        "pasn_mob_no": "9876543210",
        "pnr_no": "4521367890",
        "luggage_qty": 2,
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
        "created_at": "2026-11-03 14:05:33",
        "updated_by": "howrah.op1",
        "updated_at": "2026-11-03 14:05:33"
      }
    ],
    "has_more": false,
    "next_cursor": null,
    "server_time": "2026-11-03 14:05:33"
  },
  "requestId": "9a34201e-7c16-4c2f-bc22-d6bde05a23ff"
}
```

Next pull with `since=2026-11-03 14:05:33`:
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
          "created_at": "2026-11-03 14:05:32",
          "updated_by": "superadmin",
          "updated_at": "2026-11-03 14:05:32"
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
          "created_at": "2026-11-03 14:05:32",
          "updated_by": "superadmin",
          "updated_at": "2026-11-03 14:05:32"
        }
      ],
      "rates": [
        {
          "rate_id": 1,
          "customer_id": 1,
          "block_hours": 24,
          "first_block_rate": "50.00",
          "next_block_rate": "20.00",
          "effective_from": "2026-01-01",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "superadmin",
          "created_at": "2026-11-03 14:05:31",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-03 14:05:32"
        },
        {
          "rate_id": 2,
          "customer_id": 1,
          "block_hours": 24,
          "first_block_rate": "60.00",
          "next_block_rate": "25.00",
          "effective_from": "2027-01-02",
          "is_deleted": "Y",
          "sync_flag": "N",
          "created_by": "howrah.admin",
          "created_at": "2026-11-03 14:05:32",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-03 14:05:32"
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
          "created_at": "2026-11-03 14:05:32",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-03 14:05:32"
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
          "created_at": "2026-11-03 14:05:32",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-03 14:05:32"
        }
      ],
      "shift_assignments": [
        {
          "id": 1,
          "customer_id": 1,
          "user_id": 3,
          "shift_id": 1,
          "effective_from": "2026-11-04",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "howrah.admin",
          "created_at": "2026-11-03 14:05:32",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-03 14:05:32"
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
          "created_at": "2026-11-03 14:05:32",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-03 14:05:32"
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
          "created_at": "2026-11-03 14:05:32",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-03 14:05:32"
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
          "updated_at": "2026-11-03 14:05:31"
        },
        {
          "id": 3,
          "customer_id": 1,
          "user_code": "U03",
          "user_name": "Ravi Kumar Singh",
          "user_type": "O",
          "allow_flag": "Y",
          "is_deleted": "N",
          "updated_at": "2026-11-03 14:05:33"
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
      "GST_FLAG": "Y",
      "GST_TYPE": "E",
      "QR_FLAG": "Y",
      "DEFAULT_PAY_MODE": "C",
      "PAPER_WIDTH": "58"
    },
    "counters": {
      "reset": "Y",
      "period_key": "FY2026-27",
      "receipt_last_no": 0,
      "bill_last_no": 0
    },
    "luggage": [
      {
        "trans_id": "61de0b51-e483-421d-b4c0-b881dc42f2cc",
        "customer_id": 1,
        "receipt_no": "U03-00002",
        "receipt_dt": "2026-11-02",
        "user_id_in": 3,
        "device_id_in": "POS-HWH-01",
        "shift_id_in": 1,
        "date_time_in": "2026-11-02 12:05:33",
        "pasn_name": "Meera Iyer",
        "pasn_mob_no": "9123456780",
        "pnr_no": "8812345670",
        "luggage_qty": 1,
        "id_proof_id": 3,
        "id_number": "P1234567",
        "user_id_out": 3,
        "device_id_out": "POS-HWH-01",
        "shift_id_out": 1,
        "date_time_out": "2026-11-03 13:05:33",
        "out_flag": "Y",
        "is_deleted": "N",
        "sync_flag": "N",
        "created_by": "howrah.op1",
        "created_at": "2026-11-03 14:05:33",
        "updated_by": "howrah.op1",
        "updated_at": "2026-11-03 14:05:33"
      },
      {
        "trans_id": "c360891f-4be8-4083-879b-f97cecc49184",
        "customer_id": 1,
        "receipt_no": "U03-00001",
        "receipt_dt": "2026-11-03",
        "user_id_in": 3,
        "device_id_in": "POS-HWH-01",
        "shift_id_in": 1,
        "date_time_in": "2026-11-03 12:05:33",
        "pasn_name": "Suresh Patel",
        "pasn_mob_no": "9876543210",
        "pnr_no": "4521367890",
        "luggage_qty": 2,
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
        "created_at": "2026-11-03 14:05:33",
        "updated_by": "howrah.op1",
        "updated_at": "2026-11-03 14:05:33"
      }
    ],
    "has_more": false,
    "next_cursor": null,
    "server_time": "2026-11-03 14:05:33"
  },
  "requestId": "edbb4cc3-c90c-4279-b3e1-4fd907f9ea2f"
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
          "created_at": "2026-11-03 14:05:32",
          "updated_by": "superadmin",
          "updated_at": "2026-11-03 14:05:32"
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
          "created_at": "2026-11-03 14:05:32",
          "updated_by": "superadmin",
          "updated_at": "2026-11-03 14:05:32"
        }
      ],
      "rates": [
        {
          "rate_id": 1,
          "customer_id": 1,
          "block_hours": 24,
          "first_block_rate": "50.00",
          "next_block_rate": "20.00",
          "effective_from": "2026-01-01",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "superadmin",
          "created_at": "2026-11-03 14:05:31",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-03 14:05:32"
        },
        {
          "rate_id": 2,
          "customer_id": 1,
          "block_hours": 24,
          "first_block_rate": "60.00",
          "next_block_rate": "25.00",
          "effective_from": "2027-01-02",
          "is_deleted": "Y",
          "sync_flag": "N",
          "created_by": "howrah.admin",
          "created_at": "2026-11-03 14:05:32",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-03 14:05:32"
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
          "created_at": "2026-11-03 14:05:32",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-03 14:05:32"
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
          "created_at": "2026-11-03 14:05:32",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-03 14:05:32"
        }
      ],
      "shift_assignments": [
        {
          "id": 1,
          "customer_id": 1,
          "user_id": 3,
          "shift_id": 1,
          "effective_from": "2026-11-04",
          "is_deleted": "N",
          "sync_flag": "N",
          "created_by": "howrah.admin",
          "created_at": "2026-11-03 14:05:32",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-03 14:05:32"
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
          "created_at": "2026-11-03 14:05:32",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-03 14:05:32"
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
          "created_at": "2026-11-03 14:05:32",
          "updated_by": "howrah.admin",
          "updated_at": "2026-11-03 14:05:32"
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
          "updated_at": "2026-11-03 14:05:31"
        },
        {
          "id": 3,
          "customer_id": 1,
          "user_code": "U03",
          "user_name": "Ravi Kumar Singh",
          "user_type": "O",
          "allow_flag": "Y",
          "is_deleted": "N",
          "updated_at": "2026-11-03 14:05:33"
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
      "GST_FLAG": "Y",
      "GST_TYPE": "E",
      "QR_FLAG": "Y",
      "DEFAULT_PAY_MODE": "C",
      "PAPER_WIDTH": "58"
    },
    "counters": {
      "reset": "Y",
      "period_key": "FY2026-27",
      "receipt_last_no": 0,
      "bill_last_no": 0
    },
    "luggage": [
      {
        "trans_id": "61de0b51-e483-421d-b4c0-b881dc42f2cc",
        "customer_id": 1,
        "receipt_no": "U03-00002",
        "receipt_dt": "2026-11-02",
        "user_id_in": 3,
        "device_id_in": "POS-HWH-01",
        "shift_id_in": 1,
        "date_time_in": "2026-11-02 12:05:33",
        "pasn_name": "Meera Iyer",
        "pasn_mob_no": "9123456780",
        "pnr_no": "8812345670",
        "luggage_qty": 1,
        "id_proof_id": 3,
        "id_number": "P1234567",
        "user_id_out": 3,
        "device_id_out": "POS-HWH-01",
        "shift_id_out": 1,
        "date_time_out": "2026-11-03 13:05:33",
        "out_flag": "Y",
        "is_deleted": "N",
        "sync_flag": "N",
        "created_by": "howrah.op1",
        "created_at": "2026-11-03 14:05:33",
        "updated_by": "howrah.op1",
        "updated_at": "2026-11-03 14:05:33"
      }
    ],
    "has_more": true,
    "next_cursor": "eyJtIjoiY2hhbmdl…(base64)",
    "server_time": "2026-11-03 14:05:33"
  },
  "requestId": "c98a1872-420a-4696-9078-1f56e631ad45"
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
        "trans_id": "c360891f-4be8-4083-879b-f97cecc49184",
        "customer_id": 1,
        "receipt_no": "U03-00001",
        "receipt_dt": "2026-11-03",
        "user_id_in": 3,
        "device_id_in": "POS-HWH-01",
        "shift_id_in": 1,
        "date_time_in": "2026-11-03 12:05:33",
        "pasn_name": "Suresh Patel",
        "pasn_mob_no": "9876543210",
        "pnr_no": "4521367890",
        "luggage_qty": 2,
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
        "created_at": "2026-11-03 14:05:33",
        "updated_by": "howrah.op1",
        "updated_at": "2026-11-03 14:05:33"
      }
    ],
    "has_more": false,
    "next_cursor": null,
    "server_time": "2026-11-03 14:05:33"
  },
  "requestId": "82af9ec1-f572-49b8-bfde-487fd8735e57"
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
      "trans_id": "c360891f-4be8-4083-879b-f97cecc49184",
      "customer_id": 1,
      "receipt_no": "U03-00001",
      "receipt_dt": "2026-11-03",
      "user_id_in": 3,
      "device_id_in": "POS-HWH-01",
      "shift_id_in": 1,
      "date_time_in": "2026-11-03 12:05:33",
      "pasn_name": "Suresh Patel",
      "pasn_mob_no": "9876543210",
      "pnr_no": "4521367890",
      "luggage_qty": 2,
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
      "created_at": "2026-11-03 14:05:33",
      "updated_by": "howrah.op1",
      "updated_at": "2026-11-03 14:05:33"
    }
  ],
  "meta": {
    "page": 1,
    "limit": 50,
    "total": 1
  },
  "requestId": "88093890-8ebb-4955-85e1-0461c2a3ba35"
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
    "trans_id": "61de0b51-e483-421d-b4c0-b881dc42f2cc",
    "customer_id": 1,
    "receipt_no": "U03-00002",
    "receipt_dt": "2026-11-02",
    "user_id_in": 3,
    "device_id_in": "POS-HWH-01",
    "shift_id_in": 1,
    "date_time_in": "2026-11-02 12:05:33",
    "pasn_name": "Meera Iyer",
    "pasn_mob_no": "9123456780",
    "pnr_no": "8812345670",
    "luggage_qty": 1,
    "id_proof_id": 3,
    "id_number": "P1234567",
    "user_id_out": 3,
    "device_id_out": "POS-HWH-01",
    "shift_id_out": 1,
    "date_time_out": "2026-11-03 13:05:33",
    "out_flag": "Y",
    "is_deleted": "N",
    "sync_flag": "N",
    "created_by": "howrah.op1",
    "created_at": "2026-11-03 14:05:33",
    "updated_by": "howrah.op1",
    "updated_at": "2026-11-03 14:05:33",
    "receipt": {
      "trans_id": "61de0b51-e483-421d-b4c0-b881dc42f2cc",
      "customer_id": 1,
      "bill_no": "U03-00001",
      "bill_dt": "2026-11-03",
      "user_id": 3,
      "device_id": "POS-HWH-01",
      "shift_id": 1,
      "luggage_qty": 1,
      "days_charged": 2,
      "block_hours": 24,
      "first_block_rate": "50.00",
      "next_block_rate": "20.00",
      "base_amt": "70.00",
      "penal_amt": "0.00",
      "net_amt": "70.00",
      "taxable_amt": "59.32",
      "gst_pct": "18.00",
      "cgst": "5.34",
      "sgst": "5.34",
      "igst": "0.00",
      "gst_flag": "Y",
      "gst_no": "19ABCDE1234F1Z5",
      "pay_mode": "U",
      "upi_ref": "432198765012",
      "bill_printed": "Y",
      "is_deleted": "N",
      "sync_flag": "N",
      "created_by": "howrah.op1",
      "created_at": "2026-11-03 14:05:33",
      "updated_by": "howrah.op1",
      "updated_at": "2026-11-03 14:05:33"
    }
  },
  "requestId": "03800aba-4eda-41cf-8800-dffd54a3ac7d"
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
  "requestId": "9f9af3c9-ef2d-45e2-8c84-b76af49ac91a"
}
```

---

## 7. Billing rules (must match the server exactly)

```
minutes      = whole minutes from date_time_in to date_time_out (seconds dropped)
days_charged = max(1, ceil(minutes / (block_hours × 60)))          24h00m → 1 block, 24h01m → 2
base_amt     = luggage_qty × (first_block_rate + next_block_rate × (days_charged − 1))
net_amt      = base_amt + penal_amt                                (GST-INCLUSIVE total collected)
rate row     = latest rate with effective_from ≤ receipt_dt
GST row      = latest GST with effective_from ≤ bill_dt
gst_pct      = cgst_pct + sgst_pct  (GST_TYPE = E)   |   igst_pct  (GST_TYPE = I)
taxable_amt  = round(net_amt × 100 / (100 + gst_pct))
gst          = net_amt − taxable_amt
GST_TYPE = E: cgst = round(gst / 2), sgst = gst − cgst, igst = 0   (odd paisa goes to CGST)
GST_TYPE = I: igst = gst, cgst = sgst = 0
```
- Do all arithmetic in **integer paise** (₹1 = 100). `round` = **half-up** to the paisa.
- Examples: 2 min × 2 bags → ₹100 · 24h00m × 1 → ₹50 · 24h01m × 1 → ₹70 · 50 h × 3 → ₹270 ·
  ₹70 at 18 % → taxable 59.32, CGST 5.34, SGST 5.34 · ₹60.50 at 18 % → 51.27, CGST 4.62, SGST 4.61.
- Store the rate snapshot (`block_hours`, `first_block_rate`, `next_block_rate`) and `gst_pct` on the bill.
- No payment at check-in (receipt only); one bill per bag transaction at check-out; pay mode Cash or UPI.

### Receipt / bill numbers
- Format `<user_code>-<5 digits>` (e.g. `U03-00125`; more digits after 99999). Two series per user:
  receipts (check-in) and bills (check-out). The series belongs to the **user**, not the device.
- Reset by `RECEIPT_RESET`: `D` daily (period `YYYY-MM-DD`), `M` monthly (`YYYY-MM`), `Y` Indian
  financial year (`FY2026-27`, April–March); periods use the IST date.
- Next number = max(local last number, server `counters.*_last_no` for the current `period_key`) + 1.
  Login, pull and push return `counters`; the server never lowers them.

---

## 8. Settings keys (customer-wide, optionally overridden per user)

| Key | Values | Meaning |
|---|---|---|
| `REPORT_FLAG` | Y/N | operators may open reports |
| `REPORT_PWD_FLAG` | Y/N | reports need the report password (`X-Report-Password` header) |
| `REPORT_PWD` | 4–64 chars | the report password (stored hashed, returned as `********`, never sent to devices) |
| `TOTAL_COLLECTION_FLAG` | Y/N | show total collection (business meaning pending) |
| `RECEIPT_RESET` | D/M/Y | numbering reset period (default Y) |
| `SYNC_INTERVAL_MIN` | 15–1440 | background sync interval in minutes |
| `FORCE_ONLINE_DAYS` | 0–365 | days the device may work offline before it must go online (0 = off) |
| `GST_FLAG` | Y/N | print the GST breakup on the bill |
| `GST_TYPE` | E/I | E intra-state (CGST+SGST) / I inter-state (IGST) |
| `QR_FLAG` | Y/N | print the QR (trans_id) on receipts |
| `DEFAULT_PAY_MODE` | C/U | preselected pay mode |
| `PAPER_WIDTH` | 58/80 | printer paper width (mm) |

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
      "bill_dt": "2026-11-03",
      "bills": 2,
      "bags": 2,
      "base_amt": "120.00",
      "penal_amt": "0.00",
      "net_amt": "120.00",
      "cash_amt": "50.00",
      "upi_amt": "70.00"
    }
  ],
  "meta": {
    "page": 1,
    "limit": 50,
    "total": 1,
    "totals": {
      "bills": 2,
      "bags": 2,
      "base_amt": "120.00",
      "penal_amt": "0.00",
      "net_amt": "120.00",
      "cash_amt": "50.00",
      "upi_amt": "70.00"
    },
    "filters": {
      "page": 1,
      "limit": 50,
      "from": "2026-11-02",
      "to": "2026-11-03"
    }
  },
  "requestId": "1cc737f0-4b07-4b50-a729-f5549a3ae3c8"
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
      "trans_id": "61de0b51-e483-421d-b4c0-b881dc42f2cc",
      "receipt_no": "U03-00002",
      "receipt_dt": "2026-11-02",
      "date_time_in": "2026-11-02 12:05:33",
      "date_time_in_ist": "2026-11-02 17:35:33",
      "user_id_in": 3,
      "user_name_in": "Ravi Kumar Singh",
      "shift_id_in": 1,
      "shift_name_in": "Morning",
      "pasn_name": "Meera Iyer",
      "pasn_mob_no": "9123456780",
      "pnr_no": "8812345670",
      "luggage_qty": 1,
      "id_proof_id": 3,
      "id_proof_name": "Passport",
      "id_number": "P1234567",
      "out_flag": "Y",
      "date_time_out": "2026-11-03 13:05:33",
      "date_time_out_ist": "2026-11-03 18:35:33"
    },
    {
      "trans_id": "dcc9385f-f69f-4771-a039-dcacdafd3fab",
      "receipt_no": "U03-00003",
      "receipt_dt": "2026-11-03",
      "date_time_in": "2026-11-03 11:05:33",
      "date_time_in_ist": "2026-11-03 16:35:33",
      "user_id_in": 3,
      "user_name_in": "Ravi Kumar Singh",
      "shift_id_in": 1,
      "shift_name_in": "Morning",
      "pasn_name": "Arjun Sen",
      "pasn_mob_no": "9000011111",
      "pnr_no": "",
      "luggage_qty": 1,
      "id_proof_id": 3,
      "id_proof_name": "Passport",
      "id_number": "Z7654321",
      "out_flag": "Y",
      "date_time_out": "2026-11-03 13:35:33",
      "date_time_out_ist": "2026-11-03 19:05:33"
    },
    "… 1 more item(s) of the same shape"
  ],
  "meta": {
    "page": 1,
    "limit": 50,
    "total": 3,
    "totals": {
      "bags_entries": 3,
      "bags": 4
    },
    "filters": {
      "page": 1,
      "limit": 50,
      "from": "2026-11-02",
      "to": "2026-11-03"
    }
  },
  "requestId": "ece30605-0e69-4da4-b257-8846feba191e"
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
      "trans_id": "61de0b51-e483-421d-b4c0-b881dc42f2cc",
      "receipt_no": "U03-00002",
      "receipt_dt": "2026-11-02",
      "date_time_in": "2026-11-02 12:05:33",
      "date_time_in_ist": "2026-11-02 17:35:33",
      "user_id_in": 3,
      "user_name_in": "Ravi Kumar Singh",
      "shift_id_in": 1,
      "shift_name_in": "Morning",
      "pasn_name": "Meera Iyer",
      "pasn_mob_no": "9123456780",
      "pnr_no": "8812345670",
      "luggage_qty": 1,
      "id_proof_id": 3,
      "id_proof_name": "Passport",
      "id_number": "P1234567",
      "out_flag": "Y",
      "date_time_out": "2026-11-03 13:05:33",
      "date_time_out_ist": "2026-11-03 18:35:33",
      "user_id_out": 3,
      "user_name_out": "Ravi Kumar Singh",
      "shift_id_out": 1,
      "shift_name_out": "Morning",
      "bill_no": "U03-00001",
      "bill_dt": "2026-11-03",
      "days_charged": 2,
      "net_amt": "70.00",
      "pay_mode": "U",
      "bill_printed": "Y"
    },
    {
      "trans_id": "dcc9385f-f69f-4771-a039-dcacdafd3fab",
      "receipt_no": "U03-00003",
      "receipt_dt": "2026-11-03",
      "date_time_in": "2026-11-03 11:05:33",
      "date_time_in_ist": "2026-11-03 16:35:33",
      "user_id_in": 3,
      "user_name_in": "Ravi Kumar Singh",
      "shift_id_in": 1,
      "shift_name_in": "Morning",
      "pasn_name": "Arjun Sen",
      "pasn_mob_no": "9000011111",
      "pnr_no": "",
      "luggage_qty": 1,
      "id_proof_id": 3,
      "id_proof_name": "Passport",
      "id_number": "Z7654321",
      "out_flag": "Y",
      "date_time_out": "2026-11-03 13:35:33",
      "date_time_out_ist": "2026-11-03 19:05:33",
      "user_id_out": 4,
      "user_name_out": "Anita Das",
      "shift_id_out": null,
      "shift_name_out": null,
      "bill_no": "U04-00001",
      "bill_dt": "2026-11-03",
      "days_charged": 1,
      "net_amt": "50.00",
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
      "net_amt": "120.00"
    },
    "filters": {
      "page": 1,
      "limit": 50,
      "from": "2026-11-02",
      "to": "2026-11-03"
    }
  },
  "requestId": "8ebe45a0-1cb5-439f-a6c8-e78a6c869d0d"
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
      "trans_id": "c360891f-4be8-4083-879b-f97cecc49184",
      "receipt_no": "U03-00001",
      "receipt_dt": "2026-11-03",
      "date_time_in": "2026-11-03 12:05:33",
      "date_time_in_ist": "2026-11-03 17:35:33",
      "user_id_in": 3,
      "user_name_in": "Ravi Kumar Singh",
      "shift_id_in": 1,
      "shift_name_in": "Morning",
      "pasn_name": "Suresh Patel",
      "pasn_mob_no": "9876543210",
      "pnr_no": "4521367890",
      "luggage_qty": 2,
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
      "bags": 2
    },
    "filters": {
      "page": 1,
      "limit": 50
    }
  },
  "requestId": "5c0ad2b6-db15-45a9-92a6-d8c30c3d1dbf"
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
      "bill_dt": "2026-11-03",
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
      "from": "2026-11-02",
      "to": "2026-11-03"
    }
  },
  "requestId": "b2fc4df0-7775-4239-8d42-547123004364"
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
      "cash_amt": "50.00",
      "upi_amt": "0.00"
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
      "cash_amt": "0.00",
      "upi_amt": "70.00"
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
      "cash_amt": "50.00",
      "upi_amt": "70.00"
    },
    "filters": {
      "page": 1,
      "limit": 50,
      "from": "2026-11-02",
      "to": "2026-11-03"
    }
  },
  "requestId": "d5aaa8b2-2cb6-4de9-8f69-14e6538deab7"
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
      "net_amt": "0.00"
    },
    "filters": {
      "page": 1,
      "limit": 50,
      "from": "2026-11-02",
      "to": "2026-11-03"
    }
  },
  "requestId": "a911ae42-eff5-4700-89b6-8da23996a22f"
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
      "trans_id": "c05ab850-3556-402a-8268-6a8fc2438639",
      "table_name": "td_receipt",
      "reason": "BILL_WITHOUT_LUGGAGE: no luggage row for this trans_id",
      "payload": {
        "cgst": "5.34",
        "igst": "0.00",
        "sgst": "5.34",
        "bill_dt": "2026-11-03",
        "bill_no": "U03-00002",
        "gst_pct": "18.00",
        "net_amt": "70.00",
        "upi_ref": "432198765012",
        "user_id": 3,
        "base_amt": "70.00",
        "gst_flag": "Y",
        "pay_mode": "U",
        "shift_id": 1,
        "trans_id": "c05ab850-3556-402a-8268-6a8fc2438639",
        "device_id": "POS-HWH-01",
        "penal_amt": "0.00",
        "block_hours": 24,
        "luggage_qty": 1,
        "taxable_amt": "59.32",
        "bill_printed": "Y",
        "days_charged": 2,
        "local_version": 1,
        "next_block_rate": "20.00",
        "first_block_rate": "50.00"
      },
      "user_id": 3,
      "user_name": "Ravi Kumar Singh",
      "device_id": "POS-HWH-01",
      "resolved_flag": "N",
      "resolved_by": null,
      "resolved_at": null,
      "created_at": "2026-11-03 14:05:33",
      "created_at_ist": "2026-11-03 19:35:33"
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
  "requestId": "5866b570-fd34-4f40-9323-03dc92e0890e"
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
    "trans_id": "c05ab850-3556-402a-8268-6a8fc2438639",
    "table_name": "td_receipt",
    "payload": {
      "cgst": "5.34",
      "igst": "0.00",
      "sgst": "5.34",
      "bill_dt": "2026-11-03",
      "bill_no": "U03-00002",
      "gst_pct": "18.00",
      "net_amt": "70.00",
      "upi_ref": "432198765012",
      "user_id": 3,
      "base_amt": "70.00",
      "gst_flag": "Y",
      "pay_mode": "U",
      "shift_id": 1,
      "trans_id": "c05ab850-3556-402a-8268-6a8fc2438639",
      "device_id": "POS-HWH-01",
      "penal_amt": "0.00",
      "block_hours": 24,
      "luggage_qty": 1,
      "taxable_amt": "59.32",
      "bill_printed": "Y",
      "days_charged": 2,
      "local_version": 1,
      "next_block_rate": "20.00",
      "first_block_rate": "50.00"
    },
    "reason": "BILL_WITHOUT_LUGGAGE: no luggage row for this trans_id",
    "user_id": 3,
    "device_id": "POS-HWH-01",
    "resolved_flag": "Y",
    "resolved_by": "howrah.admin",
    "resolved_at": "2026-11-03 14:05:33",
    "created_at": "2026-11-03 14:05:33"
  },
  "requestId": "a9898f00-e0ab-4f4f-a276-1c3a3557a24d"
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
  "requestId": "8adc136d-bd25-4a6b-b864-20de75cdc86d"
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
  "requestId": "ec9821ff-527c-4553-831c-e02af2664177"
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
| Admin: users | `/users` (+ allow, reset-password, force-logout) |
| Admin: masters | `/rates`, `/shifts`, `/shift-assignments`, `/header-footer` (+ `/logo`), `/settings` |
| Admin: reports | `/reports/*`, exceptions + resolve |
| Super admin | `/customers` (+ summary, registrations, payments), `/gst`, `/id-proofs`, `POST /app/version` |
| Logout | push pending → `POST /auth/logout` |

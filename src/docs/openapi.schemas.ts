/**
 * Response shapes for the OpenAPI document (request shapes come from the modules' own zod
 * schemas). These describe what the API returns; they are not used for validation at runtime.
 */
import { extendZodWithOpenApi } from '@asteasolutions/zod-to-openapi';
import { z } from 'zod';
import { ERROR_CODES } from '../core/http/ApiError';

// Before any schema below is created, so they can be registered as reusable components.
extendZodWithOpenApi(z);

const yn = z.enum(['Y', 'N']);
const utc = z.string().describe('UTC `YYYY-MM-DD HH:mm:ss`');
const utcN = utc.nullable();
const ist = z.string().describe('IST `YYYY-MM-DD HH:mm:ss` (display copy)');
const day = z.string().describe('`YYYY-MM-DD`');
const money = z.string().describe('Decimal string with 2 places, e.g. `70.00`');
const id = z.number().int();

const audit = {
  is_deleted: yn,
  sync_flag: yn,
  created_by: z.string().nullable(),
  created_at: utcN,
  updated_by: z.string().nullable(),
  updated_at: utcN,
};

// ------------------------------------------------------------------ envelope

export const pageMeta = z.object({ page: id, limit: id, total: id }).openapi('PageMeta');

export function envelope<T extends z.ZodType>(data: T, meta?: z.ZodType) {
  return z.object({
    success: z.literal(true),
    message: z.string(),
    data,
    ...(meta && { meta }),
    requestId: z.string(),
  });
}

export const errorEnvelope = z.object({
  success: z.literal(false),
  message: z.string(),
  code: z.enum(ERROR_CODES),
  data: z.null(),
  errors: z
    .unknown()
    .optional()
    .describe('Details: list of `{location?, path, message}` for VALIDATION_ERROR; object otherwise'),
  requestId: z.string(),
}).openapi('ErrorEnvelope');

// ------------------------------------------------------------------ users / customers

export const userPublic = z.object({
  id,
  customer_id: id.nullable(),
  user_id: z.string(),
  user_name: z.string(),
  user_code: z.string().nullable(),
  user_type: z.enum(['S', 'A', 'O']),
  allow_flag: yn,
  must_change_pwd: yn.describe('Y → only change-password, logout and me work until it is changed'),
}).openapi('UserPublic');

export const userRow = userPublic.extend({
  device_id: z.string().nullable(),
  session_started_at: utcN,
  last_login: utcN,
  created_by: z.string().nullable(),
  created_at: utcN,
  updated_by: z.string().nullable(),
  updated_at: utcN,
}).openapi('UserRow');

export const customerPublic = z.object({
  customer_id: id,
  customer_name: z.string(),
  customer_addr: z.string(),
  state: z.string().nullable(),
  pin_code: z.string(),
  mobile_no: z.string(),
  email: z.string().nullable(),
  gst_no: z.string().nullable(),
  upi_id: z.string().nullable(),
  renewal_dt: day.nullable(),
  user_limit: id,
  active_flag: yn,
}).openapi('CustomerPublic');

export const customerRow = customerPublic.extend({ remarks: z.string().nullable(), ...audit }).openapi('CustomerRow');

export const registrationRow = z.object({
  reg_id: id,
  reg_no: z.string(),
  customer_id: id,
  start_date: day,
  end_date: day,
  user_count: id,
  remarks: z.string().nullable(),
  ...audit,
}).openapi('RegistrationRow');

export const paymentRow = z.object({
  payment_id: id,
  customer_id: id,
  reg_id: id.nullable(),
  payment_dt: day,
  base_amt: money,
  cgst: money,
  sgst: money,
  igst: money,
  net_amt: money,
  pay_mode: z.enum(['C', 'U', 'B']),
  transaction_no: z.string().nullable(),
  remarks: z.string().nullable(),
  ...audit,
}).openapi('PaymentRow');

export const customerSummary = z.object({
  customer: customerRow,
  users: z.object({ total: id, allowed: id, admins: id, operators: id, active_sessions: id }),
  latest_registration: registrationRow.nullable(),
  payments: z.object({ count: id, total_net: money, last_payment_dt: day.nullable() }),
  luggage: z.object({ total: id, in_store: id }),
});

// ------------------------------------------------------------------ masters

export const rateSlab = z.object({
  from_hour: id,
  to_hour: id,
  rate: money.describe('Per bag'),
}).openapi('RateSlab');

export const rateRow = z.object({
  rate_id: id,
  customer_id: id,
  rate_slabs: z
    .array(rateSlab)
    .describe('Authoritative tariff (Phase 13): every started slab adds its rate, the last repeats'),
  block_hours: id.describe('= first slab length (kept for older apps)'),
  first_block_rate: money.describe('= first slab rate'),
  next_block_rate: money.describe('= second slab rate (first when only one slab)'),
  effective_from: day,
  ...audit,
}).openapi('RateRow');

export const shiftRow = z.object({
  shift_id: id,
  customer_id: id,
  shift_name: z.string(),
  from_time: z.string().describe('IST `HH:mm:ss`'),
  to_time: z.string().describe('IST `HH:mm:ss`; may be earlier than from_time (crosses midnight)'),
  ...audit,
}).openapi('ShiftRow');

export const shiftAssignmentRow = z.object({
  id,
  customer_id: id,
  user_id: id,
  shift_id: id,
  effective_from: day,
  ...audit,
}).openapi('ShiftAssignmentRow');

export const headerFooterRow = z.object({
  id,
  customer_id: id,
  line_type: z.enum(['H', 'F', 'L']),
  line_order: id,
  narration: z.string().nullable(),
  image: z.string().nullable().describe('Base64 PNG (logo rows only)'),
  in_flag: yn,
  out_flag: yn,
  report_flag: yn,
  ...audit,
}).openapi('HeaderFooterRow');

export const settingRow = z.object({
  setting_id: id,
  customer_id: id,
  user_id: id.nullable(),
  setting_name: z.string(),
  setting_value: z.string().describe('`********` for REPORT_PWD'),
  setting_type: z.enum(['G', 'B', 'P']),
  ...audit,
}).openapi('SettingRow');

export const gstRow = z.object({
  gst_id: id,
  cgst_pct: money,
  sgst_pct: money,
  igst_pct: money,
  effective_from: day,
  ...audit,
}).openapi('GstRow');

export const idProofRow = z.object({
  id_proof_id: id,
  id_proof_name: z.string(),
  mask_flag: yn.describe('Y = only the last 4 characters of the ID number are stored/printed'),
  ...audit,
}).openapi('IdProofRow');

export const userBrief = z.object({
  id,
  customer_id: id,
  user_code: z.string().nullable(),
  user_name: z.string(),
  user_type: z.enum(['A', 'O']),
  allow_flag: yn,
  is_deleted: yn,
  updated_at: utcN,
}).describe('Users of the customer (deleted ones included) to show names on rows from other counters').openapi('UserBrief');

export const mastersSnapshot = z.object({
  id_proofs: z.array(idProofRow),
  gst: z.array(gstRow),
  rates: z.array(rateRow),
  shifts: z.array(shiftRow),
  shift_assignments: z.array(shiftAssignmentRow),
  header_footer: z.array(headerFooterRow),
  users: z.array(userBrief),
}).openapi('MastersSnapshot');

export const counters = z.object({
  reset: z.enum(['D', 'M', 'Y']),
  period_key: z.string().describe('`YYYY-MM-DD`, `YYYY-MM`, `FY2026-27` or `ALL` (RECEIPT_RESET = N)'),
  receipt_last_no: id,
  bill_last_no: id,
}).openapi('Counters');

export const settingsMap = z
  .record(z.string(), z.string())
  .describe('Customer settings overlaid with the user’s overrides (REPORT_PWD never included)').openapi('Settings');

// ------------------------------------------------------------------ auth

export const loginData = z.object({
  accessToken: z.string(),
  refreshToken: z.string(),
  user: userPublic,
  customer: customerPublic.nullable(),
  masters: mastersSnapshot,
  settings: settingsMap,
  counters: counters.nullable(),
  server_time: utc,
});

export const refreshData = z.object({
  accessToken: z.string(),
  refreshToken: z.string(),
  server_time: utc,
});

export const meData = z.object({
  user: userPublic.extend({
    device_id: z.string().nullable(),
    session_started_at: utcN,
    last_login: utcN,
  }),
  customer: customerPublic.nullable(),
  session_id: z.string(),
  server_time: utc,
});

// ------------------------------------------------------------------ luggage / sync

export const luggageRow = z.object({
  trans_id: z.uuid(),
  customer_id: id,
  receipt_no: z.string(),
  receipt_dt: day,
  user_id_in: id,
  device_id_in: z.string().nullable().describe('null while only a QR check-out has synced'),
  shift_id_in: id.nullable(),
  date_time_in: utc,
  pasn_name: z.string(),
  pasn_mob_no: z.string(),
  pnr_no: z.string(),
  luggage_qty: id,
  extra_lugage_qty: id.describe('Extra bags stored free, never billed (Phase 13)'),
  adv_amt: money.describe('Advance taken at check-in (Phase 13)'),
  adv_hours: id.describe('ADVANCE_HOURS the advance covered'),
  id_proof_id: id.nullable().describe('null while only a QR check-out has synced, or no ID was taken'),
  id_number: z.string().nullable().describe('Masked (`XXXXXXXX1234`) when the ID proof has mask_flag Y'),
  user_id_out: id.nullable(),
  device_id_out: z.string().nullable(),
  shift_id_out: id.nullable(),
  date_time_out: utcN,
  out_flag: yn,
  ...audit,
}).openapi('LuggageRow');

export const receiptRow = z.object({
  trans_id: z.uuid(),
  customer_id: id,
  bill_no: z.string(),
  bill_dt: day,
  user_id: id,
  device_id: z.string(),
  shift_id: id.nullable(),
  luggage_qty: id,
  days_charged: id.describe('Slab charges counted (repeats included)'),
  block_hours: id,
  first_block_rate: money,
  next_block_rate: money,
  rate_slabs: z.array(rateSlab).nullable().describe('Slabs the bill was made with (null: before Phase 13)'),
  grace_minutes: id.nullable().describe('Billing option used (null: stored before Phase 11 = 0)'),
  base_amt: money,
  penal_amt: money,
  net_amt: money.describe('Full bill'),
  adv_amt: money.describe('Advance subtracted on this bill'),
  bal_amt: money.describe('Collected at check-out = max(0, net_amt − adv_amt)'),
  taxable_amt: money,
  gst_pct: money,
  cgst: money,
  sgst: money,
  igst: money,
  gst_flag: yn.describe('N = 0 % GST'),
  gst_inclusive: yn.nullable().describe('Billing option used (null: before Phase 11 = Y)'),
  penalty_gst_flag: yn.nullable().describe('Billing option used (null: before Phase 11 = Y)'),
  gst_no: z.string().nullable().describe('Customer GSTIN stamped by the server when the bill was stored'),
  pay_mode: z.enum(['C', 'U']),
  upi_ref: z.string().nullable(),
  bill_printed: yn,
  ...audit,
}).openapi('ReceiptRow');

export const luggageDetail = luggageRow.extend({ receipt: receiptRow.nullable() });

export const pushResult = z.object({
  table: z.enum(['td_luggage_in_out', 'td_receipt']),
  trans_id: z.string(),
  local_version: z.union([z.number(), z.string()]).nullable(),
  status: z.enum(['ACCEPTED', 'EXCEPTION']).describe('Both mean: set sync_flag = Y on the device'),
  reason: z
    .string()
    .optional()
    .describe(
      '`CODE: detail`. Codes: VALIDATION_ERROR, INVALID_REFERENCE, TENANT_MISMATCH, DUPLICATE_RECEIPT_NO, DUPLICATE_CHECKOUT, BILL_WITHOUT_LUGGAGE, DUPLICATE_BILL, DUPLICATE_BILL_NO, CHECKOUT_MISSING, NO_RATE, AMOUNT_MISMATCH, APP_UPDATE_REQUIRED, DB_ERROR',
    ),
}).openapi('PushResult');

export const pushData = z.object({
  results: z.array(pushResult),
  counters,
  server_time: utc,
});

export const pullData = z.object({
  masters: mastersSnapshot.nullable().describe('null on continuation pages'),
  settings: settingsMap.nullable().describe('null on continuation pages'),
  counters: counters.nullable().describe('null on continuation pages'),
  luggage: z.array(luggageRow),
  has_more: z.boolean(),
  next_cursor: z.string().nullable().describe('Pass as `?cursor=` while has_more is true'),
  server_time: utc.describe('Store as the next `since` once has_more is false'),
});

// ------------------------------------------------------------------ reports

/** Bills on their bill_dt, advances on their check-in date (Phase 13). */
const receiptTotals = {
  bills: id,
  bags: id,
  base_amt: money,
  penal_amt: money,
  net_amt: money.describe('Billed (full bills)'),
  adv_amt: money.describe('Advances subtracted on these bills'),
  bal_amt: money.describe('Collected at check-out'),
  adv_collected: money.describe('Advances taken at check-in (cash) on this day'),
  cash_amt: money.describe('Cash balances + advances taken'),
  upi_amt: money.describe('UPI balances'),
  collected_amt: money.describe('cash_amt + upi_amt = adv_collected + bal_amt'),
};
const luggageTotals = { bags_entries: id, bags: id, extra_bags: id, adv_amt: money };

export const reportMeta = (totals: z.ZodType) =>
  pageMeta.extend({ totals, filters: z.record(z.string(), z.unknown()) });

const luggageReportBase = {
  trans_id: z.string(),
  receipt_no: z.string(),
  receipt_dt: day,
  date_time_in: utc,
  date_time_in_ist: ist,
  user_id_in: id,
  user_name_in: z.string().nullable(),
  shift_id_in: id.nullable(),
  shift_name_in: z.string().nullable(),
  pasn_name: z.string(),
  pasn_mob_no: z.string(),
  pnr_no: z.string(),
  luggage_qty: id,
  extra_lugage_qty: id,
  adv_amt: money,
  adv_hours: id,
  id_proof_id: id.nullable(),
  id_proof_name: z.string().nullable(),
  id_number: z.string().nullable(),
  out_flag: yn,
};

const gstSums = {
  bills: id,
  taxable_amt: money,
  cgst: money,
  sgst: money,
  igst: money,
  net_amt: money,
};

export const reports = {
  collectionSummary: {
    row: z.object({ bill_dt: day, ...receiptTotals }),
    totals: z.object(receiptTotals),
  },
  gst: {
    row: z.object({ bill_dt: day, gst_pct: money, ...gstSums }),
    totals: z.object(gstSums),
  },
  userShift: {
    row: z.object({
      user_id: id,
      user_name: z.string(),
      user_code: z.string().nullable(),
      shift_id: id.nullable(),
      shift_name: z.string().nullable(),
      ...receiptTotals,
    }),
    totals: z.object(receiptTotals),
  },
  unprinted: {
    row: z.object({
      trans_id: z.string(),
      bill_no: z.string(),
      bill_dt: day,
      user_id: id,
      user_name: z.string(),
      shift_id: id.nullable(),
      device_id: z.string(),
      luggage_qty: id,
      extra_lugage_qty: id,
      net_amt: money,
      adv_amt: money,
      bal_amt: money,
      pay_mode: z.enum(['C', 'U']),
      upi_ref: z.string().nullable(),
      receipt_no: z.string(),
      pasn_name: z.string(),
      pasn_mob_no: z.string(),
      synced_at_ist: ist,
    }),
    totals: z.object({ bills: id, net_amt: money, bal_amt: money }),
  },
  checkIn: {
    row: z.object({ ...luggageReportBase, date_time_out: utcN, date_time_out_ist: ist.nullable() }),
    totals: z.object(luggageTotals),
  },
  checkOut: {
    row: z.object({
      ...luggageReportBase,
      date_time_out: utc,
      date_time_out_ist: ist,
      user_id_out: id,
      user_name_out: z.string().nullable(),
      shift_id_out: id.nullable(),
      shift_name_out: z.string().nullable(),
      bill_no: z.string().nullable(),
      bill_dt: day.nullable(),
      days_charged: id.nullable(),
      net_amt: money.nullable(),
      bill_adv_amt: money.nullable().describe('Advance subtracted on the bill'),
      bal_amt: money.nullable(),
      pay_mode: z.enum(['C', 'U']).nullable(),
      bill_printed: yn.nullable(),
    }),
    totals: z.object({
      bags_entries: id,
      bags: id,
      extra_bags: id,
      net_amt: money,
      adv_amt: money,
      bal_amt: money,
    }),
  },
  bagsInStore: {
    row: z.object({ ...luggageReportBase, minutes_stored: id }),
    totals: z.object(luggageTotals),
  },
  exceptions: {
    row: z.object({
      id,
      trans_id: z.string(),
      table_name: z.string(),
      reason: z.string(),
      payload: z.unknown().describe('The refused row (plus `expected` / `existing` when relevant)'),
      user_id: id,
      user_name: z.string().nullable(),
      device_id: z.string(),
      resolved_flag: yn,
      resolved_by: z.string().nullable(),
      resolved_at: utcN,
      created_at: utc,
      created_at_ist: ist,
    }),
    totals: z.object({ exceptions: id, unresolved: id }),
  },
};

export const exceptionRow = z.object({
  id,
  customer_id: id,
  trans_id: z.string(),
  table_name: z.string(),
  payload: z.unknown(),
  reason: z.string(),
  user_id: id,
  device_id: z.string(),
  resolved_flag: yn,
  resolved_by: z.string().nullable(),
  resolved_at: utcN,
  created_at: utc,
});

// ------------------------------------------------------------------ app / health

export const appVersionRow = z.object({
  id,
  version_code: id,
  version_name: z.string(),
  apk_url: z.string(),
  mandatory_flag: yn,
  release_notes: z.string().nullable(),
  created_at: utcN,
}).openapi('AppVersionRow');

export const appVersionData = z.object({
  latest: appVersionRow.nullable(),
  update_available: z.boolean().nullable().describe('null when `current` is not sent'),
  mandatory: z.boolean().nullable().describe('true if any newer release is mandatory'),
});

export const healthData = z.object({
  status: z.literal('ok'),
  version: z.string(),
  uptime_sec: id,
  server_time: utc,
});

export const readyData = z.object({ status: z.literal('ready'), db: z.literal('ok'), server_time: utc });

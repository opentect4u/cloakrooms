import { z } from 'zod';
import type { SettingType } from '../../config/defaults';
import { positiveId } from '../../core/validation/fragments';

const yn = z.enum(['Y', 'N']);
const intRange = (min: number, max: number) =>
  z
    .string()
    .regex(/^\d+$/, 'must be a whole number')
    .refine((v) => Number(v) >= min && Number(v) <= max, `must be between ${min} and ${max}`)
    .transform((v) => String(Number(v)));
/** GST % 0.00 – 28.00, stored with 2 decimals. */
const gstPercent = z
  .string()
  .regex(/^\d{1,2}(\.\d{1,2})?$/, 'must be a percentage between 0.00 and 28.00')
  .refine((v) => Number(v) <= 28, 'must be a percentage between 0.00 and 28.00')
  .transform((v) => Number(v).toFixed(2));
/** Money 0.00 – 99999.99, stored with 2 decimals ("500" → "500.00"). */
const amount = z
  .string()
  .regex(/^\d{1,5}(\.\d{1,2})?$/, 'must be an amount between 0.00 and 99999.99')
  .transform((v) => Number(v).toFixed(2));

/**
 * Who a value applies to: `user` keys may have per-user override rows; `customer` keys are read
 * from the customer-wide row only (an override is refused, devices would ignore it).
 */
export type SettingScope = 'customer' | 'user';

interface Rule {
  type: SettingType;
  scope: SettingScope;
  value: z.ZodType<string, string>;
  /** Value devices and the server assume while no row exists. */
  default: string | null;
  /** Only a super admin may write it (station admins get 403). */
  superOnly?: boolean;
}

/**
 * Whitelisted keys (CLAUDE.md §11 + Phase 11 / BACKEND_API_REQUESTS A2): setting_type, scope,
 * allowed values and default per key. Of the Phase 11 keys the server itself uses
 * SHIFT_LOGIN_FLAG / SHIFT_EARLY_LOGIN_MIN / SINGLE_SESSION_FLAG (login) and GST_PCT,
 * GRACE_MINUTES, GST_INCLUSIVE, PENALTY_GST_FLAG (bill recompute); the rest are stored,
 * validated and synced for the devices.
 */
export const SETTING_RULES = {
  REPORT_FLAG: { type: 'G', scope: 'user', value: yn, default: 'N' },
  REPORT_PWD_FLAG: { type: 'G', scope: 'user', value: yn, default: 'N' },
  /** Stored as an argon2id hash, never returned. */
  REPORT_PWD: { type: 'G', scope: 'user', value: z.string().min(4).max(64), default: null },
  TOTAL_COLLECTION_FLAG: { type: 'G', scope: 'user', value: yn, default: 'Y' },
  RECEIPT_RESET: { type: 'G', scope: 'user', value: z.enum(['D', 'M', 'Y', 'N']), default: 'Y' },
  SYNC_INTERVAL_MIN: { type: 'G', scope: 'user', value: intRange(15, 1440), default: '15' },
  FORCE_ONLINE_DAYS: { type: 'G', scope: 'user', value: intRange(0, 365), default: '0' },
  GST_FLAG: { type: 'B', scope: 'user', value: yn, default: 'Y' },
  GST_TYPE: { type: 'B', scope: 'user', value: z.enum(['E', 'I']), default: 'E' },
  QR_FLAG: { type: 'B', scope: 'user', value: yn, default: 'Y' },
  DEFAULT_PAY_MODE: { type: 'B', scope: 'user', value: z.enum(['C', 'U']), default: 'C' },
  PAPER_WIDTH: { type: 'P', scope: 'user', value: z.enum(['58', '80']), default: '58' },

  // ---- Phase 11 (A2, as revised by the app developer on 2026-11-05) ----
  /** The station's own GST %; replaces the md_gst rate in the bill check. No default (= md_gst). */
  GST_PCT: { type: 'B', scope: 'customer', value: gstPercent, default: null },
  PENALTY_FLAG: { type: 'B', scope: 'customer', value: yn, default: 'Y' },
  PENALTY_MAX_AMT: { type: 'B', scope: 'customer', value: amount, default: '500.00' },
  PENALTY_GST_FLAG: { type: 'B', scope: 'customer', value: yn, default: 'Y' },
  GRACE_MINUTES: { type: 'B', scope: 'customer', value: intRange(0, 1440), default: '0' },
  GST_INCLUSIVE: { type: 'B', scope: 'customer', value: yn, default: 'Y' },
  UPI_ENABLED: { type: 'B', scope: 'customer', value: yn, default: 'Y' },
  OUT_WITHOUT_PRINT_FLAG: { type: 'B', scope: 'customer', value: yn, default: 'Y' },
  /** N: users of the station may be signed in on several devices at once (login). */
  SINGLE_SESSION_FLAG: { type: 'G', scope: 'customer', value: yn, default: 'Y', superOnly: true },
  MAX_BAGS_PER_RECEIPT: { type: 'G', scope: 'customer', value: intRange(1, 999), default: '10' },
  PNR_REQUIRED: { type: 'G', scope: 'customer', value: yn, default: 'Y' },
  ID_PROOF_REQUIRED: { type: 'G', scope: 'customer', value: yn, default: 'Y' },
  ID_MASK_FLAG: { type: 'G', scope: 'customer', value: yn, default: 'Y' },
  SHIFT_LOGIN_FLAG: { type: 'G', scope: 'customer', value: yn, default: 'Y' },
  SHIFT_EARLY_LOGIN_MIN: { type: 'G', scope: 'customer', value: intRange(0, 120), default: '15' },
  PWD_MIN_LENGTH: { type: 'G', scope: 'customer', value: intRange(8, 32), default: '8' },
  // ---- Phase 13 (advance at check-in) ----
  /** Y: the counter takes an advance at check-in (the tariff for ADVANCE_HOURS). */
  ADVANCE_FLAG: { type: 'B', scope: 'customer', value: yn, default: 'N' },
  ADVANCE_HOURS: { type: 'B', scope: 'customer', value: intRange(1, 720), default: '24' },
  CHECKIN_PRINT_FLAG: { type: 'P', scope: 'user', value: yn, default: 'Y' },
  PRINT_COPIES: { type: 'P', scope: 'user', value: intRange(1, 3), default: '1' },
} as const satisfies Record<string, Rule>;

export type SettingName = keyof typeof SETTING_RULES;
export const SETTING_NAMES = Object.keys(SETTING_RULES) as [SettingName, ...SettingName[]];

/** Default of a key (null: no default, e.g. REPORT_PWD). */
export function settingDefault(name: string): string | null {
  return (SETTING_RULES as Record<string, Rule>)[name]?.default ?? null;
}

const settingValue = z.union([z.string(), z.number()]).transform((v) => String(v).trim());

export const settingCreate = z.object({
  setting_name: z.enum(SETTING_NAMES),
  setting_value: settingValue,
  /** md_user.id for a per-user override (only `user`-scoped keys); omit/null for the customer. */
  user_id: positiveId.nullable().default(null),
});

export const settingUpdate = z.object({ setting_value: settingValue });

/** Is this a whitelisted key? (old rows of dropped keys are ignored everywhere). */
export function isSettingName(name: string): name is SettingName {
  return Object.prototype.hasOwnProperty.call(SETTING_RULES, name);
}

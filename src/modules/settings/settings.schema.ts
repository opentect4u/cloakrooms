import { z } from 'zod';
import type { SettingType } from '../../config/defaults';
import { positiveId } from '../../core/validation/fragments';

const yn = z.enum(['Y', 'N']);
const intRange = (min: number, max: number) =>
  z
    .string()
    .regex(/^\d+$/, 'must be a whole number')
    .refine((v) => Number(v) >= min && Number(v) <= max, `must be between ${min} and ${max}`);

/** Whitelisted keys (CLAUDE.md §11): setting_type and the allowed values per key. */
export const SETTING_RULES = {
  REPORT_FLAG: { type: 'G', value: yn },
  REPORT_PWD_FLAG: { type: 'G', value: yn },
  /** Stored as an argon2id hash, never returned. */
  REPORT_PWD: { type: 'G', value: z.string().min(4).max(64) },
  TOTAL_COLLECTION_FLAG: { type: 'G', value: yn },
  RECEIPT_RESET: { type: 'G', value: z.enum(['D', 'M', 'Y']) },
  SYNC_INTERVAL_MIN: { type: 'G', value: intRange(15, 1440) },
  FORCE_ONLINE_DAYS: { type: 'G', value: intRange(0, 365) },
  GST_FLAG: { type: 'B', value: yn },
  GST_TYPE: { type: 'B', value: z.enum(['E', 'I']) },
  QR_FLAG: { type: 'B', value: yn },
  DEFAULT_PAY_MODE: { type: 'B', value: z.enum(['C', 'U']) },
  PAPER_WIDTH: { type: 'P', value: z.enum(['58', '80']) },
} as const satisfies Record<string, { type: SettingType; value: z.ZodType<string> }>;

export type SettingName = keyof typeof SETTING_RULES;
export const SETTING_NAMES = Object.keys(SETTING_RULES) as [SettingName, ...SettingName[]];

const settingValue = z.union([z.string(), z.number()]).transform((v) => String(v).trim());

export const settingCreate = z.object({
  setting_name: z.enum(SETTING_NAMES),
  setting_value: settingValue,
  /** md_user.id for a per-user override; omit/null for the whole customer. */
  user_id: positiveId.nullable().default(null),
});

export const settingUpdate = z.object({ setting_value: settingValue });

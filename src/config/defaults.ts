/**
 * Templates applied when a customer is created (CLAUDE.md §11 "Seeds"; used by the customers
 * service in Phase 4, inside the same transaction as the md_customer insert).
 */

export type SettingType = 'G' | 'B' | 'P';

export interface SettingTemplate {
  setting_name: string;
  setting_value: string;
  setting_type: SettingType;
}

export const DEFAULT_SETTINGS: readonly SettingTemplate[] = [
  { setting_name: 'REPORT_FLAG', setting_value: 'N', setting_type: 'G' },
  { setting_name: 'REPORT_PWD_FLAG', setting_value: 'N', setting_type: 'G' },
  { setting_name: 'TOTAL_COLLECTION_FLAG', setting_value: 'Y', setting_type: 'G' },
  // TODO(open-point): FY numbering format to be confirmed with the CA.
  { setting_name: 'RECEIPT_RESET', setting_value: 'Y', setting_type: 'G' },
  { setting_name: 'SYNC_INTERVAL_MIN', setting_value: '15', setting_type: 'G' },
  { setting_name: 'GST_FLAG', setting_value: 'Y', setting_type: 'B' },
  { setting_name: 'GST_TYPE', setting_value: 'E', setting_type: 'B' },
  { setting_name: 'QR_FLAG', setting_value: 'Y', setting_type: 'B' },
  { setting_name: 'DEFAULT_PAY_MODE', setting_value: 'C', setting_type: 'B' },
  { setting_name: 'PAPER_WIDTH', setting_value: '58', setting_type: 'P' },
];
// REPORT_PWD is only written when an admin sets a report password; FORCE_ONLINE_DAYS is optional.

/** Default tariff per bag (CLAUDE.md §2.4). */
export const DEFAULT_RATE = {
  block_hours: 24,
  first_block_rate: '50.00',
  next_block_rate: '20.00',
} as const;

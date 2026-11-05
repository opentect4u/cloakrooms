/** Human labels and inputs for the settings keys (values are validated by SETTING_RULES). */
import { SETTING_NAMES, type SettingName } from '../modules/settings/settings.schema';

export type SettingInput =
  | { kind: 'yn' }
  | { kind: 'select'; options: readonly (readonly [string, string])[] }
  | { kind: 'int'; min: number; max: number; suffix?: string }
  | { kind: 'secret' };

export interface SettingMeta {
  label: string;
  help: string;
  group: 'General' | 'Billing' | 'Printing' | 'Reports';
  icon: string;
  input: SettingInput;
}

export const SETTING_META: Record<SettingName, SettingMeta> = {
  REPORT_FLAG: {
    label: 'Counter users can open reports',
    help: 'Admins always can. Off: operators see no reports.',
    group: 'Reports',
    icon: 'bar-chart',
    input: { kind: 'yn' },
  },
  REPORT_PWD_FLAG: {
    label: 'Ask for a report password',
    help: 'Reports then need the password set below.',
    group: 'Reports',
    icon: 'shield-lock',
    input: { kind: 'yn' },
  },
  REPORT_PWD: {
    label: 'Report password',
    help: '4–64 characters. Stored hashed; leave empty to keep the current one.',
    group: 'Reports',
    icon: 'key',
    input: { kind: 'secret' },
  },
  TOTAL_COLLECTION_FLAG: {
    label: 'Show total collection',
    help: 'Show the day’s total collection on the counter.',
    group: 'Reports',
    icon: 'cash-stack',
    input: { kind: 'yn' },
  },
  RECEIPT_RESET: {
    label: 'Receipt and bill numbers restart',
    help: 'Each user has their own series (e.g. U03-00125).',
    group: 'General',
    icon: '123',
    input: {
      kind: 'select',
      options: [
        ['D', 'Every day'],
        ['M', 'Every month'],
        ['Y', 'Every financial year (April)'],
      ],
    },
  },
  SYNC_INTERVAL_MIN: {
    label: 'Background sync every',
    help: 'Devices also sync after each check-in, check-out and when the internet returns.',
    group: 'General',
    icon: 'arrow-repeat',
    input: { kind: 'int', min: 15, max: 1440, suffix: 'minutes' },
  },
  FORCE_ONLINE_DAYS: {
    label: 'Require going online after',
    help: '0 = never. Devices offline for longer must connect before billing.',
    group: 'General',
    icon: 'wifi',
    input: { kind: 'int', min: 0, max: 365, suffix: 'days' },
  },
  GST_FLAG: {
    label: 'Print the GST breakup on bills',
    help: 'Rates include GST; this only controls the printed breakup.',
    group: 'Billing',
    icon: 'percent',
    input: { kind: 'yn' },
  },
  GST_TYPE: {
    label: 'GST type',
    help: 'Intra-state splits GST into CGST + SGST; inter-state charges IGST.',
    group: 'Billing',
    icon: 'diagram-2',
    input: {
      kind: 'select',
      options: [
        ['E', 'Intra-state (CGST + SGST)'],
        ['I', 'Inter-state (IGST)'],
      ],
    },
  },
  QR_FLAG: {
    label: 'Print a QR code on check-in receipts',
    help: 'Lets any counter check the bag out by scanning, even before it syncs.',
    group: 'Printing',
    icon: 'qr-code',
    input: { kind: 'yn' },
  },
  DEFAULT_PAY_MODE: {
    label: 'Default payment mode',
    help: 'Pre-selected at check-out.',
    group: 'Billing',
    icon: 'wallet2',
    input: {
      kind: 'select',
      options: [
        ['C', 'Cash'],
        ['U', 'UPI'],
      ],
    },
  },
  PAPER_WIDTH: {
    label: 'Printer paper width',
    help: 'Thermal paper roll used by the counters.',
    group: 'Printing',
    icon: 'printer',
    input: {
      kind: 'select',
      options: [
        ['58', '58 mm'],
        ['80', '80 mm'],
      ],
    },
  },
};

export const SETTING_GROUPS = ['General', 'Billing', 'Printing', 'Reports'] as const;

/** Display text of a stored value. */
export function settingText(name: SettingName, value: string | null | undefined): string {
  if (value === null || value === undefined || value === '') return 'Not set';
  const input = SETTING_META[name].input;
  if (input.kind === 'yn') return value === 'Y' ? 'On' : 'Off';
  if (input.kind === 'select') return input.options.find(([v]) => v === value)?.[1] ?? value;
  if (input.kind === 'secret') return 'Set';
  return `${value}${input.suffix ? ` ${input.suffix}` : ''}`;
}

export { SETTING_NAMES };

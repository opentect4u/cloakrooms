/** Human labels and inputs for the settings keys (values are validated by SETTING_RULES). */
import {
  SETTING_NAMES,
  SETTING_RULES,
  settingDefault,
  type SettingName,
} from '../modules/settings/settings.schema';

export type SettingInput =
  | { kind: 'yn' }
  | { kind: 'select'; options: readonly (readonly [string, string])[] }
  | { kind: 'int'; min: number; max: number; suffix?: string }
  | { kind: 'amount' }
  | { kind: 'percent' }
  | { kind: 'secret' };

export const SETTING_GROUPS = [
  'Billing',
  'Penalty & limits',
  'Check-in',
  'Sign-in & security',
  'Numbers & sync',
  'Printing',
  'Reports',
] as const;
export type SettingGroup = (typeof SETTING_GROUPS)[number];

export interface SettingMeta {
  label: string;
  help: string;
  group: SettingGroup;
  icon: string;
  input: SettingInput;
  /** Used by the server itself (not only by the devices). */
  server?: string;
  /** Shown instead of "Not set" when the key has no default (e.g. GST_PCT). */
  unsetText?: string;
}

const YN = { kind: 'yn' } as const;

export const SETTING_META: Record<SettingName, SettingMeta> = {
  // ---- Billing
  GST_FLAG: {
    label: 'Charge GST',
    help: 'Off: the station is not GST-registered; bills carry 0 % GST.',
    group: 'Billing',
    icon: 'percent',
    input: YN,
    server: 'Used when the server checks each bill.',
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
    server: 'Used when the server checks each bill.',
  },
  GST_PCT: {
    label: 'Station GST rate',
    help: 'Only if this station uses its own GST %; replaces the platform GST rate on its bills.',
    group: 'Billing',
    icon: 'percent',
    input: { kind: 'percent' },
    server: 'Used when the server checks each bill.',
    unsetText: 'Not set · platform GST rate',
  },
  GST_INCLUSIVE: {
    label: 'Rates include GST',
    help: 'On: GST is inside the tariff. Off: GST is added on top of the tariff.',
    group: 'Billing',
    icon: 'calculator',
    input: YN,
    server: 'Used when the server checks each bill.',
  },
  GRACE_MINUTES: {
    label: 'Grace period',
    help: 'Minutes free before the next block is charged (e.g. 24 h 10 min with 15 min grace = 1 day).',
    group: 'Billing',
    icon: 'hourglass-split',
    input: { kind: 'int', min: 0, max: 1440, suffix: 'minutes' },
    server: 'Used when the server checks each bill.',
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
  UPI_ENABLED: {
    label: 'Accept UPI',
    help: 'Show the UPI option and the payment QR at check-out.',
    group: 'Billing',
    icon: 'qr-code-scan',
    input: YN,
  },
  // ---- Penalty & limits
  PENALTY_FLAG: {
    label: 'Allow a penalty at check-out',
    help: 'The counter may add a penalty (e.g. for a damaged receipt).',
    group: 'Penalty & limits',
    icon: 'exclamation-diamond',
    input: YN,
  },
  PENALTY_MAX_AMT: {
    label: 'Highest penalty',
    help: 'Largest penalty the counter may type.',
    group: 'Penalty & limits',
    icon: 'cash',
    input: { kind: 'amount' },
  },
  PENALTY_GST_FLAG: {
    label: 'GST on the penalty',
    help: 'Off: GST applies to the rent only; the penalty is outside the taxable value.',
    group: 'Penalty & limits',
    icon: 'percent',
    input: YN,
    server: 'Used when the server checks each bill.',
  },
  OUT_WITHOUT_PRINT_FLAG: {
    label: 'Allow “Out without bill print”',
    help: 'The counter may close a bag without printing the bill.',
    group: 'Penalty & limits',
    icon: 'printer',
    input: YN,
  },
  // ---- Check-in
  ADVANCE_FLAG: {
    label: 'Take an advance at check-in',
    help: 'The counter collects the rent for the advance hours at check-in; the bill then asks only for the rest (no refund when the bill is smaller).',
    group: 'Check-in',
    icon: 'wallet2',
    input: YN,
  },
  ADVANCE_HOURS: {
    label: 'Advance covers',
    help: 'Hours of rent collected as the advance.',
    group: 'Check-in',
    icon: 'hourglass-split',
    input: { kind: 'int', min: 1, max: 720, suffix: 'hours' },
  },
  MAX_BAGS_PER_RECEIPT: {
    label: 'Most bags on one receipt',
    help: 'Largest number of bags in a single check-in.',
    group: 'Check-in',
    icon: 'suitcase2',
    input: { kind: 'int', min: 1, max: 999, suffix: 'bags' },
  },
  PNR_REQUIRED: {
    label: 'PNR is mandatory',
    help: 'The counter must enter the passenger’s PNR.',
    group: 'Check-in',
    icon: 'ticket-perforated',
    input: YN,
  },
  ID_PROOF_REQUIRED: {
    label: 'ID proof is mandatory',
    help: 'The counter must record an ID document and number.',
    group: 'Check-in',
    icon: 'person-vcard',
    input: YN,
  },
  ID_MASK_FLAG: {
    label: 'Mask every ID number',
    help: 'Keep only the last 4 characters of any ID number (not only Aadhaar).',
    group: 'Check-in',
    icon: 'eye-slash',
    input: YN,
  },
  QR_FLAG: {
    label: 'QR code on check-in receipts',
    help: 'Lets any counter check the bag out by scanning, even before it syncs.',
    group: 'Check-in',
    icon: 'qr-code',
    input: YN,
  },
  // ---- Sign-in & security
  SINGLE_SESSION_FLAG: {
    label: 'One device per user at a time',
    help: 'Off: a user of this station may be signed in on several devices; each still takes a licensed seat. Only Synergic can change this.',
    group: 'Sign-in & security',
    icon: 'phone-flip',
    input: YN,
    server: 'Enforced by the server at sign-in.',
  },
  SHIFT_LOGIN_FLAG: {
    label: 'Counter users sign in only during their shift',
    help: 'Applies to counter users who have a shift in the roster; admins are not limited.',
    group: 'Sign-in & security',
    icon: 'clock-history',
    input: YN,
    server: 'Enforced by the server at sign-in.',
  },
  SHIFT_EARLY_LOGIN_MIN: {
    label: 'Sign-in opens before the shift',
    help: 'Minutes before the shift starts when counter users may sign in.',
    group: 'Sign-in & security',
    icon: 'alarm',
    input: { kind: 'int', min: 0, max: 120, suffix: 'minutes' },
    server: 'Enforced by the server at sign-in.',
  },
  PWD_MIN_LENGTH: {
    label: 'Shortest password on the device',
    help: 'The server always requires at least 8 characters with a letter and a digit.',
    group: 'Sign-in & security',
    icon: 'key',
    input: { kind: 'int', min: 8, max: 32, suffix: 'characters' },
  },
  FORCE_ONLINE_DAYS: {
    label: 'Require going online after',
    help: '0 = never. Devices offline for longer must connect before billing.',
    group: 'Sign-in & security',
    icon: 'wifi',
    input: { kind: 'int', min: 0, max: 365, suffix: 'days' },
  },
  // ---- Numbers & sync
  RECEIPT_RESET: {
    label: 'Receipt and bill numbers restart',
    help: 'Each user has their own series (e.g. U03-00125).',
    group: 'Numbers & sync',
    icon: '123',
    input: {
      kind: 'select',
      options: [
        ['D', 'Every day'],
        ['M', 'Every month'],
        ['Y', 'Every financial year (April)'],
        ['N', 'Never'],
      ],
    },
  },
  SYNC_INTERVAL_MIN: {
    label: 'Background sync every',
    help: 'Devices also sync after each check-in, check-out and when the internet returns.',
    group: 'Numbers & sync',
    icon: 'arrow-repeat',
    input: { kind: 'int', min: 15, max: 1440, suffix: 'minutes' },
  },
  // ---- Printing
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
  CHECKIN_PRINT_FLAG: {
    label: 'Print a receipt at check-in',
    help: 'Can be set per user below.',
    group: 'Printing',
    icon: 'receipt',
    input: YN,
  },
  PRINT_COPIES: {
    label: 'Copies per print',
    help: 'Can be set per user below.',
    group: 'Printing',
    icon: 'files',
    input: { kind: 'int', min: 1, max: 3, suffix: 'copies' },
  },
  // ---- Reports
  REPORT_FLAG: {
    label: 'Counter users can open reports',
    help: 'Admins always can. Off: operators see no reports.',
    group: 'Reports',
    icon: 'bar-chart',
    input: YN,
  },
  REPORT_PWD_FLAG: {
    label: 'Ask for a report password',
    help: 'Reports then need the password set below.',
    group: 'Reports',
    icon: 'shield-lock',
    input: YN,
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
    input: YN,
  },
};

/** Display text of a stored value. */
export function settingText(name: SettingName, value: string | null | undefined): string {
  if (value === null || value === undefined || value === '') return 'Not set';
  const input = SETTING_META[name].input;
  if (input.kind === 'yn') return value === 'Y' ? 'On' : 'Off';
  if (input.kind === 'select') return input.options.find(([v]) => v === value)?.[1] ?? value;
  if (input.kind === 'secret') return 'Set';
  if (input.kind === 'amount') return `₹${value}`;
  if (input.kind === 'percent') return `${value}%`;
  return `${value}${input.suffix ? ` ${input.suffix}` : ''}`;
}

/** "Default: On" for an unset key (null when the key has no default). */
export function defaultText(name: SettingName): string | null {
  const d = settingDefault(name);
  return d === null ? null : settingText(name, d);
}

/** Keys that may be overridden per user. */
export const USER_SCOPED: SettingName[] = SETTING_NAMES.filter(
  (n) => SETTING_RULES[n].scope === 'user',
);

export { SETTING_NAMES };

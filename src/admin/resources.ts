/**
 * Customer tabs rendered by the generic list/form views. Each wraps the API module's CrudConfig,
 * so the panel validates with the same schemas and writes through the same crudService hooks.
 */
import type { CrudConfig } from '../core/crud/crudFactory';
import type { Row } from '../core/db/dml';
import { toPaise, fromPaise } from '../core/utils/money';
import { toIstDate } from '../core/utils/time';
import { paymentsCrud, registrationsCrud } from '../modules/customers/customers.routes';
import { gstCrud } from '../modules/gst/gst.routes';
import { headerFooterCrud } from '../modules/header-footer/header-footer.routes';
import { idProofsCrud } from '../modules/id-proofs/id-proofs.routes';
import { ratesCrud } from '../modules/rates/rates.routes';
import { shiftAssignmentsCrud } from '../modules/shift-assignments/shift-assignments.routes';
import { shiftsCrud } from '../modules/shifts/shifts.routes';
import { YES_NO, type Column, type Field, type Lookup } from './fields';
import { inr } from './viewHelpers';

export interface Badge {
  label: string;
  tone: 'success' | 'warning' | 'danger' | 'primary' | 'muted';
  icon?: string;
}

export interface Resource {
  /** URL segment and tab key. */
  key: string;
  title: string;
  singular: string;
  icon: string;
  blurb: string;
  crud: CrudConfig;
  /**
   * tenant: rows of the customer (tenant context); parent: nested under md_customer (S);
   * global: platform masters (S).
   */
  scope: 'tenant' | 'parent' | 'global';
  columns: Column[];
  fields: Field[];
  lookups?: Lookup[];
  emptyTitle: string;
  emptyText: string;
  /** Prefilled values on the create form. */
  defaults?: () => Record<string, string>;
  /** Status badges per row (e.g. the tariff in force). */
  badges?: (row: Row, rows: Row[], today: string) => Badge[];
  /** One-line hint under the row's main cell. */
  hint?: (row: Row) => string;
  deleteWarning?: string;
  limit?: number;
}

const ynSwitch = (name: string, label: string, help?: string): Field => ({
  name,
  label,
  type: 'switch',
  help,
  col: 'col-sm-4',
});

/** Tariff example: total for one bag over 1, 2 and 3 blocks. */
function tariffHint(row: Row): string {
  const first = toPaise(String(row.first_block_rate));
  const next = toPaise(String(row.next_block_rate));
  const hrs = Number(row.block_hours);
  return [1, 2, 3]
    .map((d) => `${d * hrs} h ${inr(fromPaise(first + next * (d - 1)), true)}`)
    .join(' · ');
}

/** Row in force today = latest effective_from ≤ today; later ones are scheduled. */
function rateBadges(row: Row, rows: Row[], today: string): Badge[] {
  const from = String(row.effective_from);
  if (from > today) return [{ label: 'Scheduled', tone: 'primary', icon: 'calendar-plus' }];
  const current = rows
    .map((r) => String(r.effective_from))
    .filter((d) => d <= today)
    .sort()
    .at(-1);
  return from === current
    ? [{ label: 'In force', tone: 'success', icon: 'check-circle' }]
    : [{ label: 'Past', tone: 'muted' }];
}

export const RESOURCES: Resource[] = [
  {
    key: 'rates',
    title: 'Tariff',
    singular: 'tariff',
    icon: 'currency-rupee',
    blurb:
      'Per-bag rates, versioned by date. A bill uses the tariff in force on its check-in date; amounts include GST.',
    crud: ratesCrud,
    scope: 'tenant',
    columns: [
      { key: 'effective_from', label: 'Effective from', kind: 'date' },
      { key: 'block_hours', label: 'Block', kind: 'text', suffix: 'h' },
      { key: 'first_block_rate', label: 'First block', kind: 'money' },
      { key: 'next_block_rate', label: 'Each further block', kind: 'money' },
    ],
    fields: [
      {
        name: 'effective_from',
        label: 'Effective from',
        type: 'date',
        required: true,
        col: 'col-sm-6',
        help: 'Check-ins from this date use this tariff.',
      },
      {
        name: 'block_hours',
        label: 'Block length',
        type: 'number',
        min: 1,
        max: 720,
        suffix: 'hours',
        required: true,
        col: 'col-sm-6',
      },
      {
        name: 'first_block_rate',
        label: 'First block (per bag)',
        type: 'money',
        prefix: '₹',
        required: true,
        col: 'col-sm-6',
      },
      {
        name: 'next_block_rate',
        label: 'Each further started block',
        type: 'money',
        prefix: '₹',
        required: true,
        col: 'col-sm-6',
      },
    ],
    defaults: () => ({
      effective_from: toIstDate(),
      block_hours: '24',
      first_block_rate: '50.00',
      next_block_rate: '20.00',
    }),
    badges: rateBadges,
    hint: tariffHint,
    emptyTitle: 'No tariff yet',
    emptyText: 'Add a tariff so devices can bill check-outs.',
    deleteWarning:
      'Bills of bags checked in on dates this tariff covers will be flagged when they sync.',
  },
  {
    key: 'shifts',
    title: 'Shifts',
    singular: 'shift',
    icon: 'clock',
    blurb: 'Working shifts of this cloak room. A shift may cross midnight (e.g. 22:00 → 06:00).',
    crud: shiftsCrud,
    scope: 'tenant',
    columns: [
      { key: 'shift_name', label: 'Shift', kind: 'strong' },
      { key: 'from_time', label: 'From', kind: 'time' },
      { key: 'to_time', label: 'To', kind: 'time' },
    ],
    fields: [
      {
        name: 'shift_name',
        label: 'Name',
        type: 'text',
        required: true,
        maxlength: 50,
        col: 'col-12',
        placeholder: 'Morning',
      },
      { name: 'from_time', label: 'Starts', type: 'time', required: true, col: 'col-sm-6' },
      { name: 'to_time', label: 'Ends', type: 'time', required: true, col: 'col-sm-6' },
    ],
    badges: (row) =>
      String(row.to_time) < String(row.from_time)
        ? [{ label: 'Crosses midnight', tone: 'primary', icon: 'moon-stars' }]
        : [],
    emptyTitle: 'No shifts yet',
    emptyText: 'Add the cloak room’s shifts, then assign users to them.',
  },
  {
    key: 'shift-assignments',
    title: 'Shift roster',
    singular: 'assignment',
    icon: 'calendar-week',
    blurb:
      'Which shift each user works, from a date. The latest assignment on or before a day applies.',
    crud: shiftAssignmentsCrud,
    scope: 'tenant',
    lookups: ['users', 'shifts'],
    columns: [
      { key: 'user_id', label: 'User', kind: 'lookup', lookup: 'users' },
      { key: 'shift_id', label: 'Shift', kind: 'lookup', lookup: 'shifts' },
      { key: 'effective_from', label: 'From', kind: 'date' },
    ],
    fields: [
      {
        name: 'user_id',
        label: 'User',
        type: 'select',
        optionsFrom: 'users',
        required: true,
        createOnly: true,
        col: 'col-12',
      },
      {
        name: 'shift_id',
        label: 'Shift',
        type: 'select',
        optionsFrom: 'shifts',
        required: true,
        col: 'col-sm-6',
      },
      { name: 'effective_from', label: 'From', type: 'date', required: true, col: 'col-sm-6' },
    ],
    defaults: () => ({ effective_from: toIstDate() }),
    emptyTitle: 'No assignments yet',
    emptyText: 'Assign users to shifts so the app can stamp the shift on each receipt.',
  },
  {
    key: 'header-footer',
    title: 'Receipt lines',
    singular: 'line',
    icon: 'receipt',
    blurb: 'Text printed at the top and bottom of receipts, bills and reports.',
    crud: headerFooterCrud,
    scope: 'tenant',
    columns: [
      {
        key: 'line_type',
        label: 'Where',
        kind: 'enum',
        labels: { H: 'Header', F: 'Footer', L: 'Logo' },
      },
      { key: 'line_order', label: 'Order', kind: 'text', align: 'center' },
      { key: 'narration', label: 'Text', kind: 'strong' },
      { key: 'in_flag', label: 'Check-in', kind: 'yn' },
      { key: 'out_flag', label: 'Bill', kind: 'yn' },
      { key: 'report_flag', label: 'Reports', kind: 'yn', hideBelow: 'md' },
    ],
    fields: [
      {
        name: 'line_type',
        label: 'Position',
        type: 'select',
        options: [
          ['H', 'Header (top)'],
          ['F', 'Footer (bottom)'],
        ],
        required: true,
        createOnly: true,
        col: 'col-sm-6',
      },
      {
        name: 'line_order',
        label: 'Order',
        type: 'number',
        min: 0,
        max: 999,
        col: 'col-sm-6',
        help: 'Lower numbers print first.',
      },
      {
        name: 'narration',
        label: 'Text',
        type: 'text',
        required: true,
        maxlength: 100,
        col: 'col-12',
        placeholder: 'Howrah Station Cloak Room',
      },
      ynSwitch('in_flag', 'On check-in receipt'),
      ynSwitch('out_flag', 'On bill'),
      ynSwitch('report_flag', 'On reports'),
    ],
    defaults: () => ({
      line_type: 'H',
      line_order: '0',
      in_flag: 'Y',
      out_flag: 'Y',
      report_flag: 'N',
    }),
    emptyTitle: 'No receipt lines yet',
    emptyText: 'Add the station name, address and a thank-you line.',
  },
  {
    key: 'registrations',
    title: 'Subscriptions',
    singular: 'subscription',
    icon: 'patch-check',
    blurb:
      'Registrations with Synergic. The latest end date becomes the renewal date; after it, the station’s users cannot sign in.',
    crud: registrationsCrud,
    scope: 'parent',
    columns: [
      { key: 'reg_no', label: 'Registration no.', kind: 'strong' },
      { key: 'start_date', label: 'Start', kind: 'date' },
      { key: 'end_date', label: 'End', kind: 'date' },
      { key: 'user_count', label: 'Users', kind: 'text', align: 'center' },
      { key: 'remarks', label: 'Remarks', kind: 'text', hideBelow: 'lg' },
    ],
    fields: [
      {
        name: 'reg_no',
        label: 'Registration no.',
        type: 'text',
        required: true,
        maxlength: 30,
        col: 'col-sm-6',
        placeholder: 'REG-2026-001',
      },
      {
        name: 'user_count',
        label: 'Licensed users',
        type: 'number',
        min: 1,
        max: 1000,
        required: true,
        col: 'col-sm-6',
      },
      { name: 'start_date', label: 'Start', type: 'date', required: true, col: 'col-sm-6' },
      { name: 'end_date', label: 'End', type: 'date', required: true, col: 'col-sm-6' },
      { name: 'remarks', label: 'Remarks', type: 'textarea', maxlength: 255, col: 'col-12' },
    ],
    defaults: () => {
      const today = toIstDate();
      const nextYear = `${Number(today.slice(0, 4)) + 1}${today.slice(4)}`;
      return { start_date: today, end_date: nextYear, user_count: '1' };
    },
    badges: (row, _rows, today) => {
      if (String(row.start_date) > today)
        return [{ label: 'Upcoming', tone: 'primary', icon: 'calendar-plus' }];
      if (String(row.end_date) < today) return [{ label: 'Expired', tone: 'muted' }];
      return [{ label: 'Active', tone: 'success', icon: 'check-circle' }];
    },
    emptyTitle: 'No subscription yet',
    emptyText: 'Add a registration: until then this station’s users cannot sign in.',
    deleteWarning: 'The renewal date is recalculated from the remaining registrations.',
  },
  {
    key: 'payments',
    title: 'Payments',
    singular: 'payment',
    icon: 'wallet2',
    blurb:
      'Payments received from this customer. Net = base + CGST + SGST + IGST (computed when left empty).',
    crud: paymentsCrud,
    scope: 'parent',
    lookups: ['registrations'],
    columns: [
      { key: 'payment_dt', label: 'Date', kind: 'date' },
      { key: 'pay_mode', label: 'Mode', kind: 'enum', labels: { C: 'Cash', U: 'UPI', B: 'Bank' } },
      {
        key: 'reg_id',
        label: 'Registration',
        kind: 'lookup',
        lookup: 'registrations',
        hideBelow: 'md',
      },
      { key: 'transaction_no', label: 'Reference', kind: 'text', hideBelow: 'lg' },
      { key: 'base_amt', label: 'Base', kind: 'money', hideBelow: 'md' },
      { key: 'net_amt', label: 'Net', kind: 'money' },
    ],
    fields: [
      { name: 'payment_dt', label: 'Date', type: 'date', required: true, col: 'col-sm-6' },
      {
        name: 'pay_mode',
        label: 'Mode',
        type: 'select',
        options: [
          ['C', 'Cash'],
          ['U', 'UPI'],
          ['B', 'Bank transfer'],
        ],
        required: true,
        col: 'col-sm-6',
      },
      {
        name: 'reg_id',
        label: 'For registration',
        type: 'select',
        optionsFrom: 'registrations',
        emptyOption: '— Not linked —',
        col: 'col-sm-6',
      },
      {
        name: 'transaction_no',
        label: 'Reference / UTR',
        type: 'text',
        maxlength: 50,
        col: 'col-sm-6',
      },
      {
        name: 'base_amt',
        label: 'Base amount',
        type: 'money',
        prefix: '₹',
        required: true,
        col: 'col-sm-6 col-lg-3',
      },
      { name: 'cgst', label: 'CGST', type: 'money', prefix: '₹', col: 'col-sm-6 col-lg-3' },
      { name: 'sgst', label: 'SGST', type: 'money', prefix: '₹', col: 'col-sm-6 col-lg-3' },
      { name: 'igst', label: 'IGST', type: 'money', prefix: '₹', col: 'col-sm-6 col-lg-3' },
      {
        name: 'net_amt',
        label: 'Net amount',
        type: 'money',
        prefix: '₹',
        col: 'col-sm-6',
        help: 'Leave empty to compute it.',
      },
      { name: 'remarks', label: 'Remarks', type: 'textarea', maxlength: 255, col: 'col-12' },
    ],
    defaults: () => ({ payment_dt: toIstDate(), pay_mode: 'U' }),
    emptyTitle: 'No payments yet',
    emptyText: 'Record payments received from this customer here.',
  },
];

/** Platform-wide masters maintained by Synergic (sidebar "Platform"). */
export const GLOBAL_RESOURCES: Resource[] = [
  {
    key: 'gst',
    title: 'GST rates',
    singular: 'GST rate',
    icon: 'percent',
    blurb:
      'GST rates for every station, versioned by date. A bill uses the rate in force on its bill date; intra-state = CGST + SGST, inter-state = IGST.',
    crud: gstCrud,
    scope: 'global',
    columns: [
      { key: 'effective_from', label: 'Effective from', kind: 'date' },
      { key: 'cgst_pct', label: 'CGST', kind: 'text', suffix: '%', align: 'end' },
      { key: 'sgst_pct', label: 'SGST', kind: 'text', suffix: '%', align: 'end' },
      { key: 'igst_pct', label: 'IGST', kind: 'text', suffix: '%', align: 'end' },
    ],
    fields: [
      {
        name: 'effective_from',
        label: 'Effective from',
        type: 'date',
        required: true,
        col: 'col-12',
        help: 'Bills dated from this day use these rates.',
      },
      { name: 'cgst_pct', label: 'CGST', type: 'money', suffix: '%', col: 'col-sm-4' },
      { name: 'sgst_pct', label: 'SGST', type: 'money', suffix: '%', col: 'col-sm-4' },
      { name: 'igst_pct', label: 'IGST', type: 'money', suffix: '%', col: 'col-sm-4' },
    ],
    defaults: () => ({ effective_from: toIstDate(), cgst_pct: '9', sgst_pct: '9', igst_pct: '18' }),
    badges: rateBadges,
    hint: (row) =>
      `Intra-state ${Number(row.cgst_pct) + Number(row.sgst_pct)}% · inter-state ${Number(row.igst_pct)}%`,
    emptyTitle: 'No GST rates',
    emptyText: 'Add the rate in force so bills can be split into taxable value and GST.',
    deleteWarning:
      'Bills dated in this rate’s period will be recalculated with the previous rate and may be flagged.',
  },
  {
    key: 'id-proofs',
    title: 'ID proofs',
    singular: 'ID proof',
    icon: 'person-vcard',
    blurb: 'Identity documents counter users can record at check-in, for every station.',
    crud: idProofsCrud,
    scope: 'global',
    columns: [
      { key: 'id_proof_name', label: 'Document', kind: 'strong' },
      { key: 'mask_flag', label: 'Masked', kind: 'yn' },
    ],
    fields: [
      {
        name: 'id_proof_name',
        label: 'Document name',
        type: 'text',
        required: true,
        maxlength: 50,
        col: 'col-12',
        placeholder: 'Aadhaar Card',
      },
      {
        name: 'mask_flag',
        label: 'Keep only the last 4 characters',
        type: 'switch',
        col: 'col-12',
        help: 'For numbers that must not be stored in full (Aadhaar): stored and printed as XXXXXXXX1234.',
      },
    ],
    defaults: () => ({ mask_flag: 'N' }),
    badges: (row) =>
      row.mask_flag === 'Y' ? [{ label: 'Last 4 only', tone: 'primary', icon: 'eye-slash' }] : [],
    emptyTitle: 'No ID proofs',
    emptyText: 'Add the documents stations accept at check-in.',
    deleteWarning:
      'Devices stop offering it at check-in after their next sync; old check-ins keep it.',
  },
];

export const resourceByKey = new Map(RESOURCES.map((r) => [r.key, r]));
export { YES_NO };

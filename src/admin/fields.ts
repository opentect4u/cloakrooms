/** Declarative form fields and table columns for the panel's generic list/form views. */
import type { Row } from '../core/db/dml';
import { date, dateTimeIst, inr } from './viewHelpers';

export type Lookup = 'users' | 'shifts' | 'registrations';
export type Lookups = Partial<Record<Lookup, Map<number, string>>>;

export interface Field {
  name: string;
  label: string;
  type:
    | 'text'
    | 'email'
    | 'number'
    | 'money'
    | 'date'
    | 'time'
    | 'select'
    | 'switch'
    | 'textarea'
    | 'password';
  options?: readonly (readonly [string, string])[];
  /** Select options from a lookup of the customer (users, shifts, registrations). */
  optionsFrom?: Lookup;
  required?: boolean;
  help?: string;
  placeholder?: string;
  /** Bootstrap column class inside the form grid. */
  col?: string;
  /** Only on the create form (e.g. keys that cannot change later). */
  createOnly?: boolean;
  min?: number;
  max?: number;
  maxlength?: number;
  /** Leading icon inside the input. */
  icon?: string;
  /** Text shown before the input (e.g. ₹). */
  prefix?: string;
  suffix?: string;
  autocomplete?: string;
  /** Suggestions for a text input (HTML datalist). */
  datalist?: readonly string[];
  /** Allow an empty choice in a select ("— none —"). */
  emptyOption?: string;
}

export type CellKind =
  'text' | 'strong' | 'money' | 'date' | 'datetime' | 'time' | 'yn' | 'lookup' | 'enum';

export interface Column {
  key: string;
  label: string;
  kind?: CellKind;
  lookup?: Lookup;
  /** Labels for kind 'enum'. */
  labels?: Record<string, string>;
  align?: 'end' | 'center';
  /** Unit after the value, e.g. "h". */
  suffix?: string;
  /** Hidden below this breakpoint (keeps phone tables readable). */
  hideBelow?: 'sm' | 'md' | 'lg';
}

export interface Cell {
  text: string;
  kind: CellKind;
  align?: string;
  hideBelow?: string;
  yes?: boolean;
}

export function cellOf(col: Column, row: Row, lookups: Lookups): Cell {
  const v = row[col.key];
  const kind = col.kind ?? 'text';
  const base = { kind, align: col.align, hideBelow: col.hideBelow };
  if (v === null || v === undefined || v === '') return { ...base, text: '—' };
  switch (kind) {
    case 'money':
      return { ...base, text: inr(String(v)), align: col.align ?? 'end' };
    case 'date':
      return { ...base, text: date(String(v)) };
    case 'datetime':
      return { ...base, text: dateTimeIst(String(v)) };
    case 'time':
      return { ...base, text: String(v).slice(0, 5) };
    case 'yn':
      return {
        ...base,
        text: v === 'Y' ? 'Yes' : 'No',
        yes: v === 'Y',
        align: col.align ?? 'center',
      };
    case 'lookup':
      return { ...base, text: lookups[col.lookup!]?.get(Number(v)) ?? `#${String(v)}` };
    case 'enum':
      return { ...base, text: col.labels?.[String(v)] ?? String(v) };
    default:
      return { ...base, text: col.suffix ? `${String(v)} ${col.suffix}` : String(v) };
  }
}

/** Row values → form input values (dates as YYYY-MM-DD, times as HH:mm). */
export function inputValues(fields: readonly Field[], row: Row | null): Record<string, string> {
  const out: Record<string, string> = {};
  if (!row) return out;
  for (const f of fields) {
    const v = row[f.name];
    if (v === null || v === undefined) continue;
    out[f.name] = f.type === 'time' ? String(v).slice(0, 5) : String(v);
  }
  return out;
}

export const YES_NO = [
  ['Y', 'Yes'],
  ['N', 'No'],
] as const;

export const INDIAN_STATES = [
  'Andaman and Nicobar Islands',
  'Andhra Pradesh',
  'Arunachal Pradesh',
  'Assam',
  'Bihar',
  'Chandigarh',
  'Chhattisgarh',
  'Dadra and Nagar Haveli and Daman and Diu',
  'Delhi',
  'Goa',
  'Gujarat',
  'Haryana',
  'Himachal Pradesh',
  'Jammu and Kashmir',
  'Jharkhand',
  'Karnataka',
  'Kerala',
  'Ladakh',
  'Lakshadweep',
  'Madhya Pradesh',
  'Maharashtra',
  'Manipur',
  'Meghalaya',
  'Mizoram',
  'Nagaland',
  'Odisha',
  'Puducherry',
  'Punjab',
  'Rajasthan',
  'Sikkim',
  'Tamil Nadu',
  'Telangana',
  'Tripura',
  'Uttar Pradesh',
  'Uttarakhand',
  'West Bengal',
].map((s) => [s, s] as const);

/**
 * Master data a device needs offline. Used by login (full snapshot) and by pull (Phase 6, with
 * `since`: changed rows only, soft-deleted included so devices can hide them).
 */
import type { RequestContext } from '../../core/context';
import { serializeRow } from '../../core/crud/crudFactory';
import { fetch, type Executor, type OrderBy, type Row, type Where } from '../../core/db/dml';
import {
  isSettingName,
  SETTING_NAMES,
  SETTING_RULES,
  settingDefault,
} from '../settings/settings.schema';

export interface MastersSnapshot {
  id_proofs: Row[];
  gst: Row[];
  rates: Row[];
  shifts: Row[];
  shift_assignments: Row[];
  header_footer: Row[];
  /** The tenant's users, display fields only (names/codes on rows from other counters). */
  users: Row[];
}

type MasterSpec = readonly [
  key: keyof MastersSnapshot,
  table: string,
  orderBy: readonly OrderBy[],
  columns?: readonly string[],
  /** Also on the full snapshot: old rows still reference deleted entries. */
  withDeleted?: boolean,
];

const GLOBAL_MASTERS: readonly MasterSpec[] = [
  ['id_proofs', 'md_id_proof', [{ column: 'id_proof_id' }]],
  ['gst', 'md_gst', [{ column: 'effective_from' }]],
];

/** Sent to devices instead of the full md_user row (no device/session/login details). */
const USER_COLUMNS = [
  'id',
  'customer_id',
  'user_code',
  'user_name',
  'user_type',
  'allow_flag',
  'is_deleted',
  'updated_at',
];

const TENANT_MASTERS: readonly MasterSpec[] = [
  ['rates', 'md_rate_dtls', [{ column: 'effective_from' }]],
  ['shifts', 'md_shift', [{ column: 'shift_id' }]],
  ['shift_assignments', 'td_assign_shift', [{ column: 'user_id' }, { column: 'effective_from' }]],
  ['header_footer', 'md_header_footer', [{ column: 'line_type' }, { column: 'line_order' }]],
  ['users', 'md_user', [{ column: 'id' }], USER_COLUMNS, true],
];

export async function loadMasters(
  ctx: RequestContext,
  opts: { since?: string } = {},
  conn?: Executor,
): Promise<MastersSnapshot> {
  const where: Where = opts.since ? { updated_at: { op: '>=', value: opts.since } } : {};
  const load = async ([, table, orderBy, columns, withDeleted]: MasterSpec) => {
    const { rows } = await fetch(
      table,
      {
        where,
        orderBy,
        includeDeleted: Boolean(opts.since) || Boolean(withDeleted),
        ...(columns && { columns: [...columns] }),
      },
      ctx,
      conn,
    );
    return rows.map(serializeRow);
  };

  const out: MastersSnapshot = {
    id_proofs: [],
    gst: [],
    rates: [],
    shifts: [],
    shift_assignments: [],
    header_footer: [],
    users: [],
  };
  // A super admin has no tenant: without this guard the unscoped reads would return every tenant.
  const tables =
    ctx.customerId !== null && ctx.userType !== 'S'
      ? [...GLOBAL_MASTERS, ...TENANT_MASTERS]
      : GLOBAL_MASTERS;
  // Sequential on purpose: the load test showed parallel reads (several pool connections per
  // request) lowering throughput under saturation (docs/load-test-results.md).
  for (const spec of tables) out[spec[0]] = await load(spec);
  return out;
}

/** Never sent to devices. */
const SECRET_SETTINGS = new Set(['REPORT_PWD']);

/**
 * `{ NAME: value }` for the signed-in user: defaults of every whitelisted key, overlaid with the
 * customer-wide rows, then the user's own overrides (only for per-user keys). Keys without a
 * default (GST_PCT = "use md_gst", REPORT_PWD) stay absent until set; rows of keys no longer
 * whitelisted are ignored.
 */
export async function loadSettings(
  ctx: RequestContext,
  conn?: Executor,
): Promise<Record<string, string>> {
  if (ctx.customerId === null || ctx.userType === 'S') return {};
  const columns = ['setting_name', 'setting_value'];
  const [base, own] = await Promise.all([
    fetch('md_setting', { where: { user_id: null }, columns }, ctx, conn),
    fetch('md_setting', { where: { user_id: ctx.userId }, columns }, ctx, conn),
  ]);
  const settings: Record<string, string> = {};
  for (const name of SETTING_NAMES) {
    const d = settingDefault(name);
    if (d !== null) settings[name] = d;
  }
  const apply = (rows: Record<string, unknown>[], perUserOnly: boolean) => {
    for (const r of rows) {
      const name = String(r.setting_name);
      if (!isSettingName(name) || SECRET_SETTINGS.has(name)) continue;
      if (perUserOnly && SETTING_RULES[name].scope !== 'user') continue;
      settings[name] = String(r.setting_value);
    }
  };
  apply(base.rows, false);
  apply(own.rows, true);
  return settings;
}

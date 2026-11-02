/**
 * Master data a device needs offline. Used by login (full snapshot) and by pull (Phase 6, with
 * `since`: changed rows only, soft-deleted included so devices can hide them).
 */
import type { RequestContext } from '../../core/context';
import { serializeRow } from '../../core/crud/crudFactory';
import { fetch, type Executor, type OrderBy, type Row, type Where } from '../../core/db/dml';

export interface MastersSnapshot {
  id_proofs: Row[];
  gst: Row[];
  rates: Row[];
  shifts: Row[];
  shift_assignments: Row[];
  header_footer: Row[];
}

const GLOBAL_MASTERS = [
  ['id_proofs', 'md_id_proof', [{ column: 'id_proof_id' }]],
  ['gst', 'md_gst', [{ column: 'effective_from' }]],
] as const;

const TENANT_MASTERS = [
  ['rates', 'md_rate_dtls', [{ column: 'effective_from' }]],
  ['shifts', 'md_shift', [{ column: 'shift_id' }]],
  ['shift_assignments', 'td_assign_shift', [{ column: 'user_id' }, { column: 'effective_from' }]],
  ['header_footer', 'md_header_footer', [{ column: 'line_type' }, { column: 'line_order' }]],
] as const;

export async function loadMasters(
  ctx: RequestContext,
  opts: { since?: string } = {},
  conn?: Executor,
): Promise<MastersSnapshot> {
  const where: Where = opts.since ? { updated_at: { op: '>=', value: opts.since } } : {};
  const load = async (table: string, orderBy: readonly OrderBy[]) => {
    const { rows } = await fetch(
      table,
      { where, orderBy, includeDeleted: Boolean(opts.since) },
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
  };
  // A super admin has no tenant: without this guard the unscoped reads would return every tenant.
  const tables =
    ctx.customerId !== null && ctx.userType !== 'S'
      ? [...GLOBAL_MASTERS, ...TENANT_MASTERS]
      : GLOBAL_MASTERS;
  // Sequential on purpose: the load test showed parallel reads (several pool connections per
  // request) lowering throughput under saturation (docs/load-test-results.md).
  for (const [key, table, order] of tables) out[key] = await load(table, order);
  return out;
}

/** Never sent to devices. */
const SECRET_SETTINGS = new Set(['REPORT_PWD']);

/** Customer-wide settings overlaid with the user's own overrides → `{ NAME: value }`. */
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
  for (const r of [...base.rows, ...own.rows]) {
    const name = String(r.setting_name);
    if (!SECRET_SETTINGS.has(name)) settings[name] = String(r.setting_value);
  }
  return settings;
}

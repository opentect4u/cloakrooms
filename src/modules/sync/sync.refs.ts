/**
 * Everything a push needs to validate and recompute rows, loaded once per request: the tenant's
 * users/shifts (deleted ones too — rows are historical), ID proofs, rates, GST and settings.
 */
import type { RequestContext } from '../../core/context';
import { fetch, type Row } from '../../core/db/dml';
import type { GstRates, GstType } from '../../domain/gst';
import { isResetMode, type ResetMode } from '../../domain/numbering';
import type { RateSlab } from '../../domain/slabs';

export interface RateRow {
  effective_from: string;
  block_hours: number;
  first_block_rate: string;
  next_block_rate: string;
  /** NULL only for rows written before migration 008 (then the two-rate fields apply). */
  rate_slabs?: RateSlab[] | null;
}

export interface GstRow extends GstRates {
  effective_from: string;
}

export interface PushRefs {
  users: Map<number, { user_type: string; user_code: string | null }>;
  shifts: Set<number>;
  idProofs: Map<number, { mask: boolean }>;
  rates: RateRow[];
  gst: GstRow[];
  /** md_customer.gst_no now — stamped on every bill stored by this push. */
  customerGstNo: string | null;
  setting(userId: number, name: string): string | undefined;
  /** Customer-wide value only (keys with scope 'customer'). */
  customerSetting(name: string): string | undefined;
  resetModeFor(userId: number): ResetMode;
  gstTypeFor(userId: number): GstType;
}

export async function loadPushRefs(ctx: RequestContext): Promise<PushRefs> {
  const all = { includeDeleted: true };
  const [users, shifts, idProofs, rates, gst, settings, customer] = await Promise.all([
    fetch('md_user', { ...all, columns: ['id', 'user_type', 'user_code'] }, ctx),
    fetch('md_shift', { ...all, columns: ['shift_id'] }, ctx),
    fetch('md_id_proof', { ...all, columns: ['id_proof_id', 'mask_flag'] }, ctx),
    fetch(
      'md_rate_dtls',
      {
        columns: [
          'effective_from',
          'block_hours',
          'first_block_rate',
          'next_block_rate',
          'rate_slabs',
        ],
      },
      ctx,
    ),
    fetch('md_gst', { columns: ['effective_from', 'cgst_pct', 'sgst_pct', 'igst_pct'] }, ctx),
    fetch('md_setting', { columns: ['user_id', 'setting_name', 'setting_value'] }, ctx),
    fetch('md_customer', { columns: ['gst_no'], includeDeleted: true }, ctx),
  ]);

  const customerWide = new Map<string, string>();
  const perUser = new Map<string, string>();
  for (const s of settings.rows) {
    const name = String(s.setting_name);
    if (s.user_id === null) customerWide.set(name, String(s.setting_value));
    else perUser.set(`${s.user_id}:${name}`, String(s.setting_value));
  }
  const setting = (userId: number, name: string) =>
    perUser.get(`${userId}:${name}`) ?? customerWide.get(name);

  return {
    users: new Map(
      users.rows.map((u: Row) => [
        Number(u.id),
        { user_type: String(u.user_type), user_code: (u.user_code as string | null) ?? null },
      ]),
    ),
    shifts: new Set(shifts.rows.map((s) => Number(s.shift_id))),
    idProofs: new Map(
      idProofs.rows.map((p) => [Number(p.id_proof_id), { mask: p.mask_flag === 'Y' }]),
    ),
    rates: rates.rows as unknown as RateRow[],
    gst: gst.rows as unknown as GstRow[],
    customerGstNo: (customer.rows[0]?.gst_no as string | null | undefined) || null,
    setting,
    customerSetting: (name) => customerWide.get(name),
    resetModeFor: (userId) => {
      const v = setting(userId, 'RECEIPT_RESET');
      return isResetMode(v) ? v : 'Y';
    },
    gstTypeFor: (userId) => (setting(userId, 'GST_TYPE') === 'I' ? 'I' : 'E'),
  };
}

/** Reason for a bad reference, or null when every referenced row belongs to the tenant. */
export function checkRefs(
  refs: PushRefs,
  checks: { users?: (number | null)[]; shifts?: (number | null)[]; idProof?: number },
): string | null {
  for (const id of checks.users ?? []) {
    const u = id === null ? undefined : refs.users.get(id);
    if (id !== null && (!u || u.user_type === 'S'))
      return `user ${id} is not a user of this customer`;
  }
  for (const id of checks.shifts ?? []) {
    if (id !== null && !refs.shifts.has(id)) return `shift ${id} does not exist for this customer`;
  }
  if (checks.idProof !== undefined && !refs.idProofs.has(checks.idProof)) {
    return `id proof ${checks.idProof} does not exist`;
  }
  return null;
}

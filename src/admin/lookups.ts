import type { RequestContext } from '../core/context';
import { fetch } from '../core/db/dml';
import type { Lookup, Lookups } from './fields';

/**
 * Id → label maps of one customer for selects and table cells. Users and shifts are read with the
 * tenant context; registrations (cloud-only, no tenant scoping) are filtered by customer id.
 */
export async function loadLookups(
  wanted: readonly Lookup[] | undefined,
  tctx: RequestContext,
  sctx: RequestContext,
  customerId: number,
): Promise<Lookups> {
  const out: Lookups = {};
  for (const key of wanted ?? []) {
    if (key === 'users') {
      const { rows } = await fetch(
        'md_user',
        {
          where: { user_type: { in: ['A', 'O'] } },
          columns: ['id', 'user_name', 'user_code'],
          orderBy: [{ column: 'user_name' }],
        },
        tctx,
      );
      out.users = new Map(
        rows.map((r) => [Number(r.id), `${String(r.user_name)} (${String(r.user_code)})`]),
      );
    } else if (key === 'shifts') {
      const { rows } = await fetch(
        'md_shift',
        {
          columns: ['shift_id', 'shift_name', 'from_time', 'to_time'],
          orderBy: [{ column: 'from_time' }],
        },
        tctx,
      );
      out.shifts = new Map(
        rows.map((r) => [
          Number(r.shift_id),
          `${String(r.shift_name)} · ${String(r.from_time).slice(0, 5)}–${String(r.to_time).slice(0, 5)}`,
        ]),
      );
    } else {
      const { rows } = await fetch(
        'md_customer_registration',
        {
          where: { customer_id: customerId },
          columns: ['reg_id', 'reg_no', 'end_date'],
          orderBy: [{ column: 'end_date', dir: 'desc' }],
        },
        sctx,
      );
      out.registrations = new Map(
        rows.map((r) => [Number(r.reg_id), `${String(r.reg_no)} (to ${String(r.end_date)})`]),
      );
    }
  }
  return out;
}

export function optionsOf(map: Map<number, string> | undefined): [string, string][] {
  return [...(map ?? new Map<number, string>()).entries()].map(([id, label]) => [
    String(id),
    label,
  ]);
}

/** Every live customer as [id, name] for pickers (super admin context). */
export async function customerOptions(sctx: RequestContext): Promise<[string, string][]> {
  const { rows } = await fetch(
    'md_customer',
    { columns: ['customer_id', 'customer_name'], orderBy: [{ column: 'customer_name' }] },
    sctx,
  );
  return rows.map((r) => [String(r.customer_id), String(r.customer_name)]);
}

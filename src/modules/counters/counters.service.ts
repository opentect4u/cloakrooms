import type { RequestContext } from '../../core/context';
import { fetch, fetchOne, raw, type Executor } from '../../core/db/dml';
import { ApiError } from '../../core/http/ApiError';
import { isResetMode, periodKey, type ResetMode, type Series } from '../../domain/numbering';

export interface CurrentCounters {
  reset: ResetMode;
  period_key: string;
  /** Last receipt (check-in) number used in this period; 0 = none yet. */
  receipt_last_no: number;
  /** Last bill (check-out) number used in this period; 0 = none yet. */
  bill_last_no: number;
}

export function resetModeOf(setting: string | undefined): ResetMode {
  return isResetMode(setting) ? setting : 'Y';
}

/** Current-period counters of the acting user (login payload, CLAUDE.md §4). */
export async function getCurrentCounters(
  ctx: RequestContext,
  resetSetting: string | undefined,
  conn?: Executor,
  at: Date = new Date(),
): Promise<CurrentCounters> {
  const reset = resetModeOf(resetSetting);
  const key = periodKey(reset, at);
  const { rows } = await fetch(
    'md_user_counter',
    { where: { user_id: ctx.userId, period_key: key }, columns: ['series', 'last_no'] },
    ctx,
    conn,
  );
  const last = (series: string) => Number(rows.find((r) => r.series === series)?.last_no ?? 0);
  return { reset, period_key: key, receipt_last_no: last('R'), bill_last_no: last('B') };
}

export interface CounterBump {
  /** md_user.id owning the series (the series moves with the user across devices). */
  userId: number;
  series: Series;
  periodKey: string;
  no: number;
}

/**
 * Raises a user's counter to at least `no` (never lowers it), creating the row on first use.
 * Safe under concurrent pushes and re-pushes: `last_no = GREATEST(stored, pushed)` in one statement.
 * updated_* only move when the number actually rises, so an idempotent re-push changes nothing.
 */
export async function bumpCounter(
  ctx: RequestContext,
  bump: CounterBump,
  conn?: Executor,
): Promise<void> {
  if (ctx.customerId === null) throw ApiError.forbidden('Counters belong to a customer');
  if (!Number.isInteger(bump.no) || bump.no < 1)
    throw ApiError.badRequest(`Invalid number ${bump.no}`);
  // raw() bypasses tenant scoping: make sure the series owner is a user of the caller's tenant
  // (deleted users still own the numbers they issued).
  const owner = await fetchOne('md_user', { id: bump.userId }, ctx, conn, {
    columns: ['id'],
    includeDeleted: true,
  });
  if (!owner) throw ApiError.badRequest(`User ${bump.userId} is not a user of this customer`);
  await raw(
    `INSERT INTO md_user_counter
       (customer_id, user_id, series, period_key, last_no, sync_flag,
        created_by, created_at, updated_by, updated_at)
     VALUES (?, ?, ?, ?, ?, 'N', ?, NOW(), ?, NOW()) AS incoming
     ON DUPLICATE KEY UPDATE
       updated_by = IF(incoming.last_no > md_user_counter.last_no, incoming.updated_by, md_user_counter.updated_by),
       updated_at = IF(incoming.last_no > md_user_counter.last_no, NOW(), md_user_counter.updated_at),
       sync_flag  = IF(incoming.last_no > md_user_counter.last_no, 'N', md_user_counter.sync_flag),
       last_no    = GREATEST(md_user_counter.last_no, incoming.last_no)`,
    [ctx.customerId, bump.userId, bump.series, bump.periodKey, bump.no, ctx.loginId, ctx.loginId],
    conn,
  );
}

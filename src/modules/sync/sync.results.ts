import type { RequestContext } from '../../core/context';
import { save, type Executor } from '../../core/db/dml';

export type SyncTable = 'td_luggage_in_out' | 'td_receipt';

/** Why a row was refused or flagged (td_sync_exception.reason prefix). */
export type ExceptionCode =
  | 'VALIDATION_ERROR'
  | 'INVALID_REFERENCE'
  | 'TENANT_MISMATCH'
  | 'DUPLICATE_RECEIPT_NO'
  | 'DUPLICATE_CHECKOUT'
  | 'BILL_WITHOUT_LUGGAGE'
  | 'DUPLICATE_BILL'
  | 'DUPLICATE_BILL_NO'
  | 'CHECKOUT_MISSING'
  | 'NO_RATE'
  | 'AMOUNT_MISMATCH'
  | 'DB_ERROR';

export interface RowResult {
  table: SyncTable;
  trans_id: string;
  local_version: unknown;
  status: 'ACCEPTED' | 'EXCEPTION';
  reason?: string;
}

export const accepted = (table: SyncTable, raw: Record<string, unknown>): RowResult => ({
  table,
  trans_id: String(raw.trans_id ?? ''),
  local_version: raw.local_version ?? null,
  status: 'ACCEPTED',
});

/**
 * Logs the row to td_sync_exception (admin-owned from now on) and returns the EXCEPTION result.
 * Both statuses tell the device to mark the row synced (CLAUDE.md §5).
 */
export async function exception(
  table: SyncTable,
  raw: Record<string, unknown>,
  code: ExceptionCode,
  detail: string,
  ctx: RequestContext,
  conn: Executor,
  extra?: Record<string, unknown>,
): Promise<RowResult> {
  const reason = `${code}: ${detail}`.slice(0, 255);
  await save(
    'td_sync_exception',
    {
      trans_id: String(raw.trans_id ?? '').slice(0, 36),
      table_name: table,
      payload: JSON.stringify(extra ? { row: raw, ...extra } : raw),
      reason,
      user_id: ctx.userId,
      device_id: (ctx.deviceId ?? 'unknown').slice(0, 100),
    },
    { flag: 'I' },
    ctx,
    conn,
  );
  return { ...accepted(table, raw), status: 'EXCEPTION', reason };
}

/** Lock/deadlock errors abort the transaction and must reach withTransaction's retry. */
export function isFatalDbError(err: unknown): boolean {
  const code = (err as { code?: string }).code ?? '';
  return (
    !code.startsWith('ER_') ||
    code === 'ER_LOCK_DEADLOCK' ||
    code === 'ER_LOCK_WAIT_TIMEOUT' ||
    code === 'ER_QUERY_INTERRUPTED'
  );
}

export function dupKeyName(err: unknown): string | null {
  const e = err as { code?: string; sqlMessage?: string };
  if (e.code !== 'ER_DUP_ENTRY') return null;
  return /for key '(?:[^.']+\.)?([^']+)'/.exec(e.sqlMessage ?? '')?.[1] ?? '';
}

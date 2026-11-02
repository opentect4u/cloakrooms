import type { RowDataPacket } from 'mysql2/promise';
import type { RequestContext } from '../../core/context';
import { raw, withTransaction } from '../../core/db/dml';
import { serializeRow } from '../../core/crud/crudFactory';
import { ApiError } from '../../core/http/ApiError';
import { toUtcString } from '../../core/utils/time';
import { getCurrentCounters } from '../counters/counters.service';
import { loadMasters, loadSettings } from '../masters/masters.service';
import { CounterCollector } from './counters.collector';
import { mergeLuggageChunk } from './luggage.merge';
import { mergeReceiptChunk } from './receipt.merge';
import { loadPushRefs } from './sync.refs';
import {
  CHUNK_SIZE,
  pullCursor,
  type PullCursor,
  type PullQuery,
  type PushBody,
} from './sync.schema';
import type { RowResult } from './sync.results';

type Merge = typeof mergeLuggageChunk;

/** Re-read window behind the client's cursor (client dedupes by primary key). */
export const PULL_OVERLAP_SEC = 120;

function chunks<T>(rows: T[], size: number): T[][] {
  const out: T[][] = [];
  for (let i = 0; i < rows.length; i += size) out.push(rows.slice(i, i + size));
  return out;
}

function tenantCtx(ctx: RequestContext): RequestContext & { customerId: number } {
  if (ctx.customerId === null || ctx.userType === 'S') {
    throw ApiError.forbidden('Sync is for customer users only');
  }
  return ctx as RequestContext & { customerId: number };
}

/**
 * POST /sync/push — luggage first, then receipts; each chunk of ≤ 500 rows is one transaction
 * (rows + exceptions + counter bumps commit together). Committed chunks stay committed if a
 * later chunk fails; re-pushing everything is always safe (idempotent).
 */
export async function push(authCtx: RequestContext, body: PushBody) {
  const ctx = tenantCtx(authCtx);
  const refs = await loadPushRefs(ctx);
  const results: RowResult[] = [];

  const run = async (rows: Record<string, unknown>[], merge: Merge) => {
    for (const chunk of chunks(rows, CHUNK_SIZE)) {
      results.push(
        ...(await withTransaction(async (conn) => {
          // Fresh collector per attempt: withTransaction may retry the whole callback.
          const counters = new CounterCollector();
          const chunkResults = await merge(chunk, refs, counters, ctx, conn);
          await counters.flush(ctx, conn);
          return chunkResults;
        })),
      );
    }
  };
  await run(body.luggage, mergeLuggageChunk);
  await run(body.receipts, mergeReceiptChunk);

  return {
    results,
    counters: await getCurrentCounters(ctx, refs.setting(ctx.userId, 'RECEIPT_RESET')),
    server_time: toUtcString(),
  };
}

export function encodeCursor(c: PullCursor): string {
  return Buffer.from(JSON.stringify(c)).toString('base64url');
}

export function decodeCursor(value: string): PullCursor {
  try {
    return pullCursor.parse(JSON.parse(Buffer.from(value, 'base64url').toString('utf8')));
  } catch {
    throw ApiError.validation([{ path: 'cursor', message: 'invalid cursor' }]);
  }
}

/** One page of luggage rows, keyset-ordered by (updated_at, trans_id). Always tenant-bound. */
async function luggagePage(
  customerId: number,
  mode: PullCursor['m'],
  start: { from: string } | { after: Pick<PullCursor, 'u' | 't'> },
  limit: number,
) {
  const where = ['customer_id = ?'];
  const params: unknown[] = [customerId];
  // First pull: open check-ins only. Later pulls: every change, soft-deleted rows included.
  if (mode === 'open') where.push("out_flag = 'N'", "is_deleted = 'N'");
  if ('from' in start) {
    where.push('updated_at >= ?');
    params.push(start.from);
  } else {
    where.push('(updated_at > ? OR (updated_at = ? AND trans_id > ?))');
    params.push(start.after.u, start.after.u, start.after.t);
  }
  const rows = await raw<RowDataPacket[]>(
    `SELECT * FROM td_luggage_in_out WHERE ${where.join(' AND ')}
      ORDER BY updated_at, trans_id LIMIT ?`,
    [...params, limit + 1],
  );
  const hasMore = rows.length > limit;
  return { rows: hasMore ? rows.slice(0, limit) : rows, hasMore };
}

/**
 * GET /sync/pull (CLAUDE.md §5).
 *  - First pull (no `since`): all live masters + open check-ins.
 *  - Later pulls (`since` = previous server_time): changed rows, soft-deleted included, read with a
 *    120 s overlap behind `since` (late commits; the client dedupes by PK).
 *  - At most `limit` luggage rows per response: while `has_more`, call again with `cursor` only.
 *    Continuation pages carry luggage only and repeat the first page's `server_time`, which is
 *    the `since` to store once `has_more` is false (taken from the DB clock before reading).
 */
export async function pull(authCtx: RequestContext, q: PullQuery) {
  const ctx = tenantCtx(authCtx);

  if (q.cursor) {
    const c = decodeCursor(q.cursor);
    const page = await luggagePage(ctx.customerId, c.m, { after: c }, q.limit);
    return pageResponse(page, c.m, c.s, { masters: null, settings: null, counters: null });
  }

  const [clock] = await raw<RowDataPacket[]>(
    'SELECT UTC_TIMESTAMP() AS now, CAST(? AS DATETIME) - INTERVAL ? SECOND AS from_ts',
    [q.since ?? '1970-01-01 00:00:00', PULL_OVERLAP_SEC],
  );
  const serverTime = String(clock?.now);
  const from = q.since ? String(clock?.from_ts) : undefined;
  const mode: PullCursor['m'] = from ? 'changes' : 'open';

  const page = await luggagePage(
    ctx.customerId,
    mode,
    { from: from ?? '1970-01-01 00:00:00' },
    q.limit,
  );
  const settings = await loadSettings(ctx);
  return pageResponse(page, mode, serverTime, {
    masters: await loadMasters(ctx, { since: from }),
    settings,
    counters: await getCurrentCounters(ctx, settings.RECEIPT_RESET),
  });
}

function pageResponse(
  page: { rows: RowDataPacket[]; hasMore: boolean },
  mode: PullCursor['m'],
  serverTime: string,
  extra: Record<string, unknown>,
) {
  const last = page.rows[page.rows.length - 1];
  return {
    ...extra,
    luggage: page.rows.map((r) => serializeRow(r)),
    has_more: page.hasMore,
    next_cursor:
      page.hasMore && last
        ? encodeCursor({
            m: mode,
            s: serverTime,
            u: String(last.updated_at),
            t: String(last.trans_id),
          })
        : null,
    server_time: serverTime,
  };
}

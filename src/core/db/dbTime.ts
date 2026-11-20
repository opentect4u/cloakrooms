/**
 * Time zone of the database (owner's decision 2026-11-18, migration 009): every DATETIME is
 * stored in IST (UTC+05:30, no DST) so people reading the tables see Indian time. The rest of the
 * code and the API keep working in UTC; the conversion happens only at the database boundary:
 *
 *  - writes / filters: every bound parameter that is a full `YYYY-MM-DD HH:mm:ss` string is a UTC
 *    instant and is shifted to IST (`toDbParams`, used by every query in core/db/dml.ts);
 *  - reads: every DATETIME / TIMESTAMP result column is shifted back to UTC (`dbToUtc`, pool
 *    typeCast) — except columns whose alias ends in `_ist`, which reports select as-is;
 *  - SQL "now" is `NOW()` (sessions run at time_zone '+05:30'); never use UTC_TIMESTAMP().
 * DATE columns (receipt_dt, bill_dt, effective_from, …) are IST calendar dates and never shift.
 */
export const DB_OFFSET_MINUTES = 330;
export const DB_SESSION_TIME_ZONE = '+05:30';
/** Alias suffix of result columns that must come back exactly as stored (IST). */
export const RAW_IST_SUFFIX = '_ist';

const DATETIME = /^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})$/;

function shift(value: string, minutes: number): string {
  const m = DATETIME.exec(value);
  if (!m) return value;
  const [, y, mo, d, h, mi, s] = m.map(Number) as number[];
  const ms = Date.UTC(y!, mo! - 1, d!, h!, mi!, s!);
  // Not a real calendar time (e.g. 2026-02-30): leave it for validation to reject.
  if (new Date(ms).toISOString().slice(0, 19).replace('T', ' ') !== value) return value;
  return new Date(ms + minutes * 60_000).toISOString().slice(0, 19).replace('T', ' ');
}

export const isDateTimeString = (v: unknown): v is string =>
  typeof v === 'string' && DATETIME.test(v);

/** UTC `YYYY-MM-DD HH:mm:ss` → as stored (IST). Anything else is returned unchanged. */
export function utcToDb(value: string): string {
  return shift(value, DB_OFFSET_MINUTES);
}

/** Stored (IST) `YYYY-MM-DD HH:mm:ss` → UTC. Anything else is returned unchanged. */
export function dbToUtc(value: string): string {
  return shift(value, -DB_OFFSET_MINUTES);
}

/** Query parameters with every UTC datetime string shifted to IST (arrays/objects one level). */
export function toDbParams<T>(params: T): T {
  const conv = (v: unknown): unknown => {
    if (isDateTimeString(v)) return utcToDb(v);
    if (Array.isArray(v)) return v.map(conv);
    return v;
  };
  if (Array.isArray(params)) return params.map(conv) as T;
  if (params && typeof params === 'object' && !Buffer.isBuffer(params)) {
    return Object.fromEntries(
      Object.entries(params as Record<string, unknown>).map(([k, v]) => [k, conv(v)]),
    ) as T;
  }
  return params;
}

interface CastField {
  type: string;
  name: string;
  string(): string | null;
}

/** mysql2 typeCast: DATETIME/TIMESTAMP results back to UTC strings (see file comment). */
export function castDbTime(field: CastField, next: () => unknown): unknown {
  if (field.type === 'DATETIME' || field.type === 'TIMESTAMP') {
    const v = field.string();
    if (v === null) return null;
    return field.name.endsWith(RAW_IST_SUFFIX) ? v : dbToUtc(v);
  }
  return next();
}

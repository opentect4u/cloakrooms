/** IST is UTC+05:30 with no DST. */
const IST_OFFSET_MS = 330 * 60_000;
const DAY_MS = 86_400_000;

/** Formats a Date as `YYYY-MM-DD HH:mm:ss` in UTC (API wire format, CLAUDE.md §8). */
export function toUtcString(date: Date = new Date()): string {
  return date.toISOString().slice(0, 19).replace('T', ' ');
}

/** Calendar date in India for an instant, `YYYY-MM-DD`. */
export function toIstDate(date: Date = new Date()): string {
  return new Date(date.getTime() + IST_OFFSET_MS).toISOString().slice(0, 10);
}

/** UTC instant at which an IST calendar day starts: `2026-05-02` → `2026-05-01 18:30:00`. */
export function istDayStartUtc(istDate: string): string {
  return toUtcString(new Date(Date.parse(`${istDate}T00:00:00Z`) - IST_OFFSET_MS));
}

/** `YYYY-MM-DD` ± n days. */
export function addDays(date: string, days: number): string {
  return new Date(Date.parse(`${date}T00:00:00Z`) + days * DAY_MS).toISOString().slice(0, 10);
}

/** Whole days from `from` to `to` (both `YYYY-MM-DD`). */
export function daysBetween(from: string, to: string): number {
  return Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / DAY_MS);
}

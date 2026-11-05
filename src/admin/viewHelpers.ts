/** Formatting helpers exposed to every EJS view (`app.locals`). Pure functions. */

const INR = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});
const INR_WHOLE = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  maximumFractionDigits: 0,
});
const NUM = new Intl.NumberFormat('en-IN');
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** `"1234.5"` → `₹1,234.50` (Indian grouping). Accepts DECIMAL strings. */
export function inr(value: string | number | null | undefined, whole = false): string {
  const n = Number(value ?? 0);
  return (whole ? INR_WHOLE : INR).format(Number.isFinite(n) ? n : 0);
}

/** Compact rupees for axes and tiles: ₹950, ₹12.5K, ₹3.4L, ₹1.2Cr (Indian units). */
export function inrCompact(value: string | number | null | undefined): string {
  const n = Number(value ?? 0);
  const abs = Math.abs(n);
  const fmt = (v: number, unit: string) => `₹${trimZero(v.toFixed(v < 10 ? 1 : 0))}${unit}`;
  if (abs >= 1e7) return fmt(n / 1e7, 'Cr');
  if (abs >= 1e5) return fmt(n / 1e5, 'L');
  if (abs >= 1e3) return fmt(n / 1e3, 'K');
  return `₹${NUM.format(Math.round(n))}`;
}

const trimZero = (s: string) => s.replace(/\.0$/, '');

export function num(value: string | number | null | undefined): string {
  return NUM.format(Number(value ?? 0));
}

/** `2026-11-05` → `05 Nov 2026`; empty for null. */
export function date(value: string | null | undefined): string {
  if (!value) return '';
  const [y, m, d] = String(value).slice(0, 10).split('-');
  return `${d} ${MONTHS[Number(m) - 1] ?? m} ${y}`;
}

/** UTC `YYYY-MM-DD HH:mm:ss` → `05 Nov 2026, 14:05` in IST. */
export function dateTimeIst(value: string | null | undefined): string {
  if (!value) return '';
  const t = Date.parse(`${String(value).replace(' ', 'T')}Z`);
  if (Number.isNaN(t)) return String(value);
  const ist = new Date(t + 330 * 60_000).toISOString();
  return `${date(ist.slice(0, 10))}, ${ist.slice(11, 16)}`;
}

/** Initials for avatars: "Howrah Cloak Room" → "HC". */
export function initials(name: string | null | undefined): string {
  const parts = String(name ?? '')
    .trim()
    .split(/\s+/)
    .filter(Boolean);
  return ((parts[0]?.[0] ?? '') + (parts[1]?.[0] ?? '')).toUpperCase() || '?';
}

/** Singular/plural: plural(1, 'bag') → "1 bag". */
export function plural(n: number, word: string, many = `${word}s`): string {
  return `${num(n)} ${n === 1 ? word : many}`;
}

/** "Good morning" / "Good afternoon" / "Good evening" by the time in India. */
export function greeting(now: Date = new Date()): string {
  const hour = new Date(now.getTime() + 330 * 60_000).getUTCHours();
  if (hour < 5) return 'Good evening';
  if (hour < 12) return 'Good morning';
  if (hour < 17) return 'Good afternoon';
  return 'Good evening';
}

/** "Wednesday, 05 November 2026" for today in India. */
export function longToday(now: Date = new Date()): string {
  const ist = new Date(now.getTime() + 330 * 60_000);
  const day = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
  const month = [
    'January',
    'February',
    'March',
    'April',
    'May',
    'June',
    'July',
    'August',
    'September',
    'October',
    'November',
    'December',
  ];
  return `${day[ist.getUTCDay()]}, ${String(ist.getUTCDate()).padStart(2, '0')} ${month[ist.getUTCMonth()]} ${ist.getUTCFullYear()}`;
}

export const viewHelpers = {
  inr,
  inrCompact,
  num,
  date,
  dateTimeIst,
  initials,
  plural,
  greeting,
  longToday,
};

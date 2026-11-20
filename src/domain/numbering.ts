/**
 * Receipt/bill number series (CLAUDE.md §4). Pure functions, no I/O.
 * Format `<user_code>-<5-digit no>` (e.g. U03-00125); series R = receipt, B = bill.
 * Periods are computed in IST.
 */
import { toIstDate } from '../core/utils/time';

export type ResetMode = 'D' | 'M' | 'Y' | 'N';

/** Period key of RECEIPT_RESET = N: one series for ever. */
export const NEVER_RESET_PERIOD = 'ALL';
export type Series = 'R' | 'B';

const IST_DATE = /^\d{4}-(0[1-9]|1[0-2])-\d{2}$/;
const DOC_NO = /^([A-Z0-9]{1,10})-(\d{5,9})$/;

/**
 * Period of an IST calendar date: D → `YYYY-MM-DD`, M → `YYYY-MM`,
 * Y → Indian financial year `FY2026-27` (April–March), N (never restart) → `ALL`.
 * TODO(open-point): FY numbering format to be confirmed with the CA.
 */
export function periodKeyOfDate(mode: ResetMode, istDate: string): string {
  if (!IST_DATE.test(istDate)) throw new RangeError(`Invalid date: ${istDate}`);
  if (mode === 'N') return NEVER_RESET_PERIOD;
  if (mode === 'D') return istDate;
  if (mode === 'M') return istDate.slice(0, 7);
  const year = Number(istDate.slice(0, 4));
  const start = Number(istDate.slice(5, 7)) >= 4 ? year : year - 1;
  return `FY${start}-${String((start + 1) % 100).padStart(2, '0')}`;
}

/** Period of an instant (uses its IST date). */
export function periodKey(mode: ResetMode, at: Date = new Date()): string {
  return periodKeyOfDate(mode, toIstDate(at));
}

export function isResetMode(v: unknown): v is ResetMode {
  return v === 'D' || v === 'M' || v === 'Y' || v === 'N';
}

/** `U03`, 125 → `U03-00125` (more digits once past 99999). */
export function formatDocNo(userCode: string, no: number): string {
  if (!Number.isInteger(no) || no < 1 || no > 999_999_999)
    throw new RangeError(`Invalid number: ${no}`);
  return `${userCode}-${String(no).padStart(5, '0')}`;
}

/** `U03-00125` → { userCode: 'U03', no: 125 }; null if it is not a series number. */
export function parseDocNo(docNo: string): { userCode: string; no: number } | null {
  const m = DOC_NO.exec(docNo);
  if (!m) return null;
  const no = Number(m[2]);
  return no >= 1 ? { userCode: m[1]!, no } : null;
}

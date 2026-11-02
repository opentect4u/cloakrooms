/**
 * Money as integer paise (CLAUDE.md §3: never raw float math). Inputs are decimal strings as
 * returned by MySQL DECIMAL columns or the `money` zod fragment.
 */

const AMOUNT = /^(\d+)(?:\.(\d{1,2}))?$/;

export function toPaise(amount: string | number): number {
  const m = AMOUNT.exec(String(amount).trim());
  if (!m) throw new Error(`Invalid amount: ${amount}`);
  return Number(m[1]) * 100 + Number((m[2] ?? '').padEnd(2, '0'));
}

export function fromPaise(paise: number): string {
  if (!Number.isSafeInteger(paise)) throw new Error(`Invalid paise: ${paise}`);
  const sign = paise < 0 ? '-' : '';
  const abs = Math.abs(paise);
  return `${sign}${Math.trunc(abs / 100)}.${String(abs % 100).padStart(2, '0')}`;
}

/**
 * Login-shift window (Phase 11, BACKEND_API_REQUESTS B1). Pure; times of day in IST minutes.
 * A counter user may sign in from `early` minutes before the shift starts until it ends; shifts
 * may cross midnight (22:00 → 06:00), so the window is checked on the 24-hour circle.
 */

const DAY = 1440;
const TIME = /^([01]\d|2[0-3]):([0-5]\d)(?::([0-5]\d))?$/;

/** `HH:mm` or `HH:mm:ss` → minutes since midnight. */
export function minutesOfDay(time: string): number {
  const m = TIME.exec(time);
  if (!m) throw new RangeError(`Invalid time: ${time}`);
  return Number(m[1]) * 60 + Number(m[2]);
}

/** Minutes since midnight in India for an instant. */
export function istMinutesOfDay(at: Date): number {
  return Math.floor((((at.getTime() / 60_000 + 330) % DAY) + DAY) % DAY);
}

/** When sign-in opens: shift start minus `early`, as minutes of day. */
export function opensAt(from: number, early: number): number {
  return (((from - early) % DAY) + DAY) % DAY;
}

/** Is `now` inside [from − early, to)? `from === to` never happens (shift validation). */
export function withinShift(now: number, from: number, to: number, early: number): boolean {
  if (!Number.isInteger(early) || early < 0 || early >= DAY) {
    throw new RangeError(`Invalid early minutes: ${early}`);
  }
  const start = opensAt(from, early);
  return start <= to ? now >= start && now < to : now >= start || now < to;
}

/** Minutes of day → `HH:mm`. */
export function hhmm(minutes: number): string {
  return `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`;
}

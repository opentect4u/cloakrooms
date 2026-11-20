/**
 * Server-side login-shift check (Phase 11, BACKEND_API_REQUESTS B1). Counter users (O) who have a
 * shift in the roster may sign in from SHIFT_EARLY_LOGIN_MIN before it starts until it ends,
 * while the customer's SHIFT_LOGIN_FLAG is Y (default). Admins, users without an assignment and
 * the panel are not limited. The assignment in force = latest td_assign_shift.effective_from ≤
 * today (IST).
 */
import type { RowDataPacket } from 'mysql2/promise';
import { raw } from '../../core/db/dml';
import { ApiError } from '../../core/http/ApiError';
import { toIstDate } from '../../core/utils/time';
import { hhmm, istMinutesOfDay, minutesOfDay, opensAt, withinShift } from '../../domain/shift';
import { customerSettingValue as customerSetting } from '../settings/settings.read';

export async function assertWithinShift(
  user: { id: number; user_type: string },
  customerId: number,
  now: Date = new Date(),
): Promise<void> {
  if (user.user_type !== 'O') return;
  if ((await customerSetting(customerId, 'SHIFT_LOGIN_FLAG')) !== 'Y') return;

  const [shift] = await raw<RowDataPacket[]>(
    `SELECT s.shift_name, s.from_time, s.to_time
       FROM td_assign_shift a
       JOIN md_shift s ON s.shift_id = a.shift_id AND s.is_deleted = 'N'
      WHERE a.customer_id = ? AND a.user_id = ? AND a.is_deleted = 'N' AND a.effective_from <= ?
      ORDER BY a.effective_from DESC, a.id DESC
      LIMIT 1`,
    [customerId, user.id, toIstDate(now)],
  );
  if (!shift) return;

  const early = Number(await customerSetting(customerId, 'SHIFT_EARLY_LOGIN_MIN'));
  const from = minutesOfDay(String(shift.from_time));
  const to = minutesOfDay(String(shift.to_time));
  const earlyMin = Number.isInteger(early) && early >= 0 && early <= 120 ? early : 15;
  if (withinShift(istMinutesOfDay(now), from, to, earlyMin)) return;

  const opens = hhmm(opensAt(from, earlyMin));
  throw new ApiError(
    403,
    'OUTSIDE_SHIFT',
    `Sign-in is open during your shift only (${String(shift.shift_name)} ${hhmm(from)}–${hhmm(to)}); it opens at ${opens}`,
    { shift_name: shift.shift_name, from_time: hhmm(from), to_time: hhmm(to), opens_at: opens },
  );
}

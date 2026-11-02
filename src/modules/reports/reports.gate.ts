/**
 * Report access (CLAUDE.md §12): A and S always; O only when REPORT_FLAG = 'Y'.
 * When REPORT_PWD_FLAG = 'Y' customer users must send the report password in the
 * `X-Report-Password` header (checked against the argon2id REPORT_PWD). Settings resolve as
 * customer-wide value overridden by the user's own.
 */
import type { RequestHandler } from 'express';
import type { RequestContext } from '../../core/context';
import { fetch } from '../../core/db/dml';
import { ApiError } from '../../core/http/ApiError';
import { asyncHandler } from '../../core/http/asyncHandler';
import { ctxOf } from '../../core/middleware/requireRole';
import { verifyPassword } from '../../core/security/password';

const GATE_SETTINGS = ['REPORT_FLAG', 'REPORT_PWD_FLAG', 'REPORT_PWD'];

async function gateSettings(ctx: RequestContext): Promise<Record<string, string>> {
  const { rows } = await fetch(
    'md_setting',
    {
      where: { setting_name: { in: GATE_SETTINGS }, user_id: null },
      columns: ['setting_name', 'setting_value'],
    },
    ctx,
  );
  const own = await fetch(
    'md_setting',
    {
      where: { setting_name: { in: GATE_SETTINGS }, user_id: ctx.userId },
      columns: ['setting_name', 'setting_value'],
    },
    ctx,
  );
  const out: Record<string, string> = {};
  for (const r of [...rows, ...own.rows]) out[String(r.setting_name)] = String(r.setting_value);
  return out;
}

export const reportGate: RequestHandler = asyncHandler(async (req, _res, next) => {
  const ctx = ctxOf(req);
  if (ctx.userType === 'S') return next();

  const s = await gateSettings(ctx);
  if (ctx.userType === 'O' && s.REPORT_FLAG !== 'Y') {
    throw ApiError.forbidden('Reports are not enabled for operators');
  }
  if (s.REPORT_PWD_FLAG === 'Y') {
    const given = req.get('x-report-password') ?? '';
    const ok = Boolean(given && s.REPORT_PWD) && (await verifyPassword(s.REPORT_PWD!, given));
    if (!ok) {
      throw new ApiError(403, 'REPORT_PASSWORD_REQUIRED', 'Report password missing or wrong');
    }
  }
  next();
});

/** Admins only (resolving exceptions). */
export const adminOnly: RequestHandler = (req, _res, next) => {
  next(ctxOf(req).userType === 'O' ? ApiError.forbidden('Insufficient role') : undefined);
};

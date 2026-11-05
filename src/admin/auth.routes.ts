import { Router } from 'express';
import { z } from 'zod';
import { ApiError } from '../core/http/ApiError';
import { ctxOf } from '../core/middleware/requireRole';
import { limiters } from '../core/middleware/rateLimit';
import { changePassword, login, logout } from '../modules/auth/auth.service';
import { changePasswordBody } from '../modules/auth/auth.schema';
import { clearAuthCookies, setAuthCookies } from './cookies';
import { verifyCsrf } from './csrf';
import { flash } from './flash';
import { handle, loadMe, page, safeNext } from './render';
import { panelAuth } from './session';
import { dateTimeIst } from './viewHelpers';

/**
 * Fixed device id of panel sessions: signing in again from the panel (any browser) replaces the
 * previous panel session, while a session on a phone still blocks it (one active session per
 * user, CLAUDE.md §2.6 — decision 2026-11-05, option a).
 */
export const PANEL_DEVICE_ID = 'admin-panel';

const loginForm = z.object({
  user_id: z.string().trim().min(1, 'Enter your user id').max(100),
  password: z.string().min(1, 'Enter your password').max(200),
  next: z.string().max(500).optional(),
});

export const authPages = Router();

authPages.get('/login', (req, res) => {
  page(res, 'admin/login', {
    title: 'Sign in',
    error: null,
    userId: '',
    next: safeNext(req.query.next, ''),
    reason: typeof req.query.reason === 'string' ? req.query.reason : '',
  });
});

/** Turns a login failure into a friendly sentence (never reveals whether the user exists). */
export function loginErrorMessage(err: unknown): string | null {
  if (!(err instanceof ApiError)) return null;
  if (err.status === 401) return 'That user id and password don’t match. Please try again.';
  if (err.status === 429) return 'Too many sign-in attempts. Wait a minute and try again.';
  if (err.code === 'SESSION_ACTIVE') {
    const d = err.details as { session_started_at?: string; device_id?: string } | undefined;
    const since = d?.session_started_at ? ` since ${dateTimeIst(d.session_started_at)} IST` : '';
    return `You are signed in on the app (device ${d?.device_id ?? 'unknown'})${since}. Log out of the app first, then sign in here.`;
  }
  if (err.status === 403) return err.message;
  return null;
}

authPages.post(
  '/login',
  limiters.login,
  verifyCsrf,
  handle(async (req, res) => {
    const parsed = loginForm.safeParse(req.body);
    const userId = typeof req.body?.user_id === 'string' ? req.body.user_id.slice(0, 100) : '';
    const next = safeNext(req.body?.next, '');
    const fail = (error: string, status: number) =>
      page(res, 'admin/login', { title: 'Sign in', error, userId, next, reason: '' }, status);

    if (!parsed.success) return fail(parsed.error.issues[0]?.message ?? 'Check the form', 400);
    try {
      const result = await login(
        {
          user_id: parsed.data.user_id,
          password: parsed.data.password,
          device_id: PANEL_DEVICE_ID,
          app_version: 'admin-panel',
        },
        {
          userTypes: ['S'],
          wrongTypeMessage:
            'This panel is for Synergic super admins. Station staff, please use the Cloak Room app.',
        },
      );
      setAuthCookies(req, res, result);
      const mustChange = (result.user as { must_change_pwd?: string }).must_change_pwd === 'Y';
      res.redirect(303, mustChange ? '/admin/change-password' : next || '/admin');
    } catch (err) {
      const message = loginErrorMessage(err);
      if (!message) throw err;
      fail(message, err instanceof ApiError ? err.status : 400);
    }
  }),
);

authPages.get('/change-password', panelAuth({ allowPasswordChange: true }), loadMe, (_req, res) => {
  page(res, 'admin/change-password', { title: 'Change password', errors: {} });
});

authPages.post(
  '/change-password',
  panelAuth({ allowPasswordChange: true }),
  verifyCsrf,
  loadMe,
  handle(async (req, res) => {
    const parsed = changePasswordBody.safeParse(req.body);
    const render = (errors: Record<string, string>) =>
      page(res, 'admin/change-password', { title: 'Change password', errors }, 400);
    if (!parsed.success) {
      const errors: Record<string, string> = {};
      for (const i of parsed.error.issues) errors[String(i.path[0] ?? 'form')] ??= i.message;
      return render(errors);
    }
    if (req.body.confirm_password !== parsed.data.new_password) {
      return render({ confirm_password: 'Passwords don’t match' });
    }
    try {
      await changePassword(ctxOf(req), parsed.data);
    } catch (err) {
      if (err instanceof ApiError && err.status === 400) {
        return render({ current_password: 'Current password is wrong' });
      }
      throw err;
    }
    flash(req, res, 'success', 'Your password has been changed.');
    res.redirect(303, '/admin');
  }),
);

authPages.post(
  '/logout',
  panelAuth({ allowForced: true, allowPasswordChange: true }),
  verifyCsrf,
  handle(async (req, res) => {
    await logout(ctxOf(req));
    clearAuthCookies(req, res);
    flash(req, res, 'info', 'You have signed out.');
    res.redirect(303, '/admin/login');
  }),
);

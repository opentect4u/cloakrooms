import type { NextFunction, Request, RequestHandler, Response } from 'express';
import { fetchOne } from '../core/db/dml';
import { ctxOf } from '../core/middleware/requireRole';
import { clearFlash } from './flash';

/** Sidebar entries (an item with `soon: true` is shown disabled). */
export const NAV = [
  { section: 'Overview' },
  { key: 'dashboard', label: 'Dashboard', icon: 'grid-1x2', href: '/admin' },
  { section: 'Stations' },
  { key: 'customers', label: 'Customers', icon: 'buildings', href: '/admin/customers' },
  { key: 'bags', label: 'Bag search', icon: 'search', href: '/admin/bags' },
  { key: 'reports', label: 'Reports', icon: 'bar-chart-line', href: '/admin/reports' },
  {
    key: 'exceptions',
    label: 'Sync exceptions',
    icon: 'exclamation-triangle',
    href: '/admin/exceptions',
  },
  { section: 'Platform' },
  { key: 'gst', label: 'GST rates', icon: 'percent', href: '/admin/gst' },
  {
    key: 'id-proofs',
    label: 'ID proofs',
    icon: 'person-vcard',
    href: '/admin/id-proofs',
  },
  { key: 'releases', label: 'App releases', icon: 'phone', href: '/admin/releases' },
  { key: 'admins', label: 'Super admins', icon: 'shield-lock', href: '/admin/admins' },
] as const;

/** Loads the signed-in admin's display name for the top bar (after panelAuth). */
export const loadMe: RequestHandler = (req, res, next) => {
  const ctx = ctxOf(req);
  fetchOne('md_user', { id: ctx.userId }, ctx, undefined, {
    columns: ['id', 'user_id', 'user_name', 'last_login', 'must_change_pwd'],
  })
    .then((row) => {
      res.locals.me = {
        ...ctx,
        name: row?.user_name ?? ctx.loginId,
        lastLogin: row?.last_login ?? null,
        mustChange: row?.must_change_pwd === 'Y',
      };
      next();
    })
    .catch(next);
};

export interface PageLocals {
  title: string;
  active?: string;
  [key: string]: unknown;
}

/** Renders a page inside the shell with `nav` and `active` set; never cached. */
export function page(res: Response, view: string, locals: PageLocals, status = 200) {
  res.setHeader('Cache-Control', 'no-store');
  clearFlash(res.req, res);
  res.status(status).render(view, { nav: NAV, active: '', ...locals });
}

/** Wraps an async handler so rejections reach the panel error handler. */
export const handle =
  (fn: (req: Request, res: Response, next: NextFunction) => Promise<unknown>): RequestHandler =>
  (req, res, next) => {
    fn(req, res, next).catch(next);
  };

/** A same-site `/admin…` path from user input, else the fallback (no open redirects). */
export function safeNext(value: unknown, fallback = '/admin'): string {
  if (typeof value !== 'string') return fallback;
  if (!/^\/admin(?:[/?#]|$)/.test(value) || value.startsWith('//') || value.includes('\\')) {
    return fallback;
  }
  return value;
}

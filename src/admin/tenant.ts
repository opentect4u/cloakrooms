import type { RequestHandler } from 'express';
import type { RequestContext } from '../core/context';
import { fetchOne } from '../core/db/dml';
import { ApiError } from '../core/http/ApiError';
import { ctxOf } from '../core/middleware/requireRole';
import { daysBetween, toIstDate } from '../core/utils/time';

/**
 * The context the panel uses for one customer's data: the super admin's identity (audit columns
 * record their login id) scoped to that tenant, so the DML layer forces customer_id on every
 * read, write and soft delete exactly as for that customer's own admin — a panel bug cannot leak
 * or touch another tenant's rows.
 */
export function tenantCtx(me: RequestContext, customerId: number): RequestContext {
  return { ...me, userType: 'A', customerId };
}

export const CUSTOMER_TABS = [
  { key: 'overview', label: 'Overview', icon: 'speedometer2', path: '' },
  { key: 'users', label: 'Users', icon: 'people', path: '/users' },
  { key: 'rates', label: 'Tariff', icon: 'currency-rupee', path: '/rates' },
  { key: 'shifts', label: 'Shifts', icon: 'clock', path: '/shifts' },
  {
    key: 'shift-assignments',
    label: 'Shift roster',
    icon: 'calendar-week',
    path: '/shift-assignments',
  },
  { key: 'header-footer', label: 'Receipt layout', icon: 'receipt', path: '/header-footer' },
  { key: 'settings', label: 'Settings', icon: 'sliders', path: '/settings' },
  { key: 'registrations', label: 'Subscriptions', icon: 'patch-check', path: '/registrations' },
  { key: 'payments', label: 'Payments', icon: 'wallet2', path: '/payments' },
] as const;

export type SubscriptionTone = 'success' | 'warning' | 'danger' | 'muted';

/** Subscription state from md_customer.renewal_dt (IST dates). */
export function subscriptionOf(renewal: unknown, today = toIstDate()) {
  if (!renewal) {
    return { tone: 'danger' as SubscriptionTone, label: 'No subscription', daysLeft: null };
  }
  const days = daysBetween(today, String(renewal).slice(0, 10));
  if (days < 0) return { tone: 'danger' as SubscriptionTone, label: 'Expired', daysLeft: days };
  if (days <= 30) {
    return {
      tone: 'warning' as SubscriptionTone,
      label: days === 0 ? 'Ends today' : `${days} day${days === 1 ? '' : 's'} left`,
      daysLeft: days,
    };
  }
  return { tone: 'success' as SubscriptionTone, label: 'Active', daysLeft: days };
}

/**
 * Loads `:cid` (live customer, else 404) into res.locals for the customer header and tabs, and
 * `res.locals.tctx`, the tenant-scoped context for that customer.
 */
export const loadCustomer: RequestHandler = (req, res, next) => {
  const cid = Number(req.params.cid);
  if (!Number.isSafeInteger(cid) || cid <= 0) return next(ApiError.notFound('Customer not found'));
  const ctx = ctxOf(req);
  fetchOne('md_customer', { customer_id: cid }, ctx)
    .then((customer) => {
      if (!customer) throw ApiError.notFound('Customer not found');
      res.locals.customer = customer;
      res.locals.cid = cid;
      res.locals.base = `/admin/customers/${cid}`;
      res.locals.tabs = CUSTOMER_TABS;
      res.locals.subscription = subscriptionOf(customer.renewal_dt);
      res.locals.tctx = tenantCtx(ctx, cid);
      next();
    })
    .catch(next);
};

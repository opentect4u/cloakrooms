/** /admin/customers — stations: list, create, overview, edit, delete, and every customer tab. */
import { Router, type Request, type Response } from 'express';
import type { RowDataPacket } from 'mysql2/promise';
import { z } from 'zod';
import { crudService } from '../core/crud/crudService';
import { raw, type Where } from '../core/db/dml';
import { ApiError } from '../core/http/ApiError';
import { ctxOf } from '../core/middleware/requireRole';
import { toIstDate } from '../core/utils/time';
import { customersCrud } from '../modules/customers/customers.routes';
import { customerCreate, customerUpdate } from '../modules/customers/customers.schema';
import { customerSummary } from '../modules/customers/customers.service';
import { verifyCsrf } from './csrf';
import { INDIAN_STATES, inputValues, type Field } from './fields';
import { flash } from './flash';
import { formValues, runForm, withoutEmpty, type FormFailure } from './forms';
import { layoutRoutes } from './layout.routes';
import { listQuery, pager, PAGE_SIZE } from './listing';
import { handle, page } from './render';
import { resourceRoutes } from './resource.routes';
import { settingsRoutes } from './settings.routes';
import { loadCustomer, subscriptionOf } from './tenant';
import { usersRoutes } from './users.routes';

const customers = crudService(customersCrud);

export const CUSTOMER_FIELDS: Field[] = [
  {
    name: 'customer_name',
    label: 'Cloak room name',
    type: 'text',
    required: true,
    maxlength: 155,
    col: 'col-12',
    icon: 'building',
    placeholder: 'Howrah Station Cloak Room',
  },
  {
    name: 'customer_addr',
    label: 'Address',
    type: 'textarea',
    required: true,
    maxlength: 200,
    col: 'col-12',
  },
  {
    name: 'state',
    label: 'State',
    type: 'select',
    options: INDIAN_STATES,
    emptyOption: '— Select state —',
    col: 'col-sm-6',
  },
  {
    name: 'pin_code',
    label: 'PIN code',
    type: 'text',
    required: true,
    maxlength: 6,
    col: 'col-sm-6',
    placeholder: '711101',
  },
  {
    name: 'mobile_no',
    label: 'Mobile',
    type: 'text',
    required: true,
    maxlength: 20,
    col: 'col-sm-6',
    icon: 'telephone',
    autocomplete: 'tel',
  },
  {
    name: 'email',
    label: 'Email',
    type: 'email',
    maxlength: 150,
    col: 'col-sm-6',
    icon: 'envelope',
    autocomplete: 'email',
  },
  {
    name: 'gst_no',
    label: 'GSTIN',
    type: 'text',
    maxlength: 15,
    col: 'col-sm-6',
    placeholder: '19ABCDE1234F1Z5',
    help: 'Printed on every bill from now on.',
  },
  {
    name: 'upi_id',
    label: 'UPI ID',
    type: 'text',
    maxlength: 255,
    col: 'col-sm-6',
    placeholder: 'station@okaxis',
    help: 'Used for the UPI payment QR.',
  },
  {
    name: 'user_limit',
    label: 'Licensed users',
    type: 'number',
    required: true,
    min: 1,
    max: 1000,
    col: 'col-sm-6',
    help: 'Users that may be enabled at once.',
  },
  {
    name: 'active_flag',
    label: 'Active',
    type: 'switch',
    col: 'col-sm-6',
    help: 'Inactive stations cannot sign in.',
  },
  {
    name: 'remarks',
    label: 'Internal remarks',
    type: 'textarea',
    maxlength: 255,
    col: 'col-12',
    help: 'Only visible to Synergic.',
  },
];

const filtersQuery = z.object({
  status: z.enum(['active', 'inactive', 'expired', 'none']).optional().catch(undefined),
  state: z.string().trim().max(50).optional().catch(undefined),
  sort: z.enum(['name', 'renewal', 'newest']).optional().catch(undefined),
});

const SORTS = {
  name: [{ column: 'customer_name' }],
  renewal: [{ column: 'renewal_dt', dir: 'asc' as const }],
  newest: [{ column: 'customer_id', dir: 'desc' as const }],
};

export function customersRoutes(): Router {
  const router = Router();

  router.get(
    '/',
    handle(async (req, res) => {
      const ctx = ctxOf(req);
      const q = listQuery.parse(req.query);
      const f = filtersQuery.parse(req.query);
      const today = toIstDate();
      const filters: Where = {};
      if (f.status === 'active') filters.active_flag = 'Y';
      if (f.status === 'inactive') filters.active_flag = 'N';
      if (f.status === 'expired') filters.renewal_dt = { op: '<', value: today };
      if (f.status === 'none') filters.renewal_dt = null;
      if (f.state) filters.state = f.state;
      const { rows, total } = await customers.list(ctx, {
        filters,
        page: q.page,
        limit: PAGE_SIZE,
        sort: SORTS[f.sort ?? 'name'],
        search: q.q,
      });
      const ids = rows.map((r) => Number(r.customer_id));
      const seats = new Map<number, { allowed: number; online: number }>();
      if (ids.length) {
        const usage = await raw<RowDataPacket[]>(
          `SELECT u.customer_id,
                  COALESCE(SUM(u.allow_flag = 'Y'), 0) AS allowed,
                  (SELECT COUNT(*) FROM td_user_session s
                    WHERE s.customer_id = u.customer_id AND s.status = 'A') AS online
             FROM md_user u
            WHERE u.is_deleted = 'N' AND u.customer_id IN (${ids.map(() => '?').join(',')})
            GROUP BY u.customer_id`,
          ids,
        );
        for (const u of usage) {
          seats.set(Number(u.customer_id), {
            allowed: Number(u.allowed),
            online: Number(u.online),
          });
        }
      }
      const states = await raw<RowDataPacket[]>(
        `SELECT DISTINCT state FROM md_customer
          WHERE is_deleted = 'N' AND state IS NOT NULL AND state <> '' ORDER BY state`,
      );
      page(res, 'admin/customers/list', {
        title: 'Customers',
        active: 'customers',
        q: q.q ?? '',
        f,
        states: states.map((s) => String(s.state)),
        rows: rows.map((r) => ({
          ...r,
          subscription: subscriptionOf(r.renewal_dt, today),
          seats: seats.get(Number(r.customer_id)) ?? { allowed: 0, online: 0 },
        })),
        pager: pager('/admin/customers', req.query, q.page, PAGE_SIZE, total),
      });
    }),
  );

  const renderForm = (
    res: Response,
    mode: 'create' | 'edit',
    values: Record<string, string>,
    failure: FormFailure | null,
  ) => {
    const customer = res.locals.customer as Record<string, unknown> | undefined;
    page(
      res,
      'admin/customers/form',
      {
        title: mode === 'create' ? 'New customer' : 'Edit customer',
        active: 'customers',
        tab: 'overview',
        mode,
        fields: CUSTOMER_FIELDS,
        values,
        errors: failure?.fields ?? {},
        formError: failure?.form ?? null,
        action:
          mode === 'create'
            ? '/admin/customers'
            : `/admin/customers/${String(customer?.customer_id)}`,
      },
      failure ? 400 : 200,
    );
  };

  router.get('/new', (_req, res) =>
    renderForm(res, 'create', { user_limit: '1', active_flag: 'Y' }, null),
  );

  router.post(
    '/',
    verifyCsrf,
    handle(async (req, res) => {
      const values = formValues(req.body);
      const outcome = await runForm(customerCreate, withoutEmpty(values), (data) =>
        customers.create(ctxOf(req), data),
      );
      if (!outcome.ok) return renderForm(res, 'create', values, outcome.failure);
      flash(
        req,
        res,
        'success',
        'Customer created with default settings and the standard tariff (24 h · ₹50, then ₹20). Add a subscription so its users can sign in.',
      );
      res.redirect(303, `/admin/customers/${String(outcome.data?.customer_id)}`);
    }),
  );

  const one = Router({ mergeParams: true });
  router.use('/:cid', loadCustomer, one);

  one.get(
    '/',
    handle(async (req, res) => {
      const summary = await customerSummary(Number(res.locals.cid), ctxOf(req));
      page(res, 'admin/customers/overview', {
        title: String((res.locals.customer as { customer_name: string }).customer_name),
        active: 'customers',
        tab: 'overview',
        s: summary,
      });
    }),
  );

  one.get('/edit', (_req, res) =>
    renderForm(res, 'edit', inputValues(CUSTOMER_FIELDS, res.locals.customer), null),
  );

  one.post(
    '/',
    verifyCsrf,
    handle(async (req, res) => {
      const values = formValues(req.body);
      const outcome = await runForm(customerUpdate, withoutEmpty(values), (data) =>
        customers.update(ctxOf(req), Number(res.locals.cid), data),
      );
      if (!outcome.ok) return renderForm(res, 'edit', values, outcome.failure);
      flash(req, res, 'success', 'Customer details saved.');
      res.redirect(303, String(res.locals.base));
    }),
  );

  one.post(
    '/delete',
    verifyCsrf,
    handle(async (req: Request, res: Response) => {
      const customer = res.locals.customer as { customer_name: string };
      const typed = formValues(req.body).confirm_name ?? '';
      if (typed.trim() !== customer.customer_name) {
        flash(req, res, 'danger', 'The name did not match, so nothing was deleted.');
        return res.redirect(303, `${String(res.locals.base)}/edit#danger`);
      }
      await customers.remove(ctxOf(req), Number(res.locals.cid));
      flash(req, res, 'success', `“${customer.customer_name}” was deleted.`);
      res.redirect(303, '/admin/customers');
    }),
  );

  // Tabs. Custom pages first (header-footer list overrides the generic list).
  one.use(usersRoutes());
  one.use(settingsRoutes());
  one.use(layoutRoutes());
  one.use(resourceRoutes());
  one.use((_req, _res, next) => next(ApiError.notFound('Page not found')));

  return router;
}

import { Router, type Request, type Response } from 'express';
import { z } from 'zod';
import { crudFactory, type CrudConfig } from '../../core/crud/crudFactory';
import { asyncHandler } from '../../core/http/asyncHandler';
import { sendSuccess } from '../../core/http/response';
import { ctxOf, requireRole } from '../../core/middleware/requireRole';
import { validate } from '../../core/middleware/validate';
import { positiveId } from '../../core/validation/fragments';
import {
  customerCreate,
  customerSelfUpdate,
  type CustomerSelfUpdate,
  customerUpdate,
  paymentCreate,
  paymentUpdate,
  registrationCreate,
  registrationUpdate,
} from './customers.schema';
import {
  applyCustomerDefaults,
  assertDateRange,
  checkPayment,
  customerSummary,
  getOwnCustomer,
  syncRenewalDate,
  updateOwnCustomer,
} from './customers.service';

const SUPER_ONLY = { read: ['S'], write: ['S'] } as const;
const PARENT = {
  param: 'customerId',
  column: 'customer_id',
  table: 'md_customer',
  pk: 'customer_id',
} as const;

/**
 * Tenants, their registrations (subscriptions) and payments to Synergic: super admin only —
 * except `/me`, the caller's own customer (read A/O, write A; BACKEND_API_REQUESTS A4).
 */
export const customersRoutes = Router();

// Before the crudFactory routes, whose `/:id` would otherwise catch "me".
customersRoutes.get(
  '/me',
  requireRole('A', 'O'),
  asyncHandler(async (req: Request, res: Response) => {
    sendSuccess(req, res, await getOwnCustomer(ctxOf(req)));
  }),
);

customersRoutes.patch(
  '/me',
  requireRole('A'),
  validate({ body: customerSelfUpdate }),
  asyncHandler(async (req: Request, res: Response) => {
    const data = await updateOwnCustomer(ctxOf(req), req.body as CustomerSelfUpdate);
    sendSuccess(req, res, data, { message: 'Customer updated' });
  }),
);

customersRoutes.get(
  '/:id/summary',
  requireRole('S'),
  validate({ params: z.object({ id: positiveId }) }),
  asyncHandler(async (req: Request, res: Response) => {
    sendSuccess(req, res, await customerSummary(Number(req.params.id), ctxOf(req)));
  }),
);

export const registrationsCrud: CrudConfig = {
  table: 'md_customer_registration',
  pk: 'reg_id',
  scope: 'global',
  parent: PARENT,
  roles: SUPER_ONLY,
  schema: { create: registrationCreate, update: registrationUpdate },
  sortable: ['end_date', 'start_date', 'reg_no', 'reg_id'],
  defaultSort: [{ column: 'end_date', dir: 'desc' }],
  searchable: ['reg_no'],
  hooks: {
    beforeCreate: (data) => {
      assertDateRange(data);
      return data;
    },
    beforeUpdate: (data, existing) => {
      assertDateRange({ ...existing, ...data });
      return data;
    },
    // The latest registration drives md_customer.renewal_dt (login subscription check).
    afterWrite: (_event, { parentId }, ctx, conn) => syncRenewalDate(Number(parentId), ctx, conn),
  },
};

export const paymentsCrud: CrudConfig = {
  table: 'td_customer_billing',
  pk: 'payment_id',
  scope: 'global',
  parent: PARENT,
  roles: SUPER_ONLY,
  schema: { create: paymentCreate, update: paymentUpdate },
  filterable: ['pay_mode', 'reg_id'],
  sortable: ['payment_dt', 'payment_id', 'net_amt'],
  defaultSort: [{ column: 'payment_dt', dir: 'desc' }],
  searchable: ['transaction_no'],
  hooks: {
    beforeCreate: (data, ctx, conn) =>
      checkPayment(data, data, Number(data.customer_id), ctx, conn),
    beforeUpdate: (data, existing, ctx, conn) =>
      checkPayment(data, { ...existing, ...data }, Number(existing.customer_id), ctx, conn),
  },
};

export const customersCrud: CrudConfig = {
  table: 'md_customer',
  pk: 'customer_id',
  scope: 'global',
  roles: SUPER_ONLY,
  schema: { create: customerCreate, update: customerUpdate },
  searchable: ['customer_name', 'mobile_no', 'gst_no'],
  filterable: ['active_flag', 'state'],
  sortable: ['customer_name', 'renewal_dt', 'customer_id', 'created_at'],
  defaultSort: [{ column: 'customer_name' }],
  hooks: {
    afterWrite: async (event, { id }, ctx, conn) => {
      if (event === 'create') await applyCustomerDefaults(Number(id), ctx, conn);
    },
  },
};

customersRoutes.use('/:customerId/registrations', crudFactory(registrationsCrud));
customersRoutes.use('/:customerId/payments', crudFactory(paymentsCrud));
customersRoutes.use(crudFactory(customersCrud));

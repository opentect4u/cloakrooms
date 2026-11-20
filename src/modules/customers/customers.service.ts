import type { RowDataPacket } from 'mysql2/promise';
import { DEFAULT_RATE, DEFAULT_SETTINGS } from '../../config/defaults';
import { rateCreateData } from '../rates/rates.schema';
import type { RequestContext } from '../../core/context';
import {
  fetch,
  fetchOne,
  raw,
  save,
  withTransaction,
  type Executor,
  type Row,
} from '../../core/db/dml';
import { ApiError } from '../../core/http/ApiError';
import { fromPaise, toPaise } from '../../core/utils/money';
import { toIstDate } from '../../core/utils/time';
import { assertTenantRef } from '../masters/refs';
import { CUSTOMER_PUBLIC_COLUMNS, type CustomerSelfUpdate } from './customers.schema';

/** New customer: default settings template + default tariff (same transaction as the insert). */
export async function applyCustomerDefaults(
  customerId: number,
  ctx: RequestContext,
  conn: Executor,
) {
  for (const s of DEFAULT_SETTINGS) {
    await save(
      'md_setting',
      { ...s, customer_id: customerId, user_id: null },
      { flag: 'I' },
      ctx,
      conn,
    );
  }
  await save(
    'md_rate_dtls',
    rateCreateData({ ...DEFAULT_RATE, customer_id: customerId, effective_from: toIstDate() }),
    { flag: 'I' },
    ctx,
    conn,
  );
}

/** md_customer.renewal_dt = latest end_date of the customer's live registrations (or NULL). */
export async function syncRenewalDate(customerId: number, ctx: RequestContext, conn: Executor) {
  const rows = await raw<RowDataPacket[]>(
    `SELECT MAX(end_date) AS renewal FROM md_customer_registration
      WHERE customer_id = ? AND is_deleted = 'N'`,
    [customerId],
    conn,
  );
  await save(
    'md_customer',
    { renewal_dt: rows[0]?.renewal ?? null },
    { flag: 'U', where: { customer_id: customerId } },
    ctx,
    conn,
  );
}

export function assertDateRange(row: Row) {
  if (String(row.end_date) < String(row.start_date)) {
    throw ApiError.validation([{ path: 'end_date', message: 'must be on or after start_date' }]);
  }
}

/** Payment amounts: net = base + cgst + sgst + igst (computed when omitted), in paise. */
export async function checkPayment(
  data: Row,
  merged: Row,
  customerId: number,
  ctx: RequestContext,
  conn: Executor,
): Promise<Row> {
  const sum = ['base_amt', 'cgst', 'sgst', 'igst'].reduce(
    (acc, k) => acc + toPaise(String(merged[k] ?? '0')),
    0,
  );
  const amountsChanged = ['base_amt', 'cgst', 'sgst', 'igst', 'net_amt'].some((k) => k in data);
  const out = { ...data };
  if (amountsChanged) {
    if (data.net_amt === undefined) {
      out.net_amt = fromPaise(sum);
    } else if (toPaise(String(data.net_amt)) !== sum) {
      throw ApiError.validation([
        {
          path: 'net_amt',
          message: `must equal base_amt + cgst + sgst + igst (${fromPaise(sum)})`,
        },
      ]);
    }
  }
  if (data.reg_id !== undefined && data.reg_id !== null) {
    await assertTenantRef(
      { table: 'md_customer_registration', pk: 'reg_id', id: data.reg_id, field: 'reg_id' },
      customerId,
      ctx,
      conn,
    );
  }
  return out;
}

/** GET /customers/:id/summary — TODO(open-point): final field list pending business sign-off. */
export async function customerSummary(customerId: number, ctx: RequestContext) {
  const customer = await fetchOne('md_customer', { customer_id: customerId }, ctx);
  if (!customer) throw ApiError.notFound('Customer not found');

  const [users] = await raw<RowDataPacket[]>(
    `SELECT COUNT(*) AS total,
            COALESCE(SUM(allow_flag = 'Y'), 0) AS allowed,
            COALESCE(SUM(user_type = 'A'), 0) AS admins,
            COALESCE(SUM(user_type = 'O'), 0) AS operators
       FROM md_user WHERE customer_id = ? AND is_deleted = 'N'`,
    [customerId],
  );
  const [sessions] = await raw<RowDataPacket[]>(
    `SELECT COUNT(*) AS active FROM td_user_session WHERE customer_id = ? AND status = 'A'`,
    [customerId],
  );
  const [payments] = await raw<RowDataPacket[]>(
    `SELECT COUNT(*) AS count, COALESCE(SUM(net_amt), 0) AS total_net, MAX(payment_dt) AS last_payment_dt
       FROM td_customer_billing WHERE customer_id = ? AND is_deleted = 'N'`,
    [customerId],
  );
  const [luggage] = await raw<RowDataPacket[]>(
    `SELECT COUNT(*) AS total, COALESCE(SUM(out_flag = 'N'), 0) AS in_store
       FROM td_luggage_in_out WHERE customer_id = ? AND is_deleted = 'N'`,
    [customerId],
  );
  const latest = await fetch(
    'md_customer_registration',
    {
      where: { customer_id: customerId },
      orderBy: [{ column: 'end_date', dir: 'desc' }],
      page: 1,
      limit: 1,
    },
    ctx,
  );

  return {
    customer,
    users: {
      total: Number(users?.total),
      allowed: Number(users?.allowed),
      admins: Number(users?.admins),
      operators: Number(users?.operators),
      active_sessions: Number(sessions?.active),
    },
    latest_registration: latest.rows[0] ?? null,
    payments: {
      count: Number(payments?.count),
      total_net: String(payments?.total_net),
      last_payment_dt: payments?.last_payment_dt ?? null,
    },
    luggage: { total: Number(luggage?.total), in_store: Number(luggage?.in_store) },
  };
}

/** The token's own customer; S has none (it uses /customers/:id instead). */
function ownCustomerId(ctx: RequestContext): number {
  if (ctx.userType === 'S' || ctx.customerId === null) {
    throw ApiError.forbidden('Only customer users have an own customer; use /customers/:id');
  }
  return ctx.customerId;
}

/** GET /customers/me — same shape as `customer` in the login payload. */
export async function getOwnCustomer(ctx: RequestContext, conn?: Executor) {
  const row = await fetchOne('md_customer', { customer_id: ownCustomerId(ctx) }, ctx, conn, {
    columns: CUSTOMER_PUBLIC_COLUMNS,
  });
  if (!row) throw ApiError.notFound('Customer not found');
  return row;
}

/**
 * PATCH /customers/me (station admin, BACKEND_API_REQUESTS A4). Only the fields of
 * `customerSelfUpdate` reach here; the DML layer also pins the row to the token's customer.
 * A new gst_no is stamped on bills pushed from now on — stored bills keep theirs.
 */
export async function updateOwnCustomer(ctx: RequestContext, data: CustomerSelfUpdate) {
  const customerId = ownCustomerId(ctx);
  return withTransaction(async (conn) => {
    const { affectedRows } = await save(
      'md_customer',
      data,
      { flag: 'U', where: { customer_id: customerId } },
      ctx,
      conn,
    );
    if (affectedRows === 0) throw ApiError.notFound('Customer not found');
    return getOwnCustomer(ctx, conn);
  });
}

import { randomUUID } from 'node:crypto';
import request from 'supertest';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { createApp } from '../../src/app';
import type { RequestContext } from '../../src/core/context';
import { save } from '../../src/core/db/dml';
import { closeDb, db } from '../../src/core/db/pool';
import { loadRegistry } from '../../src/core/db/registry';
import { hashPassword } from '../../src/core/security/password';
import { bearer, seedCustomer, seedUser, tokensFor, type SeededUser } from '../helpers/auth';
import { adminOf } from '../helpers/context';

const app = createApp();
type Auth = Record<string, string>;

let cid: number;
let A: RequestContext;
let op: SeededUser;
let op2: SeededUser;
let s1: number;
let s2: number;
let admin: Auth;
let operator: Auth;
let otherAdmin: Auth;
let superAuth: Auth;
const trans: Record<string, string> = {};

async function bag(key: string, over: Record<string, unknown>) {
  const transId = randomUUID();
  trans[key] = transId;
  await save(
    'td_luggage_in_out',
    {
      trans_id: transId,
      receipt_no: `R-${key}`,
      receipt_dt: '2026-05-01',
      user_id_in: op.id,
      device_id_in: 'dev',
      shift_id_in: s1,
      pasn_name: `Passenger ${key}`,
      pasn_mob_no: '9000000000',
      pnr_no: `PNR-${key}`,
      luggage_qty: 1,
      id_proof_id: 1,
      id_number: 'X1',
      out_flag: 'N',
      ...over,
    },
    { flag: 'I' },
    A,
  );
  return transId;
}

async function billFor(transId: string, over: Record<string, unknown>) {
  await save(
    'td_receipt',
    {
      trans_id: transId,
      bill_no: `B-${transId.slice(0, 8)}`,
      bill_dt: '2026-05-01',
      user_id: op.id,
      device_id: 'dev',
      shift_id: s1,
      luggage_qty: 1,
      days_charged: 1,
      block_hours: 24,
      first_block_rate: '50',
      next_block_rate: '20',
      base_amt: '50',
      net_amt: '50',
      taxable_amt: '42.37',
      gst_pct: '18',
      cgst: '3.82',
      sgst: '3.81',
      igst: '0',
      pay_mode: 'C',
      ...over,
      bal_amt: over.bal_amt ?? over.net_amt ?? '50',
    },
    { flag: 'I' },
    A,
  );
}

const get = (path: string, auth: Auth) => request(app).get(`/api/v1/reports${path}`).set(auth);

beforeAll(async () => {
  await loadRegistry(db);
  cid = await seedCustomer();
  A = adminOf(cid);
  op = await seedUser(cid, { code: 'O1' });
  op2 = await seedUser(cid, { code: 'O2' });
  s1 = (
    await save(
      'md_shift',
      { shift_name: 'Day', from_time: '06:00', to_time: '18:00' },
      { flag: 'I' },
      A,
    )
  ).insertId;
  s2 = (
    await save(
      'md_shift',
      { shift_name: 'Night', from_time: '18:00', to_time: '06:00' },
      { flag: 'I' },
      A,
    )
  ).insertId;

  // L1: in 01-May 10:00 IST, out same day; cash ₹100, 2 bags.
  const l1 = await bag('L1', {
    luggage_qty: 2,
    date_time_in: '2026-05-01 04:30:00',
    user_id_out: op.id,
    device_id_out: 'dev',
    shift_id_out: s1,
    date_time_out: '2026-05-01 10:00:00',
    out_flag: 'Y',
  });
  await billFor(l1, {
    luggage_qty: 2,
    base_amt: '100',
    net_amt: '100',
    taxable_amt: '84.75',
    cgst: '7.63',
    sgst: '7.62',
  });
  // L2: in 02-May 00:30 IST (01-May 19:00 UTC), out 03-May 01:30 IST; UPI ₹70, bill not printed.
  const l2 = await bag('L2', {
    receipt_dt: '2026-05-02',
    user_id_in: op2.id,
    shift_id_in: s2,
    date_time_in: '2026-05-01 19:00:00',
    user_id_out: op2.id,
    device_id_out: 'dev2',
    shift_id_out: s2,
    date_time_out: '2026-05-02 20:00:00',
    out_flag: 'Y',
  });
  await billFor(l2, {
    bill_dt: '2026-05-03',
    user_id: op2.id,
    shift_id: s2,
    days_charged: 2,
    base_amt: '70',
    net_amt: '70',
    taxable_amt: '59.32',
    cgst: '5.34',
    sgst: '5.34',
    pay_mode: 'U',
    upi_ref: 'UPI-9',
    bill_printed: 'N',
  });
  // L3: in 02-May 10:30 IST, still in store, 3 bags.
  await bag('L3', {
    receipt_dt: '2026-05-02',
    luggage_qty: 3,
    date_time_in: '2026-05-02 05:00:00',
  });

  admin = bearer((await tokensFor(app, await seedUser(cid, { type: 'A' }), 'r-admin')).access);
  operator = bearer((await tokensFor(app, op, 'r-op')).access);
  otherAdmin = bearer(
    (await tokensFor(app, await seedUser(await seedCustomer(), { type: 'A' }), 'r-other')).access,
  );
  superAuth = bearer((await tokensFor(app, await seedUser(null, { type: 'S' }), 'r-sa')).access);
});
afterAll(async () => {
  await closeDb();
});

const RANGE = '?from=2026-05-01&to=2026-05-03';

describe('collection reports', () => {
  it('collection-summary: day-wise rows with cash/UPI split and totals', async () => {
    const res = await get(`/collection-summary${RANGE}`, admin);
    expect(res.status).toBe(200);
    expect(res.body.data).toEqual([
      expect.objectContaining({
        bill_dt: '2026-05-01',
        bills: 1,
        bags: 2,
        net_amt: '100.00',
        cash_amt: '100.00',
        upi_amt: '0.00',
      }),
      expect.objectContaining({
        bill_dt: '2026-05-03',
        bills: 1,
        bags: 1,
        net_amt: '70.00',
        cash_amt: '0.00',
        upi_amt: '70.00',
      }),
    ]);
    expect(res.body.meta).toMatchObject({
      page: 1,
      total: 2,
      totals: {
        bills: 2,
        bags: 3,
        net_amt: '170.00',
        cash_amt: '100.00',
        upi_amt: '70.00',
        penal_amt: '0.00',
      },
    });

    const upi = await get(`/collection-summary${RANGE}&pay_mode=U`, admin);
    expect(upi.body.meta.totals.net_amt).toBe('70.00');
    const byUser = await get(`/collection-summary${RANGE}&user_id=${op.id}`, admin);
    expect(byUser.body.meta.totals.net_amt).toBe('100.00');
    const paged = await get(`/collection-summary${RANGE}&limit=1&page=2`, admin);
    expect(paged.body.data.map((r: { bill_dt: string }) => r.bill_dt)).toEqual(['2026-05-03']);
  });

  it('gst, user-shift collection and unprinted bills', async () => {
    const gst = await get(`/gst${RANGE}`, admin);
    expect(gst.body.meta.totals).toMatchObject({
      bills: 2,
      taxable_amt: '144.07',
      cgst: '12.97',
      sgst: '12.96',
      igst: '0.00',
    });
    expect(gst.body.data[0]).toMatchObject({ bill_dt: '2026-05-01', gst_pct: '18.00' });

    const us = await get(`/user-shift-collection${RANGE}`, admin);
    expect(us.body.data).toHaveLength(2);
    expect(us.body.data.find((r: { user_id: number }) => r.user_id === op2.id)).toMatchObject({
      shift_name: 'Night',
      user_code: 'O2',
      net_amt: '70.00',
    });

    const unprinted = await get(`/unprinted-bills${RANGE}`, admin);
    expect(unprinted.body.data).toEqual([
      expect.objectContaining({
        trans_id: trans.L2,
        receipt_no: 'R-L2',
        pay_mode: 'U',
        upi_ref: 'UPI-9',
      }),
    ]);
    expect(unprinted.body.meta.totals).toEqual({ bills: 1, net_amt: '70.00', bal_amt: '70.00' });
  });
});

describe('registers (IST day boundaries)', () => {
  it('check-in register uses the IST calendar day', async () => {
    const may1 = await get('/check-in-register?from=2026-05-01&to=2026-05-01', admin);
    expect(may1.body.data.map((r: { trans_id: string }) => r.trans_id)).toEqual([trans.L1]);
    expect(may1.body.data[0]).toMatchObject({
      date_time_in: '2026-05-01 04:30:00',
      date_time_in_ist: '2026-05-01 10:00:00',
      user_name_in: expect.any(String),
      shift_name_in: 'Day',
      id_proof_name: expect.any(String),
    });

    const may2 = await get('/check-in-register?from=2026-05-02&to=2026-05-02', admin);
    expect(may2.body.data.map((r: { trans_id: string }) => r.trans_id)).toEqual([
      trans.L2,
      trans.L3,
    ]);
    expect(may2.body.meta.totals).toEqual({
      bags_entries: 2,
      bags: 4,
      extra_bags: 0,
      adv_amt: '0.00',
    });
    expect(may2.body.data[0].date_time_in_ist).toBe('2026-05-02 00:30:00');

    const night = await get(
      `/check-in-register?from=2026-05-02&to=2026-05-02&shift_id=${s2}`,
      admin,
    );
    expect(night.body.data.map((r: { trans_id: string }) => r.trans_id)).toEqual([trans.L2]);
  });

  it('check-out register joins the bill', async () => {
    const res = await get('/check-out-register?from=2026-05-03&to=2026-05-03', admin);
    expect(res.body.data).toEqual([
      expect.objectContaining({
        trans_id: trans.L2,
        date_time_out_ist: '2026-05-03 01:30:00',
        bill_dt: '2026-05-03',
        net_amt: '70.00',
        bill_printed: 'N',
        shift_name_out: 'Night',
      }),
    ]);
    expect(res.body.meta.totals).toEqual({
      bags_entries: 1,
      bags: 1,
      extra_bags: 0,
      net_amt: '70.00',
      adv_amt: '0.00',
      bal_amt: '70.00',
    });
    expect(
      (await get(`/check-out-register${RANGE}&pay_mode=C`, admin)).body.meta.totals.net_amt,
    ).toBe('100.00');
  });

  it('bags in store lists open bags with time stored', async () => {
    const res = await get('/bags-in-store', admin);
    expect(res.body.data).toEqual([
      expect.objectContaining({ trans_id: trans.L3, luggage_qty: 3 }),
    ]);
    expect(res.body.data[0].minutes_stored).toBeGreaterThan(0);
    expect(res.body.meta.totals).toEqual({
      bags_entries: 1,
      bags: 3,
      extra_bags: 0,
      adv_amt: '0.00',
    });
    expect((await get('/bags-in-store?from=2026-05-03', admin)).body.data).toEqual([]);
  });
});

describe('exceptions report', () => {
  it('lists exceptions and lets admins resolve them (idempotent)', async () => {
    const { insertId } = await save(
      'td_sync_exception',
      {
        trans_id: trans.L1,
        table_name: 'td_receipt',
        payload: JSON.stringify({ row: { a: 1 } }),
        reason: 'DUPLICATE_BILL: test',
        user_id: op.id,
        device_id: 'dev',
      },
      { flag: 'I' },
      A,
    );
    const list = await get('/exceptions?resolved=N', admin);
    expect(list.body.data).toEqual([
      expect.objectContaining({
        id: insertId,
        reason: 'DUPLICATE_BILL: test',
        payload: { row: { a: 1 } },
      }),
    ]);
    expect(list.body.meta.totals).toEqual({ exceptions: 1, unresolved: 1 });

    expect(
      (await request(app).patch(`/api/v1/reports/exceptions/${insertId}/resolve`).set(otherAdmin))
        .status,
    ).toBe(404);
    const resolved = await request(app)
      .patch(`/api/v1/reports/exceptions/${insertId}/resolve`)
      .set(admin);
    expect(resolved.status).toBe(200);
    expect(resolved.body.data).toMatchObject({
      resolved_flag: 'Y',
      resolved_by: expect.any(String),
    });
    expect(resolved.body.data.resolved_at).toMatch(/^\d{4}-\d{2}-\d{2} /);
    const again = await request(app)
      .patch(`/api/v1/reports/exceptions/${insertId}/resolve`)
      .set(admin);
    expect(again.body.data.resolved_at).toBe(resolved.body.data.resolved_at);
    expect((await get('/exceptions?resolved=N', admin)).body.data).toEqual([]);
  });
});

describe('report access', () => {
  it('operators need REPORT_FLAG = Y and can never resolve exceptions', async () => {
    const denied = await get(`/collection-summary${RANGE}`, operator);
    expect(denied.status).toBe(403);
    await save(
      'md_setting',
      { user_id: null, setting_name: 'REPORT_FLAG', setting_value: 'Y', setting_type: 'G' },
      { flag: 'I' },
      A,
    );
    expect((await get(`/collection-summary${RANGE}`, operator)).status).toBe(200);
    expect(
      (await request(app).patch('/api/v1/reports/exceptions/1/resolve').set(operator)).status,
    ).toBe(403);
  });

  it('REPORT_PWD_FLAG = Y requires the report password header', async () => {
    const cust = await seedCustomer();
    const CA = adminOf(cust);
    await save(
      'md_setting',
      { user_id: null, setting_name: 'REPORT_PWD_FLAG', setting_value: 'Y', setting_type: 'G' },
      { flag: 'I' },
      CA,
    );
    await save(
      'md_setting',
      {
        user_id: null,
        setting_name: 'REPORT_PWD',
        setting_value: await hashPassword('r3port'),
        setting_type: 'G',
      },
      { flag: 'I' },
      CA,
    );
    const auth = bearer(
      (await tokensFor(app, await seedUser(cust, { type: 'A' }), 'pwd-admin')).access,
    );

    const missing = await get(`/gst${RANGE}`, auth);
    expect(missing.status).toBe(403);
    expect(missing.body.code).toBe('REPORT_PASSWORD_REQUIRED');
    expect((await get(`/gst${RANGE}`, auth).set('X-Report-Password', 'wrong')).status).toBe(403);
    expect((await get(`/gst${RANGE}`, auth).set('X-Report-Password', 'r3port')).status).toBe(200);
  });

  it('super admin must pick the customer; tenants only see their own data', async () => {
    expect((await get(`/collection-summary${RANGE}`, superAuth)).status).toBe(400);
    const sa = await get(`/collection-summary${RANGE}&customer_id=${cid}`, superAuth);
    expect(sa.body.meta.totals.net_amt).toBe('170.00');
    const other = await get(`/collection-summary${RANGE}&customer_id=${cid}`, otherAdmin);
    expect(other.body.meta.totals.net_amt).toBe('0.00');
  });

  it('validates the date range', async () => {
    expect((await get('/gst?from=2026-05-03&to=2026-05-01', admin)).status).toBe(400);
    expect((await get('/gst?from=2025-01-01&to=2026-05-01', admin)).status).toBe(400);
    expect((await get('/gst?from=2026-05-01', admin)).status).toBe(400);
    expect((await request(app).get(`/api/v1/reports/gst${RANGE}`)).status).toBe(401);
  });
});

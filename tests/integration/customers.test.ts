import request from 'supertest';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { createApp } from '../../src/app';
import { DEFAULT_SETTINGS } from '../../src/config/defaults';
import { fetch, fetchOne } from '../../src/core/db/dml';
import { closeDb, db } from '../../src/core/db/pool';
import { loadRegistry } from '../../src/core/db/registry';
import { toIstDate } from '../../src/core/utils/time';
import { bearer, loginAs, seedCustomer, seedUser, tokensFor } from '../helpers/auth';
import { superAdmin } from '../helpers/context';

const app = createApp();
let sa: Record<string, string>;
let admin: Record<string, string>;

const customerBody = (over: Record<string, unknown> = {}) => ({
  customer_name: 'Sealdah Cloak Room',
  customer_addr: 'Sealdah Station, Kolkata',
  state: 'West Bengal',
  pin_code: '700014',
  mobile_no: '9830012345',
  gst_no: '19abcde1234f1z5',
  upi_id: 'sealdah@okaxis',
  user_limit: 3,
  ...over,
});

beforeAll(async () => {
  await loadRegistry(db);
  sa = bearer((await tokensFor(app, await seedUser(null, { type: 'S' }), 'c-sa')).access);
  admin = bearer(
    (await tokensFor(app, await seedUser(await seedCustomer(), { type: 'A' }), 'c-admin')).access,
  );
});
afterAll(async () => {
  await closeDb();
});

async function createCustomer() {
  const res = await request(app).post('/api/v1/customers').set(sa).send(customerBody());
  expect(res.status).toBe(201);
  return res.body.data.customer_id as number;
}

describe('customers', () => {
  it('create applies default settings and default rate in the same transaction', async () => {
    const res = await request(app).post('/api/v1/customers').set(sa).send(customerBody());
    expect(res.status).toBe(201);
    expect(res.body.data).toMatchObject({
      gst_no: '19ABCDE1234F1Z5',
      user_limit: 3,
      active_flag: 'Y',
      renewal_dt: null,
    });
    const id = res.body.data.customer_id;

    const settings = await fetch('md_setting', { where: { customer_id: id } }, superAdmin);
    expect(settings.rows.map((r) => r.setting_name).sort()).toEqual(
      DEFAULT_SETTINGS.map((s) => s.setting_name).sort(),
    );
    const rates = await fetch('md_rate_dtls', { where: { customer_id: id } }, superAdmin);
    expect(rates.rows).toEqual([
      expect.objectContaining({
        block_hours: 24,
        first_block_rate: '50.00',
        next_block_rate: '20.00',
        effective_from: toIstDate(),
      }),
    ]);
  });

  it('validates fields; renewal_dt is not writable directly', async () => {
    const bad = await request(app)
      .post('/api/v1/customers')
      .set(sa)
      .send(customerBody({ pin_code: '123', gst_no: 'X', upi_id: 'nope' }));
    expect(bad.status).toBe(400);
    expect(bad.body.errors.map((e: { path: string }) => e.path).sort()).toEqual([
      'gst_no',
      'pin_code',
      'upi_id',
    ]);

    const id = await createCustomer();
    const patch = await request(app)
      .patch(`/api/v1/customers/${id}`)
      .set(sa)
      .send({ renewal_dt: '2099-01-01', active_flag: 'N' });
    expect(patch.body.data).toMatchObject({ renewal_dt: null, active_flag: 'N' });
  });

  it('is super-admin only', async () => {
    expect((await request(app).get('/api/v1/customers').set(admin)).status).toBe(403);
    expect(
      (await request(app).post('/api/v1/customers').set(admin).send(customerBody())).status,
    ).toBe(403);
    expect((await request(app).get('/api/v1/customers/1/registrations').set(admin)).status).toBe(
      403,
    );
    expect((await request(app).get('/api/v1/customers/1/summary').set(admin)).status).toBe(403);
  });

  it('summary aggregates users, registrations, payments and luggage', async () => {
    const id = await createCustomer();
    await seedUser(id, { type: 'A' });
    await seedUser(id, { allow: 'N' });
    await request(app)
      .post(`/api/v1/customers/${id}/registrations`)
      .set(sa)
      .send({
        reg_no: `R-${id}-S`,
        start_date: '2026-01-01',
        end_date: '2026-12-31',
        user_count: 3,
      });
    await request(app)
      .post(`/api/v1/customers/${id}/payments`)
      .set(sa)
      .send({ payment_dt: '2026-01-05', base_amt: '1000', cgst: '90', sgst: '90', pay_mode: 'B' });

    const res = await request(app).get(`/api/v1/customers/${id}/summary`).set(sa);
    expect(res.status).toBe(200);
    expect(res.body.data).toMatchObject({
      customer: { customer_id: id },
      users: { total: 2, allowed: 1, admins: 1, operators: 1, active_sessions: 0 },
      latest_registration: { reg_no: `R-${id}-S` },
      payments: { count: 1, total_net: '1180.00', last_payment_dt: '2026-01-05' },
      luggage: { total: 0, in_store: 0 },
    });
    expect((await request(app).get('/api/v1/customers/999999/summary').set(sa)).status).toBe(404);
  });
});

describe('registrations', () => {
  it('latest end_date drives renewal_dt (also after update and delete), enabling login', async () => {
    const id = await createCustomer();
    const user = await seedUser(id);
    expect((await loginAs(app, user, 'r-dev')).body.code).toBe('SUBSCRIPTION_EXPIRED');

    const base = `/api/v1/customers/${id}/registrations`;
    const r1 = await request(app)
      .post(base)
      .set(sa)
      .send({
        reg_no: `R-${id}-1`,
        start_date: '2026-01-01',
        end_date: '2099-03-31',
        user_count: 3,
      });
    expect(r1.status).toBe(201);
    expect(r1.body.data.customer_id).toBe(id);
    const r2 = await request(app)
      .post(base)
      .set(sa)
      .send({
        reg_no: `R-${id}-2`,
        start_date: '2025-01-01',
        end_date: '2025-12-31',
        user_count: 2,
      });
    expect(r2.status).toBe(201);

    const customer = async () =>
      (await request(app).get(`/api/v1/customers/${id}`).set(sa)).body.data;
    expect((await customer()).renewal_dt).toBe('2099-03-31');
    expect((await loginAs(app, user, 'r-dev')).status).toBe(200);

    await request(app)
      .patch(`${base}/${r1.body.data.reg_id}`)
      .set(sa)
      .send({ end_date: '2099-06-30' });
    expect((await customer()).renewal_dt).toBe('2099-06-30');

    await request(app).delete(`${base}/${r1.body.data.reg_id}`).set(sa);
    expect((await customer()).renewal_dt).toBe('2025-12-31');

    const list = await request(app).get(base).set(sa);
    expect(list.body.data.map((r: { reg_no: string }) => r.reg_no)).toEqual([`R-${id}-2`]);
  });

  it('validates dates, unique reg_no, parent existence and isolation between customers', async () => {
    const id = await createCustomer();
    const other = await createCustomer();
    const base = `/api/v1/customers/${id}/registrations`;
    const backwards = await request(app)
      .post(base)
      .set(sa)
      .send({ reg_no: `B-${id}`, start_date: '2026-02-01', end_date: '2026-01-01', user_count: 1 });
    expect(backwards.status).toBe(400);

    const ok = await request(app)
      .post(base)
      .set(sa)
      .send({ reg_no: `U-${id}`, start_date: '2026-01-01', end_date: '2026-12-31', user_count: 1 });
    expect(
      (
        await request(app)
          .post(`/api/v1/customers/${other}/registrations`)
          .set(sa)
          .send({
            reg_no: `U-${id}`,
            start_date: '2026-01-01',
            end_date: '2026-12-31',
            user_count: 1,
          })
      ).status,
    ).toBe(409);
    expect(
      (
        await request(app)
          .patch(`${base}/${ok.body.data.reg_id}`)
          .set(sa)
          .send({ start_date: '2027-01-01' })
      ).status,
    ).toBe(400);

    expect(
      (
        await request(app)
          .get(`/api/v1/customers/${other}/registrations/${ok.body.data.reg_id}`)
          .set(sa)
      ).status,
    ).toBe(404);
    expect((await request(app).get('/api/v1/customers/999999/registrations').set(sa)).status).toBe(
      404,
    );
  });
});

describe('customer payments', () => {
  it('computes net_amt, rejects a mismatch and a registration of another customer', async () => {
    const id = await createCustomer();
    const other = await createCustomer();
    const base = `/api/v1/customers/${id}/payments`;
    const reg = await request(app)
      .post(`/api/v1/customers/${id}/registrations`)
      .set(sa)
      .send({ reg_no: `P-${id}`, start_date: '2026-01-01', end_date: '2026-12-31', user_count: 1 });
    const otherReg = await request(app)
      .post(`/api/v1/customers/${other}/registrations`)
      .set(sa)
      .send({
        reg_no: `P-${other}`,
        start_date: '2026-01-01',
        end_date: '2026-12-31',
        user_count: 1,
      });

    const ok = await request(app).post(base).set(sa).send({
      reg_id: reg.body.data.reg_id,
      payment_dt: '2026-01-02',
      base_amt: '847.46',
      cgst: '76.27',
      sgst: '76.27',
      pay_mode: 'U',
      transaction_no: 'UTR123',
    });
    expect(ok.status).toBe(201);
    expect(ok.body.data).toMatchObject({ net_amt: '1000.00', igst: '0.00', customer_id: id });

    const mismatch = await request(app)
      .post(base)
      .set(sa)
      .send({ payment_dt: '2026-01-02', base_amt: '100', net_amt: '118.01', pay_mode: 'C' });
    expect(mismatch.status).toBe(400);
    expect(mismatch.body.errors[0].path).toBe('net_amt');

    const foreign = await request(app).post(base).set(sa).send({
      reg_id: otherReg.body.data.reg_id,
      payment_dt: '2026-01-02',
      base_amt: '1',
      pay_mode: 'C',
    });
    expect(foreign.status).toBe(400);

    const upd = await request(app)
      .patch(`${base}/${ok.body.data.payment_id}`)
      .set(sa)
      .send({ igst: '10' });
    expect(upd.body.data.net_amt).toBe('1010.00');
    const updBad = await request(app)
      .patch(`${base}/${ok.body.data.payment_id}`)
      .set(sa)
      .send({ base_amt: '1', net_amt: '5' });
    expect(updBad.status).toBe(400);
  });
});

describe('own customer (/customers/me, A4)', () => {
  let ownId: number;
  let otherId: number;
  let a: Record<string, string>;
  let o: Record<string, string>;

  beforeAll(async () => {
    ownId = await seedCustomer({ userLimit: 5 });
    otherId = await seedCustomer();
    a = bearer((await tokensFor(app, await seedUser(ownId, { type: 'A' }), 'me-admin')).access);
    o = bearer((await tokensFor(app, await seedUser(ownId, { type: 'O' }), 'me-op')).access);
  });

  it('GET returns the login customer shape to A and O, and is refused to S', async () => {
    const res = await request(app).get('/api/v1/customers/me').set(a);
    expect(res.status).toBe(200);
    expect(res.body.data.customer_id).toBe(ownId);
    expect(Object.keys(res.body.data).sort()).toEqual(
      [
        'customer_id',
        'customer_name',
        'customer_addr',
        'state',
        'pin_code',
        'mobile_no',
        'email',
        'gst_no',
        'upi_id',
        'renewal_dt',
        'user_limit',
        'active_flag',
      ].sort(),
    );
    expect((await request(app).get('/api/v1/customers/me').set(o)).status).toBe(200);
    expect((await request(app).get('/api/v1/customers/me').set(sa)).status).toBe(403);
  });

  it('station admin updates own contact, GSTIN and UPI details', async () => {
    const res = await request(app).patch('/api/v1/customers/me').set(a).send({
      customer_name: 'Howrah Cloak Room',
      customer_addr: 'Platform 1, Howrah',
      state: 'West Bengal',
      pin_code: '711101',
      mobile_no: '9830098300',
      email: 'howrah@example.in',
      gst_no: '19aaaaa1111a1z1',
      upi_id: 'howrah@okhdfc',
    });
    expect(res.status).toBe(200);
    expect(res.body.data).toMatchObject({
      customer_id: ownId,
      customer_name: 'Howrah Cloak Room',
      gst_no: '19AAAAA1111A1Z1',
      upi_id: 'howrah@okhdfc',
    });
    const row = await fetchOne('md_customer', { customer_id: ownId }, superAdmin);
    expect(row).toMatchObject({ gst_no: '19AAAAA1111A1Z1', sync_flag: 'N' });
    expect(row?.updated_by).toBeTruthy();

    // Devices refresh it through /auth/me (and the next login).
    const me = await request(app).get('/api/v1/auth/me').set(a);
    expect(me.body.data.customer.upi_id).toBe('howrah@okhdfc');
  });

  it('ignores licence fields and another customer id; empty or invalid bodies → 400', async () => {
    const res = await request(app).patch('/api/v1/customers/me').set(a).send({
      customer_id: otherId,
      user_limit: 999,
      active_flag: 'N',
      remarks: 'hack',
      renewal_dt: '2099-12-31',
      upi_id: 'station@oksbi',
    });
    expect(res.status).toBe(200);
    expect(res.body.data).toMatchObject({ customer_id: ownId, user_limit: 5, active_flag: 'Y' });
    const other = await fetchOne('md_customer', { customer_id: otherId }, superAdmin);
    expect(other?.upi_id).not.toBe('station@oksbi');

    const onlyLicence = await request(app)
      .patch('/api/v1/customers/me')
      .set(a)
      .send({ user_limit: 999 });
    expect(onlyLicence.status).toBe(400);
    expect(onlyLicence.body.code).toBe('VALIDATION_ERROR');

    const bad = await request(app)
      .patch('/api/v1/customers/me')
      .set(a)
      .send({ gst_no: '123', pin_code: '7000', upi_id: 'no-at-sign' });
    expect(bad.status).toBe(400);
    expect(bad.body.errors.map((e: { path: string }) => e.path).sort()).toEqual([
      'gst_no',
      'pin_code',
      'upi_id',
    ]);
  });

  it('operators and the super admin cannot PATCH /customers/me', async () => {
    const body = { customer_name: 'Nope' };
    expect((await request(app).patch('/api/v1/customers/me').set(o).send(body)).status).toBe(403);
    expect((await request(app).patch('/api/v1/customers/me').set(sa).send(body)).status).toBe(403);
    expect((await request(app).patch('/api/v1/customers/me').send(body)).status).toBe(401);
  });
});

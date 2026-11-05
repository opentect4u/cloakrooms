import request from 'supertest';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { createApp } from '../../src/app';
import { fetch, fetchOne, save } from '../../src/core/db/dml';
import { closeDb, db } from '../../src/core/db/pool';
import { loadRegistry } from '../../src/core/db/registry';
import { loginAs, seedCustomer, seedUser } from '../helpers/auth';
import { superAdmin } from '../helpers/context';

const app = createApp();
type Agent = ReturnType<typeof request.agent>;

const PNG_1PX = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAAAAAA6fptVAAAACklEQVR4nGNgAAAAAgABSK+kcQAAAABJRU5ErkJggg==',
  'base64',
);

let agent: Agent;
let csrf: string;

beforeAll(async () => {
  await loadRegistry(db);
  const sa = await seedUser(null, { type: 'S' });
  agent = request.agent(app);
  const login = await agent.get('/admin/login');
  csrf = /name="_csrf" value="([^"]+)"/.exec(login.text)?.[1] ?? '';
  const res = await agent
    .post('/admin/login')
    .type('form')
    .send({ _csrf: csrf, user_id: sa.user_id, password: sa.password });
  expect(res.status).toBe(303);
});
afterAll(async () => {
  await closeDb();
});

const post = (path: string, body: Record<string, string> = {}) =>
  agent
    .post(path)
    .type('form')
    .send({ _csrf: csrf, ...body });

/** Flash text of the next rendered page. */
async function flashAfter(location: string) {
  const page = await agent.get(location);
  return /<div class="app-toast[^>]*>[\s\S]*?<span>([^<]*)<\/span>/.exec(page.text)?.[1] ?? '';
}

async function newCustomer(over: Record<string, string> = {}) {
  const name = `Panel Station ${Math.random().toString(36).slice(2, 8)}`;
  const res = await post('/admin/customers', {
    customer_name: name,
    customer_addr: 'Platform 1',
    state: 'West Bengal',
    pin_code: '700001',
    mobile_no: '9830000000',
    gst_no: '19abcde1234f1z5',
    upi_id: 'station@okaxis',
    user_limit: '3',
    active_flag: 'Y',
    email: '',
    ...over,
  });
  expect(res.status).toBe(303);
  const id = Number(/\/admin\/customers\/(\d+)$/.exec(res.headers.location ?? '')?.[1]);
  return { id, name };
}

describe('customers: list, create, overview, edit, delete', () => {
  it('creates a customer with defaults and shows it everywhere', async () => {
    const bad = await post('/admin/customers', { customer_name: '', pin_code: '12' });
    expect(bad.status).toBe(400);
    expect(bad.text).toContain('is-invalid');
    expect(bad.text).toContain('Please check the highlighted fields');
    expect(bad.text).toContain('Required');
    expect(bad.text).not.toContain('received undefined');

    const { id, name } = await newCustomer();
    const row = await fetchOne('md_customer', { customer_id: id }, superAdmin);
    expect(row).toMatchObject({
      gst_no: '19ABCDE1234F1Z5',
      email: null,
      created_by: expect.any(String),
    });
    const { rows: settings } = await fetch(
      'md_setting',
      { where: { customer_id: id } },
      superAdmin,
    );
    expect(settings.length).toBeGreaterThan(5);
    const { rows: rates } = await fetch('md_rate_dtls', { where: { customer_id: id } }, superAdmin);
    expect(rates).toHaveLength(1);

    const list = await agent.get(`/admin/customers?q=${encodeURIComponent(name)}`);
    expect(list.status).toBe(200);
    expect(list.text).toContain(name);
    const overview = await agent.get(`/admin/customers/${id}`);
    expect(overview.status).toBe(200);
    expect(overview.text).toContain('No subscription yet');
    expect(overview.text).toContain('19ABCDE1234F1Z5');
  });

  it('filters the list by status and survives bad query values', async () => {
    const res = await agent.get('/admin/customers?status=bogus&sort=nope&page=-3');
    expect(res.status).toBe(200);
    const none = await agent.get('/admin/customers?status=none');
    expect(none.status).toBe(200);
  });

  it('edits details and validates them', async () => {
    const { id } = await newCustomer();
    const bad = await post(`/admin/customers/${id}`, { pin_code: '12', customer_name: 'X' });
    expect(bad.status).toBe(400);
    expect(bad.text).toContain('6-digit PIN code');
    const ok = await post(`/admin/customers/${id}`, { upi_id: 'new@oksbi', active_flag: 'N' });
    expect(ok.status).toBe(303);
    const row = await fetchOne('md_customer', { customer_id: id }, superAdmin);
    expect(row).toMatchObject({ upi_id: 'new@oksbi', active_flag: 'N' });
  });

  it('deletes only after the exact name is typed', async () => {
    const { id, name } = await newCustomer();
    await post(`/admin/customers/${id}/delete`, { confirm_name: 'wrong' });
    expect(await fetchOne('md_customer', { customer_id: id }, superAdmin)).not.toBeNull();
    const res = await post(`/admin/customers/${id}/delete`, { confirm_name: name });
    expect(res.headers.location).toBe('/admin/customers');
    expect(await fetchOne('md_customer', { customer_id: id }, superAdmin)).toBeNull();
    expect((await agent.get(`/admin/customers/${id}`)).status).toBe(404);
  });

  it('404s on unknown customers, tabs and ids', async () => {
    expect((await agent.get('/admin/customers/999999999')).status).toBe(404);
    expect((await agent.get('/admin/customers/abc')).status).toBe(404);
    const { id } = await newCustomer();
    expect((await agent.get(`/admin/customers/${id}/nope`)).status).toBe(404);
    expect((await agent.get(`/admin/customers/${id}/rates/abc/edit`)).status).toBe(404);
  });
});

describe('customer tabs (generic resources)', () => {
  it('tariff: add, list with badge, edit, remove', async () => {
    const { id } = await newCustomer();
    const form = await agent.get(`/admin/customers/${id}/rates/new`);
    expect(form.status).toBe(200);
    const res = await post(`/admin/customers/${id}/rates`, {
      effective_from: '2099-01-01',
      block_hours: '12',
      first_block_rate: '40',
      next_block_rate: '15.50',
    });
    expect(res.status).toBe(303);
    const list = await agent.get(`/admin/customers/${id}/rates`);
    expect(list.text).toContain('Scheduled');
    expect(list.text).toContain('In force');
    const { rows } = await fetch(
      'md_rate_dtls',
      { where: { customer_id: id, effective_from: '2099-01-01' } },
      superAdmin,
    );
    const rateId = Number(rows[0]?.rate_id);
    expect((await agent.get(`/admin/customers/${id}/rates/${rateId}/edit`)).text).toContain(
      '15.50',
    );
    await post(`/admin/customers/${id}/rates/${rateId}`, { next_block_rate: '18' });
    expect((await fetchOne('md_rate_dtls', { rate_id: rateId }, superAdmin))?.next_block_rate).toBe(
      '18.00',
    );
    const bad = await post(`/admin/customers/${id}/rates/${rateId}`, { first_block_rate: 'abc' });
    expect(bad.status).toBe(400);
    await post(`/admin/customers/${id}/rates/${rateId}/delete`);
    expect(await fetchOne('md_rate_dtls', { rate_id: rateId }, superAdmin)).toBeNull();
  });

  it('cannot reach another customer’s rows through this customer’s URLs', async () => {
    const a = await newCustomer();
    const b = await newCustomer();
    const { rows } = await fetch('md_rate_dtls', { where: { customer_id: b.id } }, superAdmin);
    const foreign = Number(rows[0]?.rate_id);
    expect((await agent.get(`/admin/customers/${a.id}/rates/${foreign}/edit`)).status).toBe(404);
    await post(`/admin/customers/${a.id}/rates/${foreign}/delete`);
    expect(await fetchOne('md_rate_dtls', { rate_id: foreign }, superAdmin)).not.toBeNull();
    await post(`/admin/customers/${a.id}/rates/${foreign}`, { block_hours: '6' });
    expect((await fetchOne('md_rate_dtls', { rate_id: foreign }, superAdmin))?.block_hours).toBe(
      24,
    );
  });

  it('shifts and roster: validation, selects from the same customer only', async () => {
    const { id } = await newCustomer();
    const same = await post(`/admin/customers/${id}/shifts`, {
      shift_name: 'Bad',
      from_time: '06:00',
      to_time: '06:00',
    });
    expect(same.status).toBe(400);
    expect(same.text).toContain('Must differ from from_time');
    await post(`/admin/customers/${id}/shifts`, {
      shift_name: 'Night',
      from_time: '22:00',
      to_time: '06:00',
    });
    expect((await agent.get(`/admin/customers/${id}/shifts`)).text).toContain('Crosses midnight');

    const user = await seedUser(id, { type: 'O', code: 'R01' });
    const { rows: shifts } = await fetch('md_shift', { where: { customer_id: id } }, superAdmin);
    const form = await agent.get(`/admin/customers/${id}/shift-assignments/new`);
    expect(form.text).toContain(`User ${user.user_id} (R01)`);
    const ok = await post(`/admin/customers/${id}/shift-assignments`, {
      user_id: String(user.id),
      shift_id: String(shifts[0]?.shift_id),
      effective_from: '2026-11-01',
    });
    expect(ok.status).toBe(303);
    expect((await agent.get(`/admin/customers/${id}/shift-assignments`)).text).toContain(
      'Night · 22:00–06:00',
    );

    const other = await seedUser(await seedCustomer(), { type: 'O' });
    const foreign = await post(`/admin/customers/${id}/shift-assignments`, {
      user_id: String(other.id),
      shift_id: String(shifts[0]?.shift_id),
      effective_from: '2026-11-01',
    });
    expect(foreign.status).toBe(400);
  });

  it('subscriptions set the renewal date; payments compute the net', async () => {
    const { id } = await newCustomer();
    await post(`/admin/customers/${id}/registrations`, {
      reg_no: `REG-${id}`,
      start_date: '2026-01-01',
      end_date: '2099-12-31',
      user_count: '3',
    });
    expect((await fetchOne('md_customer', { customer_id: id }, superAdmin))?.renewal_dt).toBe(
      '2099-12-31',
    );
    expect((await agent.get(`/admin/customers/${id}/registrations`)).text).toContain('Active');
    const backwards = await post(`/admin/customers/${id}/registrations`, {
      reg_no: `REG-${id}-B`,
      start_date: '2026-05-01',
      end_date: '2026-01-01',
      user_count: '1',
    });
    expect(backwards.status).toBe(400);

    const pay = await post(`/admin/customers/${id}/payments`, {
      payment_dt: '2026-11-05',
      pay_mode: 'U',
      base_amt: '1000',
      cgst: '90',
      sgst: '90',
      reg_id: '',
    });
    expect(pay.status).toBe(303);
    const { rows } = await fetch('td_customer_billing', { where: { customer_id: id } }, superAdmin);
    expect(rows[0]?.net_amt).toBe('1180.00');
    expect((await agent.get(`/admin/customers/${id}/payments`)).text).toContain('₹1,180.00');
    expect((await agent.get(`/admin/customers/${id}`)).text).toContain('REG-');
  });

  it('receipt layout: lines, preview and PNG logo', async () => {
    const { id } = await newCustomer();
    await post(`/admin/customers/${id}/header-footer`, {
      line_type: 'H',
      line_order: '1',
      narration: 'Howrah Cloak Room',
      in_flag: 'Y',
      out_flag: 'N',
      report_flag: 'N',
    });
    await post(`/admin/customers/${id}/header-footer`, {
      line_type: 'F',
      narration: 'Thank you',
      in_flag: 'N',
      out_flag: 'Y',
      report_flag: 'N',
    });
    const page = await agent.get(`/admin/customers/${id}/header-footer`);
    expect(page.status).toBe(200);
    expect(page.text).toContain('Howrah Cloak Room');
    expect(page.text).toContain('paper-58');

    const notPng = await agent
      .post(`/admin/customers/${id}/header-footer/logo`)
      .field('_csrf', csrf)
      .attach('logo', Buffer.from('GIF89a not a png'), 'logo.png');
    expect(notPng.status).toBe(303);
    expect(await flashAfter(notPng.headers.location ?? '')).toContain('Only PNG');

    const big = await agent
      .post(`/admin/customers/${id}/header-footer/logo`)
      .field('_csrf', csrf)
      .attach('logo', Buffer.alloc(210 * 1024, 1), 'big.png');
    expect(await flashAfter(big.headers.location ?? '')).toContain('200 KB or smaller');

    const ok = await agent
      .post(`/admin/customers/${id}/header-footer/logo`)
      .field('_csrf', csrf)
      .attach('logo', PNG_1PX, 'logo.png');
    expect(await flashAfter(ok.headers.location ?? '')).toBe('Logo uploaded.');
    expect((await agent.get(`/admin/customers/${id}/header-footer`)).text).toContain(
      'data:image/png;base64,',
    );

    const noCsrf = await agent
      .post(`/admin/customers/${id}/header-footer/logo`)
      .field('_csrf', 'forged')
      .attach('logo', PNG_1PX, 'logo.png');
    expect(noCsrf.status).toBe(303);

    await post(`/admin/customers/${id}/header-footer/logo/delete`);
    expect(
      await fetchOne('md_header_footer', { customer_id: id, line_type: 'L' }, superAdmin),
    ).toBeNull();
  });
});

describe('customer users', () => {
  it('creates, edits, resets, blocks, signs out and deletes station users', async () => {
    const { id } = await newCustomer({ user_limit: '2' });
    const base = `/admin/customers/${id}/users`;
    const created = await post(base, {
      user_name: 'Ravi Kumar',
      user_id: `ravi.${id}`,
      user_code: 'u03',
      user_type: 'O',
      password: 'Temp1234',
      allow_flag: 'Y',
    });
    expect(created.status).toBe(303);
    const user = await fetchOne('md_user', { user_id: `ravi.${id}` }, superAdmin, undefined, {
      columns: ['id', 'customer_id', 'user_code', 'must_change_pwd'],
    });
    expect(user).toMatchObject({ customer_id: id, user_code: 'U03', must_change_pwd: 'Y' });
    const uid = Number(user?.id);

    const dup = await post(base, {
      user_name: 'Dup',
      user_id: `dup.${id}`,
      user_code: 'U03',
      user_type: 'O',
      password: 'Temp1234',
      allow_flag: 'Y',
    });
    expect(dup.status).toBe(400);
    expect(dup.text).toContain('already exists');

    const superType = await post(base, {
      user_name: 'Sneaky',
      user_id: `sneaky.${id}`,
      user_code: 'U09',
      user_type: 'S',
      password: 'Temp1234',
    });
    expect(superType.status).toBe(400);

    await post(base, {
      user_name: 'Second',
      user_id: `second.${id}`,
      user_code: 'U04',
      user_type: 'A',
      password: 'Temp1234',
      allow_flag: 'Y',
    });
    const third = await post(base, {
      user_name: 'Third',
      user_id: `third.${id}`,
      user_code: 'U05',
      user_type: 'O',
      password: 'Temp1234',
      allow_flag: 'Y',
    });
    expect(third.status).toBe(400);
    expect(third.text).toContain('User limit');

    const list = await agent.get(base);
    expect(list.text).toContain('Ravi Kumar');
    expect(list.text).toContain('2</strong> of 2 licensed users');

    await post(`${base}/${uid}`, { user_name: 'Ravi K', user_code: 'U07', user_type: 'O' });
    expect((await fetchOne('md_user', { id: uid }, superAdmin))?.user_name).toBe('Ravi K');

    const weak = await post(`${base}/${uid}/password`, { password: 'short' });
    expect(weak.status).toBe(400);
    await post(`${base}/${uid}/password`, { password: 'NewTemp123' });
    // The user can sign in with the new password (session on a phone for the next checks).
    await save(
      'md_customer',
      { renewal_dt: '2099-12-31' },
      { flag: 'U', where: { customer_id: id } },
      superAdmin,
    );
    expect(
      (await loginAs(app, { user_id: `ravi.${id}`, password: 'NewTemp123' }, 'ph-1')).status,
    ).toBe(200);
    expect((await agent.get(base)).text).toContain('Online');

    const out = await post(`${base}/${uid}/force-logout`);
    expect(await flashAfter(out.headers.location ?? '')).toContain('was signed out');

    await post(`${base}/${uid}/allow`, { allow_flag: 'N' });
    expect((await fetchOne('md_user', { id: uid }, superAdmin))?.allow_flag).toBe('N');
    await post(`${base}/${uid}/allow`, { allow_flag: 'Y' });
    expect((await fetchOne('md_user', { id: uid }, superAdmin))?.allow_flag).toBe('Y');

    await post(`${base}/${uid}/delete`);
    expect(await fetchOne('md_user', { id: uid }, superAdmin)).toBeNull();

    const other = await seedUser(await seedCustomer(), { type: 'O' });
    expect((await agent.get(`${base}/${other.id}/edit`)).status).toBe(404);
    await post(`${base}/${other.id}/delete`);
    expect(await fetchOne('md_user', { id: other.id }, superAdmin)).not.toBeNull();
  });
});

describe('customer settings', () => {
  it('saves customer-wide values with validation, secrets hashed, per-user overrides', async () => {
    const { id } = await newCustomer();
    const base = `/admin/customers/${id}/settings`;
    expect((await agent.get(base)).status).toBe(200);

    await post(`${base}/GST_TYPE`, { setting_value: 'I' });
    const gst = await fetchOne(
      'md_setting',
      { customer_id: id, setting_name: 'GST_TYPE', user_id: null },
      superAdmin,
    );
    expect(gst?.setting_value).toBe('I');

    const bad = await post(`${base}/SYNC_INTERVAL_MIN`, { setting_value: '5' });
    expect(await flashAfter(bad.headers.location ?? '')).toContain('between 15 and 1440');

    await post(`${base}/FORCE_ONLINE_DAYS`, { setting_value: '7' });
    expect(
      (
        await fetchOne(
          'md_setting',
          { customer_id: id, setting_name: 'FORCE_ONLINE_DAYS' },
          superAdmin,
        )
      )?.setting_value,
    ).toBe('7');

    await post(`${base}/REPORT_PWD`, { setting_value: 'secret12' });
    const pwd = await fetchOne(
      'md_setting',
      { customer_id: id, setting_name: 'REPORT_PWD' },
      superAdmin,
    );
    expect(String(pwd?.setting_value)).toMatch(/^\$argon2id\$/);
    expect((await agent.get(base)).text).not.toContain('argon2');
    expect((await post(`${base}/NOPE`, { setting_value: 'x' })).status).toBe(404);

    const user = await seedUser(id, { type: 'O' });
    await post(`${base}/overrides`, {
      user_id: String(user.id),
      setting_name: 'DEFAULT_PAY_MODE',
      setting_value: 'U',
    });
    await post(`${base}/overrides`, {
      user_id: String(user.id),
      setting_name: 'DEFAULT_PAY_MODE',
      setting_value: 'C',
    });
    const { rows } = await fetch(
      'md_setting',
      { where: { customer_id: id, user_id: user.id } },
      superAdmin,
    );
    expect(rows).toHaveLength(1);
    expect(rows[0]?.setting_value).toBe('C');
    const page = await agent.get(base);
    expect(page.text).toContain('Default payment mode');

    const foreignUser = await seedUser(await seedCustomer(), { type: 'O' });
    const foreign = await post(`${base}/overrides`, {
      user_id: String(foreignUser.id),
      setting_name: 'QR_FLAG',
      setting_value: 'N',
    });
    expect(await flashAfter(foreign.headers.location ?? '')).toContain(
      'does not exist for this customer',
    );

    await post(`${base}/overrides/${String(rows[0]?.setting_id)}/delete`);
    expect(
      await fetchOne('md_setting', { setting_id: Number(rows[0]?.setting_id) }, superAdmin),
    ).toBeNull();
  });
});

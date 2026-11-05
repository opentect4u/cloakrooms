import { randomUUID } from 'node:crypto';
import type { RowDataPacket } from 'mysql2/promise';
import request from 'supertest';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { createApp } from '../../src/app';
import { fetchOne, raw, save } from '../../src/core/db/dml';
import { closeDb, db } from '../../src/core/db/pool';
import { loadRegistry } from '../../src/core/db/registry';
import { bearer, seedCustomer, seedUser, tokensFor, type SeededUser } from '../helpers/auth';
import { adminOf, superAdmin } from '../helpers/context';

const app = createApp();
type Agent = ReturnType<typeof request.agent>;

let agent: Agent;
let csrf: string;
let me: SeededUser;
let cid: number;
let otherCid: number;
let customerName: string;
const pnr = `PNR${randomUUID().slice(0, 8).toUpperCase()}`;
const storedTrans = randomUUID();
const outTrans = randomUUID();
const badTrans = randomUUID();

beforeAll(async () => {
  await loadRegistry(db);
  cid = await seedCustomer({ userLimit: 10 });
  otherCid = await seedCustomer();
  customerName = String(
    (await fetchOne('md_customer', { customer_id: cid }, superAdmin))?.customer_name,
  );
  const A = adminOf(cid);
  await save(
    'md_rate_dtls',
    {
      block_hours: 24,
      first_block_rate: '50',
      next_block_rate: '20',
      effective_from: '2026-01-01',
    },
    { flag: 'I' },
    A,
  );
  const op = await seedUser(cid, { code: 'P01' });
  const device = bearer((await tokensFor(app, op, 'p-dev')).access);
  const [proof] = await raw<RowDataPacket[]>(
    "SELECT id_proof_id FROM md_id_proof WHERE id_proof_name = 'Passport' AND created_by = 'system'",
  );
  const bag = (trans: string, n: number, over: Record<string, unknown> = {}) => ({
    trans_id: trans,
    local_version: 1,
    receipt_no: `P01-${String(n).padStart(5, '0')}`,
    receipt_dt: '2026-05-01',
    user_id_in: op.id,
    device_id_in: 'p-dev',
    date_time_in: '2026-05-01 04:30:00',
    pasn_name: `Traveller ${n}`,
    pasn_mob_no: `98000000${n}0`,
    pnr_no: n === 1 ? pnr : `X${n}`,
    luggage_qty: 1,
    id_proof_id: proof?.id_proof_id,
    id_number: `P${n}`,
    out_flag: 'N',
    ...over,
  });
  const out = {
    user_id_out: op.id,
    device_id_out: 'p-dev',
    date_time_out: '2026-05-02 04:31:00',
    out_flag: 'Y',
  };
  const bill = (trans: string, n: number, net = '70.00') => ({
    trans_id: trans,
    local_version: 1,
    bill_no: `P01-${String(n).padStart(5, '0')}`,
    bill_dt: '2026-05-02',
    user_id: op.id,
    device_id: 'p-dev',
    luggage_qty: 1,
    days_charged: 2,
    block_hours: 24,
    first_block_rate: '50.00',
    next_block_rate: '20.00',
    base_amt: net,
    penal_amt: '0.00',
    net_amt: net,
    taxable_amt: '59.32',
    gst_pct: '18.00',
    cgst: '5.34',
    sgst: '5.34',
    igst: '0.00',
    gst_flag: 'Y',
    pay_mode: 'U',
    bill_printed: 'Y',
  });
  const res = await request(app)
    .post('/api/v1/sync/push')
    .set(device)
    .send({
      luggage: [bag(storedTrans, 1), bag(outTrans, 2, out), bag(badTrans, 3, out)],
      receipts: [bill(outTrans, 1), bill(badTrans, 2, '99.00')],
    });
  expect(res.status).toBe(200);

  me = await seedUser(null, { type: 'S' });
  agent = request.agent(app);
  const login = await agent.get('/admin/login');
  csrf = /name="_csrf" value="([^"]+)"/.exec(login.text)?.[1] ?? '';
  await agent
    .post('/admin/login')
    .type('form')
    .send({ _csrf: csrf, user_id: me.user_id, password: me.password });
});
afterAll(async () => {
  await closeDb();
});

const post = (path: string, body: Record<string, string> = {}) =>
  agent
    .post(path)
    .type('form')
    .send({ _csrf: csrf, ...body });

async function flashAfter(location: string) {
  const page = await agent.get(location);
  return /<div class="app-toast[^>]*>[\s\S]*?<span>([^<]*)<\/span>/.exec(page.text)?.[1] ?? '';
}

describe('reports', () => {
  it('asks for a customer first', async () => {
    const res = await agent.get('/admin/reports');
    expect(res.status).toBe(200);
    expect(res.text).toContain('Choose a customer');
  });

  it('runs the reports for one customer with totals', async () => {
    const range = `customer_id=${cid}&from=2026-05-01&to=2026-05-03`;
    const summary = await agent.get(`/admin/reports?report=collection-summary&${range}`);
    expect(summary.status).toBe(200);
    expect(summary.text).toContain('02 May 2026');
    expect(summary.text).toContain('₹169.00'); // 70 + 99 (the mismatched bill is kept)
    const register = await agent.get(`/admin/reports?report=check-in-register&${range}`);
    expect(register.text).toContain('Traveller 1');
    expect(register.text).toContain('In store');
    const store = await agent.get(`/admin/reports?report=bags-in-store&customer_id=${cid}`);
    expect(store.text).toContain('Traveller 1');
    expect(store.text).not.toContain('Traveller 2');
    const gst = await agent.get(`/admin/reports?report=gst&${range}`);
    expect(gst.text).toContain('18%');
  });

  it('shows validation problems instead of failing', async () => {
    const res = await agent.get(`/admin/reports?customer_id=${cid}&from=2026-05-05&to=2026-05-01`);
    expect(res.status).toBe(400);
    expect(res.text).toContain('Must be on or after from');
    const unknown = await agent.get(`/admin/reports?report=nope&customer_id=999999999`);
    expect(unknown.status).toBe(200);
    expect(unknown.text).toContain('Choose a customer');
  });
});

describe('sync exceptions', () => {
  it('summarises per customer, lists with payload and resolves in the right tenant', async () => {
    const summary = await agent.get('/admin/exceptions');
    expect(summary.text).toContain(customerName);
    const list = await agent.get(`/admin/exceptions?customer_id=${cid}`);
    expect(list.status).toBe(200);
    expect(list.text).toContain('AMOUNT_MISMATCH');
    expect(list.text).toContain('What the device sent');
    const [row] = await raw<RowDataPacket[]>(
      "SELECT id FROM td_sync_exception WHERE trans_id = ? AND resolved_flag = 'N'",
      [badTrans],
    );
    const id = Number(row?.id);

    // Wrong customer in the form: the tenant context makes it a 404, nothing changes.
    expect(
      (await post(`/admin/exceptions/${id}/resolve`, { customer_id: String(otherCid) })).status,
    ).toBe(404);
    expect((await fetchOne('td_sync_exception', { id }, superAdmin))?.resolved_flag).toBe('N');

    const ok = await post(`/admin/exceptions/${id}/resolve`, {
      customer_id: String(cid),
      back: `/admin/exceptions?customer_id=${cid}&resolved=Y`,
    });
    expect(ok.headers.location).toBe(`/admin/exceptions?customer_id=${cid}&resolved=Y`);
    const after = await fetchOne('td_sync_exception', { id }, superAdmin);
    expect(after).toMatchObject({ resolved_flag: 'Y', resolved_by: me.user_id });
    expect((await agent.get(ok.headers.location ?? '')).text).toContain('Reviewed');

    const evil = await post(`/admin/exceptions/${id}/resolve`, {
      customer_id: String(cid),
      back: '//evil.example',
    });
    expect(evil.headers.location).toBe(`/admin/exceptions?customer_id=${cid}`);
  });
});

describe('bag search', () => {
  it('finds a bag everywhere or per customer and shows its journey', async () => {
    const all = await agent.get(`/admin/bags?q=${pnr}`);
    expect(all.status).toBe(200);
    expect(all.text).toContain('Traveller 1');
    expect(all.text).toContain(customerName);
    const other = await agent.get(`/admin/bags?q=${pnr}&customer_id=${otherCid}`);
    expect(other.text).not.toContain('Traveller 1');
    const out = await agent.get(`/admin/bags?customer_id=${cid}&status=out`);
    expect(out.text).toContain('Traveller 2');
    expect(out.text).not.toContain('Traveller 1');

    const detail = await agent.get(`/admin/bags/${outTrans}`);
    expect(detail.status).toBe(200);
    expect(detail.text).toContain('Checked out');
    expect(detail.text).toContain('₹70.00');
    expect(detail.text).toContain('Taxable value');
    expect((await agent.get(`/admin/bags/${storedTrans}`)).text).toContain('No bill yet');
    expect((await agent.get('/admin/bags/not-a-uuid')).status).toBe(404);
    expect((await agent.get(`/admin/bags/${randomUUID()}`)).status).toBe(404);
  });
});

describe('platform masters', () => {
  it('GST rates: list, add, duplicate date refused, edit, remove', async () => {
    const list = await agent.get('/admin/gst');
    expect(list.status).toBe(200);
    expect(list.text).toContain('9.00 %');
    const add = await post('/admin/gst', {
      effective_from: '2098-04-01',
      cgst_pct: '6',
      sgst_pct: '6',
      igst_pct: '12',
    });
    expect(add.status).toBe(303);
    const dup = await post('/admin/gst', {
      effective_from: '2098-04-01',
      cgst_pct: '9',
      sgst_pct: '9',
      igst_pct: '18',
    });
    expect(dup.status).toBe(400);
    expect(dup.text).toContain('already exists');
    const [row] = await raw<RowDataPacket[]>(
      "SELECT gst_id FROM md_gst WHERE effective_from = '2098-04-01' AND is_deleted = 'N'",
    );
    const id = Number(row?.gst_id);
    await post(`/admin/gst/${id}`, { igst_pct: '13' });
    expect((await fetchOne('md_gst', { gst_id: id }, superAdmin))?.igst_pct).toBe('13.00');
    await post(`/admin/gst/${id}/delete`);
    expect(await fetchOne('md_gst', { gst_id: id }, superAdmin)).toBeNull();
  });

  it('ID proofs: add with masking, edit', async () => {
    const name = `Doc ${randomUUID().slice(0, 6)}`;
    await post('/admin/id-proofs', { id_proof_name: name, mask_flag: 'Y' });
    const row = await fetchOne('md_id_proof', { id_proof_name: name }, superAdmin);
    expect(row?.mask_flag).toBe('Y');
    await post(`/admin/id-proofs/${String(row?.id_proof_id)}`, { mask_flag: 'N' });
    expect((await fetchOne('md_id_proof', { id_proof_name: name }, superAdmin))?.mask_flag).toBe(
      'N',
    );
    expect((await agent.get('/admin/id-proofs')).text).toContain(name);
  });
});

describe('app releases', () => {
  it('publishes, validates and withdraws a release', async () => {
    // Low, optional version: must not disturb app-version.test.ts (latest = 11, mandatory 10).
    const bad = await post('/admin/releases', {
      version_code: '5',
      version_name: '0.5',
      apk_url: 'http://insecure/x.apk',
    });
    expect(bad.status).toBe(400);
    expect(bad.text).toContain('Https URL or /downloads/');
    const ok = await post('/admin/releases', {
      version_code: '5',
      version_name: '0.5-panel',
      apk_url: '/downloads/cloak-0.5.apk',
      mandatory_flag: 'N',
      release_notes: 'First panel release',
    });
    expect(ok.status).toBe(303);
    const list = await agent.get('/admin/releases');
    expect(list.text).toContain('0.5-panel');
    const dup = await post('/admin/releases', {
      version_code: '5',
      version_name: 'dup',
      apk_url: '/downloads/d.apk',
    });
    expect(dup.status).toBe(400);
    const row = await fetchOne('md_app_version', { version_code: 5 }, superAdmin);
    await post(`/admin/releases/${String(row?.id)}/withdraw`);
    expect(await fetchOne('md_app_version', { version_code: 5 }, superAdmin)).toBeNull();
    expect((await post('/admin/releases/999999999/withdraw')).status).toBe(404);
  });
});

describe('super admins', () => {
  it('manages other super admins, never itself, and offers no sign-out', async () => {
    const list = await agent.get('/admin/admins');
    expect(list.text).toContain('You');
    const loginId = `sa.${randomUUID().slice(0, 6)}`;
    const created = await post('/admin/admins', {
      user_name: 'Priya S',
      user_id: loginId,
      password: 'Temp1234x',
    });
    expect(created.status).toBe(303);
    const user = await fetchOne('md_user', { user_id: loginId }, superAdmin, undefined, {
      columns: ['id', 'user_type', 'customer_id', 'must_change_pwd'],
    });
    expect(user).toMatchObject({ user_type: 'S', customer_id: null, must_change_pwd: 'Y' });
    const id = Number(user?.id);
    expect(
      (await post('/admin/admins', { user_name: 'X', user_id: loginId, password: 'Temp1234x' }))
        .status,
    ).toBe(400);

    await post(`/admin/admins/${id}`, { user_name: 'Priya Sharma' });
    expect((await fetchOne('md_user', { id }, superAdmin))?.user_name).toBe('Priya Sharma');
    expect((await post(`/admin/admins/${id}/password`, { password: 'short' })).status).toBe(400);
    expect((await post(`/admin/admins/${id}/password`, { password: 'Another123' })).status).toBe(
      303,
    );
    await post(`/admin/admins/${id}/allow`, { allow_flag: 'N' });
    expect((await fetchOne('md_user', { id }, superAdmin))?.allow_flag).toBe('N');
    await post(`/admin/admins/${id}/allow`, { allow_flag: 'Y' });

    expect((await post(`/admin/admins/${id}/force-logout`)).status).toBe(404);
    const selfDelete = await post(`/admin/admins/${me.id}/delete`);
    expect(await flashAfter(selfDelete.headers.location ?? '')).toContain('Change password');
    expect(await fetchOne('md_user', { id: me.id }, superAdmin)).not.toBeNull();

    const station = await seedUser(cid, { type: 'O' });
    expect((await agent.get(`/admin/admins/${station.id}/edit`)).status).toBe(404);

    await post(`/admin/admins/${id}/delete`);
    expect(await fetchOne('md_user', { id }, superAdmin)).toBeNull();
  });
});

/**
 * Generates docs/FRONTEND_API_GUIDE.md: a realistic scenario is run against the real API on the
 * disposable test database, every request/response is recorded, and the guide is assembled from
 * those real samples (long values abbreviated). Run with `npm run docs:frontend`.
 */
import { randomUUID } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import jwt from 'jsonwebtoken';
import request from 'supertest';
import { afterAll, beforeAll, it } from 'vitest';
import { createApp } from '../../src/app';
import { env } from '../../src/config/env';
import { closeDb, db } from '../../src/core/db/pool';
import { loadRegistry } from '../../src/core/db/registry';
import { addDays, toIstDate, toUtcString } from '../../src/core/utils/time';
import { SETTING_META } from '../../src/admin/settingsMeta';
import { fromPaise, toPaise } from '../../src/core/utils/money';
import { computeBill } from '../../src/domain/billing';
import { balancePaise } from '../../src/domain/slabs';
import { SETTING_NAMES, SETTING_RULES } from '../../src/modules/settings/settings.schema';

const app = createApp();
const API = '/api/v1';

interface Call {
  method: string;
  path: string;
  body?: unknown;
  status: number;
  response: unknown;
}
const calls: Record<string, Call> = {};

type Opts = { token?: string; body?: unknown; headers?: Record<string, string> };

async function call(key: string, method: 'get' | 'post' | 'patch' | 'delete', path: string, o: Opts = {}) {
  let r = request(app)[method](`${API}${path}`);
  if (o.token) r = r.set('Authorization', `Bearer ${o.token}`);
  for (const [k, v] of Object.entries(o.headers ?? {})) r = r.set(k, v);
  const res = await (o.body !== undefined ? r.send(o.body as object) : r);
  calls[key] = { method: method.toUpperCase(), path: `${API}${path}`, body: o.body, status: res.status, response: res.body };
  return res;
}

const PNG_1PX = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAAAAAA6fptVAAAACklEQVR4nGNgAAAAAgABSK+kcQAAAABJRU5ErkJggg==',
  'base64',
);

beforeAll(async () => {
  await loadRegistry(db);
});
afterAll(async () => {
  await closeDb();
});

it('generates docs/FRONTEND_API_GUIDE.md', async () => {
  const today = toIstDate();
  const ids: Record<string, number | string> = {};

  // ---------------------------------------------------------------- public
  await call('health', 'get', '/health');
  await call('ready', 'get', '/ready');

  // ---------------------------------------------------------------- super admin: station setup
  const saLogin = await call('login_sa', 'post', '/auth/login', {
    body: { user_id: env.SUPER_ADMIN_USER_ID, password: env.SUPER_ADMIN_PASSWORD, device_id: 'admin-web-sa', app_version: 'web' },
  });
  const SA = saLogin.body.data.accessToken as string;

  const cust = await call('customer_create', 'post', '/customers', {
    token: SA,
    body: {
      customer_name: 'Howrah Cloak Room',
      customer_addr: 'Howrah Station, Platform 1',
      state: 'West Bengal',
      pin_code: '711101',
      mobile_no: '9830012345',
      email: 'ops@howrahcloak.in',
      gst_no: '19ABCDE1234F1Z5',
      upi_id: 'howrahcloak@okaxis',
      user_limit: 5,
    },
  });
  const cid = cust.body.data.customer_id as number;
  ids.cid = cid;
  await call('customer_list', 'get', '/customers?search=Howrah&limit=20', { token: SA });
  await call('customer_get', 'get', `/customers/${cid}`, { token: SA });
  await call('customer_update', 'patch', `/customers/${cid}`, { token: SA, body: { remarks: 'Pilot station' } });

  const reg = await call('reg_create', 'post', `/customers/${cid}/registrations`, {
    token: SA,
    body: { reg_no: 'REG-2026-0001', start_date: today, end_date: addDays(today, 364), user_count: 5, remarks: 'Annual plan' },
  });
  await call('reg_list', 'get', `/customers/${cid}/registrations`, { token: SA });
  await call('payment_create', 'post', `/customers/${cid}/payments`, {
    token: SA,
    body: { reg_id: reg.body.data.reg_id, payment_dt: today, base_amt: '5000.00', cgst: '450.00', sgst: '450.00', pay_mode: 'B', transaction_no: 'UTR123456789' },
  });
  await call('payment_list', 'get', `/customers/${cid}/payments`, { token: SA });

  await call('user_create_by_sa', 'post', '/users', {
    token: SA,
    body: { user_id: 'howrah.admin', user_name: 'Station Admin', user_code: 'A01', password: 'Admin@2026', user_type: 'A', customer_id: cid },
  });

  // ---------------------------------------------------------------- customer admin
  const aLogin = await call('login_admin', 'post', '/auth/login', {
    body: { user_id: 'howrah.admin', password: 'Admin@2026', device_id: 'admin-web-01', app_version: 'web' },
  });
  const A = aLogin.body.data.accessToken as string;
  // Created by the super admin → must_change_pwd = Y: everything but change-password is refused.
  await call('pwd_required', 'get', '/users', { token: A });
  await call('change_password', 'post', '/auth/change-password', {
    token: A,
    body: { current_password: 'Admin@2026', new_password: 'Admin@2027' },
  });
  await call('change_password_wrong', 'post', '/auth/change-password', {
    token: A,
    body: { current_password: 'Admin@2026', new_password: 'Admin@2028' },
  });

  // Station admin: own cloak room details (A4).
  await call('customer_me', 'get', '/customers/me', { token: A });
  await call('customer_me_update', 'patch', '/customers/me', {
    token: A,
    body: { customer_addr: 'Howrah Station, Platform 1, Howrah', gst_no: '19aabcs1234c1z5', upi_id: 'howrah.cloak@oksbi' },
  });
  await call('customer_me_empty', 'patch', '/customers/me', { token: A, body: { user_limit: 50 } });

  const op1 = await call('user_create', 'post', '/users', {
    token: A,
    body: { user_id: 'howrah.op1', user_name: 'Ravi Kumar', user_code: 'U03', password: 'Counter@123', user_type: 'O' },
  });
  const op1Id = op1.body.data.id as number;
  const op2 = await call('user_create_2', 'post', '/users', {
    token: A,
    body: { user_id: 'howrah.op2', user_name: 'Anita Das', user_code: 'U04', password: 'Counter@456', user_type: 'O' },
  });
  const op2Id = op2.body.data.id as number;
  await call('user_list', 'get', '/users?user_type=O&sort=user_name:asc', { token: A });
  await call('user_get', 'get', `/users/${op1Id}`, { token: A });
  await call('user_update', 'patch', `/users/${op1Id}`, { token: A, body: { user_name: 'Ravi Kumar Singh' } });
  await call('user_dup', 'post', '/users', {
    token: A,
    body: { user_id: 'howrah.op9', user_name: 'Dup', user_code: 'U03', password: 'Counter@999', user_type: 'O' },
  });

  // Rates: the customer got a default rate (24 h / 50 / 20) effective today; backdate it.
  const rates = await call('rate_list', 'get', '/rates', { token: A });
  const defaultRateId = rates.body.data[0].rate_id as number;
  await call('rate_update', 'patch', `/rates/${defaultRateId}`, { token: A, body: { effective_from: '2026-01-01' } });
  const futureRate = await call('rate_create', 'post', '/rates', {
    token: A,
    body: {
      effective_from: addDays(today, 60),
      rate_slabs: [
        { from_hour: 0, to_hour: 24, rate: '30.00' },
        { from_hour: 24, to_hour: 48, rate: '10.00' },
      ],
    },
  });
  await call('rate_delete', 'delete', `/rates/${futureRate.body.data.rate_id}`, { token: A });

  const morning = await call('shift_create', 'post', '/shifts', { token: A, body: { shift_name: 'Morning', from_time: '06:00', to_time: '14:00' } });
  const shiftId = morning.body.data.shift_id as number;
  await call('shift_create_night', 'post', '/shifts', { token: A, body: { shift_name: 'Night', from_time: '22:00', to_time: '06:00' } });
  await call('shift_list', 'get', '/shifts', { token: A });
  await call('shift_update', 'patch', `/shifts/${shiftId}`, { token: A, body: { to_time: '14:30' } });

  const assign = await call('assign_create', 'post', '/shift-assignments', {
    token: A,
    body: { user_id: op1Id, shift_id: shiftId, effective_from: today },
  });
  await call('assign_list', 'get', `/shift-assignments?user_id=${op1Id}`, { token: A });
  await call('assign_update', 'patch', `/shift-assignments/${assign.body.data.id}`, { token: A, body: { effective_from: addDays(today, 1) } });

  await call('hf_create', 'post', '/header-footer', { token: A, body: { line_type: 'H', line_order: 1, narration: 'HOWRAH CLOAK ROOM' } });
  const footer = await call('hf_create_f', 'post', '/header-footer', {
    token: A,
    body: { line_type: 'F', line_order: 1, narration: 'Thank you. Keep this receipt safe.' },
  });
  await call('hf_update', 'patch', `/header-footer/${footer.body.data.id}`, { token: A, body: { report_flag: 'Y' } });
  const logo = await request(app)
    .post(`${API}/header-footer/logo`)
    .set('Authorization', `Bearer ${A}`)
    .field('in_flag', 'Y')
    .field('out_flag', 'Y')
    .attach('logo', PNG_1PX, 'logo.png');
  calls.hf_logo = { method: 'POST', path: `${API}/header-footer/logo`, status: logo.status, response: logo.body };
  await call('hf_list', 'get', '/header-footer', { token: A });

  const settings = await call('setting_list', 'get', '/settings?limit=100', { token: A });
  const syncSetting = settings.body.data.find((s: { setting_name: string }) => s.setting_name === 'SYNC_INTERVAL_MIN');
  await call('setting_update', 'patch', `/settings/${syncSetting.setting_id}`, { token: A, body: { setting_value: '30' } });
  const override = await call('setting_create', 'post', '/settings', {
    token: A,
    body: { setting_name: 'QR_FLAG', setting_value: 'N', user_id: op2Id },
  });
  await call('setting_create_pwd', 'post', '/settings', { token: A, body: { setting_name: 'REPORT_PWD', setting_value: '4321' } });
  await call('setting_delete', 'delete', `/settings/${override.body.data.setting_id}`, { token: A });
  await call('setting_bad', 'post', '/settings', { token: A, body: { setting_name: 'SYNC_INTERVAL_MIN', setting_value: '5' } });

  await call('gst_list', 'get', '/gst', { token: A });
  const gst = await call('gst_create', 'post', '/gst', {
    token: SA,
    body: { cgst_pct: '9', sgst_pct: '9', igst_pct: '18', effective_from: '2030-04-01' },
  });
  await call('gst_delete', 'delete', `/gst/${gst.body.data.gst_id}`, { token: SA });
  const proofs = await call('idproof_list', 'get', '/id-proofs', { token: A });
  const aadhaarId = proofs.body.data.find((p: { id_proof_name: string }) => p.id_proof_name === 'Aadhaar Card').id_proof_id;
  const passportId = proofs.body.data.find((p: { id_proof_name: string }) => p.id_proof_name === 'Passport').id_proof_id;
  await call('idproof_create', 'post', '/id-proofs', { token: SA, body: { id_proof_name: 'Senior Citizen Card', mask_flag: 'N' } });

  // ---------------------------------------------------------------- counter device (operator)
  const oLogin = await call('login_op', 'post', '/auth/login', {
    body: { user_id: 'howrah.op1', password: 'Counter@123', device_id: 'POS-HWH-01', app_version: '1.0.0' },
  });
  let O = oLogin.body.data.accessToken as string;
  await call('login_conflict', 'post', '/auth/login', {
    body: { user_id: 'howrah.op1', password: 'Counter@123', device_id: 'POS-HWH-99', app_version: '1.0.0' },
  });
  await call('login_wrong', 'post', '/auth/login', {
    body: { user_id: 'howrah.op1', password: 'wrong-password', device_id: 'POS-HWH-01' },
  });
  await call('login_invalid', 'post', '/auth/login', { body: { user_id: 'howrah.op1' } });
  await call('me', 'get', '/auth/me', { token: O });
  await call('change_password_op1', 'post', '/auth/change-password', {
    token: O,
    body: { current_password: 'Counter@123', new_password: 'Counter@1234' },
  });
  const refreshed = await call('refresh', 'post', '/auth/refresh', { body: { refreshToken: oLogin.body.data.refreshToken } });
  O = refreshed.body.data.accessToken as string;
  await call('refresh_reuse_hint', 'get', '/auth/me', { token: oLogin.body.data.accessToken });

  const claims = jwt.decode(O) as Record<string, unknown>;
  const expired = jwt.sign(
    { cid: claims.cid, typ: claims.typ, sid: claims.sid, exp: Math.floor(Date.now() / 1000) - 60 },
    env.JWT_ACCESS_SECRET,
    { subject: String(claims.sub), issuer: 'cloak-room-billing', audience: 'access' },
  );
  await call('token_expired', 'get', '/auth/me', { token: expired });
  await call('forbidden', 'get', '/users', { token: O });

  // Bags: L1 still in store (Aadhaar → masked), L2 checked in 26 h ago and out 1 h ago (2 blocks).
  const inAt = new Date(Date.now() - 26 * 3_600_000);
  const outAt = new Date(Date.now() - 3_600_000);
  const l1 = {
    trans_id: randomUUID(),
    local_version: 1,
    receipt_no: 'U03-00001',
    receipt_dt: today,
    user_id_in: op1Id,
    device_id_in: 'POS-HWH-01',
    shift_id_in: shiftId,
    date_time_in: toUtcString(new Date(Date.now() - 2 * 3_600_000)),
    pasn_name: 'Suresh Patel',
    pasn_mob_no: '9876543210',
    pnr_no: '4521367890',
    luggage_qty: 2,
    id_proof_id: aadhaarId,
    id_number: '1234 5678 9012',
    out_flag: 'N',
  };
  const l2 = {
    trans_id: randomUUID(),
    local_version: 3,
    receipt_no: 'U03-00002',
    receipt_dt: toIstDate(inAt),
    user_id_in: op1Id,
    device_id_in: 'POS-HWH-01',
    shift_id_in: shiftId,
    date_time_in: toUtcString(inAt),
    pasn_name: 'Meera Iyer',
    pasn_mob_no: '9123456780',
    pnr_no: '8812345670',
    luggage_qty: 1,
    extra_lugage_qty: 1,
    adv_amt: '50.00',
    adv_hours: 24,
    id_proof_id: passportId,
    id_number: 'P1234567',
    user_id_out: op1Id,
    device_id_out: 'POS-HWH-01',
    shift_id_out: shiftId,
    date_time_out: toUtcString(outAt),
    out_flag: 'Y',
  };
  const bill = computeBill({
    luggage_qty: 1,
    date_time_in: l2.date_time_in,
    date_time_out: l2.date_time_out,
    tariff: { block_hours: 24, first_block_rate: '50.00', next_block_rate: '20.00' },
    penal_amt: '0',
    gst_pct: '18.00',
    gst_type: 'E',
  });
  const r2 = {
    trans_id: l2.trans_id,
    local_version: 1,
    bill_no: 'U03-00001',
    bill_dt: toIstDate(outAt),
    user_id: op1Id,
    device_id: 'POS-HWH-01',
    shift_id: shiftId,
    luggage_qty: 1,
    ...bill,
    adv_amt: '50.00',
    bal_amt: fromPaise(balancePaise(toPaise(bill.net_amt), toPaise('50.00'))),
    gst_flag: 'Y',
    grace_minutes: 0,
    gst_inclusive: 'Y',
    penalty_gst_flag: 'Y',
    pay_mode: 'U',
    upi_ref: '432198765012',
    bill_printed: 'Y',
  };
  const orphan = { ...r2, trans_id: randomUUID(), bill_no: 'U03-00002', local_version: 1 };
  await call('push', 'post', '/sync/push', { token: O, body: { luggage: [l1, l2], receipts: [r2, orphan] } });
  await call('push_repeat', 'post', '/sync/push', { token: O, body: { luggage: [l1, l2], receipts: [r2] } });

  const o2Login = await call('login_op2', 'post', '/auth/login', {
    body: { user_id: 'howrah.op2', password: 'Counter@456', device_id: 'POS-HWH-02', app_version: '1.0.0' },
  });
  const O2 = o2Login.body.data.accessToken as string;
  await call('change_password_op2', 'post', '/auth/change-password', {
    token: O2,
    body: { current_password: 'Counter@456', new_password: 'Counter@4567' },
  });
  const firstPull = await call('pull_first', 'get', '/sync/pull', { token: O2 });
  await call('pull_since', 'get', `/sync/pull?since=${encodeURIComponent(firstPull.body.data.server_time)}`, { token: O2 });
  const paged = await call('pull_page', 'get', `/sync/pull?since=${encodeURIComponent('2026-01-01 00:00:00')}&limit=1`, { token: O2 });
  if (paged.body.data.next_cursor) {
    await call('pull_cursor', 'get', `/sync/pull?limit=1&cursor=${paged.body.data.next_cursor}`, { token: O2 });
  }

  // Case 1 with the QR: op1 checks L3 in offline; op2 scans the receipt QR, checks it out and bills it
  // before op1 has synced. The QR has no check-in device / shift / ID proof.
  const in3 = new Date(Date.now() - 3 * 3_600_000);
  const out3 = new Date(Date.now() - 30 * 60_000);
  const l3 = {
    trans_id: randomUUID(),
    local_version: 1,
    receipt_no: 'U03-00003',
    receipt_dt: toIstDate(in3),
    user_id_in: op1Id,
    device_id_in: 'POS-HWH-01',
    shift_id_in: shiftId,
    date_time_in: toUtcString(in3),
    pasn_name: 'Arjun Sen',
    pasn_mob_no: '9000011111',
    pnr_no: '',
    luggage_qty: 1,
    id_proof_id: passportId,
    id_number: 'Z7654321',
    out_flag: 'N',
  };
  const l3FromQr = {
    trans_id: l3.trans_id,
    local_version: 1,
    receipt_no: l3.receipt_no,
    receipt_dt: l3.receipt_dt,
    user_id_in: l3.user_id_in,
    date_time_in: l3.date_time_in,
    luggage_qty: l3.luggage_qty,
    pasn_name: l3.pasn_name,
    pasn_mob_no: l3.pasn_mob_no,
    pnr_no: l3.pnr_no,
    user_id_out: op2Id,
    device_id_out: 'POS-HWH-02',
    shift_id_out: null,
    date_time_out: toUtcString(out3),
    out_flag: 'Y',
  };
  const r3 = {
    trans_id: l3.trans_id,
    local_version: 1,
    bill_no: 'U04-00001',
    bill_dt: toIstDate(out3),
    user_id: op2Id,
    device_id: 'POS-HWH-02',
    shift_id: null,
    luggage_qty: 1,
    ...computeBill({
      luggage_qty: 1,
      date_time_in: l3.date_time_in,
      date_time_out: l3FromQr.date_time_out,
      tariff: { block_hours: 24, first_block_rate: '50.00', next_block_rate: '20.00' },
      penal_amt: '0',
      gst_pct: '18.00',
      gst_type: 'E',
    }),
    gst_flag: 'Y',
    grace_minutes: 0,
    gst_inclusive: 'Y',
    penalty_gst_flag: 'Y',
    pay_mode: 'C',
    upi_ref: null,
    bill_printed: 'Y',
  };
  await call('push_qr_checkout', 'post', '/sync/push', { token: O2, body: { luggage: [l3FromQr], receipts: [r3] } });
  await call('push_checkin_after_qr', 'post', '/sync/push', { token: O, body: { luggage: [l3] } });
  await call('luggage_get_qr', 'get', `/luggage/${l3.trans_id}`, { token: O2 });

  await call('luggage_search', 'get', '/luggage?search=4521367890&status=in', { token: O2 });
  await call('luggage_get', 'get', `/luggage/${l2.trans_id}`, { token: O2 });
  await call('not_found', 'get', `/luggage/${randomUUID()}`, { token: O2 });

  // ---------------------------------------------------------------- reports (admin)
  const range = `from=${addDays(today, -1)}&to=${today}`;
  await call('rep_collection', 'get', `/reports/collection-summary?${range}`, { token: A });
  await call('rep_checkin', 'get', `/reports/check-in-register?${range}`, { token: A });
  await call('rep_checkout', 'get', `/reports/check-out-register?${range}`, { token: A });
  await call('rep_store', 'get', '/reports/bags-in-store', { token: A });
  await call('rep_gst', 'get', `/reports/gst?${range}`, { token: A });
  await call('rep_usershift', 'get', `/reports/user-shift-collection?${range}`, { token: A });
  await call('rep_unprinted', 'get', `/reports/unprinted-bills?${range}`, { token: A });
  const exc = await call('rep_exceptions', 'get', '/reports/exceptions?resolved=N', { token: A });
  await call('rep_resolve', 'patch', `/reports/exceptions/${exc.body.data[0].id}/resolve`, { token: A });
  await call('rep_op_denied', 'get', `/reports/gst?${range}`, { token: O2 });

  // ---------------------------------------------------------------- app version
  await call('app_publish', 'post', '/app/version', {
    token: SA,
    body: { version_code: 10, version_name: '1.0.0', apk_url: '/downloads/cloakroom-1.0.0.apk', mandatory_flag: 'Y', release_notes: 'First release' },
  });
  await call('app_check', 'get', '/app/version?current=9');
  await call('login_old_version', 'post', '/auth/login', {
    body: { user_id: 'howrah.op1', password: 'Counter@1234', device_id: 'POS-HWH-01', app_version: '0.9.0', version_code: 9 },
  });

  // ---------------------------------------------------------------- user administration
  await call('user_allow_n', 'patch', `/users/${op2Id}/allow`, { token: A, body: { allow_flag: 'N' } });
  await call('session_ended', 'get', '/auth/me', { token: O2 });
  await call('user_allow_y', 'patch', `/users/${op2Id}/allow`, { token: A, body: { allow_flag: 'Y' } });
  await call('user_reset_pwd', 'post', `/users/${op2Id}/reset-password`, { token: A, body: { password: 'NewPass@789' } });
  await call('user_force_logout', 'post', `/users/${op1Id}/force-logout`, { token: A });
  await call('push_after_force', 'post', '/sync/push', { token: O, body: { luggage: [], receipts: [] } });
  await call('logout', 'post', '/auth/logout', { token: O });
  const temp = await call('user_create_temp', 'post', '/users', {
    token: A,
    body: { user_id: 'howrah.temp', user_name: 'Temp', user_code: 'T01', password: 'Temp@1234', user_type: 'O', allow_flag: 'N' },
  });
  await call('user_delete', 'delete', `/users/${temp.body.data.id}`, { token: A });
  await call('customer_summary', 'get', `/customers/${cid}/summary`, { token: SA });

  // Rate limiting sample (login: 10 per minute per IP + user_id).
  for (let i = 0; i < 11; i++) {
    await call('rate_limited', 'post', '/auth/login', { body: { user_id: 'nobody', password: 'x', device_id: 'd' } });
  }

  writeFileSync(join('docs', 'FRONTEND_API_GUIDE.md'), render(calls));
}, 120_000);

// ====================================================================== rendering

const MAX_ITEMS = 2;

/** Shortens tokens, base64 and long lists so samples stay readable. */
function compact(v: unknown, maxItems = MAX_ITEMS): unknown {
  if (typeof v === 'string') {
    if (/^eyJ[\w-]+\.[\w-]+\.[\w-]+$/.test(v)) return `${v.slice(0, 20)}…(JWT)`;
    if (v.length > 60 && /^[A-Za-z0-9+/=]+$/.test(v)) return `${v.slice(0, 16)}…(base64)`;
    if (/^[A-Za-z0-9_-]{60,}$/.test(v)) return `${v.slice(0, 16)}…(opaque cursor)`;
    return v;
  }
  if (Array.isArray(v)) {
    const out = v.slice(0, maxItems).map((x) => compact(x, maxItems));
    if (v.length > maxItems) out.push(`… ${v.length - maxItems} more item(s) of the same shape`);
    return out;
  }
  if (v && typeof v === 'object') {
    return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, compact(x, maxItems)]));
  }
  return v;
}

const json = (v: unknown, maxItems = MAX_ITEMS) =>
  `\`\`\`json\n${JSON.stringify(compact(v, maxItems), null, 2)}\n\`\`\``;

type SampleOpts = { request?: boolean; label?: string; maxItems?: number };

function sample(key: string, opts: SampleOpts = {}) {
  const c = calls[key];
  if (!c) return `_(no sample captured for \`${key}\`)_`;
  const parts: string[] = [];
  if (opts.request !== false && c.body !== undefined) {
    parts.push(`Request body:\n${json(c.body, opts.maxItems)}`);
  }
  parts.push(`${opts.label ?? 'Response'} \`${c.status}\`:\n${json(c.response, opts.maxItems)}`);
  return parts.join('\n\n');
}

interface Endpoint {
  method: string;
  path: string;
  title: string;
  auth: string;
  notes?: string;
  query?: [string, string][];
  body?: [string, string][];
  samples: (string | [string, SampleOpts])[];
  errors?: string;
}

function endpoint(e: Endpoint) {
  const lines = [`#### \`${e.method} ${e.path}\` — ${e.title}`, '', `**Auth / roles:** ${e.auth}`];
  if (e.notes) lines.push('', e.notes);
  if (e.query?.length) {
    lines.push('', '| Query param | Meaning |', '|---|---|', ...e.query.map(([k, d]) => `| \`${k}\` | ${d} |`));
  }
  if (e.body?.length) {
    lines.push('', '| Body field | Rules |', '|---|---|', ...e.body.map(([k, d]) => `| \`${k}\` | ${d} |`));
  }
  for (const s of e.samples) {
    const [key, opts] = typeof s === 'string' ? [s, {}] : s;
    lines.push('', sample(key, opts));
  }
  if (e.errors) lines.push('', `**Errors:** ${e.errors}`);
  return lines.join('\n');
}

const LIST_QUERY: [string, string][] = [
  ['page', 'default 1'],
  ['limit', 'default 50, max 200'],
  ['sort', '`col:asc` / `col:desc`, comma-separated, whitelisted columns'],
  ['search', 'contains-match on the resource’s searchable columns'],
];

function crudBlock(o: {
  name: string;
  base: string;
  pk: string;
  auth: { read: string; write: string };
  notes?: string;
  filters?: [string, string][];
  sortable: string;
  searchable?: string;
  createBody: [string, string][];
  updateNote: string;
  samples: { create: string; list?: string; update?: string; del?: string; extra?: string[] };
}) {
  const query = [...LIST_QUERY];
  query[2] = ['sort', `one of: ${o.sortable}`];
  query[3] = ['search', o.searchable ? `contains-match on ${o.searchable}` : '—'];
  return [
    `### ${o.name}`,
    '',
    '| Method | Path | Purpose | Roles |',
    '|---|---|---|---|',
    `| GET | \`${o.base}\` | list (paginated) | ${o.auth.read} |`,
    `| GET | \`${o.base}/:id\` | one row | ${o.auth.read} |`,
    `| POST | \`${o.base}\` | create → \`201\` | ${o.auth.write} |`,
    `| PATCH | \`${o.base}/:id\` | partial update | ${o.auth.write} |`,
    `| DELETE | \`${o.base}/:id\` | soft delete → \`{ "${o.pk}": id }\` | ${o.auth.write} |`,
    '',
    o.notes ?? '',
    '',
    endpoint({
      method: 'POST',
      path: o.base,
      title: `create`,
      auth: o.auth.write,
      body: o.createBody,
      samples: [o.samples.create, ...(o.samples.extra ?? [])],
      errors: '`400` validation · `401` · `403` role · `409` duplicate',
    }),
    '',
    endpoint({
      method: 'GET',
      path: o.base,
      title: 'list',
      auth: o.auth.read,
      query: [...query, ...(o.filters ?? [])],
      samples: o.samples.list ? [o.samples.list] : [],
    }),
    '',
    `\`GET ${o.base}/:id\` returns \`data\` = one row of the same shape (or \`404\`).`,
    '',
    endpoint({
      method: 'PATCH',
      path: `${o.base}/:id`,
      title: 'update',
      auth: o.auth.write,
      notes: o.updateNote,
      samples: o.samples.update ? [o.samples.update] : [],
      errors: '`400` · `401` · `403` · `404` · `409`',
    }),
    ...(o.samples.del
      ? ['', endpoint({ method: 'DELETE', path: `${o.base}/:id`, title: 'soft delete', auth: o.auth.write, samples: [o.samples.del] })]
      : []),
  ].join('\n');
}

/** §8 table built from the code (SETTING_RULES + panel labels), so it never drifts. */
function settingsTable(): string {
  const code = (s: string) => '`' + s + '`';
  const rows = SETTING_NAMES.map((name) => {
    const rule = SETTING_RULES[name];
    const meta = SETTING_META[name];
    const input = meta.input;
    let values: string;
    if (input.kind === 'yn') values = 'Y / N';
    else if (input.kind === 'select') values = input.options.map(([v, l]) => `${code(v)} ${l}`).join(', ');
    else if (input.kind === 'int') values = `${input.min}–${input.max}${input.suffix ? ' ' + input.suffix : ''}`;
    else if (input.kind === 'amount') values = '0.00–99999.99';
    else if (input.kind === 'percent') values = '0.00–28.00 (%)';
    else values = '4–64 characters';
    const def = rule.default === null ? '—' : code(rule.default);
    const scope = rule.scope === 'customer' ? 'customer' : 'per user';
    return `| ${code(name)} | ${meta.group} | ${values} | ${def} | ${scope} | ${meta.server ? 'yes' : ''} | ${meta.label}. ${meta.help} |`;
  });
  return [
    '| Key | Group | Values | Default | Scope | Server | Meaning |',
    '|---|---|---|---|---|---|---|',
    ...rows,
  ].join('\n');
}

function render(c: Record<string, Call>) {
  const pull = c.pull_first?.response as { data?: { server_time?: string } } | undefined;
  return `# Cloak Room Billing API — Frontend Integration Guide

> **For the frontend developer's AI coding agent (Claude Code).** Paste or place this file in the
> frontend repository (e.g. as \`API_GUIDE.md\` and reference it from the frontend \`CLAUDE.md\`).
> It is the complete contract: every endpoint, its payload, a **real response sample** (captured
> from the running API by \`npm run docs:frontend\` in the backend repo — long tokens/base64 are
> shortened with \`…\`), status codes, and the rules the app must follow.
> Machine-readable spec: \`docs/openapi.json\` (OpenAPI 3.0) / Swagger UI at \`<baseUrl>/api/docs\`.

## 0. Instructions for the agent

1. **Never invent endpoints, fields or values.** Use only what is in this file. If something is
   missing, ask the developer instead of guessing.
2. All JSON endpoints are under \`<baseUrl>/api/v1\`. Make \`baseUrl\` configurable
   (dev: \`http://localhost:4000\`). Send \`Content-Type: application/json\`.
3. Every response has the **same envelope** (§2). Always read \`success\` and \`code\`, never only
   the HTTP status.
4. Implement auth exactly as in §3 (token refresh, one session per user, force-logout).
5. The Android POS app is **offline-first**: implement sync exactly as in §6 and billing
   arithmetic exactly as in §7 — the server recomputes every bill and flags any difference.
6. Times on the wire are **UTC** \`YYYY-MM-DD HH:mm:ss\`; show users IST (UTC+05:30).
   Money is a decimal **string** (\`"70.00"\`) — never use floating point for money.
7. Build a typed API client from the shapes below (one function per endpoint, typed request/response).

---

## 1. Who uses what

| Role (\`user_type\`) | Who | App | Can use |
|---|---|---|---|
| \`S\` | Synergic super admin (no customer) | admin web | customers, registrations, payments, users of any customer, masters/settings of any customer (§5, pass \`customer_id\`), GST, ID proofs, app releases, reports (pass \`customer_id\`) |
| \`A\` | Customer (station) admin | admin web / app | users, rates, shifts, shift assignments, header/footer + logo, settings, reports, exceptions, luggage lookup, sync |
| \`O\` | Counter operator | Android POS | login, sync push/pull, luggage lookup, reports only if \`REPORT_FLAG = Y\` |

**Tenant rule:** the customer is always taken from the token. Never send \`customer_id\` as a
customer user (it is ignored). A super admin sends \`customer_id\` where a create or report needs it.

---

## 2. Envelope, status codes and conventions

Success:
\`\`\`json
{ "success": true, "message": "OK", "data": { }, "meta": { "page": 1, "limit": 50, "total": 3 }, "requestId": "uuid" }
\`\`\`
Error (\`data\` is always \`null\`; \`code\` tells you what happened; \`errors\` has details):
\`\`\`json
{ "success": false, "message": "Validation failed", "code": "VALIDATION_ERROR", "data": null,
  "errors": [ { "location": "body", "path": "password", "message": "Invalid input: expected string, received undefined" } ],
  "requestId": "uuid" }
\`\`\`

| HTTP | \`code\` | Meaning → what the app should do |
|---|---|---|
| 200 | — | OK |
| 201 | — | Created (POST create) |
| 400 | \`VALIDATION_ERROR\` | Body/query/params invalid → show \`errors[].path\` + \`message\` next to fields |
| 400 | \`BAD_REQUEST\` / \`INVALID_JSON\` | Malformed request / referenced record missing → fix the request |
| 401 | \`UNAUTHORIZED\` | No/invalid token or wrong login → go to login |
| 401 | \`TOKEN_EXPIRED\` | Access token expired → call \`POST /auth/refresh\` once, retry the request |
| 401 | \`TOKEN_REUSED\` | Old refresh token re-used → session revoked → go to login |
| 401 | \`SESSION_ENDED\` | Logged out / replaced / **force-logged-out** → POS: push pending rows (allowed), then logout and show login |
| 403 | \`FORBIDDEN\` | Role not allowed, user blocked or customer inactive → show message, hide the feature |
| 403 | \`SUBSCRIPTION_EXPIRED\` | Customer subscription ended (\`errors.renewal_dt\`) → block login, ask to contact Synergic |
| 403 | \`REPORT_PASSWORD_REQUIRED\` | Report password needed/wrong → prompt, send \`X-Report-Password\` header |
| 403 | \`PASSWORD_CHANGE_REQUIRED\` | \`user.must_change_pwd = Y\` → show the change-password screen (\`POST /auth/change-password\`) |
| 403 | \`OUTSIDE_SHIFT\` | login of a counter user outside their shift (\`SHIFT_LOGIN_FLAG = Y\`); \`errors = { shift_name, from_time, to_time, opens_at }\` (IST \`HH:mm\`) → "Sign-in opens at …" |
| 404 | \`NOT_FOUND\` | Missing, or belongs to another customer |
| 409 | \`CONFLICT\` | Duplicate unique value (\`errors.key\`, e.g. \`md_user.uq_user_code\`) |
| 409 | \`SESSION_ACTIVE\` | User already logged in on another device (\`errors.session_started_at\`, masked \`errors.device_id\`) |
| 409 | \`USER_LIMIT_REACHED\` | All licensed seats used (\`errors.user_limit\`) |
| 426 | \`UPDATE_REQUIRED\` | Login from an app older than a mandatory release → show \`errors.latest\` (version, \`apk_url\`) and block login |
| 413 | \`PAYLOAD_TOO_LARGE\` | Body too big (JSON 1 MB, sync 5 MB, logo 200 KB) → split / shrink |
| 429 | \`RATE_LIMITED\` | Too many requests → back off (see \`RateLimit-*\` headers) |
| 500 | \`INTERNAL_ERROR\` | Server bug → show generic error, log \`requestId\` |
| 503 | \`SERVICE_UNAVAILABLE\` | DB busy/unavailable → retry with back-off |

**Lists:** \`?page=1&limit=50\` (max 200) → \`meta: { page, limit, total }\`. Sorting \`?sort=col:asc\`
(only whitelisted columns, else 400). \`?search=\` is a contains-match. Deleted rows never appear in lists.

**Other conventions**
- Times: UTC \`YYYY-MM-DD HH:mm:ss\`. Business dates (\`receipt_dt\`, \`bill_dt\`, report \`from/to\`) are IST dates \`YYYY-MM-DD\`.
- Flags are \`"Y"\` / \`"N"\`. Money: 2-decimal strings (send \`"50.00"\` or \`50\`; you receive \`"50.00"\`).
- Optional header \`X-Request-Id\` (8–64 chars \`[A-Za-z0-9_-]\`) is echoed back for log correlation.
- Deletes are always **soft** (\`is_deleted = "Y"\`).
- Rate limits per minute: login 10 per IP+user_id · sync 120 per user · reports 30 per user · everything else 300 per user.

Rate-limit sample (11th login attempt within a minute):
${json(c.rate_limited?.response)}

---

## 3. Authentication & sessions

**Flow**
1. \`POST /auth/login\` → store \`accessToken\` (~12 h) and \`refreshToken\` (~30 d) securely
   (Android: EncryptedSharedPreferences). Also store the offline payload (\`masters\`, \`settings\`,
   \`counters\`, \`server_time\`) for the POS.
2. Send \`Authorization: Bearer <accessToken>\` on every protected call.
3. On \`401 TOKEN_EXPIRED\`: call \`POST /auth/refresh\` **once** (single-flight; queue other calls), store
   **both** new tokens, retry. The old pair stops working immediately.
4. \`device_id\`: a stable per-install id (Android ID). Logging in again from the **same** \`device_id\`
   replaces the old session; another device gets \`409 SESSION_ACTIVE\` until the user logs out there
   or an admin force-logs them out.
5. \`401 SESSION_ENDED\` on a POS: if the device still has unsynced rows, call \`POST /sync/push\` (still
   allowed after force-logout), then \`POST /auth/logout\`, clear tokens, show login.
6. Logout: POS must push pending rows first, then \`POST /auth/logout\`.
7. **First login / after an admin reset:** \`user.must_change_pwd = "Y"\` (set when an admin creates the
   user or resets the password). Until \`POST /auth/change-password\` succeeds, every other call returns
   \`403 PASSWORD_CHANGE_REQUIRED\` (only change-password, logout and me work). The same token keeps
   working after the change. A force-logged-out old device can still push its pending rows.
8. **App version (Android):** send \`version_code\` (the APK versionCode) at login. If a newer live release
   is mandatory → \`426 UPDATE_REQUIRED\` with \`errors.latest\` (download \`apk_url\`). Web clients omit it.

${endpoint({
  method: 'POST',
  path: '/api/v1/auth/login',
  title: 'log in (counter device)',
  auth: 'public',
  body: [
    ['user_id', 'login id, required'],
    ['password', 'required'],
    ['device_id', 'stable device id, 1–100 chars, required'],
    ['app_version', 'optional, ≤ 20 chars (logged)'],
    ['version_code', 'Android versionCode (integer); refused with 426 when a newer mandatory release exists'],
  ],
  notes: 'Response contains everything the POS needs offline. `customer`/`counters` are `null` for a super admin; `settings` is the customer settings merged with the user’s own overrides (see §8).',
  samples: ['login_op'],
  errors: '`400` validation · `401` wrong user/password · `403 FORBIDDEN` blocked user / inactive customer · `403 SUBSCRIPTION_EXPIRED` · `409 SESSION_ACTIVE` · `409 USER_LIMIT_REACHED` · `426 UPDATE_REQUIRED` · `429`',
})}

Super admin login (no customer):
${sample('login_sa', { request: false })}

\`409 SESSION_ACTIVE\` (same user, another device):
${sample('login_conflict', { request: false })}

\`401\` wrong password:
${sample('login_wrong', { request: false })}

\`400 VALIDATION_ERROR\` (missing fields):
${sample('login_invalid', { request: false })}

\`426 UPDATE_REQUIRED\` (versionCode 9, release 10 is mandatory):
${sample('login_old_version')}

${endpoint({
  method: 'POST',
  path: '/api/v1/auth/change-password',
  title: 'change own password',
  auth: 'any logged-in user (also while must_change_pwd = Y)',
  body: [
    ['current_password', 'required'],
    ['new_password', '8–128 chars, at least one letter and one digit, different from the current one'],
  ],
  notes: 'Clears `must_change_pwd`. The current session and token stay valid. Rate limit 10/min per user.',
  samples: ['change_password'],
  errors: '`400 VALIDATION_ERROR` (`current_password` wrong, weak or unchanged new password) · `401` · `429`',
})}

Any other call while \`must_change_pwd = Y\`:
${sample('pwd_required', { request: false })}

Wrong current password:
${sample('change_password_wrong', { request: false })}

${endpoint({
  method: 'POST',
  path: '/api/v1/auth/refresh',
  title: 'rotate tokens',
  auth: 'public (send the refresh token in the body)',
  body: [['refreshToken', 'required']],
  samples: ['refresh'],
  errors: '`401 UNAUTHORIZED` invalid · `401 SESSION_ENDED` session over · `401 TOKEN_REUSED` (session revoked) · `403` blocked / `SUBSCRIPTION_EXPIRED`',
})}

After a refresh the previous access token is rejected:
${sample('refresh_reuse_hint', { request: false })}

\`401 TOKEN_EXPIRED\` (call refresh):
${sample('token_expired', { request: false })}

${endpoint({ method: 'GET', path: '/api/v1/auth/me', title: 'current user & session', auth: 'any logged-in user', samples: ['me'] })}

${endpoint({
  method: 'POST',
  path: '/api/v1/auth/logout',
  title: 'log out',
  auth: 'any logged-in user (also allowed after force-logout)',
  samples: ['logout'],
})}

\`401 SESSION_ENDED\` (e.g. user was blocked / force-logged-out):
${sample('session_ended', { request: false })}

---

## 4. Public endpoints

${endpoint({ method: 'GET', path: '/api/v1/health', title: 'liveness', auth: 'public', samples: ['health'] })}

${endpoint({ method: 'GET', path: '/api/v1/ready', title: 'readiness (DB reachable)', auth: 'public', samples: ['ready'], errors: '`503 SERVICE_UNAVAILABLE`' })}

${endpoint({
  method: 'GET',
  path: '/api/v1/app/version',
  title: 'update check (call before login)',
  auth: 'public',
  query: [['current', 'installed versionCode (optional) → enables `update_available` / `mandatory`']],
  notes: 'If `mandatory` is true the app must update before use; download from `apk_url` (absolute `https://…` or a path under the API host such as `/downloads/cloakroom-1.0.0.apk`).',
  samples: ['app_check'],
})}

\`GET /downloads/<file>.apk\` — public APK download (\`application/vnd.android.package-archive\`, cached 1 day).

---

## 5. Customer admin (A) and super admin (S) screens

### Users

| Method | Path | Purpose |
|---|---|---|
| GET | \`/api/v1/users\` | list (query: page, limit, sort = id/user_id/user_name/user_code/user_type/created_at, search on user_id/user_name/user_code, \`user_type\`, \`allow_flag\`, \`customer_id\` (S only)) |
| GET | \`/api/v1/users/:id\` | one user |
| POST | \`/api/v1/users\` | create |
| PATCH | \`/api/v1/users/:id\` | update name / code / type |
| DELETE | \`/api/v1/users/:id\` | soft delete (also force-logs the user out) |
| POST | \`/api/v1/users/:id/force-logout\` | end the user’s session (device may still push, then logout) |
| POST | \`/api/v1/users/:id/reset-password\` | new password (also force-logs out) |
| PATCH | \`/api/v1/users/:id/allow\` | block (\`N\`, force-logs out) / allow (\`Y\`) |

Roles: A (own customer) and S. An admin cannot block or delete their own account (400).

${endpoint({
  method: 'POST',
  path: '/api/v1/users',
  title: 'create user',
  auth: 'A, S',
  body: [
    ['user_id', 'login id, 3–100 chars `[A-Za-z0-9._@-]`, unique globally'],
    ['user_name', '1–100 chars'],
    ['user_code', 'receipt/bill prefix (e.g. `U03`), 1–10 letters/digits, upper-cased, unique per customer; required for A/O, absent for S'],
    ['password', '8–128 chars, at least one letter and one digit'],
    ['user_type', '`A` or `O` (`S` only by a super admin)'],
    ['allow_flag', '`Y` (default) / `N`'],
    ['customer_id', 'super admin only (required for A/O users)'],
  ],
  notes: 'Allowed users (allow_flag Y) of a customer may not exceed its `user_limit` → `409 USER_LIMIT_REACHED`.',
  samples: ['user_create'],
  errors: '`400` · `403` (A creating S) · `409 CONFLICT` duplicate user_id / user_code · `409 USER_LIMIT_REACHED`',
})}

Duplicate \`user_code\`:
${sample('user_dup', { request: false })}

List:
${sample('user_list')}

Update (\`user_name\`, \`user_code\`, \`user_type\` A/O — all optional):
${sample('user_update')}

Block / allow (\`{ "allow_flag": "N" | "Y" }\`):
${sample('user_allow_n')}

Reset password (\`{ "password": "…" }\`) → number of sessions ended:
${sample('user_reset_pwd')}

Force logout:
${sample('user_force_logout')}

Delete:
${sample('user_delete')}

${crudBlock({
  name: 'Rates (tariff per bag)',
  base: '/api/v1/rates',
  pk: 'rate_id',
  auth: { read: 'A, S', write: 'A, S' },
  notes: 'Versioned by `effective_from`: the bill uses the latest rate with `effective_from ≤ receipt_dt`. One row per date per customer (`409` on duplicates). A new customer gets 0–24 h ₹50 + 24–48 h ₹20 automatically. `rate_slabs` is the tariff (§7); send it, or the older two-rate fields (then the server builds the slabs). The server keeps both forms in step: `block_hours` = first slab length, `first_block_rate` / `next_block_rate` = first / second slab rate.',
  sortable: 'effective_from, rate_id (default effective_from desc)',
  createBody: [
    ['rate_slabs', 'array of `{from_hour, to_hour, rate}` (1–20): first from 0, each starts where the previous ends, hours whole numbers ≤ 8760, rate money per bag'],
    ['block_hours, first_block_rate, next_block_rate', 'older two-rate form, only without `rate_slabs` (both rates required, block_hours default 24)'],
    ['effective_from', '`YYYY-MM-DD`'],
  ],
  updateNote: 'Any of the create fields.',
  samples: { create: 'rate_create', list: 'rate_list', update: 'rate_update', del: 'rate_delete' },
})}

${crudBlock({
  name: 'Shifts',
  base: '/api/v1/shifts',
  pk: 'shift_id',
  auth: { read: 'A, S', write: 'A, S' },
  notes: 'Times are local (IST) `HH:mm` or `HH:mm:ss`; `to_time` may be earlier than `from_time` (crosses midnight) but not equal.',
  sortable: 'shift_name, from_time, shift_id',
  searchable: 'shift_name',
  createBody: [
    ['shift_name', '1–50 chars'],
    ['from_time', '`HH:mm`'],
    ['to_time', '`HH:mm`, ≠ from_time'],
  ],
  updateNote: 'Any of the create fields.',
  samples: { create: 'shift_create', list: 'shift_list', update: 'shift_update' },
})}

${crudBlock({
  name: 'Shift assignments',
  base: '/api/v1/shift-assignments',
  pk: 'id',
  auth: { read: 'A, S', write: 'A, S' },
  notes: 'Assigns a shift to a user from a date. User and shift must belong to the same customer (else `400`).',
  filters: [['user_id', 'filter'], ['shift_id', 'filter']],
  sortable: 'effective_from, user_id, id',
  createBody: [
    ['user_id', 'md_user.id (A/O user of the customer)'],
    ['shift_id', 'shift of the customer'],
    ['effective_from', '`YYYY-MM-DD`'],
  ],
  updateNote: '`shift_id` and/or `effective_from`.',
  samples: { create: 'assign_create', list: 'assign_list', update: 'assign_update' },
})}

${crudBlock({
  name: 'Receipt header / footer lines and logo',
  base: '/api/v1/header-footer',
  pk: 'id',
  auth: { read: 'A, S', write: 'A, S' },
  notes: 'Printed lines: `H` header, `F` footer (JSON create). The logo row (`L`) is created only by the upload endpoint below; `image` is returned as base64 PNG.',
  filters: [['line_type', '`H` / `F` / `L`']],
  sortable: 'line_type, line_order, id',
  createBody: [
    ['line_type', '`H` or `F`'],
    ['line_order', 'integer 0–999, default 0'],
    ['narration', '1–100 chars'],
    ['in_flag', 'print on check-in receipt, default `Y`'],
    ['out_flag', 'print on check-out bill, default `Y`'],
    ['report_flag', 'print on reports, default `N`'],
  ],
  updateNote: '`line_order`, `narration`, `in_flag`, `out_flag`, `report_flag`.',
  samples: { create: 'hf_create', list: 'hf_list', update: 'hf_update' },
})}

#### \`POST /api/v1/header-footer/logo\` — upload / replace logo

**Auth / roles:** A, S · **multipart/form-data**: field \`logo\` = PNG file ≤ 200 KB (bytes must be PNG);
optional text fields \`line_order\`, \`in_flag\`, \`out_flag\`, \`report_flag\` (\`Y\`/\`N\`/\`true\`/\`false\`),
\`customer_id\` (S only). Returns \`201\` when created, \`200\` when replacing the existing logo.

${sample('hf_logo')}

**Errors:** \`400\` not a PNG / missing file · \`413 PAYLOAD_TOO_LARGE\` > 200 KB

${crudBlock({
  name: 'Settings',
  base: '/api/v1/settings',
  pk: 'setting_id',
  auth: { read: 'A, S', write: 'A, S' },
  notes: 'Key/value settings for the whole customer (`user_id: null`) or one user (override). Keys and allowed values are listed in §8. A new customer starts with the default set. Updating changes only `setting_value`. `REPORT_PWD` is stored hashed and always returned as `********`.',
  filters: [['setting_name', 'filter'], ['user_id', 'filter'], ['setting_type', '`G`/`B`/`P`']],
  sortable: 'setting_name, setting_id',
  createBody: [
    ['setting_name', 'one of the keys in §8'],
    ['setting_value', 'string or number, validated per key'],
    ['user_id', 'optional md_user.id for a per-user override (default null = customer-wide)'],
  ],
  updateNote: '`{ "setting_value": "…" }` only (validated against the row’s key).',
  samples: { create: 'setting_create', list: 'setting_list', update: 'setting_update', del: 'setting_delete', extra: ['setting_create_pwd'] },
})}

Invalid value for a key:
${sample('setting_bad')}

### Customer masters as super admin (S)

Applies to \`/rates\`, \`/shifts\`, \`/shift-assignments\`, \`/header-footer\` (+ \`/logo\`) and \`/settings\`.
An S token has no customer, so S picks the customer explicitly. A/O never send \`customer_id\` (ignored, the token's customer is used).

| Call | S behaviour |
|---|---|
| \`GET /<master>?customer_id=<id>\` | Lists that customer's rows only. **Without \`customer_id\` S gets the rows of all customers** (each row carries \`customer_id\`), so always pass it on a per-customer screen. Combines with the other filters, \`page\`, \`limit\`, \`sort\`. |
| \`GET /<master>/:id\` | Works for a row of any customer; no \`customer_id\` needed. |
| \`POST /<master>\` | \`customer_id\` is **required in the body** → else \`400 VALIDATION_ERROR\` (\`customer_id: Required for super admin\`). References in the body (\`user_id\`, \`shift_id\`) must belong to that customer (\`400\` otherwise). Logo upload: \`customer_id\` as a multipart text field. |
| \`PATCH /<master>/:id\` | Row addressed by id; **do not send \`customer_id\`** — it is not an update field and is silently dropped (a row can't be moved to another customer). |
| \`DELETE /<master>/:id\` | Row addressed by id; no \`customer_id\` needed. |

Example — S adds a rate for customer 1:

\`\`\`http
POST /api/v1/rates
Authorization: Bearer <S access token>

{ "customer_id": 1, "first_block_rate": 60, "next_block_rate": 25, "effective_from": "2027-04-01" }
\`\`\`

${crudBlock({
  name: 'GST rates (global)',
  base: '/api/v1/gst',
  pk: 'gst_id',
  auth: { read: 'S, A, O', write: 'S' },
  notes: 'Versioned by `effective_from` (bill uses the latest row with `effective_from ≤ bill_dt`). One live row per date.',
  sortable: 'effective_from, gst_id',
  createBody: [
    ['cgst_pct', 'percent 0–100, default 0'],
    ['sgst_pct', 'percent 0–100, default 0'],
    ['igst_pct', 'percent 0–100, default 0'],
    ['effective_from', '`YYYY-MM-DD`'],
  ],
  updateNote: 'Any of the create fields.',
  samples: { create: 'gst_create', list: 'gst_list', del: 'gst_delete' },
})}

${crudBlock({
  name: 'ID proof types (global)',
  base: '/api/v1/id-proofs',
  pk: 'id_proof_id',
  auth: { read: 'S, A, O', write: 'S' },
  notes: '`mask_flag = Y` (Aadhaar) → only the last 4 characters of the ID number may be stored/printed; the server masks them as `XXXXXXXX1234`.',
  sortable: 'id_proof_name, id_proof_id',
  searchable: 'id_proof_name',
  createBody: [
    ['id_proof_name', '1–50 chars, unique among live rows'],
    ['mask_flag', '`Y` / `N` (default)'],
  ],
  updateNote: '`id_proof_name`, `mask_flag`.',
  samples: { create: 'idproof_create', list: 'idproof_list' },
})}

### Own cloak room details (A edits, A and O read)

${endpoint({
  method: 'GET',
  path: '/api/v1/customers/me',
  title: 'own cloak room details',
  auth: 'A, O (S → 403: it has no own customer, use `/customers/:id`)',
  notes: 'Same shape as `customer` in the login payload (`GET /auth/me` returns the same object). Use it to refresh the Cloak room screen.',
  samples: ['customer_me'],
})}

${endpoint({
  method: 'PATCH',
  path: '/api/v1/customers/me',
  title: 'station admin edits own cloak room details',
  auth: 'A only (O and S → 403)',
  notes:
    'Partial update: send the changed fields (at least one). Same rules as the super-admin customer update. `user_limit`, `active_flag`, `renewal_dt`, `remarks` and `customer_id` are licence/platform fields and are silently ignored here (a body with only those → 400). Returns the updated customer: store it as the local `md_customer` row. A new `gst_no` is stamped by the server on bills pushed **after** the change; bills already stored keep theirs. Other devices of the customer get the new values at their next login or `GET /auth/me` / `GET /customers/me` (pull does not carry the customer).',
  body: [
    ['customer_name', '1–155 chars'],
    ['customer_addr', '1–200 chars'],
    ['state', '≤ 50 chars'],
    ['pin_code', '6 digits'],
    ['mobile_no', '7–20 chars of digits, `+`, `-`, space'],
    ['email', 'email, ≤ 150 chars'],
    ['gst_no', '15-char GSTIN (upper-cased by the server)'],
    ['upi_id', '`name@bank` (used for the UPI QR)'],
  ],
  samples: ['customer_me_update', 'customer_me_empty'],
  errors: '400 `VALIDATION_ERROR` (bad field, or nothing to change), 403 `FORBIDDEN` (not a station admin), 403 `PASSWORD_CHANGE_REQUIRED`.',
})}

### Super admin only (S): customers, registrations, payments, app releases

${crudBlock({
  name: 'Customers (stations)',
  base: '/api/v1/customers',
  pk: 'customer_id',
  auth: { read: 'S', write: 'S' },
  notes: 'Creating a customer also creates its default settings and default rate. `renewal_dt` is not writable: it is the latest registration `end_date` (login is refused after it).',
  filters: [['active_flag', '`Y`/`N`'], ['state', 'exact']],
  sortable: 'customer_name, renewal_dt, customer_id, created_at',
  searchable: 'customer_name, mobile_no, gst_no',
  createBody: [
    ['customer_name', '1–155 chars'],
    ['customer_addr', '1–200 chars'],
    ['state', 'optional, ≤ 50'],
    ['pin_code', '6 digits'],
    ['mobile_no', '7–20 chars of digits, `+`, `-`, space'],
    ['email', 'optional email'],
    ['gst_no', 'optional 15-char GSTIN (upper-cased)'],
    ['upi_id', 'optional `name@bank` (used for the payment QR)'],
    ['user_limit', 'licensed users 1–1000, default 1'],
    ['active_flag', 'default `Y`'],
    ['remarks', 'optional, ≤ 255'],
  ],
  updateNote: 'Any of the create fields.',
  samples: { create: 'customer_create', list: 'customer_list', update: 'customer_update' },
})}

${endpoint({ method: 'GET', path: '/api/v1/customers/:id/summary', title: 'customer summary', auth: 'S', samples: ['customer_summary'] })}

${crudBlock({
  name: 'Registrations (subscriptions) of a customer',
  base: '/api/v1/customers/:customerId/registrations',
  pk: 'reg_id',
  auth: { read: 'S', write: 'S' },
  notes: 'Create/update/delete recompute the customer’s `renewal_dt` = latest live `end_date`. `reg_no` is unique.',
  sortable: 'end_date, start_date, reg_no, reg_id',
  searchable: 'reg_no',
  createBody: [
    ['reg_no', '1–30 chars, unique'],
    ['start_date', '`YYYY-MM-DD`'],
    ['end_date', '`YYYY-MM-DD`, ≥ start_date'],
    ['user_count', 'integer 1–1000'],
    ['remarks', 'optional'],
  ],
  updateNote: 'Any of the create fields.',
  samples: { create: 'reg_create', list: 'reg_list' },
})}

${crudBlock({
  name: 'Payments received from a customer',
  base: '/api/v1/customers/:customerId/payments',
  pk: 'payment_id',
  auth: { read: 'S', write: 'S' },
  notes: '`net_amt` is computed as base + cgst + sgst + igst when omitted; when sent it must match (else `400`). `reg_id` must belong to the same customer.',
  filters: [['pay_mode', '`C`/`U`/`B`'], ['reg_id', 'filter']],
  sortable: 'payment_dt, payment_id, net_amt',
  searchable: 'transaction_no',
  createBody: [
    ['reg_id', 'optional registration'],
    ['payment_dt', '`YYYY-MM-DD`'],
    ['base_amt', 'money'],
    ['cgst / sgst / igst', 'money, default 0'],
    ['net_amt', 'optional money (must equal the sum)'],
    ['pay_mode', '`C` cash · `U` UPI · `B` bank'],
    ['transaction_no', 'optional, ≤ 50'],
    ['remarks', 'optional'],
  ],
  updateNote: 'Any of the create fields (amounts re-checked).',
  samples: { create: 'payment_create', list: 'payment_list' },
})}

${endpoint({
  method: 'POST',
  path: '/api/v1/app/version',
  title: 'publish an app release',
  auth: 'S',
  body: [
    ['version_code', 'integer ≥ 1, unique'],
    ['version_name', '1–20 chars'],
    ['apk_url', '`https://…` or `/downloads/<file>.apk`'],
    ['mandatory_flag', '`Y` forces older apps to update (default `N`)'],
    ['release_notes', 'optional, ≤ 5000'],
  ],
  samples: ['app_publish'],
})}

---

## 6. Offline sync (Android POS) — the core contract

### 6.1 Local data model on the device
Mirror the server rows of \`td_luggage_in_out\` (check-in/out) and \`td_receipt\` (bill) in SQLite, plus:
- \`sync_flag\` (\`N\` = not yet pushed, \`Y\` = pushed) — set \`N\` on every local insert/change.
- \`local_version\` — your own counter/marker per row; the server **echoes it back, never stores it**,
  so you can match results to the exact local version you sent.
- \`trans_id\` — a UUID v4 created **on the check-in device** and printed in the receipt QR. It is the
  same on every device and in the cloud. A bag checked in on device 1 can be checked out on device 2
  by scanning the QR (create/update the local row with the same \`trans_id\`).
- Masters (rates, shifts, ID proofs, GST, header/footer, **users**), settings and counters come from
  login/pull. Devices never create master rows. \`masters.users\` lists the customer's users (deleted ones
  too, \`is_deleted = Y\`) with \`id, user_code, user_name, user_type, allow_flag\` — use it to show who
  checked a bag in/out or billed it on rows from other counters.

### 6.2 Push — \`POST /api/v1/sync/push\`
Roles A, O (also allowed for a force-logged-out session). Body \`{ "luggage": [...], "receipts": [...] }\`,
max 5000 rows each, 5 MB. Send luggage rows with \`sync_flag = N\` and receipts with \`sync_flag = N\`
(send batches of ≤ 500 rows per request for best results).

Luggage row fields (\`customer_id\`, \`sync_flag\`, audit fields are ignored if sent):

| Field | Rules |
|---|---|
| \`trans_id\` | UUID (required) |
| \`local_version\` | number or string (echoed back) |
| \`receipt_no\` | \`<user_code>-<5 digits>\`, ≤ 20 chars, unique per customer per \`receipt_dt\` |
| \`receipt_dt\` | IST check-in date \`YYYY-MM-DD\` |
| \`user_id_in\`, \`date_time_in\` (UTC) | check-in (required) |
| \`device_id_in\` | required when \`out_flag = N\`; may be omitted/null on a QR check-out row |
| \`shift_id_in\` | nullable |
| \`pasn_name\` (≤ 200), \`pasn_mob_no\` (≤ 15), \`pnr_no\` (≤ 20, may be empty), \`luggage_qty\` (1–999) | passenger / bags |
| \`extra_lugage_qty\` (0–999, default 0) | extra bags stored **free**, never billed (QR key \`x\`) |
| \`adv_amt\` (money, default 0), \`adv_hours\` (0–720, default 0) | advance taken at check-in and the \`ADVANCE_HOURS\` it covered (QR key \`a\` = adv_amt) |
| \`id_proof_id\`, \`id_number\` (≤ 50) | ID proof — both or neither (null when none was taken, or on a QR check-out row); masked by the server when \`mask_flag = Y\` |
| \`user_id_out\`, \`device_id_out\`, \`shift_id_out\`, \`date_time_out\` | check-out (null while in store) |
| \`out_flag\` | \`N\` in store / \`Y\` checked out (then the 3 out fields + date_time_out are required) |

Receipt (bill) row fields: \`trans_id\`, \`local_version\`, \`bill_no\` (bill series), \`bill_dt\` (IST),
\`user_id\`, \`device_id\`, \`shift_id\`, \`luggage_qty\`, \`days_charged\`, \`rate_slabs\` (the slabs used; then
\`block_hours\`, \`first_block_rate\`, \`next_block_rate\` are optional), \`base_amt\`, \`penal_amt\`, \`net_amt\`
(full bill), \`adv_amt\` (the bag's advance, default 0), \`bal_amt\` (max(0, net_amt − adv_amt)), \`taxable_amt\`, \`gst_pct\`,
\`cgst\`, \`sgst\`, \`igst\`, \`gst_flag\`, \`pay_mode\` (\`C\`/\`U\`), \`upi_ref\` (nullable), \`bill_printed\`
(\`N\` = "Out Without Bill Print", the bill is still recorded). Do not send \`gst_no\`: the server stamps the
customer's current GSTIN on the bill when it stores it (old bills keep the GSTIN they were made with).

**Rules the server applies**
- Each row is validated separately: a bad row returns \`EXCEPTION\` and never fails the request.
- **Both \`ACCEPTED\` and \`EXCEPTION\` mean: set \`sync_flag = 'Y'\`** for that row/local_version
  (exceptions are now owned by the admin, visible in the exceptions report).
- Luggage merge: check-in fields are write-once; check-out fields only fill empty values; \`out_flag\`
  never goes back from \`Y\`. Push order between devices does not matter. Re-pushing is safe.
- Bills are stored once per \`trans_id\`; the server recomputes the bill (§7). A difference keeps the bill
  and logs \`AMOUNT_MISMATCH\`. A bill without \`rate_slabs\` (app older than the slab release) for a tariff
  the two-rate fields cannot express is refused with \`APP_UPDATE_REQUIRED\` (not stored).
- \`extra_lugage_qty\`, \`adv_amt\`, \`adv_hours\` are filled once while still 0 (QR row first, or a row
  synced before the app sent them), then never change.
- The response \`counters\` are the server’s last receipt/bill numbers of the current period for this user.

\`reason\` codes on \`EXCEPTION\`: \`VALIDATION_ERROR\`, \`INVALID_REFERENCE\` (user/shift/ID proof not of this
customer), \`TENANT_MISMATCH\`, \`DUPLICATE_RECEIPT_NO\`, \`DUPLICATE_CHECKOUT\` (a different second check-out),
\`BILL_WITHOUT_LUGGAGE\`, \`DUPLICATE_BILL\`, \`DUPLICATE_BILL_NO\`, \`CHECKOUT_MISSING\`, \`NO_RATE\`, \`APP_UPDATE_REQUIRED\`,
\`AMOUNT_MISMATCH\`, \`DB_ERROR\`.

Sample (bag 1 still in store with an Aadhaar number, bag 2 checked out and billed, plus a bill for an
unknown \`trans_id\`):
${sample('push', { maxItems: 10 })}

The same rows pushed again (retry) — nothing changes, all \`ACCEPTED\`:
${sample('push_repeat', { request: false, maxItems: 10 })}

Push from a force-logged-out session is still accepted:
${sample('push_after_force', { request: false })}

**Check-out of a bag that is not synced yet (Case 1, receipt QR).** The QR carries
\`t r d c u i b n m p\` = \`trans_id, receipt_no, receipt_dt, customer_id, user_id_in, date_time_in,
luggage_qty, pasn_name, pasn_mob_no, pnr_no\` — no check-in device, shift or ID proof. Device 2 creates the
local row from the QR (never send \`customer_id\`; it comes from the token), checks it out, bills it and
pushes the row with \`device_id_in\`, \`shift_id_in\`, \`id_proof_id\`, \`id_number\` left out/null. The server
stores it and accepts the bill (it is recomputed from \`receipt_dt\`, \`date_time_in\`, \`luggage_qty\` and the
check-out time). When device 1 later pushes its check-in, those four fields are filled **once** (only while
still empty); everything else stays as stored. Both orders end as one row.

Device 2 pushes the QR check-out and the bill first:
${sample('push_qr_checkout')}

Device 1 pushes its check-in later — accepted; device, shift and ID proof are filled:
${sample('push_checkin_after_qr', { request: false })}

The stored row afterwards (with the bill and its stamped \`gst_no\`):
${sample('luggage_get_qr', { request: false })}

**Errors:** \`400\` only if the envelope itself is wrong (e.g. \`luggage\` not an array) · \`401\` · \`403\` (super admin) · \`413\` · \`429\`

### 6.3 Pull — \`GET /api/v1/sync/pull\`
Roles A, O.

| Query | Meaning |
|---|---|
| \`since\` | \`server_time\` of the previous **completed** pull (omit for the first pull) |
| \`cursor\` | \`next_cursor\` of the previous page while \`has_more\` was true (send without \`since\`) |
| \`limit\` | luggage rows per page, 1–1000 (default 1000) |

**Algorithm**
1. First pull (after login / fresh install): no \`since\` → all masters, settings, counters and all **open**
   check-ins of the customer (other users’ bags too, so any counter can check them out).
2. Every \`SYNC_INTERVAL_MIN\` minutes (setting, ≥ 15) and after a push: \`GET /sync/pull?since=<stored server_time>\`
   → rows changed since then, including **soft-deleted** rows (\`is_deleted = "Y"\` → hide/delete locally) and
   bags checked out on other devices (\`out_flag = "Y"\` → mark out locally).
3. **Upsert by primary key** (\`trans_id\`, \`rate_id\`, …): the server re-sends a 2-minute overlap, so duplicates are normal.
4. While \`has_more\` is \`true\`: \`GET /sync/pull?cursor=<next_cursor>\` (continuation pages carry only \`luggage\`;
   \`masters/settings/counters\` are \`null\`). When \`has_more\` is \`false\`, store \`server_time\` as the next \`since\`.

First pull (operator 2 sees operator 1’s bag still in store):
${sample('pull_first', { request: false })}

Next pull with \`since=${pull?.data?.server_time ?? '<server_time>'}\`:
${sample('pull_since', { request: false })}

Paged pull (\`limit=1\`) → \`has_more: true\` + \`next_cursor\`:
${sample('pull_page', { request: false })}

Continuation page (\`?cursor=…\`):
${sample('pull_cursor', { request: false })}

### 6.4 Online luggage lookup

${endpoint({
  method: 'GET',
  path: '/api/v1/luggage',
  title: 'search bags',
  auth: 'A, O',
  query: [
    ['search', 'contains-match on PNR, mobile, passenger name, receipt no'],
    ['status', '`in` (in store) / `out` (checked out)'],
    ['page, limit', 'pagination (newest check-in first)'],
  ],
  samples: ['luggage_search'],
})}

${endpoint({ method: 'GET', path: '/api/v1/luggage/:trans_id', title: 'one bag with its bill (`receipt` or null)', auth: 'A, O', samples: ['luggage_get'], errors: '`400` bad UUID · `404`' })}

\`404 NOT_FOUND\`:
${sample('not_found', { request: false })}

---

## 7. Billing rules (must match the server exactly)

\`\`\`
minutes      = whole minutes from date_time_in to date_time_out (seconds dropped)
billable     = max(0, minutes − grace_minutes)                      GRACE_MINUTES, default 0
slabs        = rate_slabs of the rate row: [{from_hour, to_hour, rate}], contiguous from hour 0
per bag      = Σ rate of every slab with i = 0 or billable > from_hour × 60     (started slabs)
             + last.rate × ceil((billable − last.to_hour × 60) / ((last.to_hour − last.from_hour) × 60))
                                                                    when billable > last.to_hour × 60
days_charged = number of rates added (slabs started + repeats of the last slab)
base_amt     = luggage_qty × per bag                                 extra bags are free
adv_amt      = the bag's advance (0 when none); bal_amt = max(0, net_amt − adv_amt)   no refund
rate row     = latest rate with effective_from ≤ receipt_dt
GST row      = latest GST with effective_from ≤ bill_dt
gst_pct      = 0 when gst_flag = N, else the customer's GST_PCT when set,
               else cgst_pct + sgst_pct (GST_TYPE = E) | igst_pct (GST_TYPE = I) of the GST row
taxed        = base_amt + (penalty_gst_flag = Y ? penal_amt : 0)    PENALTY_GST_FLAG, default Y

gst_inclusive = Y (GST_INCLUSIVE, default):
  taxable_amt = round(taxed × 100 / (100 + gst_pct))
  gst         = taxed − taxable_amt
  net_amt     = base_amt + penal_amt                                 total collected
gst_inclusive = N (GST added on top):
  taxable_amt = taxed
  gst         = round(taxed × gst_pct / 100)
  net_amt     = base_amt + penal_amt + gst

GST_TYPE = E: cgst = round(gst / 2), sgst = gst − cgst, igst = 0   (odd paisa goes to CGST)
GST_TYPE = I: igst = gst, cgst = sgst = 0
\`\`\`
- Do all arithmetic in **integer paise** (₹1 = 100). \`round\` = **half-up** to the paisa.
- A penalty without GST is **not** part of \`taxable_amt\`: net = taxable + GST + untaxed penalty.
- Examples (defaults): 2 min × 2 bags → ₹100 · 24h00m × 1 → ₹50 · 24h01m × 1 → ₹70 · 50 h × 3 → ₹270 ·
  ₹70 at 18 % → taxable 59.32, CGST 5.34, SGST 5.34 · ₹60.50 at 18 % → 51.27, CGST 4.62, SGST 4.61.
- Examples (options): 24h10m with 15 grace minutes → 1 block (₹50) · GST on top, ₹50 at 18 % → taxable 50.00,
  CGST 4.50, SGST 4.50, net 59.00 · on top with ₹10 penalty → taxable 60.00, GST 10.80, net 70.80 · on top,
  penalty without GST → taxable 50.00, GST 9.00, net 69.00 · inclusive ₹70 + ₹10 penalty without GST →
  taxable 59.32, CGST 5.34, SGST 5.34, net 80.00 · \`gst_flag = N\` → 0 % GST, taxable = net.
- **Send the options you billed with on every bill**: \`grace_minutes\`, \`gst_inclusive\`, \`penalty_gst_flag\`,
  \`gst_flag\`. The server recomputes with them and stores them, so a later settings change never flags old
  offline bills. Omitted → the customer's settings at the time the bill arrives, then the defaults.
- Slab examples: 0–24 h ₹30 only → 2 bags × 2 days = 2 × (30 + 30) = ₹120 · 0–24 h ₹30 + 24–48 h ₹10 →
  2 bags × 3 days = 2 × (30 + 10 + 10) = ₹100 · 0–4 h ₹10 + 4–24 h ₹20 → 3 h ₹10, 5 h ₹30, 25 h ₹50 ·
  the two-rate tariff 24 h / ₹50 / ₹20 is the slabs 0–24 h ₹50 + 24–48 h ₹20 (same bills as before).
- Store the snapshot on the bill: \`rate_slabs\` (plus the two-rate fields if you have them) and \`gst_pct\`.
- **Advance** (\`ADVANCE_FLAG = Y\`): at check-in take \`adv_amt\` = the bill for \`ADVANCE_HOURS\` with the same
  rate and GST setting and save \`adv_hours\`; print it on the receipt. At check-out \`net_amt\` is the full bill,
  \`adv_amt\` is subtracted and \`bal_amt\` is collected (Cash or UPI). The GST breakup is printed on the bill
  only. Reports count the advance on the check-in date and \`bal_amt\` on the bill date.
- One bill per bag transaction at check-out; pay mode Cash or UPI.

### Receipt / bill numbers
- Format \`<user_code>-<5 digits>\` (e.g. \`U03-00125\`; more digits after 99999). Two series per user:
  receipts (check-in) and bills (check-out). The series belongs to the **user**, not the device.
- Reset by \`RECEIPT_RESET\`: \`D\` daily (period \`YYYY-MM-DD\`), \`M\` monthly (\`YYYY-MM\`), \`Y\` Indian
  financial year (\`FY2026-27\`, April–March), \`N\` never (one period \`ALL\`); periods use the IST date.
- Next number = max(local last number, server \`counters.*_last_no\` for the current \`period_key\`) + 1.
  Login, pull and push return \`counters\`; the server never lowers them.

---

## 8. Settings keys

Values travel as strings. **Scope** "customer" = only the customer-wide row exists (a per-user value is
refused with \`400\`); "per user" = a user's own row overrides the customer-wide one. Login and pull
send \`settings\` for the signed-in user with **every key**: the stored value, else the default. Keys
without a default are absent until set: \`GST_PCT\` (absent = use the \`md_gst\` rate of the bill date)
and \`REPORT_PWD\` (never sent to devices). "Server" = the server itself uses the value (everything else
is stored, validated and synced for the app). Roles A and S write settings with \`POST/PATCH /settings\`;
\`SINGLE_SESSION_FLAG\` only S (\`403\` for A). \`SINGLE_SESSION_FLAG = N\` lets a user of that station be
signed in on several devices at once (no \`409 SESSION_ACTIVE\`); every session still takes a seat of
\`user_limit\`. \`GST_PCT\` set → bills are checked with that rate instead of \`md_gst\` (the value in force
when the bill reaches the server).

${settingsTable()}

---

## 9. Reports (A, S; O only when \`REPORT_FLAG = Y\`)

Common query: \`from\`, \`to\` (IST dates, inclusive, ≤ 366 days, required except bags-in-store/exceptions),
\`user_id\`, \`shift_id\`, \`pay_mode\` (\`C\`/\`U\`), \`page\`, \`limit\`, \`customer_id\` (**required for S**).
\`meta\` has \`page, limit, total, totals, filters\`. Timestamps come with \`*_ist\` copies for display.
When \`REPORT_PWD_FLAG = Y\` send header \`X-Report-Password: <password>\` (else \`403 REPORT_PASSWORD_REQUIRED\`).

${endpoint({ method: 'GET', path: '/api/v1/reports/collection-summary', title: 'day-wise collection', auth: 'A, S, (O)', samples: ['rep_collection'] })}

${endpoint({ method: 'GET', path: '/api/v1/reports/check-in-register', title: 'check-ins in the range', auth: 'A, S, (O)', samples: ['rep_checkin'] })}

${endpoint({ method: 'GET', path: '/api/v1/reports/check-out-register', title: 'check-outs with bills', auth: 'A, S, (O)', samples: ['rep_checkout'] })}

${endpoint({ method: 'GET', path: '/api/v1/reports/bags-in-store', title: 'bags currently in store', auth: 'A, S, (O)', notes: '`from`/`to` optional (check-in date window).', samples: ['rep_store'] })}

${endpoint({ method: 'GET', path: '/api/v1/reports/gst', title: 'GST by day and rate', auth: 'A, S, (O)', samples: ['rep_gst'] })}

${endpoint({ method: 'GET', path: '/api/v1/reports/user-shift-collection', title: 'collection per user and shift', auth: 'A, S, (O)', samples: ['rep_usershift'] })}

${endpoint({ method: 'GET', path: '/api/v1/reports/unprinted-bills', title: '"Out Without Bill Print" bills', auth: 'A, S, (O)', samples: ['rep_unprinted'] })}

${endpoint({
  method: 'GET',
  path: '/api/v1/reports/exceptions',
  title: 'sync exceptions to review',
  auth: 'A, S, (O)',
  query: [
    ['resolved', '`Y` / `N`'],
    ['table_name', '`td_luggage_in_out` / `td_receipt`'],
    ['from, to', 'optional (created date, IST)'],
  ],
  samples: ['rep_exceptions'],
})}

${endpoint({ method: 'PATCH', path: '/api/v1/reports/exceptions/:id/resolve', title: 'mark an exception resolved (idempotent)', auth: 'A, S', samples: ['rep_resolve'] })}

Operator without \`REPORT_FLAG\`:
${sample('rep_op_denied', { request: false })}

Role denied example (operator calling \`GET /users\`):
${sample('forbidden', { request: false })}

---

## 10. Suggested screens → endpoints

| Screen | Endpoints |
|---|---|
| Splash / update check | \`GET /app/version?current=<code>\` |
| Login | \`POST /auth/login\` (handle 401/403/409 per §2) |
| POS home (offline) | local DB; background \`POST /sync/push\` then \`GET /sync/pull\` every \`SYNC_INTERVAL_MIN\` |
| Check-in | local insert (new \`trans_id\`, next receipt no), print receipt with QR, push later |
| Check-out (scan QR / search) | local row by \`trans_id\`; if missing and online \`GET /luggage/:trans_id\` or \`GET /luggage?search=\`; compute bill (§7); print; push |
| Admin: cloak room details | \`GET /customers/me\`, \`PATCH /customers/me\` |
| Admin: users | \`/users\` (+ allow, reset-password, force-logout) |
| Admin: masters | \`/rates\`, \`/shifts\`, \`/shift-assignments\`, \`/header-footer\` (+ \`/logo\`), \`/settings\` |
| Admin: reports | \`/reports/*\`, exceptions + resolve |
| Super admin | \`/customers\` (+ summary, registrations, payments), \`/gst\`, \`/id-proofs\`, \`POST /app/version\` |
| Logout | push pending → \`POST /auth/logout\` |
`;
}

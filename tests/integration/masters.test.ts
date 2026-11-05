import type { RowDataPacket } from 'mysql2/promise';
import request from 'supertest';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { createApp } from '../../src/app';
import { raw } from '../../src/core/db/dml';
import { closeDb, db } from '../../src/core/db/pool';
import { loadRegistry } from '../../src/core/db/registry';
import { verifyPassword } from '../../src/core/security/password';
import { bearer, seedCustomer, seedUser, tokensFor } from '../helpers/auth';

const app = createApp();
const PNG = Buffer.concat([
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
  Buffer.alloc(64, 1),
]);

type Auth = Record<string, string>;
let cid: number;
let otherCid: number;
let admin: Auth;
let otherAdmin: Auth;
let operator: Auth;
let superAuth: Auth;
let operatorId: number;

beforeAll(async () => {
  await loadRegistry(db);
  cid = await seedCustomer();
  otherCid = await seedCustomer();
  const a = await seedUser(cid, { type: 'A' });
  const o = await seedUser(cid);
  operatorId = o.id;
  admin = bearer((await tokensFor(app, a, 'm-admin')).access);
  operator = bearer((await tokensFor(app, o, 'm-op')).access);
  otherAdmin = bearer(
    (await tokensFor(app, await seedUser(otherCid, { type: 'A' }), 'm-other')).access,
  );
  superAuth = bearer((await tokensFor(app, await seedUser(null, { type: 'S' }), 'm-sa')).access);
});
afterAll(async () => {
  await closeDb();
});

describe('rates', () => {
  const rate = { first_block_rate: 50, next_block_rate: '20.00', effective_from: '2026-05-01' };

  it('creates per tenant, rejects duplicates and revives a deleted date', async () => {
    const created = await request(app).post('/api/v1/rates').set(admin).send(rate);
    expect(created.status).toBe(201);
    expect(created.body.data).toMatchObject({
      customer_id: cid,
      block_hours: 24,
      first_block_rate: '50.00',
      next_block_rate: '20.00',
    });
    const id = created.body.data.rate_id;

    expect((await request(app).post('/api/v1/rates').set(admin).send(rate)).status).toBe(409);
    expect((await request(app).post('/api/v1/rates').set(otherAdmin).send(rate)).status).toBe(201);

    expect((await request(app).delete(`/api/v1/rates/${id}`).set(admin)).status).toBe(200);
    const revived = await request(app)
      .post('/api/v1/rates')
      .set(admin)
      .send({ ...rate, first_block_rate: '60' });
    expect(revived.status).toBe(201);
    expect(revived.body.data).toMatchObject({
      rate_id: id,
      first_block_rate: '60.00',
      is_deleted: 'N',
    });
  });

  it('validates amounts and restricts to admins', async () => {
    const bad = await request(app)
      .post('/api/v1/rates')
      .set(admin)
      .send({ ...rate, first_block_rate: '12.345', block_hours: 0 });
    expect(bad.status).toBe(400);
    expect(bad.body.errors.map((e: { path: string }) => e.path).sort()).toEqual([
      'block_hours',
      'first_block_rate',
    ]);
    expect((await request(app).get('/api/v1/rates').set(operator)).status).toBe(403);
    expect((await request(app).get('/api/v1/rates')).status).toBe(401);
  });

  it('super admin manages any tenant by customer_id', async () => {
    const res = await request(app)
      .post('/api/v1/rates')
      .set(superAuth)
      .send({ ...rate, effective_from: '2026-06-01', customer_id: otherCid });
    expect(res.status).toBe(201);
    expect(res.body.data.customer_id).toBe(otherCid);
    const list = await request(app).get(`/api/v1/rates?customer_id=${otherCid}`).set(superAuth);
    expect(list.body.data.every((r: { customer_id: number }) => r.customer_id === otherCid)).toBe(
      true,
    );

    const missing = await request(app)
      .post('/api/v1/rates')
      .set(superAuth)
      .send({ ...rate, effective_from: '2026-06-02' });
    expect(missing.status).toBe(400);
    expect(missing.body.errors[0].path).toBe('customer_id');

    // customer_id is not an update field: a row can't be moved to another tenant.
    const moved = await request(app)
      .patch(`/api/v1/rates/${res.body.data.rate_id}`)
      .set(superAuth)
      .send({ next_block_rate: 25, customer_id: cid });
    expect(moved.status).toBe(200);
    expect(moved.body.data.customer_id).toBe(otherCid);
    expect(moved.body.data.next_block_rate).toBe('25.00');
  });
});

describe('shifts and shift assignments', () => {
  it('shift times may cross midnight but not be equal', async () => {
    const night = await request(app)
      .post('/api/v1/shifts')
      .set(admin)
      .send({ shift_name: 'Night', from_time: '22:00', to_time: '06:00' });
    expect(night.status).toBe(201);
    const same = await request(app)
      .post('/api/v1/shifts')
      .set(admin)
      .send({ shift_name: 'Zero', from_time: '06:00', to_time: '06:00:00' });
    expect(same.status).toBe(400);
    const patch = await request(app)
      .patch(`/api/v1/shifts/${night.body.data.shift_id}`)
      .set(admin)
      .send({ to_time: '22:00' });
    expect(patch.status).toBe(400);
  });

  it('assignments must reference a user and shift of the same tenant', async () => {
    const shift = await request(app)
      .post('/api/v1/shifts')
      .set(admin)
      .send({ shift_name: 'Morning', from_time: '06:00', to_time: '14:00' });
    const shiftId = shift.body.data.shift_id;
    const otherShift = await request(app)
      .post('/api/v1/shifts')
      .set(otherAdmin)
      .send({ shift_name: 'Theirs', from_time: '06:00', to_time: '14:00' });
    const foreignUser = await seedUser(otherCid);

    const ok = await request(app)
      .post('/api/v1/shift-assignments')
      .set(admin)
      .send({ user_id: operatorId, shift_id: shiftId, effective_from: '2026-05-01' });
    expect(ok.status).toBe(201);
    expect(ok.body.data).toMatchObject({
      customer_id: cid,
      user_id: operatorId,
      shift_id: shiftId,
    });

    const wrongUser = await request(app)
      .post('/api/v1/shift-assignments')
      .set(admin)
      .send({ user_id: foreignUser.id, shift_id: shiftId, effective_from: '2026-05-01' });
    expect(wrongUser.status).toBe(400);
    expect(wrongUser.body.errors[0].path).toBe('user_id');

    const wrongShift = await request(app)
      .patch(`/api/v1/shift-assignments/${ok.body.data.id}`)
      .set(admin)
      .send({ shift_id: otherShift.body.data.shift_id });
    expect(wrongShift.status).toBe(400);

    const list = await request(app)
      .get(`/api/v1/shift-assignments?user_id=${operatorId}`)
      .set(admin);
    expect(list.body.meta.total).toBe(1);
  });
});

describe('header-footer', () => {
  it('creates text lines; logo rows only via upload', async () => {
    const h = await request(app)
      .post('/api/v1/header-footer')
      .set(admin)
      .send({ line_type: 'H', line_order: 1, narration: 'Howrah Cloak Room' });
    expect(h.status).toBe(201);
    expect(h.body.data).toMatchObject({
      line_type: 'H',
      in_flag: 'Y',
      out_flag: 'Y',
      report_flag: 'N',
    });
    expect(
      (
        await request(app)
          .post('/api/v1/header-footer')
          .set(admin)
          .send({ line_type: 'L', narration: 'x' })
      ).status,
    ).toBe(400);
  });

  it('uploads a PNG logo (base64 back), replaces it on re-upload, rejects non-PNG and >200 KB', async () => {
    const first = await request(app)
      .post('/api/v1/header-footer/logo')
      .set(admin)
      .field('report_flag', 'true')
      .attach('logo', PNG, 'logo.png');
    expect(first.status).toBe(201);
    expect(first.body.data).toMatchObject({ line_type: 'L', report_flag: 'Y', customer_id: cid });
    expect(Buffer.from(first.body.data.image, 'base64').equals(PNG)).toBe(true);

    const png2 = Buffer.concat([PNG, Buffer.from('v2')]);
    const second = await request(app)
      .post('/api/v1/header-footer/logo')
      .set(admin)
      .attach('logo', png2, 'logo.png');
    expect(second.status).toBe(200);
    expect(second.body.data.id).toBe(first.body.data.id);
    expect(Buffer.from(second.body.data.image, 'base64').equals(png2)).toBe(true);

    const jpeg = await request(app)
      .post('/api/v1/header-footer/logo')
      .set(admin)
      .attach('logo', Buffer.from([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 4, 5]), {
        filename: 'x.png',
        contentType: 'image/png',
      });
    expect(jpeg.status).toBe(400);

    const big = Buffer.concat([PNG, Buffer.alloc(200 * 1024)]);
    const tooBig = await request(app)
      .post('/api/v1/header-footer/logo')
      .set(admin)
      .attach('logo', big, 'big.png');
    expect(tooBig.status).toBe(413);
    expect(tooBig.body.code).toBe('PAYLOAD_TOO_LARGE');

    expect(
      (await request(app).post('/api/v1/header-footer/logo').set(admin).field('in_flag', 'Y'))
        .status,
    ).toBe(400);
    expect(
      (
        await request(app)
          .post('/api/v1/header-footer/logo')
          .set(operator)
          .attach('logo', PNG, 'l.png')
      ).status,
    ).toBe(403);

    const list = await request(app).get('/api/v1/header-footer?line_type=L').set(admin);
    expect(list.body.data).toHaveLength(1);
  });
});

describe('settings', () => {
  it('whitelists keys, validates values per key and derives setting_type', async () => {
    const ok = await request(app)
      .post('/api/v1/settings')
      .set(admin)
      .send({ setting_name: 'SYNC_INTERVAL_MIN', setting_value: 30 });
    expect(ok.status).toBe(201);
    expect(ok.body.data).toMatchObject({ setting_value: '30', setting_type: 'G', user_id: null });
    expect(ok.body.data).not.toHaveProperty('user_key');

    const low = await request(app)
      .post('/api/v1/settings')
      .set(admin)
      .send({ setting_name: 'PAPER_WIDTH', setting_value: '72' });
    expect(low.status).toBe(400);
    expect(
      (
        await request(app)
          .post('/api/v1/settings')
          .set(admin)
          .send({ setting_name: 'HACK', setting_value: 'Y' })
      ).status,
    ).toBe(400);
    expect(
      (
        await request(app)
          .post('/api/v1/settings')
          .set(admin)
          .send({ setting_name: 'SYNC_INTERVAL_MIN', setting_value: 45 })
      ).status,
    ).toBe(409);

    const patch = await request(app)
      .patch(`/api/v1/settings/${ok.body.data.setting_id}`)
      .set(admin)
      .send({ setting_value: '10' });
    expect(patch.status).toBe(400);
    const patched = await request(app)
      .patch(`/api/v1/settings/${ok.body.data.setting_id}`)
      .set(admin)
      .send({ setting_value: '60' });
    expect(patched.body.data.setting_value).toBe('60');

    expect(
      (await request(app).delete(`/api/v1/settings/${ok.body.data.setting_id}`).set(admin)).status,
    ).toBe(200);
    const again = await request(app)
      .post('/api/v1/settings')
      .set(admin)
      .send({ setting_name: 'SYNC_INTERVAL_MIN', setting_value: 20 });
    expect(again.status).toBe(201);
    expect(again.body.data.setting_id).toBe(ok.body.data.setting_id);
  });

  it('hashes REPORT_PWD and never returns it', async () => {
    const res = await request(app)
      .post('/api/v1/settings')
      .set(admin)
      .send({ setting_name: 'REPORT_PWD', setting_value: 'r3port' });
    expect(res.status).toBe(201);
    expect(res.body.data.setting_value).toBe('********');
    const [row] = await raw<RowDataPacket[]>(
      'SELECT setting_value FROM md_setting WHERE setting_id = ?',
      [res.body.data.setting_id],
    );
    expect(row?.setting_value).toMatch(/^\$argon2id\$/);
    expect(await verifyPassword(row?.setting_value, 'r3port')).toBe(true);

    const list = await request(app).get('/api/v1/settings?setting_name=REPORT_PWD').set(admin);
    expect(list.body.data[0].setting_value).toBe('********');
  });

  it('per-user overrides must target a user of the same tenant', async () => {
    const ok = await request(app)
      .post('/api/v1/settings')
      .set(admin)
      .send({ setting_name: 'QR_FLAG', setting_value: 'N', user_id: operatorId });
    expect(ok.status).toBe(201);
    const foreign = await seedUser(otherCid);
    const bad = await request(app)
      .post('/api/v1/settings')
      .set(admin)
      .send({ setting_name: 'QR_FLAG', setting_value: 'N', user_id: foreign.id });
    expect(bad.status).toBe(400);
  });
});

describe('global masters', () => {
  it('gst: everyone reads, only S writes, one row per effective date', async () => {
    const read = await request(app).get('/api/v1/gst').set(operator);
    expect(read.status).toBe(200);
    expect(
      read.body.data.some((g: { effective_from: string }) => g.effective_from === '2017-07-01'),
    ).toBe(true);

    expect(
      (
        await request(app)
          .post('/api/v1/gst')
          .set(admin)
          .send({ cgst_pct: 9, sgst_pct: 9, effective_from: '2030-01-01' })
      ).status,
    ).toBe(403);
    const created = await request(app)
      .post('/api/v1/gst')
      .set(superAuth)
      .send({ cgst_pct: 6, sgst_pct: '6', igst_pct: 12, effective_from: '2030-01-01' });
    expect(created.status).toBe(201);
    expect(created.body.data).toMatchObject({
      cgst_pct: '6.00',
      sgst_pct: '6.00',
      igst_pct: '12.00',
    });
    expect(
      (await request(app).post('/api/v1/gst').set(superAuth).send({ effective_from: '2030-01-01' }))
        .status,
    ).toBe(409);
    expect(
      (
        await request(app)
          .post('/api/v1/gst')
          .set(superAuth)
          .send({ cgst_pct: 101, effective_from: '2031-01-01' })
      ).status,
    ).toBe(400);
    expect(
      (
        await request(app)
          .patch(`/api/v1/gst/${created.body.data.gst_id}`)
          .set(superAuth)
          .send({ effective_from: '2017-07-01' })
      ).status,
    ).toBe(409);
  });

  it('id-proofs: everyone reads, only S writes, names unique among live rows', async () => {
    expect((await request(app).get('/api/v1/id-proofs').set(operator)).status).toBe(200);
    expect(
      (await request(app).post('/api/v1/id-proofs').set(admin).send({ id_proof_name: 'X' })).status,
    ).toBe(403);
    expect(
      (
        await request(app)
          .post('/api/v1/id-proofs')
          .set(superAuth)
          .send({ id_proof_name: 'Passport' })
      ).status,
    ).toBe(409);
    const ok = await request(app)
      .post('/api/v1/id-proofs')
      .set(superAuth)
      .send({ id_proof_name: 'Railway Pass', mask_flag: 'Y' });
    expect(ok.status).toBe(201);
    expect(
      (
        await request(app)
          .patch(`/api/v1/id-proofs/${ok.body.data.id_proof_id}`)
          .set(superAuth)
          .send({ id_proof_name: 'PAN Card' })
      ).status,
    ).toBe(409);
  });
});

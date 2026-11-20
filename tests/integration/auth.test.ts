import jwt from 'jsonwebtoken';
import type { RowDataPacket } from 'mysql2/promise';
import request from 'supertest';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { createApp } from '../../src/app';
import { env } from '../../src/config/env';
import { raw, save } from '../../src/core/db/dml';
import { closeDb, db } from '../../src/core/db/pool';
import { loadRegistry } from '../../src/core/db/registry';
import { sha256 } from '../../src/core/security/tokens';
import { periodKey } from '../../src/domain/numbering';
import { bearer, loginAs, seedCustomer, seedUser, tokensFor } from '../helpers/auth';
import { adminOf, superAdmin } from '../helpers/context';

const app = createApp();
const pushEmpty = (token: string) =>
  request(app).post('/api/v1/sync/push').set(bearer(token)).send({ luggage: [], receipts: [] });

beforeAll(async () => {
  await loadRegistry(db);
});
afterAll(async () => {
  await closeDb();
});

async function session(sessionId: string) {
  const rows = await raw<RowDataPacket[]>('SELECT * FROM td_user_session WHERE session_id = ?', [
    sessionId,
  ]);
  return rows[0];
}
const sidOf = (token: string) => (jwt.decode(token) as { sid: string }).sid;

describe('POST /auth/login', () => {
  it('returns tokens, user, customer, masters, merged settings and counters', async () => {
    const cid = await seedCustomer();
    const user = await seedUser(cid);
    const A = adminOf(cid);
    await save(
      'md_setting',
      { user_id: null, setting_name: 'RECEIPT_RESET', setting_value: 'M', setting_type: 'G' },
      { flag: 'I' },
      A,
    );
    await save(
      'md_setting',
      { user_id: null, setting_name: 'QR_FLAG', setting_value: 'Y', setting_type: 'B' },
      { flag: 'I' },
      A,
    );
    await save(
      'md_setting',
      { user_id: null, setting_name: 'REPORT_PWD', setting_value: 'hash', setting_type: 'G' },
      { flag: 'I' },
      A,
    );
    await save(
      'md_setting',
      { user_id: user.id, setting_name: 'QR_FLAG', setting_value: 'N', setting_type: 'B' },
      { flag: 'I' },
      A,
    );
    await save(
      'md_shift',
      { shift_name: 'Day', from_time: '06:00', to_time: '18:00' },
      { flag: 'I' },
      A,
    );
    await save(
      'md_user_counter',
      { user_id: user.id, series: 'R', period_key: periodKey('M'), last_no: 125 },
      { flag: 'I' },
      A,
    );

    const res = await loginAs(app, user, 'android-abc12345');
    expect(res.status).toBe(200);
    const d = res.body.data;
    expect(d.accessToken).toBeTruthy();
    expect(d.refreshToken).toBeTruthy();
    expect(d.user).toEqual({
      id: user.id,
      customer_id: cid,
      user_id: user.user_id,
      user_name: expect.any(String),
      user_code: expect.any(String),
      user_type: 'O',
      allow_flag: 'Y',
      must_change_pwd: 'N',
    });
    expect(d.masters.users).toEqual([
      {
        id: user.id,
        customer_id: cid,
        user_code: expect.any(String),
        user_name: expect.any(String),
        user_type: 'O',
        allow_flag: 'Y',
        is_deleted: 'N',
        updated_at: expect.any(String),
      },
    ]);
    expect(d.customer).toMatchObject({ customer_id: cid, renewal_dt: '2099-12-31' });
    expect(d.masters.id_proofs.length).toBeGreaterThanOrEqual(6);
    expect(d.masters.gst).toHaveLength(1);
    expect(d.masters.shifts.map((s: { shift_name: string }) => s.shift_name)).toEqual(['Day']);
    // Stored (customer-wide + the user's override) on top of every key's default.
    expect(d.settings).toMatchObject({ RECEIPT_RESET: 'M', QR_FLAG: 'N', GRACE_MINUTES: '0' });
    expect(d.settings).not.toHaveProperty('REPORT_PWD');
    expect(d.counters).toEqual({
      reset: 'M',
      period_key: periodKey('M'),
      receipt_last_no: 125,
      bill_last_no: 0,
    });
    expect(d.server_time).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/);
    expect(JSON.stringify(res.body)).not.toMatch(/password_hash|session_token|token_hash/);

    const claims = jwt.decode(d.accessToken) as Record<string, unknown>;
    expect(claims).toMatchObject({ sub: String(user.id), cid, typ: 'O' });
    const s = await session(claims.sid as string);
    expect(s).toMatchObject({
      status: 'A',
      user_id: user.id,
      customer_id: cid,
      device_id: 'android-abc12345',
    });
    expect(s?.token_hash).toBe(sha256(d.accessToken));
    expect(s?.refresh_hash).toBe(sha256(d.refreshToken));

    const [u] = await raw<RowDataPacket[]>(
      'SELECT device_id, session_token, last_login FROM md_user WHERE id = ?',
      [user.id],
    );
    expect(u).toMatchObject({
      device_id: 'android-abc12345',
      session_token: sha256(d.accessToken),
    });
    expect(u?.last_login).toBeTruthy();
  });

  it('rejects a wrong password and an unknown user with the same 401', async () => {
    const user = await seedUser(await seedCustomer());
    const wrong = await loginAs(app, { ...user, password: 'nope' });
    const unknown = await loginAs(app, { user_id: 'no_such_user', password: 'x' });
    expect(wrong.status).toBe(401);
    expect(unknown.status).toBe(401);
    expect(wrong.body.message).toBe(unknown.body.message);
  });

  it('rejects a blocked user, an inactive customer and an expired subscription', async () => {
    const blocked = await loginAs(app, await seedUser(await seedCustomer(), { allow: 'N' }));
    expect(blocked.status).toBe(403);
    expect(blocked.body.code).toBe('FORBIDDEN');

    const inactive = await loginAs(app, await seedUser(await seedCustomer({ active: 'N' })));
    expect(inactive.status).toBe(403);

    const expired = await loginAs(
      app,
      await seedUser(await seedCustomer({ renewal: '2020-01-01' })),
    );
    expect(expired.status).toBe(403);
    expect(expired.body).toMatchObject({
      code: 'SUBSCRIPTION_EXPIRED',
      errors: { renewal_dt: '2020-01-01' },
    });

    const noRenewal = await loginAs(app, await seedUser(await seedCustomer({ renewal: null })));
    expect(noRenewal.body.code).toBe('SUBSCRIPTION_EXPIRED');
  });

  it('second device → 409 SESSION_ACTIVE with start time and masked device', async () => {
    const user = await seedUser(await seedCustomer());
    await tokensFor(app, user, 'first-device-9876');
    const res = await loginAs(app, user, 'second-device');
    expect(res.status).toBe(409);
    expect(res.body.code).toBe('SESSION_ACTIVE');
    expect(res.body.errors.device_id).toBe('********9876');
    expect(res.body.errors.session_started_at).toMatch(/^\d{4}-\d{2}-\d{2} /);
  });

  it('only one of two simultaneous logins from different devices wins', async () => {
    const user = await seedUser(await seedCustomer());
    const results = await Promise.all([loginAs(app, user, 'dev-a'), loginAs(app, user, 'dev-b')]);
    expect(results.map((r) => r.status).sort()).toEqual([200, 409]);
  });

  it('same device logging in again replaces its old session', async () => {
    const user = await seedUser(await seedCustomer());
    const first = await tokensFor(app, user, 'same-device');
    const second = await tokensFor(app, user, 'same-device');
    expect((await request(app).get('/api/v1/auth/me').set(bearer(first.access))).status).toBe(401);
    expect((await request(app).get('/api/v1/auth/me').set(bearer(second.access))).status).toBe(200);
    expect((await session(sidOf(first.access)))?.ended_by).toBe('system:relogin');
  });

  it('enforces user_limit on concurrent sessions of a customer', async () => {
    const cid = await seedCustomer({ userLimit: 1 });
    const a = await seedUser(cid);
    // Seeded directly: user_limit is also enforced on user creation via the API.
    const b = await seedUser(cid);
    await tokensFor(app, a, 'dev-1');
    const res = await loginAs(app, b, 'dev-2');
    expect(res.status).toBe(409);
    expect(res.body.code).toBe('USER_LIMIT_REACHED');
  });

  it('expires sessions idle longer than the refresh TTL', async () => {
    const user = await seedUser(await seedCustomer());
    const old = await tokensFor(app, user, 'lost-device');
    await raw(
      `UPDATE td_user_session SET last_seen_at = NOW() - INTERVAL 31 DAY,
              started_at = NOW() - INTERVAL 31 DAY WHERE session_id = ?`,
      [sidOf(old.access)],
    );
    expect((await loginAs(app, user, 'new-device')).status).toBe(200);
    expect(await session(sidOf(old.access))).toMatchObject({
      status: 'L',
      ended_by: 'system:expired',
    });
  });

  it('lets a super admin log in without customer data', async () => {
    const sa = await seedUser(null, { type: 'S' });
    const res = await loginAs(app, sa);
    expect(res.status).toBe(200);
    expect(res.body.data).toMatchObject({ customer: null, settings: {}, counters: null });
    expect(res.body.data.masters.shifts).toEqual([]);
    expect(res.body.data.masters.id_proofs.length).toBeGreaterThanOrEqual(6);
  });

  it('validates the body', async () => {
    const res = await request(app).post('/api/v1/auth/login').send({ user_id: 'x' });
    expect(res.status).toBe(400);
    expect(res.body.code).toBe('VALIDATION_ERROR');
  });

  it('rate-limits login attempts per IP + user_id (10/min)', async () => {
    const statuses: number[] = [];
    for (let i = 0; i < 11; i++) {
      statuses.push((await loginAs(app, { user_id: 'brute_force_target', password: 'x' })).status);
    }
    expect(statuses.slice(0, 10).every((s) => s === 401)).toBe(true);
    expect(statuses[10]).toBe(429);
  });
});

describe('access token checks', () => {
  it('requires a valid, unexpired bearer token', async () => {
    expect((await request(app).get('/api/v1/auth/me')).status).toBe(401);
    expect((await request(app).get('/api/v1/auth/me').set(bearer('a.b.c'))).body.code).toBe(
      'UNAUTHORIZED',
    );

    const user = await seedUser(await seedCustomer());
    const { access } = await tokensFor(app, user);
    const claims = jwt.decode(access) as Record<string, unknown>;
    const expired = jwt.sign(
      { cid: claims.cid, typ: claims.typ, sid: claims.sid, exp: Math.floor(Date.now() / 1000) - 5 },
      env.JWT_ACCESS_SECRET,
      { subject: String(user.id), issuer: 'cloak-room-billing', audience: 'access' },
    );
    const res = await request(app).get('/api/v1/auth/me').set(bearer(expired));
    expect(res.status).toBe(401);
    expect(res.body.code).toBe('TOKEN_EXPIRED');

    const forged = jwt.sign({ cid: 1, typ: 'S', sid: claims.sid }, 'x'.repeat(40), {
      subject: String(user.id),
      issuer: 'cloak-room-billing',
      audience: 'access',
    });
    expect((await request(app).get('/api/v1/auth/me').set(bearer(forged))).status).toBe(401);

    // A refresh token is not an access token.
    const { refresh } = await tokensFor(app, await seedUser(await seedCustomer()));
    expect((await request(app).get('/api/v1/auth/me').set(bearer(refresh))).status).toBe(401);
  });

  it('GET /auth/me returns the caller from the DB', async () => {
    const user = await seedUser(await seedCustomer());
    const { access } = await tokensFor(app, user, 'me-device');
    const res = await request(app).get('/api/v1/auth/me').set(bearer(access));
    expect(res.status).toBe(200);
    expect(res.body.data.user).toMatchObject({
      id: user.id,
      user_id: user.user_id,
      device_id: 'me-device',
    });
    expect(res.body.data.customer.customer_id).toBe(user.customer_id);
    expect(res.body.data.user).not.toHaveProperty('password_hash');
  });
});

describe('POST /auth/refresh', () => {
  it('rotates both tokens; the old access token stops working', async () => {
    const user = await seedUser(await seedCustomer());
    const first = await tokensFor(app, user);
    const res = await request(app)
      .post('/api/v1/auth/refresh')
      .send({ refreshToken: first.refresh });
    expect(res.status).toBe(200);
    const { accessToken, refreshToken } = res.body.data;
    expect(refreshToken).not.toBe(first.refresh);
    expect(sidOf(accessToken)).toBe(sidOf(first.access));

    expect((await request(app).get('/api/v1/auth/me').set(bearer(accessToken))).status).toBe(200);
    const old = await request(app).get('/api/v1/auth/me').set(bearer(first.access));
    expect(old.status).toBe(401);
    expect(old.body.code).toBe('SESSION_ENDED');
  });

  it('reusing a rotated refresh token revokes the whole session', async () => {
    const user = await seedUser(await seedCustomer());
    const first = await tokensFor(app, user);
    const rotated = await request(app)
      .post('/api/v1/auth/refresh')
      .send({ refreshToken: first.refresh });
    const reuse = await request(app)
      .post('/api/v1/auth/refresh')
      .send({ refreshToken: first.refresh });
    expect(reuse.status).toBe(401);
    expect(reuse.body.code).toBe('TOKEN_REUSED');

    const after = await request(app)
      .get('/api/v1/auth/me')
      .set(bearer(rotated.body.data.accessToken));
    expect(after.status).toBe(401);
    expect(await session(sidOf(first.access))).toMatchObject({
      status: 'L',
      ended_by: 'system:refresh-reuse',
    });
    // The user can log in again.
    expect((await loginAs(app, user, 'other-device')).status).toBe(200);
  });

  it('rejects garbage, access tokens and tokens of ended sessions', async () => {
    expect(
      (await request(app).post('/api/v1/auth/refresh').send({ refreshToken: 'x.y.z' })).status,
    ).toBe(401);
    const user = await seedUser(await seedCustomer());
    const t = await tokensFor(app, user);
    expect(
      (await request(app).post('/api/v1/auth/refresh').send({ refreshToken: t.access })).status,
    ).toBe(401);
    await request(app).post('/api/v1/auth/logout').set(bearer(t.access));
    const ended = await request(app).post('/api/v1/auth/refresh').send({ refreshToken: t.refresh });
    expect(ended.body.code).toBe('SESSION_ENDED');
  });

  it('stops refreshing once the subscription has expired', async () => {
    const cid = await seedCustomer();
    const t = await tokensFor(app, await seedUser(cid));
    await save(
      'md_customer',
      { renewal_dt: '2020-01-01' },
      { flag: 'U', where: { customer_id: cid } },
      superAdmin,
    );
    const res = await request(app).post('/api/v1/auth/refresh').send({ refreshToken: t.refresh });
    expect(res.body.code).toBe('SUBSCRIPTION_EXPIRED');
  });
});

describe('POST /auth/logout', () => {
  it('ends the session, clears md_user and frees the user for another device', async () => {
    const user = await seedUser(await seedCustomer());
    const t = await tokensFor(app, user, 'dev-x');
    const res = await request(app).post('/api/v1/auth/logout').set(bearer(t.access));
    expect(res.status).toBe(200);
    expect(await session(sidOf(t.access))).toMatchObject({ status: 'L', ended_by: user.user_id });
    const [u] = await raw<RowDataPacket[]>(
      'SELECT device_id, session_token FROM md_user WHERE id = ?',
      [user.id],
    );
    expect(u).toMatchObject({ device_id: null, session_token: null });
    expect((await request(app).get('/api/v1/auth/me').set(bearer(t.access))).status).toBe(401);
    expect((await loginAs(app, user, 'dev-y')).status).toBe(200);
  });
});

describe('force logout', () => {
  it('a forced-out token may only push and log out, then is dead', async () => {
    const cid = await seedCustomer();
    const admin = await seedUser(cid, { type: 'A' });
    const op = await seedUser(cid);
    const adminT = await tokensFor(app, admin, 'admin-dev');
    const opT = await tokensFor(app, op, 'op-dev-1');

    const forced = await request(app)
      .post(`/api/v1/users/${op.id}/force-logout`)
      .set(bearer(adminT.access));
    expect(forced.status).toBe(200);
    expect(forced.body.data).toEqual({ sessions_ended: 1 });
    expect(await session(sidOf(opT.access))).toMatchObject({
      status: 'F',
      ended_by: admin.user_id,
    });

    const me = await request(app).get('/api/v1/auth/me').set(bearer(opT.access));
    expect(me.status).toBe(401);
    expect(me.body.code).toBe('SESSION_ENDED');

    // The user can already log in elsewhere while the old device still owes a push.
    const elsewhere = await tokensFor(app, op, 'op-dev-2');

    expect((await pushEmpty(opT.access)).status).toBe(200);
    expect(
      (await request(app).post('/api/v1/auth/refresh').send({ refreshToken: opT.refresh })).status,
    ).toBe(401);
    expect((await request(app).post('/api/v1/auth/logout').set(bearer(opT.access))).status).toBe(
      200,
    );
    expect((await pushEmpty(opT.access)).status).toBe(401);

    // Logging out the old device did not clear the new session's md_user columns.
    const [u] = await raw<RowDataPacket[]>('SELECT device_id FROM md_user WHERE id = ?', [op.id]);
    expect(u?.device_id).toBe('op-dev-2');
    expect((await request(app).get('/api/v1/auth/me').set(bearer(elsewhere.access))).status).toBe(
      200,
    );
  });
});

describe('must_change_pwd and POST /auth/change-password', () => {
  it('forces a password change after admin create / reset, without blocking a forced push', async () => {
    const cid = await seedCustomer({ userLimit: 10 });
    const admin = await seedUser(cid, { type: 'A' });
    const adminT = await tokensFor(app, admin, 'pwd-admin');
    const created = await request(app)
      .post('/api/v1/users')
      .set(bearer(adminT.access))
      .send({
        user_id: `pwd_${Date.now()}`,
        user_name: 'New Op',
        user_code: 'P01',
        password: 'First@1234',
        user_type: 'O',
      });
    expect(created.status).toBe(201);
    expect(created.body.data.must_change_pwd).toBe('Y');
    const op = { ...created.body.data, password: 'First@1234' };

    const first = await loginAs(app, op, 'pwd-dev-1');
    expect(first.status).toBe(200);
    expect(first.body.data.user.must_change_pwd).toBe('Y');
    const t = first.body.data.accessToken as string;

    const blocked = await request(app).get('/api/v1/sync/pull').set(bearer(t));
    expect(blocked.status).toBe(403);
    expect(blocked.body.code).toBe('PASSWORD_CHANGE_REQUIRED');
    expect((await pushEmpty(t)).status).toBe(403);
    expect((await request(app).get('/api/v1/auth/me').set(bearer(t))).status).toBe(200);

    const change = (body: Record<string, string>) =>
      request(app).post('/api/v1/auth/change-password').set(bearer(t)).send(body);
    const wrong = await change({ current_password: 'Nope@1234', new_password: 'Second@1234' });
    expect(wrong.status).toBe(400);
    expect(wrong.body.errors[0].path).toBe('current_password');
    expect(
      (await change({ current_password: 'First@1234', new_password: 'First@1234' })).status,
    ).toBe(400);
    expect((await change({ current_password: 'First@1234', new_password: 'short' })).status).toBe(
      400,
    );
    const ok = await change({ current_password: 'First@1234', new_password: 'Second@1234' });
    expect(ok.status).toBe(200);
    expect(ok.body.data).toEqual({ must_change_pwd: 'N' });

    // Same token keeps working; the new password is the one that logs in.
    expect((await request(app).get('/api/v1/sync/pull').set(bearer(t))).status).toBe(200);
    expect((await request(app).post('/api/v1/auth/logout').set(bearer(t))).status).toBe(200);
    expect((await loginAs(app, op, 'pwd-dev-1')).status).toBe(401);
    const second = await loginAs(app, { ...op, password: 'Second@1234' }, 'pwd-dev-1');
    expect(second.body.data.user.must_change_pwd).toBe('N');
    const t2 = second.body.data.accessToken as string;

    // Admin reset: the old (now F) session may still push its data, the next login must change.
    const reset = await request(app)
      .post(`/api/v1/users/${op.id}/reset-password`)
      .set(bearer(adminT.access))
      .send({ password: 'Third@1234' });
    expect(reset.status).toBe(200);
    expect((await pushEmpty(t2)).status).toBe(200);
    const third = await loginAs(app, { ...op, password: 'Third@1234' }, 'pwd-dev-2');
    expect(third.body.data.user.must_change_pwd).toBe('Y');
  });
});

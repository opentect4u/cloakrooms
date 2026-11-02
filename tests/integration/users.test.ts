import request from 'supertest';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { createApp } from '../../src/app';
import { closeDb, db } from '../../src/core/db/pool';
import { loadRegistry } from '../../src/core/db/registry';
import {
  bearer,
  loginAs,
  seedCustomer,
  seedUser,
  tokensFor,
  type SeededUser,
} from '../helpers/auth';

const app = createApp();

beforeAll(async () => {
  await loadRegistry(db);
});
afterAll(async () => {
  await closeDb();
});

const newUser = (over: Record<string, unknown> = {}) => ({
  user_id: `new_${Math.random().toString(36).slice(2, 10)}`,
  user_name: 'Counter Clerk',
  user_code: 'u07',
  password: 'Secret123',
  user_type: 'O',
  ...over,
});

async function adminSession(opts: { userLimit?: number } = {}) {
  const cid = await seedCustomer({ userLimit: opts.userLimit ?? 5 });
  const admin = await seedUser(cid, { type: 'A' });
  const { access } = await tokensFor(app, admin, `admin-${admin.id}`);
  return { cid, admin, auth: bearer(access) };
}

describe('users: create', () => {
  it('admin creates an operator in their own tenant; no hashes returned', async () => {
    const { cid, admin, auth } = await adminSession();
    const res = await request(app)
      .post('/api/v1/users')
      .set(auth)
      .send(newUser({ customer_id: 999 }));
    expect(res.status).toBe(201);
    expect(res.body.data).toMatchObject({
      customer_id: cid,
      user_code: 'U07',
      user_type: 'O',
      allow_flag: 'Y',
      created_by: admin.user_id,
    });
    expect(res.body.data).not.toHaveProperty('password_hash');
    expect(res.body.data).not.toHaveProperty('session_token');

    // …and that user can log in with the given password.
    expect(
      (await loginAs(app, { user_id: res.body.data.user_id, password: 'Secret123' })).status,
    ).toBe(200);
  });

  it('user_code is unique per customer; user_id is unique globally', async () => {
    const one = await adminSession();
    const two = await adminSession();
    expect(
      (
        await request(app)
          .post('/api/v1/users')
          .set(one.auth)
          .send(newUser({ user_code: 'X1' }))
      ).status,
    ).toBe(201);
    const dupCode = await request(app)
      .post('/api/v1/users')
      .set(one.auth)
      .send(newUser({ user_code: 'X1' }));
    expect(dupCode.status).toBe(409);
    expect(dupCode.body.errors.key).toBe('md_user.uq_user_code');
    expect(
      (
        await request(app)
          .post('/api/v1/users')
          .set(two.auth)
          .send(newUser({ user_code: 'X1' }))
      ).status,
    ).toBe(201);

    const body = newUser({ user_code: 'Y1' });
    await request(app).post('/api/v1/users').set(one.auth).send(body);
    const dupId = await request(app)
      .post('/api/v1/users')
      .set(two.auth)
      .send({ ...body, user_code: 'Y2' });
    expect(dupId.status).toBe(409);
  });

  it('enforces user_limit, validation and role rules', async () => {
    const { auth } = await adminSession({ userLimit: 2 }); // the admin already holds one seat
    expect(
      (
        await request(app)
          .post('/api/v1/users')
          .set(auth)
          .send(newUser({ user_code: 'A1' }))
      ).status,
    ).toBe(201);
    const full = await request(app)
      .post('/api/v1/users')
      .set(auth)
      .send(newUser({ user_code: 'A2' }));
    expect(full.status).toBe(409);
    expect(full.body.code).toBe('USER_LIMIT_REACHED');
    // A blocked user does not take a seat.
    expect(
      (
        await request(app)
          .post('/api/v1/users')
          .set(auth)
          .send(newUser({ user_code: 'A3', allow_flag: 'N' }))
      ).status,
    ).toBe(201);

    const weak = await request(app)
      .post('/api/v1/users')
      .set(auth)
      .send(newUser({ password: 'short' }));
    expect(weak.status).toBe(400);
    expect(
      (
        await request(app)
          .post('/api/v1/users')
          .set(auth)
          .send(newUser({ user_type: 'S' }))
      ).status,
    ).toBe(403);
    expect(
      (
        await request(app)
          .post('/api/v1/users')
          .set(auth)
          .send(newUser({ user_code: undefined }))
      ).status,
    ).toBe(400);
  });

  it('super admin must name the customer; can create super admins without one', async () => {
    const sa = await seedUser(null, { type: 'S' });
    const auth = bearer((await tokensFor(app, sa, 'sa-dev')).access);
    const cid = await seedCustomer();

    expect((await request(app).post('/api/v1/users').set(auth).send(newUser())).status).toBe(400);
    const ok = await request(app)
      .post('/api/v1/users')
      .set(auth)
      .send(newUser({ customer_id: cid, user_type: 'A' }));
    expect(ok.status).toBe(201);
    expect(ok.body.data.customer_id).toBe(cid);

    const s = await request(app)
      .post('/api/v1/users')
      .set(auth)
      .send(newUser({ user_type: 'S', user_code: undefined }));
    expect(s.status).toBe(201);
    expect(s.body.data).toMatchObject({ customer_id: null, user_code: null, user_type: 'S' });
    expect(
      (
        await request(app)
          .post('/api/v1/users')
          .set(auth)
          .send(newUser({ user_type: 'S', customer_id: cid }))
      ).status,
    ).toBe(400);
  });
});

describe('users: read / update / tenant isolation', () => {
  it('lists own tenant with pagination and search; other tenants are invisible', async () => {
    const { cid, auth } = await adminSession();
    const other = await adminSession();
    const target = await seedUser(cid);
    await seedUser(cid);

    const list = await request(app).get('/api/v1/users?limit=2&sort=id:asc').set(auth);
    expect(list.status).toBe(200);
    expect(list.body.meta).toEqual({ page: 1, limit: 2, total: 3 });
    expect(list.body.data.every((u: { customer_id: number }) => u.customer_id === cid)).toBe(true);

    const search = await request(app).get(`/api/v1/users?search=${target.user_id}`).set(auth);
    expect(search.body.data.map((u: SeededUser) => u.id)).toEqual([target.id]);

    for (const call of [
      request(app).get(`/api/v1/users/${target.id}`),
      request(app).patch(`/api/v1/users/${target.id}`).send({ user_name: 'x' }),
      request(app).post(`/api/v1/users/${target.id}/force-logout`),
      request(app)
        .post(`/api/v1/users/${target.id}/reset-password`)
        .send({ password: 'Another123' }),
      request(app).patch(`/api/v1/users/${target.id}/allow`).send({ allow_flag: 'N' }),
      request(app).delete(`/api/v1/users/${target.id}`),
    ]) {
      expect((await call.set(other.auth)).status).toBe(404);
    }
  });

  it('updates name/code/type', async () => {
    const { cid, auth } = await adminSession();
    const target = await seedUser(cid);
    const res = await request(app)
      .patch(`/api/v1/users/${target.id}`)
      .set(auth)
      .send({ user_name: 'Renamed', user_code: 'z9', user_type: 'A', password_hash: 'nope' });
    expect(res.status).toBe(200);
    expect(res.body.data).toMatchObject({ user_name: 'Renamed', user_code: 'Z9', user_type: 'A' });
    expect(
      (await request(app).patch(`/api/v1/users/${target.id}`).set(auth).send({ user_type: 'S' }))
        .status,
    ).toBe(400);
  });

  it('operators cannot manage users', async () => {
    const op = await seedUser(await seedCustomer());
    const auth = bearer((await tokensFor(app, op, 'op-dev')).access);
    const res = await request(app).get('/api/v1/users').set(auth);
    expect(res.status).toBe(403);
    expect(res.body.code).toBe('FORBIDDEN');
  });
});

describe('users: password, block, delete', () => {
  it('reset-password forces the user out and changes the password', async () => {
    const { cid, auth } = await adminSession();
    const target = await seedUser(cid);
    const t = await tokensFor(app, target, 'victim');
    const res = await request(app)
      .post(`/api/v1/users/${target.id}/reset-password`)
      .set(auth)
      .send({ password: 'BrandNew123' });
    expect(res.body.data).toEqual({ sessions_ended: 1 });
    expect((await request(app).get('/api/v1/auth/me').set(bearer(t.access))).body.code).toBe(
      'SESSION_ENDED',
    );
    expect((await loginAs(app, target, 'victim')).status).toBe(401);
    expect(
      (await loginAs(app, { user_id: target.user_id, password: 'BrandNew123' }, 'victim')).status,
    ).toBe(200);
  });

  it('blocking forces out and prevents login; unblocking respects user_limit', async () => {
    const { cid, auth, admin } = await adminSession({ userLimit: 2 });
    const target = await seedUser(cid);
    await tokensFor(app, target, 'tdev');

    const block = await request(app)
      .patch(`/api/v1/users/${target.id}/allow`)
      .set(auth)
      .send({ allow_flag: 'N' });
    expect(block.status).toBe(200);
    expect(block.body.data.allow_flag).toBe('N');
    expect((await loginAs(app, target, 'tdev')).status).toBe(403);

    // Seat freed by the block is taken by a new user; unblocking now exceeds the limit.
    expect(
      (
        await request(app)
          .post('/api/v1/users')
          .set(auth)
          .send(newUser({ user_code: 'B1' }))
      ).status,
    ).toBe(201);
    const unblock = await request(app)
      .patch(`/api/v1/users/${target.id}/allow`)
      .set(auth)
      .send({ allow_flag: 'Y' });
    expect(unblock.body.code).toBe('USER_LIMIT_REACHED');

    expect(
      (
        await request(app)
          .patch(`/api/v1/users/${admin.id}/allow`)
          .set(auth)
          .send({ allow_flag: 'N' })
      ).status,
    ).toBe(400);
    expect(
      (
        await request(app)
          .patch(`/api/v1/users/${admin.id}/allow`)
          .set(auth)
          .send({ allow_flag: 'Y' })
      ).status,
    ).toBe(200);
  });

  it('delete is soft, forces out and blocks login; self-delete is refused', async () => {
    const { cid, auth, admin } = await adminSession();
    const target = await seedUser(cid);
    await tokensFor(app, target, 'ddev');
    expect((await request(app).delete(`/api/v1/users/${target.id}`).set(auth)).status).toBe(200);
    expect((await request(app).get(`/api/v1/users/${target.id}`).set(auth)).status).toBe(404);
    expect((await loginAs(app, target, 'ddev')).status).toBe(401);
    expect((await request(app).delete(`/api/v1/users/${admin.id}`).set(auth)).status).toBe(400);
  });
});

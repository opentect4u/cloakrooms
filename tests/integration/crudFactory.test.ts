import express from 'express';
import request from 'supertest';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { z } from 'zod';
import type { RequestContext } from '../../src/core/context';
import { crudFactory } from '../../src/core/crud/crudFactory';
import { closeDb, db } from '../../src/core/db/pool';
import { loadRegistry } from '../../src/core/db/registry';
import { errorHandler } from '../../src/core/middleware/errorHandler';
import { notFound } from '../../src/core/middleware/notFound';
import { requestId } from '../../src/core/middleware/requestId';
import { money, timeOfDay, ynEnum } from '../../src/core/validation/fragments';
import { adminOf, operatorOf, superAdmin } from '../helpers/context';
import { createCustomer } from '../helpers/db';

const shiftCreate = z.object({
  shift_name: z.string().trim().min(1).max(50),
  from_time: timeOfDay,
  to_time: timeOfDay,
});

/** Test-only app: the X-Test-Auth header stands in for the Phase 3 auth middleware. */
function buildApp() {
  const app = express();
  app.use(requestId);
  app.use(express.json());
  app.use((req, _res, next) => {
    const h = req.get('x-test-auth');
    if (h) req.auth = JSON.parse(h) as RequestContext;
    next();
  });
  app.use(
    '/shifts',
    crudFactory({
      table: 'md_shift',
      pk: 'shift_id',
      scope: 'tenant',
      roles: { read: ['A', 'O', 'S'], write: ['A', 'S'] },
      schema: { create: shiftCreate, update: shiftCreate.partial() },
      searchable: ['shift_name'],
      sortable: ['shift_name', 'shift_id'],
      filterable: ['shift_name'],
      hooks: {
        beforeCreate: (data) => ({ ...data, shift_name: String(data.shift_name).toUpperCase() }),
        beforeUpdate: (data, existing) => {
          expect(existing.shift_id).toBeDefined();
          return data;
        },
      },
    }),
  );
  app.use(
    '/id-proofs',
    crudFactory({
      table: 'md_id_proof',
      pk: 'id_proof_id',
      scope: 'global',
      roles: { read: ['A', 'O', 'S'], write: ['S'] },
      schema: {
        create: z.object({
          id_proof_name: z.string().min(1).max(50),
          mask_flag: ynEnum.default('N'),
        }),
        update: z.object({ id_proof_name: z.string().min(1).max(50).optional() }),
      },
      hidden: ['created_by'],
    }),
  );
  app.use(
    '/rates',
    crudFactory({
      table: 'md_rate_dtls',
      pk: 'rate_id',
      scope: 'tenant',
      roles: { read: ['A'], write: ['A'] },
      schema: {
        create: z.object({
          first_block_rate: money,
          next_block_rate: money,
          effective_from: z.string(),
        }),
        update: z.object({}),
      },
    }),
  );
  app.use(notFound);
  app.use(errorHandler);
  return app;
}

const app = buildApp();
const as = (ctx: RequestContext) => ({ 'x-test-auth': JSON.stringify(ctx) });

let c1: number;
let c2: number;

beforeAll(async () => {
  await loadRegistry(db);
  c1 = await createCustomer();
  c2 = await createCustomer();
});

afterAll(async () => {
  await closeDb();
});

describe('crudFactory (tenant table)', () => {
  it('creates with the tenant from the token, ignoring a body customer_id', async () => {
    const res = await request(app)
      .post('/shifts')
      .set(as(adminOf(c1)))
      .send({ shift_name: 'morning', from_time: '06:00', to_time: '14:00', customer_id: c2 });
    expect(res.status).toBe(201);
    expect(res.body).toMatchObject({
      success: true,
      message: 'Created',
      data: { customer_id: c1, shift_name: 'MORNING', from_time: '06:00:00' },
    });
  });

  it('requires customer_id from a super admin and accepts it', async () => {
    const body = { shift_name: 'sa shift', from_time: '22:00', to_time: '06:00' };
    const missing = await request(app).post('/shifts').set(as(superAdmin)).send(body);
    expect(missing.status).toBe(400);
    expect(missing.body.code).toBe('VALIDATION_ERROR');

    const ok = await request(app)
      .post('/shifts')
      .set(as(superAdmin))
      .send({ ...body, customer_id: c2 });
    expect(ok.status).toBe(201);
    expect(ok.body.data.customer_id).toBe(c2);
  });

  it('lists with pagination meta, sort, search and filters — own tenant only', async () => {
    const cust = await createCustomer();
    const A = adminOf(cust);
    for (const n of ['b-day', 'a-day', 'c-night']) {
      await request(app)
        .post('/shifts')
        .set(as(A))
        .send({ shift_name: n, from_time: '06:00', to_time: '14:00' });
    }

    const list = await request(app).get('/shifts?limit=2&sort=shift_name:desc').set(as(A));
    expect(list.status).toBe(200);
    expect(list.body.meta).toEqual({ page: 1, limit: 2, total: 3 });
    expect(list.body.data.map((r: { shift_name: string }) => r.shift_name)).toEqual([
      'C-NIGHT',
      'B-DAY',
    ]);

    const search = await request(app).get('/shifts?search=day').set(as(A));
    expect(search.body.meta.total).toBe(2);

    const filtered = await request(app).get('/shifts?shift_name=A-DAY').set(as(A));
    expect(filtered.body.data).toHaveLength(1);

    // An operator of another tenant asking for this tenant's rows gets only their own.
    const other = await request(app)
      .get(`/shifts?customer_id=${cust}`)
      .set(as(operatorOf(c2)));
    expect(other.body.data.every((r: { customer_id: number }) => r.customer_id === c2)).toBe(true);

    const badSort = await request(app).get('/shifts?sort=customer_id:asc').set(as(A));
    expect(badSort.status).toBe(400);
  });

  it('gets, updates and soft-deletes; other tenants get 404', async () => {
    const A = adminOf(c1);
    const created = await request(app)
      .post('/shifts')
      .set(as(A))
      .send({ shift_name: 'temp', from_time: '01:00', to_time: '02:00' });
    const id = created.body.data.shift_id;

    expect(
      (
        await request(app)
          .get(`/shifts/${id}`)
          .set(as(adminOf(c2)))
      ).status,
    ).toBe(404);
    expect(
      (
        await request(app)
          .patch(`/shifts/${id}`)
          .set(as(adminOf(c2)))
          .send({ shift_name: 'x' })
      ).status,
    ).toBe(404);
    expect(
      (
        await request(app)
          .delete(`/shifts/${id}`)
          .set(as(adminOf(c2)))
      ).status,
    ).toBe(404);

    const upd = await request(app)
      .patch(`/shifts/${id}`)
      .set(as(A))
      .send({ shift_name: 'renamed' });
    expect(upd.status).toBe(200);
    expect(upd.body.data.shift_name).toBe('renamed');

    const empty = await request(app).patch(`/shifts/${id}`).set(as(A)).send({});
    expect(empty.status).toBe(400);

    const del = await request(app).delete(`/shifts/${id}`).set(as(A));
    expect(del.body).toMatchObject({ success: true, message: 'Deleted', data: { shift_id: id } });
    expect((await request(app).get(`/shifts/${id}`).set(as(A))).status).toBe(404);
    expect((await request(app).delete(`/shifts/${id}`).set(as(A))).status).toBe(404);
  });

  it('enforces auth, roles and validation', async () => {
    expect((await request(app).get('/shifts')).status).toBe(401);
    const op = await request(app)
      .post('/shifts')
      .set(as(operatorOf(c1)))
      .send({ shift_name: 'x', from_time: '06:00', to_time: '14:00' });
    expect(op.status).toBe(403);
    expect(op.body.code).toBe('FORBIDDEN');

    const bad = await request(app)
      .post('/shifts')
      .set(as(adminOf(c1)))
      .send({ shift_name: '', from_time: '25:00' });
    expect(bad.status).toBe(400);
    expect(bad.body.errors.length).toBeGreaterThanOrEqual(3);

    expect(
      (
        await request(app)
          .get('/shifts/abc')
          .set(as(adminOf(c1)))
      ).status,
    ).toBe(400);
  });

  it('maps a duplicate unique key to 409 CONFLICT', async () => {
    const A = adminOf(c2);
    const body = { first_block_rate: 50, next_block_rate: '20.00', effective_from: '2026-04-01' };
    expect((await request(app).post('/rates').set(as(A)).send(body)).status).toBe(201);
    const dup = await request(app).post('/rates').set(as(A)).send(body);
    expect(dup.status).toBe(409);
    expect(dup.body).toMatchObject({
      code: 'CONFLICT',
      errors: { key: 'md_rate_dtls.uq_rate_effective' },
    });
  });
});

describe('crudFactory (global table)', () => {
  it('everyone reads, only S writes, hidden columns are not returned', async () => {
    const list = await request(app)
      .get('/id-proofs')
      .set(as(operatorOf(c1)));
    expect(list.status).toBe(200);
    expect(list.body.meta.total).toBeGreaterThanOrEqual(6);
    expect(list.body.data[0]).not.toHaveProperty('created_by');

    const denied = await request(app)
      .post('/id-proofs')
      .set(as(adminOf(c1)))
      .send({ id_proof_name: 'X' });
    expect(denied.status).toBe(403);

    const created = await request(app)
      .post('/id-proofs')
      .set(as(superAdmin))
      .send({ id_proof_name: 'Student ID' });
    expect(created.status).toBe(201);
    expect(created.body.data).toMatchObject({ id_proof_name: 'Student ID', mask_flag: 'N' });
  });
});

/**
 * Cross-cutting hardening checks (BUILD_PROMPT Phase 8). The route audit walks every registered
 * endpoint, so a router added later without auth fails here.
 */
import { Writable } from 'node:stream';
import listEndpoints from 'express-list-endpoints';
import jwt from 'jsonwebtoken';
import pino from 'pino';
import request from 'supertest';
import { afterAll, describe, expect, it, vi } from 'vitest';
import { createApp } from '../../src/app';
import { env } from '../../src/config/env';
import { closeDb } from '../../src/core/db/pool';
import { REDACT_PATHS } from '../../src/core/logger';

const app = createApp();

afterAll(async () => {
  await closeDb();
});

/** Endpoints reachable without a token, by design. */
const PUBLIC = new Set([
  'GET /api/v1/health',
  'GET /api/v1/ready',
  'POST /api/v1/auth/login',
  'POST /api/v1/auth/refresh',
  'GET /api/v1/app/version',
]);

const SAMPLE_PARAMS: Record<string, string> = {
  ':id': '1',
  ':customerId': '1',
  ':trans_id': '3f2504e0-4f89-41d3-9a0c-0305e82c3301',
};

/**
 * express-list-endpoints prints a mount path that has a param (`/:customerId/registrations`) as its
 * compiled RegExp, e.g. ` RegExp(/^(?:\/)\/registrations\/?(?=\/|$)/i) `; turn it back into a path.
 */
const MOUNT_REGEXP = / RegExp\(\/\^\(\?:\\\/\)\\\/([\w-]+)\\\/\?\(\?=\\\/\|\$\)\/i\) ?/;

function endpoints() {
  return listEndpoints(app).flatMap((e) => {
    const path = e.path.replace(MOUNT_REGEXP, ':customerId/$1');
    return e.methods.map((m) => ({ method: m, path, key: `${m} ${path}` }));
  });
}

describe('route audit', () => {
  it('lists the full API surface', () => {
    const keys = endpoints().map((e) => e.key);
    expect(keys.length).toBeGreaterThan(60);
    for (const must of [
      'POST /api/v1/sync/push',
      'GET /api/v1/sync/pull',
      'GET /api/v1/reports/collection-summary',
      'POST /api/v1/header-footer/logo',
      'GET /api/v1/customers/:customerId/registrations/:id',
      'PATCH /api/v1/users/:id/allow',
    ]) {
      expect(keys).toContain(must);
    }
  });

  it('every non-public endpoint rejects anonymous requests with 401', async () => {
    const failures: string[] = [];
    for (const e of endpoints()) {
      if (!e.path.startsWith('/api/v1') || PUBLIC.has(e.key)) continue;
      const url = e.path.replace(/:\w+/g, (p) => SAMPLE_PARAMS[p] ?? '1');
      const method = e.method.toLowerCase() as 'get' | 'post' | 'patch' | 'delete' | 'put';
      const res = await request(app)[method](url).send({});
      if (res.status !== 401) failures.push(`${e.key} → ${res.status}`);
    }
    expect(failures).toEqual([]);
  });
});

describe('tokens', () => {
  it('rejects unsigned (alg: none) and wrongly-signed tokens', async () => {
    const header = Buffer.from(JSON.stringify({ alg: 'none', typ: 'JWT' })).toString('base64url');
    const payload = Buffer.from(
      JSON.stringify({
        sub: '1',
        cid: null,
        typ: 'S',
        sid: 'x',
        aud: 'access',
        iss: 'cloak-room-billing',
        exp: 9_999_999_999,
      }),
    ).toString('base64url');
    const none = await request(app)
      .get('/api/v1/auth/me')
      .set('Authorization', `Bearer ${header}.${payload}.`);
    expect(none.status).toBe(401);

    const hs512 = jwt.sign({ cid: null, typ: 'S', sid: 'x' }, env.JWT_ACCESS_SECRET, {
      algorithm: 'HS512',
      subject: '1',
      issuer: 'cloak-room-billing',
      audience: 'access',
    });
    expect(
      (await request(app).get('/api/v1/auth/me').set('Authorization', `Bearer ${hs512}`)).status,
    ).toBe(401);
  });
});

describe('response headers', () => {
  it('API responses are never cacheable and carry security headers', async () => {
    const res = await request(app).get('/api/v1/health');
    expect(res.headers['cache-control']).toBe('no-store');
    expect(res.headers['strict-transport-security']).toMatch(/max-age=\d+/);
    expect(res.headers['x-content-type-options']).toBe('nosniff');
    expect(res.headers['x-frame-options']).toBe('SAMEORIGIN');
    expect(res.headers['content-security-policy']).toBeDefined();
    expect(res.headers['x-powered-by']).toBeUndefined();
  });
});

describe('log redaction', () => {
  it('censors secrets, tokens, ID numbers and SQL with bound values', () => {
    const lines: string[] = [];
    const sink = new Writable({
      write(chunk, _enc, cb) {
        lines.push(String(chunk));
        cb();
      },
    });
    const log = pino({ redact: { paths: REDACT_PATHS, censor: '[REDACTED]' } }, sink);
    log.info({
      req: { headers: { authorization: 'Bearer abc', 'x-report-password': 'pw' } },
      body: { password: 'secret1', id_number: '123456789012', refreshToken: 'r' },
      err: { message: 'boom', sql: "INSERT … VALUES ('Ravi', '9876543210')" },
    });
    const out = lines.join('');
    for (const leaked of ['Bearer abc', 'secret1', '123456789012', '9876543210', '"pw"', '"r"']) {
      expect(out).not.toContain(leaked);
    }
    expect(out).toContain('[REDACTED]');
  });
});

describe('production error responses', () => {
  it('hide internal error details and stack traces', async () => {
    vi.resetModules();
    vi.doMock('../../src/config/env', async (orig) => ({
      ...(await orig<Record<string, unknown>>()),
      isProd: true,
    }));
    const express = (await import('express')).default;
    const { errorHandler } = await import('../../src/core/middleware/errorHandler.js');
    const { requestId } = await import('../../src/core/middleware/requestId.js');
    const prodApp = express();
    prodApp.use(requestId);
    prodApp.get('/boom', () => {
      throw new Error('db password is hunter2');
    });
    prodApp.get('/bad-value', (_req, _res, next) =>
      next(
        Object.assign(new Error('x'), {
          code: 'ER_DATA_TOO_LONG',
          sqlMessage: "Data too long for column 'pasn_name'",
        }),
      ),
    );
    prodApp.use(errorHandler);

    const res = await request(prodApp).get('/boom');
    expect(res.status).toBe(500);
    expect(res.body).toEqual({
      success: false,
      message: 'Internal server error',
      code: 'INTERNAL_ERROR',
      data: null,
      requestId: expect.any(String),
    });
    expect(JSON.stringify(res.body)).not.toMatch(/hunter2|stack|at /);

    const bad = await request(prodApp).get('/bad-value');
    expect(bad.status).toBe(400);
    expect(bad.body.errors).toBeUndefined();
    vi.doUnmock('../../src/config/env');
  });
});

import express, { type NextFunction, type Request, type Response } from 'express';
import request from 'supertest';
import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../src/core/http/ApiError';
import { errorHandler } from '../../src/core/middleware/errorHandler';
import { createRateLimiter } from '../../src/core/middleware/rateLimit';
import { requestId } from '../../src/core/middleware/requestId';
import { hashPassword, verifyPassword } from '../../src/core/security/password';
import { onShutdown, runShutdownHooks } from '../../src/core/shutdown';

describe('shutdown hooks', () => {
  it('runs hooks in reverse order and survives a failing hook', async () => {
    const order: string[] = [];
    onShutdown('first', () => {
      order.push('first');
    });
    onShutdown('failing', () => {
      throw new Error('nope');
    });
    onShutdown('last', async () => {
      order.push('last');
    });
    await runShutdownHooks();
    expect(order).toEqual(['last', 'first']);
  });
});

describe('password hashing', () => {
  it('hashes with argon2id and verifies', async () => {
    const hash = await hashPassword('S3cret!pass');
    expect(hash).toMatch(/^\$argon2id\$v=19\$m=19456,t=2,p=1\$/);
    expect(await verifyPassword(hash, 'S3cret!pass')).toBe(true);
    expect(await verifyPassword(hash, 'wrong')).toBe(false);
  });

  it('treats a malformed stored hash as a wrong password', async () => {
    expect(await verifyPassword('not-a-hash', 'x')).toBe(false);
  });
});

describe('errorHandler edge cases', () => {
  function appThrowing(err: unknown) {
    const app = express();
    app.use(requestId);
    app.get('/', (_req, _res, next) => next(err));
    app.use(errorHandler);
    return app;
  }

  it('maps other 4xx body-parser errors to BAD_REQUEST', async () => {
    const err = Object.assign(new Error('unsupported charset'), {
      type: 'charset.unsupported',
      status: 415,
    });
    const res = await request(appThrowing(err)).get('/');
    expect(res.status).toBe(400);
    expect(res.body).toMatchObject({ code: 'BAD_REQUEST', message: 'unsupported charset' });
  });

  it('maps mysql errors and zod errors', async () => {
    const dup = await request(appThrowing({ code: 'ER_DUP_ENTRY', message: 'dup' })).get('/');
    expect(dup.status).toBe(409);

    const { z } = await import('zod');
    const zerr = z.object({ a: z.string() }).safeParse({}).error;
    const res = await request(appThrowing(zerr)).get('/');
    expect(res.status).toBe(400);
    expect(res.body.errors[0].path).toBe('a');
  });

  it('hides unknown errors behind 500 INTERNAL_ERROR (details only outside production)', async () => {
    const res = await request(appThrowing(new Error('secret detail'))).get('/');
    expect(res.status).toBe(500);
    expect(res.body).toMatchObject({ code: 'INTERNAL_ERROR', message: 'Internal server error' });
    expect(res.body.errors.error).toBe('secret detail'); // NODE_ENV=test

    const nonError = await request(appThrowing('a string')).get('/');
    expect(nonError.status).toBe(500);
    expect(nonError.body.errors).toBeUndefined();
  });

  it('ends the response if headers were already sent', () => {
    const res = { headersSent: true, end: vi.fn() } as unknown as Response;
    errorHandler(
      new ApiError(400, 'BAD_REQUEST', 'x'),
      { requestId: 'r' } as Request,
      res,
      vi.fn() as NextFunction,
    );
    expect(res.end).toHaveBeenCalled();
  });
});

describe('createRateLimiter', () => {
  it('returns 429 RATE_LIMITED with the envelope once the limit is hit', async () => {
    const app = express();
    app.use(requestId);
    app.use(createRateLimiter({ name: 'test', limit: 2 }));
    app.get('/', (_req, res) => {
      res.json({ ok: true });
    });
    app.use(errorHandler);

    expect((await request(app).get('/')).status).toBe(200);
    expect((await request(app).get('/')).status).toBe(200);
    const limited = await request(app).get('/');
    expect(limited.status).toBe(429);
    expect(limited.body).toMatchObject({ success: false, code: 'RATE_LIMITED' });
    expect(limited.headers['ratelimit-policy']).toBeDefined();
  });
});

import request from 'supertest';
import { afterAll, describe, expect, it, vi } from 'vitest';
import { createApp } from '../../src/app';
import { closeDb, db } from '../../src/core/db/pool';
import { loadRegistry } from '../../src/core/db/registry';

const app = createApp();

describe('GET /api/v1/health', () => {
  it('returns the standard envelope', async () => {
    const res = await request(app).get('/api/v1/health');

    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({
      success: true,
      message: 'OK',
      data: { status: 'ok' },
    });
    expect(res.body.data.server_time).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/);
    expect(res.body.requestId).toBe(res.headers['x-request-id']);
  });

  it('sets security headers and hides x-powered-by', async () => {
    const res = await request(app).get('/api/v1/health');

    expect(res.headers['x-powered-by']).toBeUndefined();
    expect(res.headers['x-content-type-options']).toBe('nosniff');
    expect(res.headers['ratelimit-policy']).toBeDefined();
  });

  it('echoes a well-formed incoming request id and replaces a malformed one', async () => {
    const ok = await request(app).get('/api/v1/health').set('X-Request-Id', 'abc12345-req');
    expect(ok.body.requestId).toBe('abc12345-req');

    const bad = await request(app).get('/api/v1/health').set('X-Request-Id', '<script>');
    expect(bad.body.requestId).not.toBe('<script>');
    expect(bad.body.requestId).toMatch(/^[0-9a-f-]{36}$/);
  });
});

describe('GET /api/v1/ready', () => {
  afterAll(async () => {
    await closeDb();
  });

  it('is 503 until the registry is loaded, then 200', async () => {
    const before = await request(app).get('/api/v1/ready');
    expect(before.status).toBe(503);
    expect(before.body).toMatchObject({ success: false, code: 'SERVICE_UNAVAILABLE' });

    await loadRegistry(db);
    const after = await request(app).get('/api/v1/ready');
    expect(after.status).toBe(200);
    expect(after.body.data).toMatchObject({ status: 'ready', db: 'ok' });
  });

  it('is 503 when the database is unreachable', async () => {
    const spy = vi.spyOn(db, 'query').mockRejectedValueOnce(new Error('ECONNREFUSED'));
    const res = await request(app).get('/api/v1/ready');
    expect(res.status).toBe(503);
    expect(res.body.message).toBe('Database unreachable');
    spy.mockRestore();
  });
});

describe('error envelope', () => {
  it('returns 404 NOT_FOUND for unknown routes', async () => {
    const res = await request(app).get('/api/v1/nope');

    expect(res.status).toBe(404);
    expect(res.body).toMatchObject({ success: false, code: 'NOT_FOUND', data: null });
    expect(res.body.requestId).toBeTruthy();
  });

  it('returns 400 INVALID_JSON for a malformed body', async () => {
    const res = await request(app)
      .post('/api/v1/health')
      .set('Content-Type', 'application/json')
      .send('{"broken":');

    expect(res.status).toBe(400);
    expect(res.body).toMatchObject({ success: false, code: 'INVALID_JSON' });
  });

  it('returns 413 PAYLOAD_TOO_LARGE above the 1 MB limit', async () => {
    const big = JSON.stringify({ blob: 'x'.repeat(1024 * 1024 + 10) });
    const res = await request(app)
      .post('/api/v1/health')
      .set('Content-Type', 'application/json')
      .send(big);

    expect(res.status).toBe(413);
    expect(res.body.code).toBe('PAYLOAD_TOO_LARGE');
  });
});

describe('CORS', () => {
  it('allows listed origins and no-origin clients, ignores others', async () => {
    const allowed = await request(app).get('/api/v1/health').set('Origin', 'http://localhost:3000');
    expect(allowed.headers['access-control-allow-origin']).toBe('http://localhost:3000');

    const denied = await request(app).get('/api/v1/health').set('Origin', 'http://evil.test');
    expect(denied.headers['access-control-allow-origin']).toBeUndefined();
  });
});

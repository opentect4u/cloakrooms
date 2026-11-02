/**
 * Keeps the published contract (CLAUDE.md §12) in step with the code: every mounted route must be
 * documented and every documented route must exist.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import listEndpoints from 'express-list-endpoints';
import request from 'supertest';
import { afterAll, describe, expect, it } from 'vitest';
import { createApp } from '../../src/app';
import { closeDb } from '../../src/core/db/pool';
import { buildOpenApiDocument } from '../../src/docs/openapi';

const app = createApp();
const doc = buildOpenApiDocument();

afterAll(async () => {
  await closeDb();
});

/** See security.test.ts: param mount paths are printed as RegExps by express-list-endpoints. */
const MOUNT_REGEXP = / RegExp\(\/\^\(\?:\\\/\)\\\/([\w-]+)\\\/\?\(\?=\\\/\|\$\)\/i\) ?/;

const routeKeys = () =>
  new Set(
    listEndpoints(app).flatMap((e) => {
      const path = e.path.replace(MOUNT_REGEXP, ':customerId/$1');
      return path.startsWith('/api/v1') ? e.methods.map((m) => `${m} ${path}`) : [];
    }),
  );

const docKeys = () =>
  new Set(
    Object.entries(doc.paths ?? {}).flatMap(([path, item]) =>
      path.startsWith('/api/v1')
        ? Object.keys(item as object).map(
            (m) => `${m.toUpperCase()} ${path.replace(/\{(\w+)\}/g, ':$1')}`,
          )
        : [],
    ),
  );

describe('OpenAPI document', () => {
  it('documents exactly the mounted /api/v1 routes', () => {
    const routes = routeKeys();
    const documented = docKeys();
    expect([...routes].filter((k) => !documented.has(k))).toEqual([]);
    expect([...documented].filter((k) => !routes.has(k))).toEqual([]);
    expect(routes.size).toBeGreaterThan(60);
  });

  it('declares bearer auth except on public endpoints and describes the envelope', () => {
    const login = doc.paths['/api/v1/auth/login']?.post;
    expect(login?.security).toEqual([]);
    expect(doc.paths['/api/v1/sync/push']?.post?.security).toEqual([{ bearerAuth: [] }]);
    expect(doc.components?.securitySchemes?.bearerAuth).toMatchObject({ scheme: 'bearer' });
    expect(JSON.stringify(doc.paths['/api/v1/sync/pull']?.get)).toContain('next_cursor');
    expect(doc.info.description).toContain('AMOUNT_MISMATCH');
  });

  it('matches the committed docs/openapi.json (run `npm run openapi` after API changes)', () => {
    const committed = JSON.parse(readFileSync(join('docs', 'openapi.json'), 'utf8'));
    expect(committed).toEqual(JSON.parse(JSON.stringify(doc)));
  });

  it('is served as JSON and through Swagger UI', async () => {
    const json = await request(app).get('/api/docs/openapi.json');
    expect(json.status).toBe(200);
    expect(json.body.openapi).toBe('3.0.3');
    const ui = await request(app).get('/api/docs/');
    expect(ui.status).toBe(200);
    expect(ui.text).toContain('swagger-ui');
  });
});

import request from 'supertest';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { createApp } from '../../src/app';
import { closeDb, db } from '../../src/core/db/pool';
import { loadRegistry } from '../../src/core/db/registry';
import { bearer, seedCustomer, seedUser, tokensFor } from '../helpers/auth';

const app = createApp();
let sa: Record<string, string>;
let admin: Record<string, string>;

beforeAll(async () => {
  await loadRegistry(db);
  sa = bearer((await tokensFor(app, await seedUser(null, { type: 'S' }), 'v-sa')).access);
  admin = bearer(
    (await tokensFor(app, await seedUser(await seedCustomer(), { type: 'A' }), 'v-admin')).access,
  );
});
afterAll(async () => {
  await closeDb();
});

const publish = (body: Record<string, unknown>, auth = sa) =>
  request(app).post('/api/v1/app/version').set(auth).send(body);

describe('app version', () => {
  it('publishes releases (S only) and reports update / mandatory status publicly', async () => {
    expect((await request(app).get('/api/v1/app/version')).body.data).toEqual({
      latest: null,
      update_available: null,
      mandatory: null,
    });

    expect(
      (await publish({ version_code: 10, version_name: '1.0', apk_url: '/downloads/x.apk' }, admin))
        .status,
    ).toBe(403);
    const v10 = await publish({
      version_code: 10,
      version_name: '1.0.0',
      apk_url: '/downloads/cloakroom-1.0.0.apk',
      mandatory_flag: 'Y',
    });
    expect(v10.status).toBe(201);
    expect(v10.body.data).toMatchObject({ version_code: 10, mandatory_flag: 'Y' });
    expect(
      (
        await publish({
          version_code: 11,
          version_name: '1.1.0',
          apk_url: 'https://cdn.example.com/cr-1.1.0.apk',
          release_notes: 'Fixes',
        })
      ).status,
    ).toBe(201);
    expect(
      (await publish({ version_code: 11, version_name: 'dup', apk_url: 'https://x.example/a.apk' }))
        .status,
    ).toBe(409);
    expect(
      (await publish({ version_code: 12, version_name: 'bad', apk_url: 'http://insecure/a.apk' }))
        .status,
    ).toBe(400);

    const latest = await request(app).get('/api/v1/app/version');
    expect(latest.body.data.latest).toMatchObject({
      version_code: 11,
      version_name: '1.1.0',
      release_notes: 'Fixes',
    });

    expect((await request(app).get('/api/v1/app/version?current=9')).body.data).toMatchObject({
      update_available: true,
      mandatory: true,
    });
    expect((await request(app).get('/api/v1/app/version?current=10')).body.data).toMatchObject({
      update_available: true,
      mandatory: false,
    });
    expect((await request(app).get('/api/v1/app/version?current=11')).body.data).toMatchObject({
      update_available: false,
      mandatory: false,
    });
  });
});

describe('/downloads', () => {
  it('serves APKs with caching headers and nothing else', async () => {
    const apk = await request(app).get('/downloads/cloakroom-1.0.0.apk');
    expect(apk.status).toBe(200);
    expect(apk.headers['content-type']).toBe('application/vnd.android.package-archive');
    expect(apk.headers['cache-control']).toBe('public, max-age=86400');
    expect(apk.headers.etag).toBeTruthy();
    expect(apk.headers['content-disposition']).toBe('attachment');

    expect((await request(app).get('/downloads/notes.txt')).status).toBe(404);
    expect((await request(app).get('/downloads/missing.apk')).status).toBe(404);
    expect((await request(app).get('/downloads/..%2F..%2Fpackage.json')).status).toBe(404);
    expect((await request(app).get('/downloads/')).status).toBe(404);
  });
});

import jwt from 'jsonwebtoken';
import request from 'supertest';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { PANEL_DEVICE_ID } from '../../src/admin/auth.routes';
import { dashboard } from '../../src/admin/dashboard.service';
import { createApp } from '../../src/app';
import { env } from '../../src/config/env';
import { fetch, fetchOne, save } from '../../src/core/db/dml';
import { closeDb, db } from '../../src/core/db/pool';
import { loadRegistry } from '../../src/core/db/registry';
import { loginAs, seedCustomer, seedUser, type SeededUser } from '../helpers/auth';
import { superAdmin } from '../helpers/context';

const app = createApp();

beforeAll(async () => {
  await loadRegistry(db);
});
afterAll(async () => {
  await closeDb();
});

type Agent = ReturnType<typeof request.agent>;

const csrfOf = (html: string) => /name="_csrf" value="([^"]+)"/.exec(html)?.[1] ?? '';

/** Opens the sign-in page (sets the CSRF cookie) and returns its token. */
async function csrf(agent: Agent, path = '/admin/login') {
  const res = await agent.get(path);
  return csrfOf(res.text);
}

async function signIn(agent: Agent, user: Pick<SeededUser, 'user_id' | 'password'>) {
  const token = await csrf(agent);
  return agent
    .post('/admin/login')
    .type('form')
    .send({ _csrf: token, user_id: user.user_id, password: user.password });
}

const cookieHeader = (res: request.Response) => {
  const raw = res.headers['set-cookie'] as unknown as string[] | string | undefined;
  return Array.isArray(raw) ? raw : raw ? [raw] : [];
};

async function panelSessions(userId: number) {
  const { rows } = await fetch(
    'td_user_session',
    { where: { user_id: userId }, includeDeleted: true },
    superAdmin,
  );
  return rows;
}

describe('admin panel: pages and assets', () => {
  it('serves the sign-in page with a strict CSP, a CSRF cookie and no-store', async () => {
    const res = await request(app).get('/admin/login');
    expect(res.status).toBe(200);
    expect(res.type).toBe('text/html');
    expect(res.text).toContain('Welcome back');
    expect(res.headers['cache-control']).toBe('no-store');
    const csp = String(res.headers['content-security-policy']);
    expect(csp).toContain("script-src 'self'");
    expect(csp).toContain("style-src 'self'");
    expect(csp).not.toContain('unsafe-inline');
    expect(csp).toContain("frame-ancestors 'none'");
    const cookies = cookieHeader(res).join(';');
    expect(cookies).toMatch(/crb_csrf=.*HttpOnly/);
    expect(cookies).toContain('SameSite=Strict');
    expect(cookies).toContain('Path=/admin');
  });

  it('serves own and vendored assets; missing assets are 404', async () => {
    expect((await request(app).get('/admin/assets/css/admin.css')).status).toBe(200);
    expect((await request(app).get('/admin/assets/js/admin.js')).status).toBe(200);
    expect(
      (await request(app).get('/admin/assets/vendor/bootstrap/css/bootstrap.min.css')).status,
    ).toBe(200);
    expect(
      (await request(app).get('/admin/assets/vendor/icons/bootstrap-icons.min.css')).status,
    ).toBe(200);
    expect((await request(app).get('/admin/assets/vendor/font/index.css')).status).toBe(200);
    expect((await request(app).get('/admin/assets/nope.css')).status).toBe(404);
  });

  it('redirects anonymous visitors to sign-in, remembering where they were going', async () => {
    const res = await request(app).get('/admin');
    expect(res.status).toBe(303);
    expect(res.headers.location).toBe('/admin/login?reason=&next=%2Fadmin');
    const other = await request(app).get('/admin/anything');
    expect(other.status).toBe(303);
  });
});

describe('admin panel: sign-in', () => {
  it('rejects a post without a valid CSRF token', async () => {
    const sa = await seedUser(null, { type: 'S' });
    const agent = request.agent(app);
    await agent.get('/admin/login');
    const res = await agent
      .post('/admin/login')
      .type('form')
      .send({ _csrf: 'forged', user_id: sa.user_id, password: sa.password });
    expect(res.status).toBe(303);
    expect(cookieHeader(res).join(';')).not.toContain('crb_at=');
    expect(await panelSessions(sa.id)).toHaveLength(0);
  });

  it('shows a friendly error for a wrong password', async () => {
    const sa = await seedUser(null, { type: 'S' });
    const res = await signIn(request.agent(app), { user_id: sa.user_id, password: 'wrong-pass1' });
    expect(res.status).toBe(401);
    expect(res.text).toContain('don’t match');
    expect(res.text).toContain(`value="${sa.user_id}"`);
  });

  it('refuses station staff before creating any session', async () => {
    const admin = await seedUser(await seedCustomer(), { type: 'A' });
    const res = await signIn(request.agent(app), admin);
    expect(res.status).toBe(403);
    expect(res.text).toContain('This panel is for Synergic super admins');
    expect(await panelSessions(admin.id)).toHaveLength(0);
  });

  it('signs a super admin in with httpOnly cookies and shows the dashboard', async () => {
    const sa = await seedUser(null, { type: 'S' });
    const agent = request.agent(app);
    const res = await signIn(agent, sa);
    expect(res.status).toBe(303);
    expect(res.headers.location).toBe('/admin');
    const cookies = cookieHeader(res);
    for (const name of ['crb_at', 'crb_rt']) {
      const c = cookies.find((x) => x.startsWith(`${name}=`)) ?? '';
      expect(c).toContain('HttpOnly');
      expect(c).toContain('SameSite=Strict');
      expect(c).toContain('Path=/admin');
    }
    const sessions = await panelSessions(sa.id);
    expect(sessions).toHaveLength(1);
    expect(sessions[0]).toMatchObject({ device_id: PANEL_DEVICE_ID, status: 'A' });

    const page = await agent.get('/admin');
    expect(page.status).toBe(200);
    expect(page.text).toContain('Collection');
    expect(page.text).toContain(`User ${sa.user_id}`);

    const missing = await agent.get('/admin/nope');
    expect(missing.status).toBe(404);
    expect(missing.text).toContain('We couldn’t find that page');
  });

  it('goes back to the requested page after signing in (same-site paths only)', async () => {
    const sa = await seedUser(null, { type: 'S' });
    const agent = request.agent(app);
    const token = await csrf(agent, '/admin/login?next=%2Fadmin%2Fchange-password');
    const ok = await agent.post('/admin/login').type('form').send({
      _csrf: token,
      user_id: sa.user_id,
      password: sa.password,
      next: '/admin/change-password',
    });
    expect(ok.headers.location).toBe('/admin/change-password');

    const agent2 = request.agent(app);
    const token2 = await csrf(agent2);
    const evil = await agent2
      .post('/admin/login')
      .type('form')
      .send({ _csrf: token2, user_id: sa.user_id, password: sa.password, next: '//evil.example' });
    expect(evil.headers.location).toBe('/admin');
  });

  it('a second panel sign-in replaces the first; a phone session blocks it', async () => {
    const sa = await seedUser(null, { type: 'S' });
    const first = request.agent(app);
    const second = request.agent(app);
    await signIn(first, sa);
    await signIn(second, sa);
    expect((await second.get('/admin')).status).toBe(200);
    const kicked = await first.get('/admin');
    expect(kicked.status).toBe(303);
    expect(kicked.headers.location).toContain('/admin/login?reason=ended');

    const other = await seedUser(null, { type: 'S' });
    expect((await loginAs(app, other, 'phone-1234')).status).toBe(200);
    const blocked = await signIn(request.agent(app), other);
    expect(blocked.status).toBe(409);
    expect(blocked.text).toContain('Log out of the app first');
  });

  it('rotates an expired access cookie silently with the refresh cookie', async () => {
    const sa = await seedUser(null, { type: 'S' });
    const agent = request.agent(app);
    const res = await signIn(agent, sa);
    const refreshCookie = cookieHeader(res).find((c) => c.startsWith('crb_rt=')) ?? '';
    const refresh = refreshCookie.slice('crb_rt='.length).split(';')[0] ?? '';
    const [session] = await panelSessions(sa.id);
    const expired = jwt.sign(
      { cid: null, typ: 'S', sid: session?.session_id, exp: Math.floor(Date.now() / 1000) - 60 },
      env.JWT_ACCESS_SECRET,
      {
        algorithm: 'HS256',
        subject: String(sa.id),
        issuer: 'cloak-room-billing',
        audience: 'access',
      },
    );
    const page = await request(app)
      .get('/admin')
      .set('Cookie', [`crb_at=${expired}`, `crb_rt=${refresh}`]);
    expect(page.status).toBe(200);
    expect(cookieHeader(page).some((c) => c.startsWith('crb_at='))).toBe(true);

    const noRefresh = await request(app)
      .get('/admin')
      .set('Cookie', [`crb_at=${expired}`]);
    expect(noRefresh.status).toBe(303);
    expect(noRefresh.headers.location).toContain('reason=expired');
  });

  it('signs out: session L, cookies cleared, pages need sign-in again', async () => {
    const sa = await seedUser(null, { type: 'S' });
    const agent = request.agent(app);
    await signIn(agent, sa);
    const page = await agent.get('/admin');
    const res = await agent
      .post('/admin/logout')
      .type('form')
      .send({ _csrf: csrfOf(page.text) });
    expect(res.status).toBe(303);
    expect(res.headers.location).toBe('/admin/login');
    const [session] = await panelSessions(sa.id);
    expect(session?.status).toBe('L');
    expect((await agent.get('/admin')).status).toBe(303);
    const login = await agent.get('/admin/login');
    expect(login.text).toContain('You have signed out.');
  });
});

describe('admin panel: forced password change', () => {
  it('holds a must-change admin on the password page until it is changed', async () => {
    const sa = await seedUser(null, { type: 'S' });
    await save(
      'md_user',
      { must_change_pwd: 'Y' },
      { flag: 'U', where: { id: sa.id } },
      superAdmin,
    );
    const agent = request.agent(app);
    const res = await signIn(agent, sa);
    expect(res.headers.location).toBe('/admin/change-password');

    const held = await agent.get('/admin');
    expect(held.status).toBe(303);
    expect(held.headers.location).toBe('/admin/change-password');

    const form = await agent.get('/admin/change-password');
    expect(form.status).toBe(200);
    expect(form.text).toContain('Set your own password to continue');
    const token = csrfOf(form.text);

    const mismatch = await agent.post('/admin/change-password').type('form').send({
      _csrf: token,
      current_password: sa.password,
      new_password: 'NewPassw0rd',
      confirm_password: 'Other1234',
    });
    expect(mismatch.status).toBe(400);
    expect(mismatch.text).toContain('Passwords don’t match');

    const wrong = await agent.post('/admin/change-password').type('form').send({
      _csrf: token,
      current_password: 'not-it-123',
      new_password: 'NewPassw0rd',
      confirm_password: 'NewPassw0rd',
    });
    expect(wrong.status).toBe(400);
    expect(wrong.text).toContain('Current password is wrong');

    const weak = await agent.post('/admin/change-password').type('form').send({
      _csrf: token,
      current_password: sa.password,
      new_password: 'short',
      confirm_password: 'short',
    });
    expect(weak.status).toBe(400);

    const ok = await agent.post('/admin/change-password').type('form').send({
      _csrf: token,
      current_password: sa.password,
      new_password: 'NewPassw0rd',
      confirm_password: 'NewPassw0rd',
    });
    expect(ok.status).toBe(303);
    expect(ok.headers.location).toBe('/admin');
    const after = await agent.get('/admin');
    expect(after.status).toBe(200);
    expect(after.text).toContain('Your password has been changed.');
    const user = await fetchOne('md_user', { id: sa.id }, superAdmin, undefined, {
      columns: ['must_change_pwd'],
    });
    expect(user?.must_change_pwd).toBe('N');
  });
});

describe('admin dashboard data', () => {
  it('counts platform-wide customers and subscriptions in IST terms', async () => {
    await seedCustomer({ renewal: '2020-01-01' });
    await seedCustomer({ renewal: null });
    const d = await dashboard();
    expect(d.customers.expired).toBeGreaterThanOrEqual(2);
    expect(d.customers.total).toBeGreaterThanOrEqual(d.customers.active);
    expect(d.chart.columns).toHaveLength(14);
    expect(d.chartRows.at(-1)?.day).toBe(d.today);
    expect(d.renewals.length).toBeGreaterThan(0);
    expect(d.renewals.every((r) => r.status === 'expired' || r.daysLeft !== null)).toBe(true);
  });
});

import { randomUUID } from 'node:crypto';
import type { Express } from 'express';
import request from 'supertest';
import { save } from '../../src/core/db/dml';
import { hashPassword } from '../../src/core/security/password';
import { superAdmin } from './context';

export const PASSWORD = 'Passw0rd!x';

let cachedHash: Promise<string> | undefined;
const defaultHash = () => (cachedHash ??= hashPassword(PASSWORD));

export async function seedCustomer(
  opts: { userLimit?: number; renewal?: string | null; active?: 'Y' | 'N' } = {},
): Promise<number> {
  const { insertId } = await save(
    'md_customer',
    {
      customer_name: `Cust ${randomUUID().slice(0, 8)}`,
      customer_addr: 'Howrah Stn',
      pin_code: '711101',
      mobile_no: '9000000000',
      user_limit: opts.userLimit ?? 5,
      renewal_dt: opts.renewal === undefined ? '2099-12-31' : opts.renewal,
      active_flag: opts.active ?? 'Y',
    },
    { flag: 'I' },
    superAdmin,
  );
  return insertId;
}

export interface SeededUser {
  id: number;
  user_id: string;
  password: string;
  customer_id: number | null;
}

export async function seedUser(
  customerId: number | null,
  opts: { type?: 'S' | 'A' | 'O'; allow?: 'Y' | 'N'; code?: string } = {},
): Promise<SeededUser> {
  const type = opts.type ?? (customerId === null ? 'S' : 'O');
  const userId = `u_${randomUUID().slice(0, 12)}`;
  const { insertId } = await save(
    'md_user',
    {
      customer_id: customerId,
      user_id: userId,
      user_name: `User ${userId}`,
      user_code: type === 'S' ? null : (opts.code ?? `U${randomUUID().slice(0, 6).toUpperCase()}`),
      password_hash: await defaultHash(),
      user_type: type,
      allow_flag: opts.allow ?? 'Y',
    },
    { flag: 'I' },
    superAdmin,
  );
  return { id: insertId, user_id: userId, password: PASSWORD, customer_id: customerId };
}

export function loginAs(
  app: Express,
  user: Pick<SeededUser, 'user_id' | 'password'>,
  deviceId = 'device-0001',
) {
  return request(app).post('/api/v1/auth/login').send({
    user_id: user.user_id,
    password: user.password,
    device_id: deviceId,
    app_version: '1.0.0',
  });
}

/** Logs in and returns the tokens (asserts success). */
export async function tokensFor(app: Express, user: SeededUser, deviceId = 'device-0001') {
  const res = await loginAs(app, user, deviceId);
  if (res.status !== 200)
    throw new Error(`login failed: ${res.status} ${JSON.stringify(res.body)}`);
  return {
    access: res.body.data.accessToken as string,
    refresh: res.body.data.refreshToken as string,
    body: res.body,
  };
}

export const bearer = (token: string) => ({ Authorization: `Bearer ${token}` });

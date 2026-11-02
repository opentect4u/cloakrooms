import { describe, expect, it } from 'vitest';
import { parseEnv } from '../../src/config/env';

const base = {
  JWT_ACCESS_SECRET: 'a'.repeat(40),
  JWT_REFRESH_SECRET: 'b'.repeat(40),
};

describe('parseEnv', () => {
  it('applies defaults and parses CORS list', () => {
    const env = parseEnv({ ...base, CORS_ORIGINS: 'http://a.test, http://b.test' });
    expect(env.PORT).toBe(4000);
    expect(env.DB_PASSWORD).toBe('');
    expect(env.CORS_ORIGINS).toEqual(['http://a.test', 'http://b.test']);
    expect(env.REDIS_URL).toBeUndefined();
  });

  it('fails fast on short JWT secrets', () => {
    expect(() => parseEnv({ JWT_ACCESS_SECRET: 'short', JWT_REFRESH_SECRET: 'short' })).toThrow(
      /JWT_ACCESS_SECRET/,
    );
  });

  it('rejects placeholder secrets and default admin password in production', () => {
    expect(() =>
      parseEnv({
        NODE_ENV: 'production',
        JWT_ACCESS_SECRET: 'change_me_min_32_chars'.padEnd(32, '_'),
        JWT_REFRESH_SECRET: 'c'.repeat(40),
      }),
    ).toThrow(/SUPER_ADMIN_PASSWORD/);
  });

  it('rejects a malformed TTL', () => {
    expect(() => parseEnv({ ...base, JWT_ACCESS_TTL: 'forever' })).toThrow(/JWT_ACCESS_TTL/);
  });
});

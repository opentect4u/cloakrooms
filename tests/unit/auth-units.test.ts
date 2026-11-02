import { describe, expect, it } from 'vitest';
import { LruCache } from '../../src/core/utils/lruCache';
import { toIstDate } from '../../src/core/utils/time';
import { isResetMode, periodKey } from '../../src/domain/numbering';
import { maskDevice } from '../../src/modules/auth/auth.service';
import {
  sha256,
  signAccessToken,
  signRefreshToken,
  ttlSeconds,
  verifyAccessToken,
  verifyRefreshToken,
} from '../../src/core/security/tokens';

describe('tokens', () => {
  it('round-trips access and refresh claims; audiences are not interchangeable', () => {
    const access = signAccessToken({ sub: '7', cid: 3, typ: 'O', sid: 's-1' });
    expect(verifyAccessToken(access)).toEqual({ sub: '7', cid: 3, typ: 'O', sid: 's-1' });
    const refresh = signRefreshToken({ sub: '7', sid: 's-1' });
    expect(verifyRefreshToken(refresh)).toEqual({ sub: '7', sid: 's-1' });
    expect(() => verifyAccessToken(refresh)).toThrow(/Invalid token/);
    expect(() => verifyRefreshToken(access)).toThrow(/Invalid token/);
    expect(signRefreshToken({ sub: '7', sid: 's-1' })).not.toBe(refresh);
  });

  it('sha256 and ttlSeconds', () => {
    expect(sha256('abc')).toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
    expect(ttlSeconds('12h')).toBe(43_200);
    expect(ttlSeconds('30d')).toBe(2_592_000);
    expect(ttlSeconds('90s')).toBe(90);
    expect(ttlSeconds('5m')).toBe(300);
    expect(() => ttlSeconds('soon')).toThrow();
  });
});

describe('LruCache', () => {
  it('expires entries and evicts the least recently used', () => {
    let now = 0;
    const c = new LruCache<string, number>(2, 100, () => now);
    c.set('a', 1);
    c.set('b', 2);
    expect(c.get('a')).toBe(1); // a is now most recent
    c.set('c', 3); // evicts b
    expect(c.get('b')).toBeUndefined();
    expect(c.size).toBe(2);
    now = 150;
    expect(c.get('a')).toBeUndefined();
    c.set('d', 4);
    c.delete('d');
    expect(c.get('d')).toBeUndefined();
    c.clear();
    expect(c.size).toBe(0);
  });
});

describe('IST dates and period keys', () => {
  it('converts UTC instants to the Indian calendar date', () => {
    expect(toIstDate(new Date('2026-03-31T18:29:59Z'))).toBe('2026-03-31');
    expect(toIstDate(new Date('2026-03-31T18:30:00Z'))).toBe('2026-04-01');
  });

  it('builds D/M/Y period keys with the April–March financial year', () => {
    const at = new Date('2026-03-31T18:30:00Z'); // 1 Apr 2026 00:00 IST
    expect(periodKey('D', at)).toBe('2026-04-01');
    expect(periodKey('M', at)).toBe('2026-04');
    expect(periodKey('Y', at)).toBe('FY2026-27');
    expect(periodKey('Y', new Date('2026-03-31T18:29:59Z'))).toBe('FY2025-26');
    expect(periodKey('Y', new Date('2099-06-01T00:00:00Z'))).toBe('FY2099-00');
    expect(isResetMode('M')).toBe(true);
    expect(isResetMode('X')).toBe(false);
  });
});

describe('maskDevice', () => {
  it('keeps only the last 4 characters', () => {
    expect(maskDevice('abcdef123456')).toBe('********3456');
    expect(maskDevice('abcdefghijklmnop1234')).toBe('********1234');
    expect(maskDevice('abc')).toBe('****');
  });
});

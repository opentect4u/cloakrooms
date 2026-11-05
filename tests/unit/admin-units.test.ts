import { describe, expect, it } from 'vitest';
import { loginErrorMessage } from '../../src/admin/auth.routes';
import { buildColumnChart, niceCeil } from '../../src/admin/chart';
import { deltaOf } from '../../src/admin/dashboard.service';
import { safeNext } from '../../src/admin/render';
import {
  date,
  dateTimeIst,
  greeting,
  inr,
  inrCompact,
  initials,
  longToday,
  plural,
} from '../../src/admin/viewHelpers';
import { ApiError } from '../../src/core/http/ApiError';

describe('safeNext (no open redirects)', () => {
  it.each([
    ['/admin', '/admin'],
    ['/admin/customers?page=2', '/admin/customers?page=2'],
    ['//evil.example', '/x'],
    ['https://evil.example/admin', '/x'],
    ['/administrator', '/x'],
    ['/admin\\..\\evil', '/x'],
    [undefined, '/x'],
  ])('%s → %s', (input, expected) => {
    expect(safeNext(input, '/x')).toBe(expected);
  });
});

describe('view helpers', () => {
  it('formats rupees with Indian grouping', () => {
    expect(inr('1234567.5')).toBe('₹12,34,567.50');
    expect(inr(70, true)).toBe('₹70');
    expect(inr(null)).toBe('₹0.00');
    expect(inrCompact(950)).toBe('₹950');
    expect(inrCompact(12500)).toBe('₹13K');
    expect(inrCompact(2500)).toBe('₹2.5K');
    expect(inrCompact(340000)).toBe('₹3.4L');
    expect(inrCompact(12000000)).toBe('₹1.2Cr');
  });

  it('formats dates in IST', () => {
    expect(date('2026-11-05')).toBe('05 Nov 2026');
    expect(date(null)).toBe('');
    expect(dateTimeIst('2026-11-05 20:00:00')).toBe('06 Nov 2026, 01:30');
    expect(dateTimeIst('')).toBe('');
    expect(longToday(new Date('2026-11-04T19:00:00Z'))).toBe('Thursday, 05 November 2026');
  });

  it('greets by the hour in India', () => {
    expect(greeting(new Date('2026-11-05T03:00:00Z'))).toBe('Good morning'); // 08:30 IST
    expect(greeting(new Date('2026-11-05T08:00:00Z'))).toBe('Good afternoon'); // 13:30
    expect(greeting(new Date('2026-11-05T14:00:00Z'))).toBe('Good evening'); // 19:30
    expect(greeting(new Date('2026-11-04T21:00:00Z'))).toBe('Good evening'); // 02:30
  });

  it('initials and plurals', () => {
    expect(initials('Howrah Cloak Room')).toBe('HC');
    expect(initials('')).toBe('?');
    expect(plural(1, 'bag')).toBe('1 bag');
    expect(plural(1200, 'bag')).toBe('1,200 bags');
  });
});

describe('dashboard maths', () => {
  it('day-over-day delta in whole percent, null without a base', () => {
    expect(deltaOf('120.00', '100.00')).toEqual({ pct: 20, dir: 'up' });
    expect(deltaOf('50.00', '100.00')).toEqual({ pct: 50, dir: 'down' });
    expect(deltaOf('100.00', '100.00')).toEqual({ pct: 0, dir: 'flat' });
    expect(deltaOf('100.00', '0.00')).toBeNull();
  });

  it('nice axis maxima', () => {
    expect(niceCeil(0)).toBe(0);
    expect(niceCeil(120)).toBe(200);
    expect(niceCeil(230)).toBe(250);
    expect(niceCeil(4100)).toBe(5000);
    expect(niceCeil(9000)).toBe(10000);
  });

  it('column geometry: capped width, labels only on peak and latest', () => {
    const pts = [10, 80, 0, 40].map((v, i) => ({ label: `d${i}`, title: `Day ${i}`, value: v }));
    const c = buildColumnChart(pts, (v) => `₹${v}`);
    expect(c.max).toBe(100);
    expect(c.columns).toHaveLength(4);
    expect(c.columns.map((x) => x.showValue)).toEqual([false, true, false, true]);
    expect(c.columns[2]?.path).toBe('');
    expect(c.columns[2]?.isEmpty).toBe(true);
    const widths = c.columns.map((x) => x.hit.w);
    expect(new Set(widths).size).toBe(1);
    expect(c.ticks.map((t) => t.label)).toEqual(['₹0', '₹25', '₹50', '₹75', '₹100']);
    expect(buildColumnChart([], String).empty).toBe(true);
  });
});

describe('loginErrorMessage', () => {
  it('maps API errors to friendly sentences', () => {
    expect(loginErrorMessage(ApiError.unauthorized())).toContain('don’t match');
    expect(loginErrorMessage(new ApiError(429, 'RATE_LIMITED', 'x'))).toContain('Too many');
    expect(
      loginErrorMessage(
        new ApiError(409, 'SESSION_ACTIVE', 'x', {
          session_started_at: '2026-11-05 04:30:00',
          device_id: '****1234',
        }),
      ),
    ).toBe(
      'You are signed in on the app (device ****1234) since 05 Nov 2026, 10:00 IST. Log out of the app first, then sign in here.',
    );
    expect(loginErrorMessage(ApiError.forbidden('Blocked'))).toBe('Blocked');
    expect(loginErrorMessage(new Error('boom'))).toBeNull();
    expect(loginErrorMessage(new ApiError(500, 'INTERNAL_ERROR' as never, 'x'))).toBeNull();
  });
});

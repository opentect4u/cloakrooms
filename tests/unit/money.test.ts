import { describe, expect, it } from 'vitest';
import { fromPaise, toPaise } from '../../src/core/utils/money';
import { isPng } from '../../src/modules/header-footer/header-footer.service';

describe('money', () => {
  it('converts decimal strings to paise without float error', () => {
    expect(toPaise('0.1')).toBe(10);
    expect(toPaise('1180.07')).toBe(118007);
    expect(toPaise(50)).toBe(5000);
    expect(toPaise('59.3')).toBe(5930);
    expect(() => toPaise('1.234')).toThrow();
    expect(() => toPaise('-1')).toThrow();
  });

  it('formats paise as 2-decimal strings', () => {
    expect(fromPaise(5932)).toBe('59.32');
    expect(fromPaise(5)).toBe('0.05');
    expect(fromPaise(-534)).toBe('-5.34');
    expect(() => fromPaise(1.5)).toThrow();
  });
});

describe('isPng', () => {
  it('checks the 8-byte signature', () => {
    const sig = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
    expect(isPng(Buffer.concat([sig, Buffer.from([0])]))).toBe(true);
    expect(isPng(sig)).toBe(false);
    expect(isPng(Buffer.from('GIF89a----'))).toBe(false);
  });
});

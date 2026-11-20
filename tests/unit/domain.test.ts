import { describe, expect, it } from 'vitest';
import {
  baseAmountPaise,
  billableMinutes,
  computeBill,
  daysCharged,
  minutesStored,
  parseUtc,
  selectEffective,
  type Tariff,
} from '../../src/domain/billing';
import { addGst, gstPctFor, roundDiv, splitGst } from '../../src/domain/gst';
import { formatDocNo, parseDocNo, periodKeyOfDate } from '../../src/domain/numbering';
import { hhmm, istMinutesOfDay, minutesOfDay, opensAt, withinShift } from '../../src/domain/shift';

const TARIFF: Tariff = { block_hours: 24, first_block_rate: '50.00', next_block_rate: '20.00' };
const IN = '2026-05-01 10:00:00';

function bill(out: string, qty: number, extra: Partial<Parameters<typeof computeBill>[0]> = {}) {
  return computeBill({
    luggage_qty: qty,
    date_time_in: IN,
    date_time_out: out,
    tariff: TARIFF,
    gst_pct: '18.00',
    gst_type: 'E',
    ...extra,
  });
}

describe('CLAUDE.md §3 test vectors', () => {
  it('2 min × 2 bags → 100', () => {
    expect(bill('2026-05-01 10:02:00', 2)).toMatchObject({ days_charged: 1, base_amt: '100.00' });
  });

  it('24h00m × 1 → 50', () => {
    expect(bill('2026-05-02 10:00:00', 1)).toMatchObject({ days_charged: 1, base_amt: '50.00' });
  });

  it('24h01m × 1 → 70', () => {
    expect(bill('2026-05-02 10:01:00', 1)).toMatchObject({ days_charged: 2, base_amt: '70.00' });
  });

  it('50h × 3 → 270', () => {
    expect(bill('2026-05-03 12:00:00', 3)).toMatchObject({ days_charged: 3, base_amt: '270.00' });
  });

  it('₹70 @ 18 % → 59.32 + 5.34 + 5.34', () => {
    expect(bill('2026-05-02 10:01:00', 1)).toEqual({
      days_charged: 2,
      block_hours: 24,
      first_block_rate: '50.00',
      next_block_rate: '20.00',
      rate_slabs: [
        { from_hour: 0, to_hour: 24, rate: '50.00' },
        { from_hour: 24, to_hour: 48, rate: '20.00' },
      ],
      base_amt: '70.00',
      penal_amt: '0.00',
      net_amt: '70.00',
      taxable_amt: '59.32',
      gst_pct: '18.00',
      cgst: '5.34',
      sgst: '5.34',
      igst: '0.00',
    });
  });
});

describe('billing details', () => {
  it('inter-state puts all GST in IGST', () => {
    expect(bill('2026-05-02 10:01:00', 1, { gst_type: 'I' })).toMatchObject({
      taxable_amt: '59.32',
      cgst: '0.00',
      sgst: '0.00',
      igst: '10.68',
    });
  });

  it('penalty is GST-inclusive and part of net; odd GST paisa goes to CGST (half-up)', () => {
    expect(bill('2026-05-01 11:00:00', 1, { penal_amt: '10.50' })).toMatchObject({
      base_amt: '50.00',
      penal_amt: '10.50',
      net_amt: '60.50',
      taxable_amt: '51.27',
      cgst: '4.62',
      sgst: '4.61',
    });
  });

  it('zero GST leaves taxable = net', () => {
    expect(bill('2026-05-01 11:00:00', 2, { gst_pct: '0' })).toMatchObject({
      net_amt: '100.00',
      taxable_amt: '100.00',
      cgst: '0.00',
      sgst: '0.00',
      gst_pct: '0.00',
    });
  });

  it('drops seconds: 24h00m59s is still one block', () => {
    expect(minutesStored(IN, '2026-05-02 10:00:59')).toBe(1440);
    expect(bill('2026-05-02 10:00:59', 1).days_charged).toBe(1);
  });

  it('honours other block lengths', () => {
    expect(daysCharged(13 * 60, 12)).toBe(2);
    expect(daysCharged(0, 24)).toBe(1);
    expect(
      baseAmountPaise(2, { block_hours: 12, first_block_rate: '30', next_block_rate: '15.5' }, 3),
    ).toBe(12_200);
  });

  it('rejects impossible input', () => {
    expect(() => minutesStored('2026-05-02 10:00:00', IN)).toThrow(/before check-in/);
    expect(() => parseUtc('2026-02-30 10:00:00')).toThrow(RangeError);
    expect(() => parseUtc('2026-05-01T10:00:00Z')).toThrow(RangeError);
    expect(() => daysCharged(-1, 24)).toThrow(RangeError);
    expect(() => daysCharged(1.5, 24)).toThrow(RangeError);
    expect(() => daysCharged(10, 0)).toThrow(RangeError);
    expect(() => baseAmountPaise(0, TARIFF, 1)).toThrow(RangeError);
    expect(() => bill('2026-05-02 10:00:00', 0)).toThrow(/luggage_qty/);
  });

  it('selectEffective picks the latest row on or before the date', () => {
    const rows = [
      { id: 1, effective_from: '2026-01-01' },
      { id: 3, effective_from: '2026-06-01' },
      { id: 2, effective_from: '2026-04-01' },
    ];
    expect(selectEffective(rows, '2026-05-15')?.id).toBe(2);
    expect(selectEffective(rows, '2026-06-01')?.id).toBe(3);
    expect(selectEffective(rows, '2025-12-31')).toBeUndefined();
  });
});

describe('gst', () => {
  it('roundDiv rounds half up and guards its inputs', () => {
    expect(roundDiv(5, 2)).toBe(3);
    expect(roundDiv(4, 3)).toBe(1);
    expect(roundDiv(0, 7)).toBe(0);
    expect(() => roundDiv(-1, 2)).toThrow(RangeError);
    expect(() => roundDiv(1, 0)).toThrow(RangeError);
  });

  it('gstPctFor sums CGST + SGST for E and uses IGST for I', () => {
    const rates = { cgst_pct: '2.50', sgst_pct: '2.5', igst_pct: '5.00' };
    expect(gstPctFor(rates, 'E')).toBe('5.00');
    expect(gstPctFor(rates, 'I')).toBe('5.00');
    expect(gstPctFor({ cgst_pct: '9', sgst_pct: '9', igst_pct: '0' }, 'I')).toBe('0.00');
  });

  it('splitGst handles fractional rates and rejects bad amounts', () => {
    expect(splitGst(10_000, '5', 'E')).toEqual({
      taxable: 9524,
      gst: 476,
      cgst: 238,
      sgst: 238,
      igst: 0,
    });
    expect(() => splitGst(-1, '18', 'E')).toThrow(RangeError);
    expect(() => splitGst(1.5, '18', 'E')).toThrow(RangeError);
  });
});

describe('numbering', () => {
  it('formats and parses U03-00125', () => {
    expect(formatDocNo('U03', 125)).toBe('U03-00125');
    expect(formatDocNo('U03', 123_456)).toBe('U03-123456');
    expect(parseDocNo('U03-00125')).toEqual({ userCode: 'U03', no: 125 });
    expect(parseDocNo('U03-00000')).toBeNull();
    expect(parseDocNo('u03-00125')).toBeNull();
    expect(parseDocNo('U03-125')).toBeNull();
    expect(() => formatDocNo('U03', 0)).toThrow(RangeError);
    expect(() => formatDocNo('U03', 1.5)).toThrow(RangeError);
  });

  it('derives period keys from an IST date', () => {
    expect(periodKeyOfDate('D', '2026-03-31')).toBe('2026-03-31');
    expect(periodKeyOfDate('M', '2026-03-31')).toBe('2026-03');
    expect(periodKeyOfDate('Y', '2026-03-31')).toBe('FY2025-26');
    expect(periodKeyOfDate('Y', '2026-04-01')).toBe('FY2026-27');
    expect(() => periodKeyOfDate('D', '2026-13-01')).toThrow(RangeError);
  });
});

describe('Phase 11 billing options (A3)', () => {
  const pick = (b: ReturnType<typeof computeBill>) => ({
    days: b.days_charged,
    base: b.base_amt,
    penal: b.penal_amt,
    net: b.net_amt,
    taxable: b.taxable_amt,
    pct: b.gst_pct,
    cgst: b.cgst,
    sgst: b.sgst,
    igst: b.igst,
  });

  it('defaults reproduce the original formula', () => {
    expect(bill('2026-05-02 10:01:00', 1, { options: {} })).toEqual(bill('2026-05-02 10:01:00', 1));
  });

  it('grace minutes are taken off before counting blocks', () => {
    // 24 h 10 min: 2 blocks without grace, 1 block with 15 minutes of grace.
    expect(bill('2026-05-02 10:10:00', 1).days_charged).toBe(2);
    expect(bill('2026-05-02 10:10:00', 1, { options: { graceMinutes: 15 } })).toMatchObject({
      days_charged: 1,
      net_amt: '50.00',
    });
    // Grace larger than the stay still charges the first block.
    expect(bill('2026-05-01 10:05:00', 1, { options: { graceMinutes: 30 } }).days_charged).toBe(1);
    expect(billableMinutes(10, 15)).toBe(0);
    expect(() => billableMinutes(10, -1)).toThrow(RangeError);
    expect(() => billableMinutes(10, 1.5)).toThrow(RangeError);
  });

  it('GST on top: taxable = rent, GST added, net = rent + penalty + GST', () => {
    expect(pick(bill('2026-05-01 12:00:00', 1, { options: { gstInclusive: false } }))).toEqual({
      days: 1,
      base: '50.00',
      penal: '0.00',
      net: '59.00',
      taxable: '50.00',
      pct: '18.00',
      cgst: '4.50',
      sgst: '4.50',
      igst: '0.00',
    });
    // GST also on the penalty (default): taxed 60.00 → 10.80.
    expect(
      pick(bill('2026-05-01 12:00:00', 1, { penal_amt: '10', options: { gstInclusive: false } })),
    ).toEqual({
      days: 1,
      base: '50.00',
      penal: '10.00',
      net: '70.80',
      taxable: '60.00',
      pct: '18.00',
      cgst: '5.40',
      sgst: '5.40',
      igst: '0.00',
    });
    // Odd paisa of GST goes to CGST; inter-state is all IGST.
    const odd = { ...TARIFF, first_block_rate: '50.05' };
    expect(
      pick(bill('2026-05-01 12:00:00', 1, { tariff: odd, options: { gstInclusive: false } })),
    ).toMatchObject({ net: '59.06', taxable: '50.05', cgst: '4.51', sgst: '4.50' });
    expect(
      pick(bill('2026-05-01 12:00:00', 1, { gst_type: 'I', options: { gstInclusive: false } })),
    ).toMatchObject({ net: '59.00', igst: '9.00', cgst: '0.00', sgst: '0.00' });
  });

  it('penalty without GST stays outside the taxable value', () => {
    // Inclusive: GST worked back from the rent only (₹70 → 59.32 + 5.34 + 5.34), penalty on top.
    expect(
      pick(bill('2026-05-02 10:01:00', 1, { penal_amt: '10', options: { penaltyGst: false } })),
    ).toEqual({
      days: 2,
      base: '70.00',
      penal: '10.00',
      net: '80.00',
      taxable: '59.32',
      pct: '18.00',
      cgst: '5.34',
      sgst: '5.34',
      igst: '0.00',
    });
    // On top: GST on the rent only.
    expect(
      pick(
        bill('2026-05-01 12:00:00', 1, {
          penal_amt: '10',
          options: { gstInclusive: false, penaltyGst: false },
        }),
      ),
    ).toMatchObject({ net: '69.00', taxable: '50.00', cgst: '4.50', sgst: '4.50' });
  });

  it('GST_FLAG = N bills 0 % GST', () => {
    for (const gstInclusive of [true, false]) {
      expect(
        pick(bill('2026-05-02 10:01:00', 1, { options: { gstEnabled: false, gstInclusive } })),
      ).toEqual({
        days: 2,
        base: '70.00',
        penal: '0.00',
        net: '70.00',
        taxable: '70.00',
        pct: '0.00',
        cgst: '0.00',
        sgst: '0.00',
        igst: '0.00',
      });
    }
  });

  it('addGst validates its input', () => {
    expect(addGst(5000, '18.00', 'E')).toEqual({
      taxable: 5000,
      gst: 900,
      cgst: 450,
      sgst: 450,
      igst: 0,
    });
    expect(() => addGst(-1, '18', 'E')).toThrow(RangeError);
    expect(() => addGst(1.5, '18', 'E')).toThrow(RangeError);
  });
});

describe('RECEIPT_RESET = N (Phase 11)', () => {
  it('uses one period for ever', () => {
    expect(periodKeyOfDate('N', '2026-05-01')).toBe('ALL');
    expect(periodKeyOfDate('N', '2031-12-31')).toBe('ALL');
    expect(() => periodKeyOfDate('N', 'bad')).toThrow(RangeError);
  });
});

describe('login shift window (Phase 11, B1)', () => {
  const at = (hm: string) => minutesOfDay(hm);

  it('parses times of day', () => {
    expect(minutesOfDay('06:00')).toBe(360);
    expect(minutesOfDay('23:59:59')).toBe(1439);
    expect(() => minutesOfDay('24:00')).toThrow(RangeError);
    expect(hhmm(5)).toBe('00:05');
    expect(hhmm(1439)).toBe('23:59');
    // 2026-05-01 18:30 UTC = 2026-05-02 00:00 IST.
    expect(istMinutesOfDay(new Date('2026-05-01T18:30:00Z'))).toBe(0);
    expect(istMinutesOfDay(new Date('2026-05-01T04:31:20Z'))).toBe(at('10:01'));
  });

  it('day shift: opens early, closes at the end', () => {
    const [from, to] = [at('06:00'), at('14:00')];
    expect(withinShift(at('05:44'), from, to, 15)).toBe(false);
    expect(withinShift(at('05:45'), from, to, 15)).toBe(true);
    expect(withinShift(at('13:59'), from, to, 15)).toBe(true);
    expect(withinShift(at('14:00'), from, to, 15)).toBe(false);
    expect(withinShift(at('05:59'), from, to, 0)).toBe(false);
  });

  it('night shift crosses midnight', () => {
    const [from, to] = [at('22:00'), at('06:00')];
    expect(withinShift(at('21:30'), from, to, 30)).toBe(true);
    expect(withinShift(at('02:00'), from, to, 30)).toBe(true);
    expect(withinShift(at('06:00'), from, to, 30)).toBe(false);
    expect(withinShift(at('12:00'), from, to, 30)).toBe(false);
  });

  it('early window that starts the evening before', () => {
    const [from, to] = [at('00:10'), at('08:00')];
    expect(opensAt(from, 15)).toBe(at('23:55'));
    expect(withinShift(at('23:56'), from, to, 15)).toBe(true);
    expect(withinShift(at('23:50'), from, to, 15)).toBe(false);
    expect(() => withinShift(0, from, to, -1)).toThrow(RangeError);
    expect(() => withinShift(0, from, to, 1440)).toThrow(RangeError);
  });
});

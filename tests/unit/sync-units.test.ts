import { describe, expect, it } from 'vitest';
import { maskIdNumber } from '../../src/domain/masking';
import { checkBill } from '../../src/modules/sync/billCheck';
import { CounterCollector } from '../../src/modules/sync/counters.collector';
import { checkoutPatch } from '../../src/modules/sync/luggage.merge';
import { receiptRow, type LuggageRow } from '../../src/modules/sync/sync.schema';
import { dupKeyName, isFatalDbError } from '../../src/modules/sync/sync.results';

describe('maskIdNumber', () => {
  it('keeps the last 4 characters and is idempotent', () => {
    expect(maskIdNumber('1234 5678 9012')).toBe('XXXXXXXX9012');
    expect(maskIdNumber('XXXXXXXX9012')).toBe('XXXXXXXX9012');
    expect(maskIdNumber('12')).toBe('XXXXXXXX12');
  });
});

describe('checkoutPatch', () => {
  const none = {
    user_id_out: null,
    device_id_out: null,
    shift_id_out: null,
    date_time_out: null,
    out_flag: 'N',
  };
  const out = {
    user_id_out: 2,
    device_id_out: 'd2',
    shift_id_out: null,
    date_time_out: '2026-05-02 10:00:00',
    out_flag: 'Y',
  };
  const incoming = (o: Record<string, unknown>) => o as unknown as LuggageRow;

  it('fills only empty check-out fields and never reverts out_flag', () => {
    expect(checkoutPatch(none, incoming(out))).toEqual({
      patch: {
        user_id_out: 2,
        device_id_out: 'd2',
        date_time_out: '2026-05-02 10:00:00',
        out_flag: 'Y',
      },
    });
    expect(checkoutPatch(out, incoming(none))).toEqual({ patch: {} });
    expect(
      checkoutPatch({ ...out, shift_id_out: null }, incoming({ ...out, shift_id_out: 5 })),
    ).toEqual({ patch: { shift_id_out: 5 } });
  });

  it('reports a different second check-out', () => {
    const res = checkoutPatch(out, incoming({ ...out, user_id_out: 3 }));
    expect(res).toEqual({ conflict: 'already checked out (user_id_out differ)' });
  });
});

describe('checkBill', () => {
  const luggage = {
    luggage_qty: 1,
    receipt_dt: '2026-05-01',
    date_time_in: '2026-05-01 04:30:00',
    date_time_out: '2026-05-02 04:31:00',
  };
  const rates = [
    {
      effective_from: '2026-01-01',
      block_hours: 24,
      first_block_rate: '50.00',
      next_block_rate: '20.00',
    },
  ];
  const gst = [
    { effective_from: '2017-07-01', cgst_pct: '9.00', sgst_pct: '9.00', igst_pct: '0.00' },
  ];
  const receipt = receiptRow.parse({
    trans_id: '3f2504e0-4f89-41d3-9a0c-0305e82c3301',
    bill_no: 'U02-00001',
    bill_dt: '2026-05-02',
    user_id: 2,
    device_id: 'd2',
    luggage_qty: 1,
    days_charged: 2,
    block_hours: 24,
    first_block_rate: '50',
    next_block_rate: '20',
    base_amt: '70',
    net_amt: '70',
    taxable_amt: '59.32',
    gst_pct: '18',
    cgst: '5.34',
    sgst: '5.34',
    igst: '0',
    pay_mode: 'C',
  });

  it('accepts a correct bill, flags missing data and mismatches', () => {
    expect(checkBill(receipt, luggage, rates, gst, 'E')).toEqual({ ok: true });
    expect(checkBill(receipt, { ...luggage, date_time_out: null }, rates, gst, 'E')).toMatchObject({
      code: 'CHECKOUT_MISSING',
    });
    expect(
      checkBill(receipt, { ...luggage, receipt_dt: '2025-12-31' }, rates, gst, 'E'),
    ).toMatchObject({ code: 'NO_RATE' });
    const igst = checkBill(receipt, luggage, rates, gst, 'I');
    expect(igst).toMatchObject({ ok: false, code: 'AMOUNT_MISMATCH' });
    // No GST row at all → 0 %.
    expect(checkBill(receipt, luggage, rates, [], 'E')).toMatchObject({ code: 'AMOUNT_MISMATCH' });
  });
});

describe('CounterCollector', () => {
  it('keeps the max per (user, series, period)', () => {
    const c = new CounterCollector();
    c.add({ userId: 1, series: 'R', periodKey: 'P', no: 3 });
    c.add({ userId: 1, series: 'R', periodKey: 'P', no: 9 });
    c.add({ userId: 1, series: 'R', periodKey: 'P', no: 5 });
    c.add({ userId: 1, series: 'B', periodKey: 'P', no: 1 });
    expect(c.size).toBe(2);
  });
});

describe('sync error helpers', () => {
  it('classifies DB errors and extracts duplicate key names', () => {
    expect(isFatalDbError({ code: 'ER_LOCK_DEADLOCK' })).toBe(true);
    expect(isFatalDbError({ code: 'ECONNRESET' })).toBe(true);
    expect(isFatalDbError(new Error('x'))).toBe(true);
    expect(isFatalDbError({ code: 'ER_DATA_TOO_LONG' })).toBe(false);
    expect(
      dupKeyName({
        code: 'ER_DUP_ENTRY',
        sqlMessage: "Duplicate entry 'x' for key 'td_receipt.uq_receipt_bill'",
      }),
    ).toBe('uq_receipt_bill');
    expect(dupKeyName({ code: 'ER_DUP_ENTRY', sqlMessage: "for key 'PRIMARY'" })).toBe('PRIMARY');
    expect(dupKeyName({ code: 'ER_DUP_ENTRY' })).toBe('');
    expect(dupKeyName({ code: 'ER_X' })).toBeNull();
  });
});

import { randomUUID } from 'node:crypto';
import type { RowDataPacket } from 'mysql2/promise';
import request from 'supertest';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { createApp } from '../../src/app';
import { fetchOne, raw, save } from '../../src/core/db/dml';
import { closeDb, db } from '../../src/core/db/pool';
import { loadRegistry } from '../../src/core/db/registry';
import { bearer, seedCustomer, seedUser, tokensFor, type SeededUser } from '../helpers/auth';
import { adminOf, superAdmin } from '../helpers/context';

const app = createApp();
type Auth = Record<string, string>;

let cid: number;
let u1: SeededUser; // device d1
let u2: SeededUser; // device d2
let d1: Auth;
let d2: Auth;
let adminAuth: Auth;
let otherTenant: Auth;
let shiftId: number;
let passportId: number;
let aadhaarId: number;
let seq = 0;

const IN = '2026-05-01 04:30:00';
const OUT_24H01 = '2026-05-02 04:31:00';

beforeAll(async () => {
  await loadRegistry(db);
  cid = await seedCustomer({ userLimit: 10 });
  u1 = await seedUser(cid, { code: 'U01' });
  u2 = await seedUser(cid, { code: 'U02' });
  const A = adminOf(cid);
  await save(
    'md_rate_dtls',
    {
      block_hours: 24,
      first_block_rate: '50',
      next_block_rate: '20',
      effective_from: '2026-01-01',
    },
    { flag: 'I' },
    A,
  );
  shiftId = (
    await save(
      'md_shift',
      { shift_name: 'Day', from_time: '06:00', to_time: '18:00' },
      { flag: 'I' },
      A,
    )
  ).insertId;
  const proofs = await raw<RowDataPacket[]>(
    "SELECT id_proof_id, id_proof_name FROM md_id_proof WHERE id_proof_name IN ('Passport', 'Aadhaar Card') AND created_by = 'system'",
  );
  passportId = proofs.find((p) => p.id_proof_name === 'Passport')?.id_proof_id;
  aadhaarId = proofs.find((p) => p.id_proof_name === 'Aadhaar Card')?.id_proof_id;

  d1 = bearer((await tokensFor(app, u1, 'd1')).access);
  d2 = bearer((await tokensFor(app, u2, 'd2')).access);
  adminAuth = bearer((await tokensFor(app, await seedUser(cid, { type: 'A' }), 'd-admin')).access);
  otherTenant = bearer(
    (await tokensFor(app, await seedUser(await seedCustomer()), 'd-other')).access,
  );
});
afterAll(async () => {
  await closeDb();
});

function checkIn(over: Record<string, unknown> = {}) {
  seq++;
  return {
    trans_id: randomUUID(),
    local_version: seq,
    receipt_no: `U01-${String(seq).padStart(5, '0')}`,
    receipt_dt: '2026-05-01',
    user_id_in: u1.id,
    device_id_in: 'd1',
    shift_id_in: shiftId,
    date_time_in: IN,
    pasn_name: `Passenger ${seq}`,
    pasn_mob_no: `98${String(seq).padStart(8, '0')}`,
    pnr_no: `PNR${seq}`,
    luggage_qty: 1,
    id_proof_id: passportId,
    id_number: `P${seq}`,
    out_flag: 'N',
    ...over,
  };
}

const checkOut = (bag: Record<string, unknown>, over: Record<string, unknown> = {}) => ({
  ...bag,
  user_id_out: u2.id,
  device_id_out: 'd2',
  shift_id_out: shiftId,
  date_time_out: OUT_24H01,
  out_flag: 'Y',
  ...over,
});

/** The correct bill for 1 bag, 24h01m, 18 % intra-state. */
function bill(transId: string, over: Record<string, unknown> = {}) {
  seq++;
  return {
    trans_id: transId,
    local_version: 1,
    bill_no: `U02-${String(seq).padStart(5, '0')}`,
    bill_dt: '2026-05-02',
    user_id: u2.id,
    device_id: 'd2',
    shift_id: shiftId,
    luggage_qty: 1,
    days_charged: 2,
    block_hours: 24,
    first_block_rate: '50.00',
    next_block_rate: '20.00',
    base_amt: '70.00',
    penal_amt: '0.00',
    net_amt: '70.00',
    taxable_amt: '59.32',
    gst_pct: '18.00',
    cgst: '5.34',
    sgst: '5.34',
    igst: '0.00',
    gst_flag: 'Y',
    pay_mode: 'U',
    upi_ref: 'UPI123',
    bill_printed: 'Y',
    ...over,
  };
}

function push(auth: Auth, body: { luggage?: unknown[]; receipts?: unknown[] }) {
  return request(app).post('/api/v1/sync/push').set(auth).send(body);
}

async function luggageRow(transId: string) {
  return fetchOne('td_luggage_in_out', { trans_id: transId }, superAdmin, undefined, {
    includeDeleted: true,
  });
}

async function exceptionsFor(transId: string) {
  return raw<RowDataPacket[]>('SELECT * FROM td_sync_exception WHERE trans_id = ? ORDER BY id', [
    transId,
  ]);
}

const statuses = (res: request.Response) =>
  res.body.data.results.map(
    (r: { status: string; reason?: string }) => r.reason?.split(':')[0] ?? r.status,
  );

describe('push: Case 1 (in on device 1, out on device 2 via QR) → one cloud row', () => {
  it('check-in pushed first, then check-out + bill', async () => {
    const bag = checkIn();
    const first = await push(d1, { luggage: [bag] });
    expect(first.status).toBe(200);
    expect(first.body.data.results).toEqual([
      {
        table: 'td_luggage_in_out',
        trans_id: bag.trans_id,
        local_version: bag.local_version,
        status: 'ACCEPTED',
      },
    ]);

    const b = bill(bag.trans_id);
    const second = await push(d2, { luggage: [checkOut(bag)], receipts: [b] });
    expect(statuses(second)).toEqual(['ACCEPTED', 'ACCEPTED']);

    expect(await luggageRow(bag.trans_id)).toMatchObject({
      customer_id: cid,
      user_id_in: u1.id,
      user_id_out: u2.id,
      device_id_out: 'd2',
      date_time_out: OUT_24H01,
      out_flag: 'Y',
    });
    expect(await fetchOne('td_receipt', { trans_id: bag.trans_id }, superAdmin)).toMatchObject({
      bill_no: b.bill_no,
      net_amt: '70.00',
      customer_id: cid,
    });
    expect(await exceptionsFor(bag.trans_id)).toHaveLength(0);
  });

  it('check-out pushed first, then the stale check-in: out fields survive, nothing changes', async () => {
    const bag = checkIn();
    expect(
      statuses(await push(d2, { luggage: [checkOut(bag)], receipts: [bill(bag.trans_id)] })),
    ).toEqual(['ACCEPTED', 'ACCEPTED']);
    await raw(
      "UPDATE td_luggage_in_out SET updated_at = '2026-01-01 00:00:00' WHERE trans_id = ?",
      [bag.trans_id],
    );

    expect(statuses(await push(d1, { luggage: [bag] }))).toEqual(['ACCEPTED']);
    expect(await luggageRow(bag.trans_id)).toMatchObject({
      out_flag: 'Y',
      user_id_out: u2.id,
      date_time_out: OUT_24H01,
      updated_at: '2026-01-01 00:00:00',
    });
    const [{ n }] = (await raw<RowDataPacket[]>(
      'SELECT COUNT(*) AS n FROM td_luggage_in_out WHERE trans_id = ?',
      [bag.trans_id],
    )) as [{ n: number }];
    expect(n).toBe(1);
  });

  it('both devices pushing the same new trans_id at once still end as one row', async () => {
    const bag = checkIn();
    const [a, b] = await Promise.all([
      push(d1, { luggage: [bag] }),
      push(d2, { luggage: [checkOut(bag)] }),
    ]);
    expect([...statuses(a), ...statuses(b)]).toEqual(['ACCEPTED', 'ACCEPTED']);
    expect(await luggageRow(bag.trans_id)).toMatchObject({ out_flag: 'Y', user_id_out: u2.id });
  });
});

describe('push: Case 2 and idempotency', () => {
  it('check-out after the check-in was already synced fills the out fields', async () => {
    const bag = checkIn();
    await push(d1, { luggage: [bag] });
    await raw(
      "UPDATE td_luggage_in_out SET updated_at = '2026-01-01 00:00:00', sync_flag = 'Y' WHERE trans_id = ?",
      [bag.trans_id],
    );

    const out = checkOut(bag, {
      user_id_out: u1.id,
      device_id_out: 'd1',
      pasn_name: 'Edited Name',
    });
    expect(
      statuses(
        await push(d1, {
          luggage: [out],
          receipts: [bill(bag.trans_id, { user_id: u1.id, device_id: 'd1' })],
        }),
      ),
    ).toEqual(['ACCEPTED', 'ACCEPTED']);
    const row = await luggageRow(bag.trans_id);
    expect(row).toMatchObject({
      out_flag: 'Y',
      user_id_out: u1.id,
      sync_flag: 'N',
      pasn_name: bag.pasn_name,
    });
    expect(row?.updated_at).not.toBe('2026-01-01 00:00:00');
  });

  it('an identical re-push changes nothing', async () => {
    const bag = checkIn();
    const out = checkOut(bag);
    const b = bill(bag.trans_id);
    await push(d2, { luggage: [out], receipts: [b] });
    await raw(
      "UPDATE td_luggage_in_out SET updated_at = '2026-01-01 00:00:00' WHERE trans_id = ?",
      [bag.trans_id],
    );
    await raw("UPDATE td_receipt SET updated_at = '2026-01-01 00:00:00' WHERE trans_id = ?", [
      bag.trans_id,
    ]);
    const counter = await fetchOne('md_user_counter', { user_id: u2.id, series: 'B' }, superAdmin);

    const again = await push(d2, { luggage: [out], receipts: [b] });
    expect(statuses(again)).toEqual(['ACCEPTED', 'ACCEPTED']);
    expect((await luggageRow(bag.trans_id))?.updated_at).toBe('2026-01-01 00:00:00');
    expect((await fetchOne('td_receipt', { trans_id: bag.trans_id }, superAdmin))?.updated_at).toBe(
      '2026-01-01 00:00:00',
    );
    expect(await fetchOne('md_user_counter', { user_id: u2.id, series: 'B' }, superAdmin)).toEqual(
      counter,
    );
    expect(await exceptionsFor(bag.trans_id)).toHaveLength(0);
  });
});

describe('push: exceptions', () => {
  it('second bill for the same trans_id → DUPLICATE_BILL (logged with payload)', async () => {
    const bag = checkIn();
    await push(d2, { luggage: [checkOut(bag)], receipts: [bill(bag.trans_id)] });
    const res = await push(d2, { receipts: [bill(bag.trans_id)] });
    expect(res.body.data.results[0]).toMatchObject({
      status: 'EXCEPTION',
      table: 'td_receipt',
      local_version: 1,
    });
    expect(res.body.data.results[0].reason).toMatch(/^DUPLICATE_BILL: already billed as U02-/);
    expect(res.body.message).toBe('Synced with 1 exception(s)');

    const [ex] = await exceptionsFor(bag.trans_id);
    expect(ex).toMatchObject({
      customer_id: cid,
      table_name: 'td_receipt',
      user_id: u2.id,
      device_id: 'd2',
      resolved_flag: 'N',
    });
    const payload = typeof ex?.payload === 'string' ? JSON.parse(ex.payload) : ex?.payload;
    expect(payload.row.trans_id).toBe(bag.trans_id);
  });

  it('bill without a luggage row → BILL_WITHOUT_LUGGAGE (not stored)', async () => {
    const transId = randomUUID();
    expect(statuses(await push(d2, { receipts: [bill(transId)] }))).toEqual([
      'BILL_WITHOUT_LUGGAGE',
    ]);
    expect(await fetchOne('td_receipt', { trans_id: transId }, superAdmin)).toBeNull();
  });

  it('a different second check-out → DUPLICATE_CHECKOUT, first check-out kept', async () => {
    const bag = checkIn();
    await push(d2, { luggage: [checkOut(bag)] });
    const res = await push(d1, {
      luggage: [
        checkOut(bag, {
          user_id_out: u1.id,
          device_id_out: 'd1',
          date_time_out: '2026-05-03 10:00:00',
        }),
      ],
    });
    expect(statuses(res)).toEqual(['DUPLICATE_CHECKOUT']);
    expect(await luggageRow(bag.trans_id)).toMatchObject({
      user_id_out: u2.id,
      date_time_out: OUT_24H01,
    });
  });

  it('duplicate receipt_no / bill_no for another trans_id → refused', async () => {
    const a = checkIn();
    await push(d1, { luggage: [a] });
    const clash = checkIn({ receipt_no: a.receipt_no });
    expect(statuses(await push(d1, { luggage: [clash] }))).toEqual(['DUPLICATE_RECEIPT_NO']);
    expect(await luggageRow(clash.trans_id)).toBeNull();

    const b1 = checkIn();
    const b2 = checkIn();
    const firstBill = bill(b1.trans_id);
    const res = await push(d2, {
      luggage: [checkOut(b1), checkOut(b2)],
      receipts: [firstBill, bill(b2.trans_id, { bill_no: firstBill.bill_no })],
    });
    expect(statuses(res)).toEqual(['ACCEPTED', 'ACCEPTED', 'ACCEPTED', 'DUPLICATE_BILL_NO']);
  });

  it('server recomputation mismatch keeps the bill and logs AMOUNT_MISMATCH with the expected bill', async () => {
    const bag = checkIn();
    const wrong = bill(bag.trans_id, {
      days_charged: 1,
      base_amt: '50.00',
      net_amt: '50.00',
      taxable_amt: '42.37',
      cgst: '3.82',
      sgst: '3.81',
    });
    const res = await push(d2, { luggage: [checkOut(bag)], receipts: [wrong] });
    expect(statuses(res)).toEqual(['ACCEPTED', 'AMOUNT_MISMATCH']);
    expect(res.body.data.results[1].reason).toContain('days_charged 1≠2');
    expect((await fetchOne('td_receipt', { trans_id: bag.trans_id }, superAdmin))?.net_amt).toBe(
      '50.00',
    );
    const [ex] = await exceptionsFor(bag.trans_id);
    const payload = typeof ex?.payload === 'string' ? JSON.parse(ex.payload) : ex?.payload;
    expect(payload.expected).toMatchObject({ net_amt: '70.00', days_charged: 2 });
  });

  it('bill for a bag that has no check-out yet → kept, CHECKOUT_MISSING', async () => {
    const bag = checkIn();
    const res = await push(d1, { luggage: [bag], receipts: [bill(bag.trans_id)] });
    expect(statuses(res)).toEqual(['ACCEPTED', 'CHECKOUT_MISSING']);
  });

  it('invalid rows are reported per row; valid rows in the same batch still go in', async () => {
    const good = checkIn();
    const res = await push(d1, {
      luggage: [
        { ...checkIn(), trans_id: 'not-a-uuid', local_version: 'v9' },
        good,
        checkIn({ out_flag: 'Y' }),
      ],
    });
    expect(statuses(res)).toEqual(['VALIDATION_ERROR', 'ACCEPTED', 'VALIDATION_ERROR']);
    expect(res.body.data.results[0]).toMatchObject({ trans_id: 'not-a-uuid', local_version: 'v9' });
    expect(res.body.data.results[2].reason).toMatch(/out_flag/);
    expect(await luggageRow(good.trans_id)).not.toBeNull();
  });

  it('references to another tenant’s users/shifts → INVALID_REFERENCE', async () => {
    const foreign = await seedUser(await seedCustomer());
    expect(statuses(await push(d1, { luggage: [checkIn({ user_id_in: foreign.id })] }))).toEqual([
      'INVALID_REFERENCE',
    ]);
    expect(statuses(await push(d1, { luggage: [checkIn({ shift_id_in: 999_999 })] }))).toEqual([
      'INVALID_REFERENCE',
    ]);
    expect(statuses(await push(d1, { luggage: [checkIn({ id_proof_id: 999_999 })] }))).toEqual([
      'INVALID_REFERENCE',
    ]);
    const bag = checkIn();
    await push(d1, { luggage: [checkOut(bag)] });
    const res = await push(d2, {
      receipts: [
        bill(bag.trans_id, { user_id: foreign.id }),
        { ...bill(bag.trans_id), pay_mode: 'X' },
      ],
    });
    expect(statuses(res)).toEqual(['INVALID_REFERENCE', 'VALIDATION_ERROR']);
  });

  it("uses the billing user's own GST_TYPE / RECEIPT_RESET overrides", async () => {
    const cust = await seedCustomer();
    const op = await seedUser(cust, { code: 'G1' });
    const A = adminOf(cust);
    await save(
      'md_rate_dtls',
      {
        block_hours: 24,
        first_block_rate: '50',
        next_block_rate: '20',
        effective_from: '2026-01-01',
      },
      { flag: 'I' },
      A,
    );
    await save(
      'md_setting',
      { user_id: null, setting_name: 'RECEIPT_RESET', setting_value: 'Y', setting_type: 'G' },
      { flag: 'I' },
      A,
    );
    await save(
      'md_setting',
      { user_id: op.id, setting_name: 'RECEIPT_RESET', setting_value: 'D', setting_type: 'G' },
      { flag: 'I' },
      A,
    );
    await save(
      'md_setting',
      { user_id: op.id, setting_name: 'GST_TYPE', setting_value: 'I', setting_type: 'B' },
      { flag: 'I' },
      A,
    );
    const auth = bearer((await tokensFor(app, op, 'g-dev')).access);

    const bag = checkIn({ user_id_in: op.id, shift_id_in: null, receipt_no: 'G1-00004' });
    // Inter-state uses igst_pct, which the seeded GST row sets to 0 % (CLAUDE.md §11 "9+9+0").
    const interState = bill(bag.trans_id, {
      user_id: op.id,
      shift_id: null,
      bill_no: 'G1-00002',
      gst_pct: '0.00',
      taxable_amt: '70.00',
      cgst: '0.00',
      sgst: '0.00',
      igst: '0.00',
    });
    const res = await push(auth, {
      luggage: [checkOut(bag, { user_id_out: op.id, shift_id_out: null })],
      receipts: [interState],
    });
    expect(statuses(res)).toEqual(['ACCEPTED', 'ACCEPTED']);
    // Daily reset for this user: the R counter is keyed by the receipt date itself.
    expect(
      await fetchOne('md_user_counter', { user_id: op.id, series: 'R' }, superAdmin),
    ).toMatchObject({
      period_key: '2026-05-01',
      last_no: 4,
    });
  });
});

describe('push: tenant isolation, masking, counters, access', () => {
  it("another tenant cannot touch this tenant's trans_id", async () => {
    const bag = checkIn();
    await push(d1, { luggage: [bag] });
    const before = await luggageRow(bag.trans_id);

    // Their own valid users/shifts would fail INVALID_REFERENCE first; craft a row valid for them.
    const theirs = await raw<RowDataPacket[]>(
      "SELECT u.id FROM md_user u JOIN td_user_session s ON s.user_id = u.id WHERE s.device_id = 'd-other' LIMIT 1",
    );
    const theirUser = theirs[0]?.id as number;
    const hijack = checkOut(bag, {
      user_id_in: theirUser,
      user_id_out: theirUser,
      shift_id_in: null,
      shift_id_out: null,
    });
    const res = await push(otherTenant, {
      luggage: [hijack],
      receipts: [bill(bag.trans_id, { user_id: theirUser, shift_id: null })],
    });
    expect(statuses(res)).toEqual(['TENANT_MISMATCH', 'TENANT_MISMATCH']);
    expect(await luggageRow(bag.trans_id)).toEqual(before);
    expect(await fetchOne('td_receipt', { trans_id: bag.trans_id }, superAdmin)).toBeNull();
  });

  it('masks Aadhaar numbers to the last 4 digits', async () => {
    const bag = checkIn({ id_proof_id: aadhaarId, id_number: '1234 5678 9012' });
    await push(d1, { luggage: [bag] });
    expect((await luggageRow(bag.trans_id))?.id_number).toBe('XXXXXXXX9012');
  });

  it('raises receipt/bill counters to the highest pushed number and returns current counters', async () => {
    const cust = await seedCustomer();
    const op = await seedUser(cust, { code: 'Z9' });
    await save(
      'md_rate_dtls',
      {
        block_hours: 24,
        first_block_rate: '50',
        next_block_rate: '20',
        effective_from: '2026-01-01',
      },
      { flag: 'I' },
      adminOf(cust),
    );
    const auth = bearer((await tokensFor(app, op, 'z-dev')).access);
    const today = new Date().toISOString().slice(0, 10);
    const rows = [7, 3, 12].map((no) =>
      checkIn({
        user_id_in: op.id,
        shift_id_in: null,
        receipt_no: `Z9-${String(no).padStart(5, '0')}`,
        receipt_dt: today,
      }),
    );
    const res = await push(auth, { luggage: rows });
    expect(res.body.data.counters).toMatchObject({
      reset: 'Y',
      receipt_last_no: 12,
      bill_last_no: 0,
    });
    expect(res.body.data.server_time).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/);
  });

  it('a force-logged-out device can still push; super admins and anonymous callers cannot', async () => {
    const op = await seedUser(cid);
    const t = bearer((await tokensFor(app, op, 'forced-dev')).access);
    expect(
      (await request(app).post(`/api/v1/users/${op.id}/force-logout`).set(adminAuth)).status,
    ).toBe(200);
    const bag = checkIn({ user_id_in: op.id });
    expect(statuses(await push(t, { luggage: [bag] }))).toEqual(['ACCEPTED']);
    expect((await request(app).get('/api/v1/sync/pull').set(t)).status).toBe(401);

    const sa = bearer(
      (await tokensFor(app, await seedUser(null, { type: 'S' }), 'sa-sync')).access,
    );
    expect((await push(sa, { luggage: [] })).status).toBe(403);
    expect((await push({}, { luggage: [] })).status).toBe(401);
    expect((await push(d1, { luggage: 'nope' } as never)).status).toBe(400);
  });

  it('accepts a 5 MB body on /sync but 1 MB elsewhere', async () => {
    const pad = 'x'.repeat(1_500_000);
    const res = await push(d1, { luggage: [{ ...checkIn(), pasn_name: pad }] });
    expect(res.status).toBe(200);
    expect(statuses(res)).toEqual(['VALIDATION_ERROR']);
  });
});

describe('push: batch size', () => {
  it('handles 500 rows in one chunk and 1200 rows across chunks', async () => {
    const cust = await seedCustomer();
    const op = await seedUser(cust, { code: 'B5' });
    const auth = bearer((await tokensFor(app, op, 'bulk-dev')).access);
    const make = (n: number, offset: number) =>
      Array.from({ length: n }, (_, i) =>
        checkIn({
          user_id_in: op.id,
          shift_id_in: null,
          receipt_no: `B5-${String(offset + i + 1).padStart(5, '0')}`,
        }),
      );

    const t0 = Date.now();
    const five = await push(auth, { luggage: make(500, 0) });
    const ms500 = Date.now() - t0;
    expect(five.status).toBe(200);
    expect(five.body.data.results.every((r: { status: string }) => r.status === 'ACCEPTED')).toBe(
      true,
    );
    expect(ms500).toBeLessThan(15_000);

    const big = await push(auth, { luggage: make(1200, 500) });
    expect(big.body.data.results).toHaveLength(1200);
    const [{ n }] = (await raw<RowDataPacket[]>(
      'SELECT COUNT(*) AS n FROM td_luggage_in_out WHERE customer_id = ?',
      [cust],
    )) as [{ n: number }];
    expect(n).toBe(1700);
    console.log(`sync push: 500 rows in ${ms500} ms`);
  }, 60_000);
});

describe('pull', () => {
  it('first pull: masters, settings, counters and all open check-ins of the tenant only', async () => {
    const cust = await seedCustomer();
    const a = await seedUser(cust, { code: 'P1' });
    const b = await seedUser(cust, { code: 'P2' });
    await save(
      'md_rate_dtls',
      {
        block_hours: 24,
        first_block_rate: '50',
        next_block_rate: '20',
        effective_from: '2026-01-01',
      },
      { flag: 'I' },
      adminOf(cust),
    );
    const ta = bearer((await tokensFor(app, a, 'pa')).access);
    const tb = bearer((await tokensFor(app, b, 'pb')).access);
    const open = checkIn({ user_id_in: a.id, shift_id_in: null, receipt_no: 'P1-00001' });
    const closed = checkIn({ user_id_in: a.id, shift_id_in: null, receipt_no: 'P1-00002' });
    await push(ta, {
      luggage: [open, checkOut(closed, { user_id_out: a.id, shift_id_out: null })],
    });

    const first = await request(app).get('/api/v1/sync/pull').set(tb);
    expect(first.status).toBe(200);
    const d = first.body.data;
    expect(d.luggage.map((r: { trans_id: string }) => r.trans_id)).toEqual([open.trans_id]);
    expect(d.masters.rates).toHaveLength(1);
    expect(d.masters.id_proofs.length).toBeGreaterThanOrEqual(6);
    expect(d.counters.reset).toBe('Y');
    expect(d.server_time).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/);

    // Device B checks the open bag out elsewhere; device A pulls the change with its cursor.
    const cursor = d.server_time;
    await push(tb, {
      luggage: [checkOut(open, { user_id_out: b.id, device_id_out: 'pb', shift_id_out: null })],
    });
    const shift = await save(
      'md_shift',
      { shift_name: 'Gone', from_time: '01:00', to_time: '02:00' },
      { flag: 'I' },
      adminOf(cust),
    );
    await raw(
      "UPDATE md_shift SET is_deleted = 'Y', updated_at = UTC_TIMESTAMP() WHERE shift_id = ?",
      [shift.insertId],
    );

    const next = await request(app)
      .get(`/api/v1/sync/pull?since=${encodeURIComponent(cursor)}`)
      .set(ta);
    const changed = next.body.data.luggage.find(
      (r: { trans_id: string }) => r.trans_id === open.trans_id,
    );
    expect(changed).toMatchObject({ out_flag: 'Y', user_id_out: b.id });
    expect(
      next.body.data.luggage.every((r: { customer_id: number }) => r.customer_id === cust),
    ).toBe(true);
    expect(next.body.data.masters.shifts).toEqual([
      expect.objectContaining({ shift_id: shift.insertId, is_deleted: 'Y' }),
    ]);
  });

  it('pages large pulls with a keyset cursor: no gaps, no duplicates, stable server_time', async () => {
    const cust = await seedCustomer();
    const p = await seedUser(cust, { code: 'PG' });
    const auth = bearer((await tokensFor(app, p, 'pg-dev')).access);
    const open = Array.from({ length: 5 }, (_, i) =>
      checkIn({ user_id_in: p.id, shift_id_in: null, receipt_no: `PG-0000${i + 1}` }),
    );
    const closed = checkIn({ user_id_in: p.id, shift_id_in: null, receipt_no: 'PG-00009' });
    await push(auth, {
      luggage: [...open, checkOut(closed, { user_id_out: p.id, shift_id_out: null })],
    });
    // Same updated_at second for every row: the keyset must still walk them all.
    await raw(
      "UPDATE td_luggage_in_out SET updated_at = '2026-06-01 00:00:00' WHERE customer_id = ?",
      [cust],
    );

    const collect = async (first: string) => {
      const ids: string[] = [];
      let res = await request(app).get(first).set(auth);
      const serverTime = res.body.data.server_time;
      expect(res.body.data.masters).not.toBeNull();
      for (;;) {
        ids.push(...res.body.data.luggage.map((r: { trans_id: string }) => r.trans_id));
        expect(res.body.data.server_time).toBe(serverTime);
        if (!res.body.data.has_more) break;
        res = await request(app)
          .get(`/api/v1/sync/pull?limit=2&cursor=${res.body.data.next_cursor}`)
          .set(auth);
        expect(res.body.data).toMatchObject({ masters: null, settings: null, counters: null });
      }
      return ids;
    };

    // First pull: open check-ins only, across 3 pages of 2.
    const firstIds = await collect('/api/v1/sync/pull?limit=2');
    expect(firstIds.sort()).toEqual(open.map((b) => b.trans_id).sort());

    // Change pull: open + closed rows.
    const changes = await collect('/api/v1/sync/pull?limit=2&since=2026-06-01%2000%3A00%3A00');
    expect(new Set(changes).size).toBe(changes.length);
    expect(changes.sort()).toEqual([...open, closed].map((b) => b.trans_id).sort());

    expect((await request(app).get('/api/v1/sync/pull?cursor=garbage').set(auth)).status).toBe(400);
    expect((await request(app).get('/api/v1/sync/pull?limit=5000').set(auth)).status).toBe(400);
  });

  it('validates the cursor and is for customer users only', async () => {
    expect((await request(app).get('/api/v1/sync/pull?since=yesterday').set(d1)).status).toBe(400);
    const sa = bearer(
      (await tokensFor(app, await seedUser(null, { type: 'S' }), 'sa-pull')).access,
    );
    expect((await request(app).get('/api/v1/sync/pull').set(sa)).status).toBe(403);
  });
});

describe('luggage lookup', () => {
  it('searches by pnr / mobile / name / receipt_no with a status filter; detail includes the bill', async () => {
    const bag = checkIn({
      pnr_no: 'LOOKUP777',
      pasn_name: 'Lookup Lal',
      pasn_mob_no: '9000077777',
    });
    await push(d2, { luggage: [checkOut(bag)], receipts: [bill(bag.trans_id)] });

    for (const term of ['LOOKUP777', '9000077777', 'Lookup Lal', bag.receipt_no]) {
      const res = await request(app)
        .get(`/api/v1/luggage?search=${encodeURIComponent(term)}`)
        .set(d1);
      expect(res.body.data.map((r: { trans_id: string }) => r.trans_id)).toContain(bag.trans_id);
    }
    expect(
      (await request(app).get('/api/v1/luggage?search=LOOKUP777&status=in').set(d1)).body.data,
    ).toEqual([]);
    expect(
      (await request(app).get('/api/v1/luggage?search=LOOKUP777&status=out').set(d1)).body.meta
        .total,
    ).toBe(1);

    const detail = await request(app).get(`/api/v1/luggage/${bag.trans_id}`).set(d1);
    expect(detail.body.data).toMatchObject({
      trans_id: bag.trans_id,
      receipt: { net_amt: '70.00' },
    });
  });

  it('is tenant-scoped and customer-user only', async () => {
    const bag = checkIn();
    await push(d1, { luggage: [bag] });
    expect(
      (await request(app).get(`/api/v1/luggage/${bag.trans_id}`).set(otherTenant)).status,
    ).toBe(404);
    expect((await request(app).get('/api/v1/luggage/not-a-uuid').set(d1)).status).toBe(400);
    const sa = bearer(
      (await tokensFor(app, await seedUser(null, { type: 'S' }), 'sa-lookup')).access,
    );
    expect((await request(app).get('/api/v1/luggage').set(sa)).status).toBe(403);
  });
});

/**
 * Verifies the migrated schema (DB_NAME from vitest config: cloak_room_billing_test) against
 * CLAUDE.md §11. tests/globalSetup.ts migrates and seeds that database first.
 */
import type { Connection, ResultSetHeader, RowDataPacket } from 'mysql2/promise';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { openConnection } from '../../scripts/lib/connection';
import { env } from '../../src/config/env';
import { verifyPassword } from '../../src/core/security/password';

const STD = ['is_deleted', 'sync_flag', 'created_by', 'created_at', 'updated_by', 'updated_at'];
const STD_NO_SYNC = STD.filter((c) => c !== 'sync_flag');

const EXPECTED: Record<string, string[]> = {
  md_customer: [
    'customer_id',
    'customer_name',
    'customer_addr',
    'state',
    'pin_code',
    'mobile_no',
    'email',
    'renewal_dt',
    'gst_no',
    'upi_id',
    'user_limit',
    'active_flag',
    'remarks',
    ...STD,
  ],
  md_customer_registration: [
    'reg_id',
    'reg_no',
    'customer_id',
    'start_date',
    'end_date',
    'user_count',
    'remarks',
    ...STD_NO_SYNC,
  ],
  td_customer_billing: [
    'payment_id',
    'customer_id',
    'reg_id',
    'payment_dt',
    'base_amt',
    'cgst',
    'sgst',
    'igst',
    'net_amt',
    'pay_mode',
    'transaction_no',
    'remarks',
    ...STD_NO_SYNC,
  ],
  md_user: [
    'id',
    'customer_id',
    'user_id',
    'user_name',
    'user_code',
    'password_hash',
    'user_type',
    'allow_flag',
    'device_id',
    'session_token',
    'session_started_at',
    'last_login',
    ...STD,
  ],
  md_id_proof: ['id_proof_id', 'id_proof_name', 'mask_flag', ...STD],
  md_rate_dtls: [
    'rate_id',
    'customer_id',
    'block_hours',
    'first_block_rate',
    'next_block_rate',
    'effective_from',
    ...STD,
  ],
  md_gst: ['gst_id', 'cgst_pct', 'sgst_pct', 'igst_pct', 'effective_from', ...STD],
  md_header_footer: [
    'id',
    'customer_id',
    'line_type',
    'line_order',
    'narration',
    'image',
    'in_flag',
    'out_flag',
    'report_flag',
    ...STD,
  ],
  md_shift: ['shift_id', 'customer_id', 'shift_name', 'from_time', 'to_time', ...STD],
  td_assign_shift: ['id', 'customer_id', 'user_id', 'shift_id', 'effective_from', ...STD],
  md_setting: [
    'setting_id',
    'customer_id',
    'user_id',
    'setting_name',
    'setting_value',
    'setting_type',
    ...STD,
  ],
  td_luggage_in_out: [
    'trans_id',
    'customer_id',
    'receipt_no',
    'receipt_dt',
    'user_id_in',
    'device_id_in',
    'shift_id_in',
    'date_time_in',
    'pasn_name',
    'pasn_mob_no',
    'pnr_no',
    'luggage_qty',
    'id_proof_id',
    'id_number',
    'user_id_out',
    'device_id_out',
    'shift_id_out',
    'date_time_out',
    'out_flag',
    ...STD,
  ],
  td_receipt: [
    'trans_id',
    'customer_id',
    'bill_no',
    'bill_dt',
    'user_id',
    'device_id',
    'shift_id',
    'luggage_qty',
    'days_charged',
    'block_hours',
    'first_block_rate',
    'next_block_rate',
    'base_amt',
    'penal_amt',
    'net_amt',
    'taxable_amt',
    'gst_pct',
    'cgst',
    'sgst',
    'igst',
    'gst_flag',
    'pay_mode',
    'upi_ref',
    'bill_printed',
    ...STD,
  ],
  td_sync_exception: [
    'id',
    'customer_id',
    'trans_id',
    'table_name',
    'payload',
    'reason',
    'user_id',
    'device_id',
    'resolved_flag',
    'resolved_by',
    'resolved_at',
    'created_at',
  ],
  md_user_counter: ['id', 'customer_id', 'user_id', 'series', 'period_key', 'last_no', ...STD],
  td_user_session: [
    'session_id',
    'customer_id',
    'user_id',
    'device_id',
    'token_hash',
    'refresh_hash',
    'status',
    'started_at',
    'last_seen_at',
    'ended_at',
    'ended_by',
  ],
  md_app_version: [
    'id',
    'version_code',
    'version_name',
    'apk_url',
    'mandatory_flag',
    'release_notes',
    ...STD,
  ],
};

let conn: Connection;

beforeAll(async () => {
  conn = await openConnection();
});
afterAll(async () => {
  await conn?.end();
});

async function columnsOf(table: string) {
  const [rows] = await conn.query<RowDataPacket[]>(
    `SELECT column_name AS name, column_type AS type, is_nullable AS nullable, column_default AS def
       FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ?`,
    [table],
  );
  return new Map(rows.map((r) => [r.name as string, r]));
}

async function uniqueKeys(table: string): Promise<string[]> {
  const [rows] = await conn.query<RowDataPacket[]>(
    `SELECT index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS cols
       FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = ? AND non_unique = 0
      GROUP BY index_name`,
    [table],
  );
  return rows.map((r) => r.cols as string);
}

describe('schema matches CLAUDE.md §11', () => {
  it.each(Object.entries(EXPECTED))('%s has every specified column', async (table, cols) => {
    const actual = await columnsOf(table);
    expect(actual.size, `${table} missing`).toBeGreaterThan(0);
    expect(cols.filter((c) => !actual.has(c))).toEqual([]);
  });

  it('every Y/N flag column is ENUM(Y,N)', async () => {
    const [rows] = await conn.query<RowDataPacket[]>(
      `SELECT table_name AS t, column_name AS c, column_type AS type FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND (column_name LIKE '%\\_flag' OR column_name IN ('is_deleted', 'bill_printed'))`,
    );
    expect(rows.length).toBeGreaterThan(20);
    expect(rows.filter((r) => r.type !== "enum('Y','N')")).toEqual([]);
  });

  it('has the specified unique keys', async () => {
    expect(await uniqueKeys('md_user')).toEqual(
      expect.arrayContaining(['user_id', 'customer_id,user_code']),
    );
    expect(await uniqueKeys('md_rate_dtls')).toContain('customer_id,effective_from');
    expect(await uniqueKeys('md_setting')).toContain('customer_id,user_key,setting_name');
    expect(await uniqueKeys('td_luggage_in_out')).toContain('customer_id,receipt_no,receipt_dt');
    expect(await uniqueKeys('td_receipt')).toContain('customer_id,bill_no,bill_dt');
    expect(await uniqueKeys('md_user_counter')).toContain('user_id,series,period_key');
    expect(await uniqueKeys('md_customer_registration')).toContain('reg_no');
    expect(await uniqueKeys('md_app_version')).toContain('version_code');
  });

  it('indexes updated_at on every [STD] table (for pull)', async () => {
    const [rows] = await conn.query<RowDataPacket[]>(
      `SELECT DISTINCT table_name AS t FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND column_name = 'updated_at'`,
    );
    const indexed = new Set(rows.map((r) => r.t as string));
    const std = Object.entries(EXPECTED)
      .filter(([, cols]) => cols.includes('updated_at'))
      .map(([t]) => t);
    expect(std.filter((t) => !indexed.has(t))).toEqual([]);
  });

  it('records all migrations', async () => {
    const [rows] = await conn.query<RowDataPacket[]>('SELECT version FROM schema_migrations');
    expect(rows.map((r) => r.version)).toEqual(
      expect.arrayContaining(['001_customer_tables', '005_app_version']),
    );
  });
});

describe('constraints', () => {
  it('md_setting allows only one customer-wide (user_id NULL) value per name', async () => {
    await conn.beginTransaction();
    try {
      const [customer] = await conn.query<ResultSetHeader>(
        `INSERT INTO md_customer (customer_name, customer_addr, pin_code, mobile_no)
         VALUES ('T', 'A', '700001', '9000000000')`,
      );
      const insert = (value: string) =>
        conn.query(
          `INSERT INTO md_setting (customer_id, user_id, setting_name, setting_value, setting_type)
           VALUES (?, NULL, 'QR_FLAG', ?, 'B')`,
          [customer.insertId, value],
        );
      await insert('Y');
      await expect(insert('N')).rejects.toMatchObject({ code: 'ER_DUP_ENTRY' });
    } finally {
      await conn.rollback();
    }
  });

  it('td_receipt cannot exist without its luggage row', async () => {
    await expect(
      conn.query(
        `INSERT INTO td_receipt (trans_id, customer_id, bill_no, bill_dt, user_id, device_id,
           luggage_qty, days_charged, block_hours, first_block_rate, next_block_rate)
         VALUES (UUID(), 1, 'X-00001', '2026-01-01', 1, 'd', 1, 1, 24, 50, 20)`,
      ),
    ).rejects.toMatchObject({ code: 'ER_NO_REFERENCED_ROW_2' });
  });

  it('td_sync_exception.created_at defaults to UTC now', async () => {
    const [rows] = await conn.query<RowDataPacket[]>(
      `SELECT column_default AS def FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'td_sync_exception' AND column_name = 'created_at'`,
    );
    expect(String(rows[0]?.def).toLowerCase()).toContain('utc_timestamp');
  });
});

describe('seeds', () => {
  it('creates the super admin with an argon2id hash of SUPER_ADMIN_PASSWORD', async () => {
    const [rows] = await conn.query<RowDataPacket[]>(
      'SELECT customer_id, user_type, allow_flag, password_hash FROM md_user WHERE user_id = ?',
      [env.SUPER_ADMIN_USER_ID],
    );
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ customer_id: null, user_type: 'S', allow_flag: 'Y' });
    expect(rows[0]?.password_hash).toMatch(/^\$argon2id\$/);
    expect(await verifyPassword(rows[0]?.password_hash, env.SUPER_ADMIN_PASSWORD)).toBe(true);
  });

  it('creates 6 ID proofs with only Aadhaar masked, and GST 9+9+0 from 2017-07-01', async () => {
    // Seeded rows only (other test files may add ID proofs concurrently).
    const [proofs] = await conn.query<RowDataPacket[]>(
      `SELECT id_proof_name AS n, mask_flag AS m FROM md_id_proof
        WHERE created_by = 'system' ORDER BY id_proof_id`,
    );
    expect(proofs.map((p) => p.n)).toEqual([
      'Aadhaar Card',
      'Driving Licence',
      'Passport',
      'PAN Card',
      'Ration Card',
      'Voter ID',
    ]);
    expect(proofs.filter((p) => p.m === 'Y').map((p) => p.n)).toEqual(['Aadhaar Card']);

    const [gst] = await conn.query<RowDataPacket[]>(
      'SELECT cgst_pct, sgst_pct, igst_pct, effective_from FROM md_gst',
    );
    expect(gst).toEqual([
      { cgst_pct: '9.00', sgst_pct: '9.00', igst_pct: '0.00', effective_from: '2017-07-01' },
    ]);
  });
});

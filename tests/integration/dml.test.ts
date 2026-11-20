import type { RowDataPacket } from 'mysql2/promise';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { fetch, fetchOne, raw, save, softDelete, withTransaction } from '../../src/core/db/dml';
import { closeDb, db } from '../../src/core/db/pool';
import { loadRegistry } from '../../src/core/db/registry';
import { adminOf, superAdmin } from '../helpers/context';
import { createCustomer } from '../helpers/db';

let c1: number;
let c2: number;

beforeAll(async () => {
  await loadRegistry(db);
  c1 = await createCustomer();
  c2 = await createCustomer();
});

afterAll(async () => {
  await closeDb();
});

const shift = (name: string) => ({ shift_name: name, from_time: '06:00', to_time: '14:00' });

describe('DML against MySQL', () => {
  it('runs every session in IST (migration 009)', async () => {
    const rows = await raw<RowDataPacket[]>('SELECT @@session.time_zone AS tz');
    expect(rows[0]?.tz).toBe('+05:30');
  });

  it('insert stamps audit columns, sync_flag and the tenant from ctx', async () => {
    const A1 = adminOf(c1);
    const { insertId } = await save(
      'md_shift',
      { ...shift('Morning'), customer_id: c2 },
      { flag: 'I' },
      A1,
    );
    const row = await fetchOne('md_shift', { shift_id: insertId }, A1);
    expect(row).toMatchObject({
      customer_id: c1,
      shift_name: 'Morning',
      from_time: '06:00:00',
      sync_flag: 'N',
      is_deleted: 'N',
      created_by: A1.loginId,
      updated_by: A1.loginId,
    });
    expect(row?.created_at).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/);
    expect(row?.updated_at).toBe(row?.created_at);
  });

  it('isolates tenants on read, update and soft delete', async () => {
    const A1 = adminOf(c1);
    const A2 = adminOf(c2);
    const { insertId } = await save('md_shift', shift('Night'), { flag: 'I' }, A1);

    expect(await fetchOne('md_shift', { shift_id: insertId }, A2)).toBeNull();
    expect(
      (
        await save(
          'md_shift',
          { shift_name: 'X' },
          { flag: 'U', where: { shift_id: insertId } },
          A2,
        )
      ).affectedRows,
    ).toBe(0);
    expect(await softDelete('md_shift', { shift_id: insertId }, A2)).toBe(0);

    const { rows } = await fetch('md_shift', {}, A2);
    expect(rows.every((r) => r.customer_id === c2)).toBe(true);

    // Super admin sees every tenant.
    expect(await fetchOne('md_shift', { shift_id: insertId }, superAdmin)).not.toBeNull();
  });

  it('update stamps updated_by; soft delete hides the row unless includeDeleted', async () => {
    const A1 = adminOf(c1);
    const editor = adminOf(c1, 'editor');
    const { insertId } = await save('md_shift', shift('Evening'), { flag: 'I' }, A1);

    const upd = await save(
      'md_shift',
      { shift_name: 'Late' },
      { flag: 'U', where: { shift_id: insertId } },
      editor,
    );
    expect(upd.affectedRows).toBe(1);
    expect(await fetchOne('md_shift', { shift_id: insertId }, A1)).toMatchObject({
      shift_name: 'Late',
      updated_by: 'editor',
      created_by: A1.loginId,
    });

    expect(await softDelete('md_shift', { shift_id: insertId }, A1)).toBe(1);
    expect(await softDelete('md_shift', { shift_id: insertId }, A1)).toBe(0);
    expect(await fetchOne('md_shift', { shift_id: insertId }, A1)).toBeNull();
    const deleted = await fetchOne('md_shift', { shift_id: insertId }, A1, undefined, {
      includeDeleted: true,
    });
    expect(deleted).toMatchObject({ is_deleted: 'Y', sync_flag: 'N' });
    // Updates never touch deleted rows.
    expect(
      (
        await save(
          'md_shift',
          { shift_name: 'Z' },
          { flag: 'U', where: { shift_id: insertId } },
          A1,
        )
      ).affectedRows,
    ).toBe(0);
  });

  it('paginates with a total and searches', async () => {
    const cust = await createCustomer();
    const A = adminOf(cust);
    for (const n of ['Alpha', 'Beta', 'Gamma', 'Alpine']) {
      await save('md_shift', shift(n), { flag: 'I' }, A);
    }
    const page = await fetch(
      'md_shift',
      { page: 2, limit: 3, orderBy: [{ column: 'shift_name' }] },
      A,
    );
    expect(page.total).toBe(4);
    expect(page.rows.map((r) => r.shift_name)).toEqual(['Gamma']);

    const found = await fetch('md_shift', { search: { columns: ['shift_name'], term: 'alp' } }, A);
    expect(found.rows.map((r) => r.shift_name).sort()).toEqual(['Alpha', 'Alpine']);
  });

  it('never returns password_hash unless asked', async () => {
    const admin = await fetchOne('md_user', { user_type: 'S' }, superAdmin);
    expect(admin).not.toHaveProperty('password_hash');
    const withHash = await fetchOne('md_user', { user_type: 'S' }, superAdmin, undefined, {
      columns: ['id', 'password_hash'],
    });
    expect(withHash?.password_hash).toMatch(/^\$argon2id\$/);
  });

  it('surfaces duplicate keys as ER_DUP_ENTRY', async () => {
    const A = adminOf(c1);
    const rate = {
      block_hours: 24,
      first_block_rate: '50.00',
      next_block_rate: '20.00',
      effective_from: '2026-01-01',
    };
    await save('md_rate_dtls', rate, { flag: 'I' }, A);
    await expect(save('md_rate_dtls', rate, { flag: 'I' }, A)).rejects.toMatchObject({
      code: 'ER_DUP_ENTRY',
    });
  });

  it('raw runs parameterised SQL (positional and named) and refuses hard deletes', async () => {
    const rows = await raw<RowDataPacket[]>('SELECT ? + ? AS s', [2, 3]);
    expect(rows[0]?.s).toBe(5);
    const named = await raw<RowDataPacket[]>('SELECT :a AS a', { a: 'x' });
    expect(named[0]?.a).toBe('x');
    // eslint-disable-next-line no-restricted-syntax -- asserts the runtime guard rejects it
    await expect(raw('  delete from md_shift')).rejects.toThrow(/forbidden/);
    await expect(raw('TRUNCATE md_shift')).rejects.toThrow(/forbidden/);
  });
});

describe('withTransaction', () => {
  it('commits on success and rolls back on error', async () => {
    const A = adminOf(c1);
    const id = await withTransaction(async (conn) => {
      const r = await save('md_shift', shift('Committed'), { flag: 'I' }, A, conn);
      return r.insertId;
    });
    expect(await fetchOne('md_shift', { shift_id: id }, A)).not.toBeNull();

    let rolledBackId = 0;
    await expect(
      withTransaction(async (conn) => {
        rolledBackId = (await save('md_shift', shift('RolledBack'), { flag: 'I' }, A, conn))
          .insertId;
        throw new Error('abort');
      }),
    ).rejects.toThrow('abort');
    expect(
      await fetchOne('md_shift', { shift_id: rolledBackId }, A, undefined, {
        includeDeleted: true,
      }),
    ).toBeNull();
  });

  it('retries deadlocks, then gives up', async () => {
    let calls = 0;
    const result = await withTransaction(async () => {
      calls++;
      if (calls < 3) throw Object.assign(new Error('deadlock'), { code: 'ER_LOCK_DEADLOCK' });
      return 'ok';
    });
    expect(result).toBe('ok');
    expect(calls).toBe(3);

    let attempts = 0;
    await expect(
      withTransaction(
        async () => {
          attempts++;
          throw Object.assign(new Error('deadlock'), { code: 'ER_LOCK_DEADLOCK' });
        },
        { retries: 1 },
      ),
    ).rejects.toThrow('deadlock');
    expect(attempts).toBe(2);
  });
});

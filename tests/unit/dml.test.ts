import { beforeAll, describe, expect, it } from 'vitest';
import {
  buildInsert,
  buildSelect,
  buildSoftDelete,
  buildUpdate,
  buildWhere,
  escapeLike,
  scopeWhere,
} from '../../src/core/db/dml';
import { buildRegistry, getTable, setRegistry, type TableMeta } from '../../src/core/db/registry';
import { ApiError } from '../../src/core/http/ApiError';
import { adminOf, superAdmin } from '../helpers/context';

const STD = ['is_deleted', 'sync_flag', 'created_by', 'created_at', 'updated_by', 'updated_at'];

function col(table: string, column: string, extra = '', key = '') {
  return {
    table_name: table,
    column_name: column,
    data_type: 'varchar',
    is_nullable: 'YES' as const,
    extra,
    column_key: key,
  };
}

const rows = [
  col('t_tenant', 'id', 'auto_increment', 'PRI'),
  col('t_tenant', 'customer_id'),
  col('t_tenant', 'name'),
  col('t_tenant', 'code'),
  col('t_tenant', 'password_hash'),
  col('t_tenant', 'user_key', 'STORED GENERATED'),
  ...STD.map((c) => col('t_tenant', c)),
  col('t_global', 'gid', 'auto_increment', 'PRI'),
  col('t_global', 'label'),
  ...STD.filter((c) => c !== 'sync_flag').map((c) => col('t_global', c)),
  col('t_plain', 'k', '', 'PRI'),
  col('t_plain', 'v'),
  col('schema_migrations', 'version', '', 'PRI'),
];

const A = adminOf(7);
let tenant: TableMeta;
let global: TableMeta;
let plain: TableMeta;

beforeAll(() => {
  setRegistry(buildRegistry(rows));
  tenant = getTable('t_tenant');
  global = getTable('t_global');
  plain = getTable('t_plain');
});

function expectApiError(fn: () => unknown, status: number) {
  try {
    fn();
  } catch (err) {
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(status);
    return;
  }
  throw new Error('expected ApiError');
}

describe('registry', () => {
  it('builds metadata: pk, generated, auto-increment; ignores schema_migrations', () => {
    expect(tenant.primaryKey).toEqual(['id']);
    expect(tenant.columns.get('user_key')?.generated).toBe(true);
    expect(tenant.columns.get('id')?.autoIncrement).toBe(true);
    expectApiError(() => getTable('schema_migrations'), 400);
  });

  it('rejects unknown and malformed table names', () => {
    expectApiError(() => getTable('nope'), 400);
    expectApiError(() => getTable('t_tenant; DROP'), 400);
  });
});

describe('scopeWhere / tenant injection', () => {
  it('forces customer_id from ctx for tenant users, overriding the caller', () => {
    expect(scopeWhere(tenant, { customer_id: 99, name: 'x' }, A, true)).toEqual({
      customer_id: 7,
      name: 'x',
      is_deleted: 'N',
    });
  });

  it('does not scope super admins', () => {
    expect(scopeWhere(tenant, { customer_id: 99 }, superAdmin, true)).toEqual({
      customer_id: 99,
      is_deleted: 'N',
    });
  });

  it('keeps an explicit is_deleted and skips tables without the columns', () => {
    expect(scopeWhere(tenant, { is_deleted: 'Y' }, A, true).is_deleted).toBe('Y');
    expect(scopeWhere(plain, {}, A, true)).toEqual({});
  });

  it('rejects a tenant user without a tenant', () => {
    expectApiError(() => scopeWhere(tenant, {}, { ...A, customerId: null }, true), 403);
  });
});

describe('buildWhere', () => {
  it('supports =, null, in, operators and like', () => {
    const w = buildWhere(tenant, {
      name: 'a',
      code: null,
      id: { in: [1, 2] },
      created_at: { op: '>=', value: '2026-01-01 00:00:00' },
      updated_by: { op: 'like', value: 'x%' },
      created_by: { op: '<>', value: null },
      updated_at: { op: '=', value: null },
      customer_id: undefined as never,
    });
    expect(w.sql).toBe(
      ' WHERE `name` = ? AND `code` IS NULL AND `id` IN (?, ?) AND `created_at` >= ?' +
        ' AND `updated_by` LIKE ? AND `created_by` IS NOT NULL AND `updated_at` IS NULL',
    );
    expect(w.params).toEqual(['a', 1, 2, '2026-01-01 00:00:00', 'x%']);
  });

  it('turns an empty IN into a false predicate', () => {
    expect(buildWhere(tenant, { id: { in: [] } }).sql).toBe(' WHERE 1 = 0');
  });

  it('accepts Buffer values as scalars', () => {
    const buf = Buffer.from('x');
    expect(buildWhere(tenant, { name: buf }).params).toEqual([buf]);
  });

  it('rejects bad operators, null comparisons and unknown/malicious columns', () => {
    expectApiError(() => buildWhere(tenant, { id: { op: '>', value: null } }), 400);
    expectApiError(() => buildWhere(tenant, { id: { op: 'OR 1=1' as never, value: 1 } }), 400);
    expectApiError(() => buildWhere(tenant, { 'name`; --': 1 }), 400);
    expectApiError(() => buildWhere(tenant, { nope: 1 }), 400);
  });

  it('builds an escaped OR search', () => {
    const w = buildWhere(tenant, {}, { columns: ['name', 'code'], term: '50%_a\\' });
    expect(w.sql).toBe(' WHERE (`name` LIKE ? OR `code` LIKE ?)');
    expect(w.params).toEqual(['%50\\%\\_a\\\\%', '%50\\%\\_a\\\\%']);
    expectApiError(() => buildWhere(tenant, {}, { columns: ['nope'], term: 'x' }), 400);
    expect(buildWhere(tenant, {}, { columns: ['name'], term: '' }).sql).toBe('');
  });

  it('escapeLike escapes wildcards and backslashes', () => {
    expect(escapeLike('a%b_c\\')).toBe('a\\%b\\_c\\\\');
  });
});

describe('buildSelect', () => {
  it('selects non-sensitive columns, tenant-scoped and not deleted', () => {
    const b = buildSelect(tenant, {}, A);
    expect(b.sql).not.toContain('password_hash');
    expect(b.sql).toMatch(/^SELECT `id`, `customer_id`, `name`/);
    expect(b.sql).toContain(' FROM `t_tenant` WHERE `customer_id` = ? AND `is_deleted` = ?');
    expect(b.params).toEqual([7, 'N']);
    expect(b.count).toBeUndefined();
  });

  it('returns sensitive columns only when named explicitly', () => {
    expect(buildSelect(tenant, { columns: ['password_hash'] }, A).sql).toMatch(
      /^SELECT `password_hash` FROM/,
    );
  });

  it('paginates with clamped limit, offset, pk order and a count query', () => {
    const b = buildSelect(tenant, { page: 3, limit: 1000 }, superAdmin);
    expect(b.sql).toContain('ORDER BY `id` ASC LIMIT ? OFFSET ?');
    expect(b.params.slice(-2)).toEqual([200, 400]);
    expect(b.count?.sql).toBe('SELECT COUNT(*) AS total FROM `t_tenant` WHERE `is_deleted` = ?');
    expect(b.limit).toBe(200);
    expect(b.page).toBe(3);

    const small = buildSelect(tenant, { page: 0, limit: 0 }, superAdmin);
    expect(small.params.slice(-2)).toEqual([1, 0]);
    expect(buildSelect(tenant, { page: 2 }, superAdmin).params.slice(-2)).toEqual([50, 50]);
  });

  it('honours orderBy and includeDeleted', () => {
    const b = buildSelect(
      tenant,
      { orderBy: [{ column: 'name', dir: 'desc' }, { column: 'id' }], includeDeleted: true },
      superAdmin,
    );
    expect(b.sql).toBe(
      `SELECT ${[...tenant.columns.keys()]
        .filter((c) => c !== 'password_hash')
        .map((c) => `\`${c}\``)
        .join(', ')} FROM \`t_tenant\` ORDER BY \`name\` DESC, \`id\` ASC`,
    );
    expectApiError(() => buildSelect(tenant, { orderBy: [{ column: 'x' }] }, A), 400);
    expectApiError(() => buildSelect(tenant, { columns: ['x'] }, A), 400);
  });
});

describe('buildInsert', () => {
  it('stamps audit columns, resets sync_flag and forces the tenant', () => {
    const b = buildInsert(
      tenant,
      { name: 'n', customer_id: 99, created_by: 'evil', sync_flag: 'Y', code: undefined },
      A,
    );
    expect(b.sql).toBe(
      'INSERT INTO `t_tenant` (`name`, `customer_id`, `sync_flag`, `created_by`, `created_at`,' +
        ' `updated_by`, `updated_at`) VALUES (?, ?, ?, ?, NOW(), ?, NOW())',
    );
    expect(b.params).toEqual(['n', 7, 'N', 'admin7', 'admin7']);
  });

  it('lets a super admin choose customer_id', () => {
    expect(buildInsert(tenant, { name: 'n', customer_id: 99 }, superAdmin).params).toEqual([
      'n',
      99,
      'N',
      'sa',
      'sa',
    ]);
  });

  it('only stamps columns the table has', () => {
    expect(buildInsert(global, { label: 'x' }, A).sql).toBe(
      'INSERT INTO `t_global` (`label`, `created_by`, `created_at`, `updated_by`, `updated_at`)' +
        ' VALUES (?, ?, NOW(), ?, NOW())',
    );
    expect(buildInsert(plain, { k: 'a', v: 'b' }, A).sql).toBe(
      'INSERT INTO `t_plain` (`k`, `v`) VALUES (?, ?)',
    );
  });

  it('rejects generated/unknown columns, empty data and tenant users without a tenant', () => {
    expectApiError(() => buildInsert(tenant, { user_key: 1 }, A), 400);
    expectApiError(() => buildInsert(tenant, { nope: 1 }, A), 400);
    expectApiError(() => buildInsert(plain, {}, A), 400);
    expectApiError(() => buildInsert(tenant, { name: 'n' }, { ...A, customerId: null }), 403);
  });
});

describe('buildUpdate', () => {
  it('sets audit columns and scopes the where to tenant + live rows', () => {
    const b = buildUpdate(tenant, { name: 'n', customer_id: 99, updated_at: 'x' }, { id: 5 }, A);
    expect(b.sql).toBe(
      "UPDATE `t_tenant` SET `name` = ?, `sync_flag` = 'N', `updated_by` = ?," +
        ' `updated_at` = NOW() WHERE `id` = ? AND `customer_id` = ? AND `is_deleted` = ?',
    );
    expect(b.params).toEqual(['n', 'admin7', 5, 7, 'N']);
  });

  it('lets a super admin move a row between tenants', () => {
    expect(buildUpdate(tenant, { customer_id: 3 }, { id: 5 }, superAdmin).sql).toContain(
      '`customer_id` = ?',
    );
  });

  it('requires a where, rejects pk/generated changes and empty updates', () => {
    expect(() => buildUpdate(tenant, { name: 'n' }, undefined, A)).toThrow(/where/);
    expect(() => buildUpdate(tenant, { name: 'n' }, { id: undefined as never }, A)).toThrow(
      /where/,
    );
    expectApiError(() => buildUpdate(tenant, { id: 9 }, { id: 5 }, A), 400);
    expectApiError(() => buildUpdate(tenant, { user_key: 9 }, { id: 5 }, A), 400);
    expectApiError(() => buildUpdate(tenant, { customer_id: 9 }, { id: 5 }, A), 400);
  });

  it('updates tables without audit columns', () => {
    expect(buildUpdate(plain, { v: 'x' }, { k: 'a' }, A).sql).toBe(
      'UPDATE `t_plain` SET `v` = ? WHERE `k` = ?',
    );
  });
});

describe('buildSoftDelete', () => {
  it('flags the live row deleted, tenant-scoped, with audit columns', () => {
    const b = buildSoftDelete(tenant, { id: 5 }, A);
    expect(b.sql).toBe(
      "UPDATE `t_tenant` SET `is_deleted` = 'Y', `sync_flag` = 'N', `updated_by` = ?," +
        ' `updated_at` = NOW() WHERE `id` = ? AND `is_deleted` = ? AND `customer_id` = ?',
    );
    expect(b.params).toEqual(['admin7', 5, 'N', 7]);
  });

  it('works on tables without sync_flag and refuses tables without is_deleted', () => {
    expect(buildSoftDelete(global, { gid: 1 }, A).sql).toBe(
      "UPDATE `t_global` SET `is_deleted` = 'Y', `updated_by` = ?, `updated_at` = NOW()" +
        ' WHERE `gid` = ? AND `is_deleted` = ?',
    );
    expectApiError(() => buildSoftDelete(plain, { k: 'a' }, A), 400);
    expect(() => buildSoftDelete(tenant, {}, A)).toThrow(/where/);
  });
});

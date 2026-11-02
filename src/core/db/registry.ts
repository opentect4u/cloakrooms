import type { Pool, PoolConnection, RowDataPacket } from 'mysql2/promise';
import { ApiError } from '../http/ApiError';

/**
 * Table/column whitelist loaded from information_schema at boot (CLAUDE.md §9). Every identifier
 * the DML layer puts into SQL must be found here first.
 */

export interface ColumnMeta {
  name: string;
  dataType: string;
  nullable: boolean;
  generated: boolean;
  autoIncrement: boolean;
}

export interface TableMeta {
  name: string;
  columns: ReadonlyMap<string, ColumnMeta>;
  primaryKey: readonly string[];
}

const IGNORED_TABLES = new Set(['schema_migrations']);
const SAFE_IDENT = /^[A-Za-z0-9_]{1,64}$/;

let tables: Map<string, TableMeta> | null = null;

interface ColumnRow extends RowDataPacket {
  table_name: string;
  column_name: string;
  data_type: string;
  is_nullable: 'YES' | 'NO';
  extra: string;
  column_key: string;
}

export async function loadRegistry(executor: Pool | PoolConnection): Promise<void> {
  const [rows] = await executor.query<ColumnRow[]>(
    `SELECT table_name AS table_name, column_name AS column_name, data_type AS data_type,
            is_nullable AS is_nullable, extra AS extra, column_key AS column_key
       FROM information_schema.columns
      WHERE table_schema = DATABASE()
      ORDER BY table_name, ordinal_position`,
  );
  setRegistry(buildRegistry(rows));
}

export function buildRegistry(rows: readonly Omit<ColumnRow, 'constructor'>[]): TableMeta[] {
  const byTable = new Map<string, { columns: Map<string, ColumnMeta>; pk: string[] }>();
  for (const r of rows) {
    if (IGNORED_TABLES.has(r.table_name)) continue;
    let t = byTable.get(r.table_name);
    if (!t) {
      t = { columns: new Map(), pk: [] };
      byTable.set(r.table_name, t);
    }
    const extra = r.extra.toLowerCase();
    t.columns.set(r.column_name, {
      name: r.column_name,
      dataType: r.data_type,
      nullable: r.is_nullable === 'YES',
      generated: extra.includes('generated'),
      autoIncrement: extra.includes('auto_increment'),
    });
    if (r.column_key === 'PRI') t.pk.push(r.column_name);
  }
  return [...byTable].map(([name, t]) => ({ name, columns: t.columns, primaryKey: t.pk }));
}

export function setRegistry(list: readonly TableMeta[]): void {
  tables = new Map(list.map((t) => [t.name, t]));
}

export function isRegistryLoaded(): boolean {
  return tables !== null && tables.size > 0;
}

export function getTable(name: string): TableMeta {
  if (!tables) throw new Error('DB registry not loaded (call loadRegistry at boot)');
  const meta = SAFE_IDENT.test(name) ? tables.get(name) : undefined;
  if (!meta) throw ApiError.badRequest(`Unknown table: ${name}`);
  return meta;
}

export function hasColumn(table: TableMeta, column: string): boolean {
  return table.columns.has(column);
}

export function assertColumn(table: TableMeta, column: string): ColumnMeta {
  const col = SAFE_IDENT.test(column) ? table.columns.get(column) : undefined;
  if (!col) throw ApiError.badRequest(`Unknown column: ${column}`);
  return col;
}

/** Back-quotes an identifier that has already been checked against the registry. */
export function quoteId(name: string): string {
  if (!SAFE_IDENT.test(name)) throw ApiError.badRequest(`Invalid identifier: ${name}`);
  return `\`${name}\``;
}

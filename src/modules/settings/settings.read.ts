/** Reads a customer-wide setting for server-side rules (login checks), falling back to the default. */
import type { RowDataPacket } from 'mysql2/promise';
import { raw, type Executor } from '../../core/db/dml';
import { settingDefault, type SettingName } from './settings.schema';

export async function customerSettingValue(
  customerId: number,
  name: SettingName,
  conn?: Executor,
): Promise<string> {
  const rows = await raw<RowDataPacket[]>(
    `SELECT setting_value FROM md_setting
      WHERE customer_id = ? AND user_id IS NULL AND setting_name = ? AND is_deleted = 'N'`,
    [customerId, name],
    conn,
  );
  return String(rows[0]?.setting_value ?? settingDefault(name) ?? '');
}

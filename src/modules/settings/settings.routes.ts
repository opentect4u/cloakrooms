import { crudFactory, type CrudConfig } from '../../core/crud/crudFactory';
import type { RequestContext } from '../../core/context';
import type { Row } from '../../core/db/dml';
import { ApiError } from '../../core/http/ApiError';
import { hashPassword } from '../../core/security/password';
import { assertTenantRef, MASTER_ROLES, tenantOf } from '../masters/refs';
import { SETTING_RULES, settingCreate, settingUpdate, type SettingName } from './settings.schema';

const MASK = '********';

/** Validates the value for its key and hashes secrets. */
async function checkedValue(name: string, value: unknown): Promise<string> {
  const rule = SETTING_RULES[name as SettingName];
  if (!rule) throw ApiError.validation([{ path: 'setting_name', message: 'Unknown setting' }]);
  const parsed = rule.value.safeParse(value);
  if (!parsed.success) {
    throw ApiError.validation(
      parsed.error.issues.map((i) => ({ path: 'setting_value', message: `${name}: ${i.message}` })),
    );
  }
  return name === 'REPORT_PWD' ? hashPassword(parsed.data) : parsed.data;
}

/** Keys only Synergic may change (e.g. SINGLE_SESSION_FLAG): 403 for station admins. */
function assertMayWrite(name: string, ctx: RequestContext) {
  const rule = (SETTING_RULES as Record<string, { superOnly?: boolean }>)[name];
  if (rule?.superOnly && ctx.userType !== 'S') {
    throw ApiError.forbidden(`${name} can only be changed by Synergic`);
  }
}

export const settingsCrud: CrudConfig = {
  table: 'md_setting',
  pk: 'setting_id',
  scope: 'tenant',
  roles: MASTER_ROLES,
  schema: { create: settingCreate, update: settingUpdate },
  filterable: ['setting_name', 'user_id', 'setting_type'],
  sortable: ['setting_name', 'setting_id'],
  defaultSort: [{ column: 'setting_name' }],
  hidden: ['user_key'],
  // UNIQUE (customer_id, user_key, setting_name)
  reviveDeletedOn: ['user_id', 'setting_name'],
  transformRow: (row: Row) =>
    row.setting_name === 'REPORT_PWD' ? { ...row, setting_value: MASK } : row,
  hooks: {
    beforeCreate: async (data, ctx, conn) => {
      const name = data.setting_name as SettingName;
      assertMayWrite(name, ctx);
      if (data.user_id !== null && SETTING_RULES[name].scope === 'customer') {
        throw ApiError.validation([
          {
            path: 'user_id',
            message: `${name} applies to the whole customer; a per-user value would be ignored`,
          },
        ]);
      }
      if (data.user_id !== null) {
        await assertTenantRef(
          { table: 'md_user', pk: 'id', id: data.user_id, field: 'user_id' },
          tenantOf(data, ctx),
          ctx,
          conn,
        );
      }
      return {
        ...data,
        setting_type: SETTING_RULES[name].type,
        setting_value: await checkedValue(name, data.setting_value),
      };
    },
    beforeUpdate: async (data, existing, ctx) => {
      assertMayWrite(String(existing.setting_name), ctx);
      return {
        setting_value: await checkedValue(String(existing.setting_name), data.setting_value),
      };
    },
  },
};

export const settingsRoutes = crudFactory(settingsCrud);

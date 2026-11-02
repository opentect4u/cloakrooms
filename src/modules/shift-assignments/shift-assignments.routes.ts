import { z } from 'zod';
import { crudFactory, type CrudConfig } from '../../core/crud/crudFactory';
import { dateOnly, positiveId } from '../../core/validation/fragments';
import { assertTenantRef, MASTER_ROLES, tenantOf } from '../masters/refs';

export const shiftAssignmentsCrud: CrudConfig = {
  table: 'td_assign_shift',
  pk: 'id',
  scope: 'tenant',
  roles: MASTER_ROLES,
  schema: {
    create: z.object({ user_id: positiveId, shift_id: positiveId, effective_from: dateOnly }),
    update: z.object({ shift_id: positiveId, effective_from: dateOnly }).partial(),
  },
  filterable: ['user_id', 'shift_id'],
  sortable: ['effective_from', 'user_id', 'id'],
  defaultSort: [{ column: 'user_id' }, { column: 'effective_from', dir: 'desc' }],
  hooks: {
    beforeCreate: async (data, ctx, conn) => {
      const tenant = tenantOf(data, ctx);
      await assertTenantRef(
        {
          table: 'md_user',
          pk: 'id',
          id: data.user_id,
          field: 'user_id',
          where: { user_type: { in: ['A', 'O'] } },
        },
        tenant,
        ctx,
        conn,
      );
      await assertTenantRef(
        { table: 'md_shift', pk: 'shift_id', id: data.shift_id, field: 'shift_id' },
        tenant,
        ctx,
        conn,
      );
      return data;
    },
    beforeUpdate: async (data, existing, ctx, conn) => {
      if (data.shift_id !== undefined) {
        await assertTenantRef(
          { table: 'md_shift', pk: 'shift_id', id: data.shift_id, field: 'shift_id' },
          Number(existing.customer_id),
          ctx,
          conn,
        );
      }
      return data;
    },
  },
};

export const shiftAssignmentsRoutes = crudFactory(shiftAssignmentsCrud);

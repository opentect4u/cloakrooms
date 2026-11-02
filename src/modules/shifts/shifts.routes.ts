import { z } from 'zod';
import { crudFactory, type CrudConfig } from '../../core/crud/crudFactory';
import type { Row } from '../../core/db/dml';
import { ApiError } from '../../core/http/ApiError';
import { timeOfDay } from '../../core/validation/fragments';
import { MASTER_ROLES } from '../masters/refs';

const fields = {
  shift_name: z.string().trim().min(1).max(50),
  from_time: timeOfDay,
  to_time: timeOfDay,
};

const hhmm = (t: unknown) => String(t).slice(0, 5);

/** to_time may be earlier than from_time (crosses midnight) but not equal. */
function assertSpan(row: Row): Row {
  if (hhmm(row.from_time) === hhmm(row.to_time)) {
    throw ApiError.validation([{ path: 'to_time', message: 'must differ from from_time' }]);
  }
  return row;
}

export const shiftsCrud: CrudConfig = {
  table: 'md_shift',
  pk: 'shift_id',
  scope: 'tenant',
  roles: MASTER_ROLES,
  schema: { create: z.object(fields), update: z.object(fields).partial() },
  searchable: ['shift_name'],
  sortable: ['shift_name', 'from_time', 'shift_id'],
  defaultSort: [{ column: 'from_time' }],
  hooks: {
    beforeCreate: (data) => assertSpan(data),
    beforeUpdate: (data, existing) => {
      assertSpan({ ...existing, ...data });
      return data;
    },
  },
};

export const shiftsRoutes = crudFactory(shiftsCrud);

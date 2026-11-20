import { crudFactory, type CrudConfig } from '../../core/crud/crudFactory';
import { MASTER_ROLES } from '../masters/refs';
import { rateCreate, rateCreateData, rateUpdate, rateUpdateData } from './rates.schema';

export { rateCreate, rateCreateData, rateSlabs, rateUpdate, rateUpdateData } from './rates.schema';

export const ratesCrud: CrudConfig = {
  table: 'md_rate_dtls',
  pk: 'rate_id',
  scope: 'tenant',
  roles: MASTER_ROLES,
  schema: { create: rateCreate, update: rateUpdate },
  sortable: ['effective_from', 'rate_id'],
  defaultSort: [{ column: 'effective_from', dir: 'desc' }],
  // UNIQUE (customer_id, effective_from): re-adding a deleted date revives that row.
  reviveDeletedOn: ['effective_from'],
  hooks: {
    beforeCreate: (data) => rateCreateData(data),
    beforeUpdate: (data, existing) => rateUpdateData(data, existing),
  },
};

export const ratesRoutes = crudFactory(ratesCrud);

import { z } from 'zod';
import { crudFactory, type CrudConfig } from '../../core/crud/crudFactory';
import { dateOnly, money } from '../../core/validation/fragments';
import { MASTER_ROLES } from '../masters/refs';

const blockHours = z.coerce.number().int().min(1).max(720);

/** Per-bag tariff, versioned by effective_from (one row per date per customer). */
export const rateCreate = z.object({
  block_hours: blockHours.default(24),
  first_block_rate: money,
  next_block_rate: money,
  effective_from: dateOnly,
});

export const rateUpdate = z
  .object({
    block_hours: blockHours,
    first_block_rate: money,
    next_block_rate: money,
    effective_from: dateOnly,
  })
  .partial();

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
};

export const ratesRoutes = crudFactory(ratesCrud);

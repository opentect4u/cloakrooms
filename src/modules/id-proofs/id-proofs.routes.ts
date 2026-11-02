import { z } from 'zod';
import { crudFactory, type CrudConfig } from '../../core/crud/crudFactory';
import { ynEnum } from '../../core/validation/fragments';
import { assertNoLiveDuplicate } from '../masters/refs';

const name = z.string().trim().min(1).max(50);

/** Global ID-proof types for the check-in dropdown. mask_flag Y → keep last 4 digits only. */
export const idProofsCrud: CrudConfig = {
  table: 'md_id_proof',
  pk: 'id_proof_id',
  scope: 'global',
  roles: { read: ['S', 'A', 'O'], write: ['S'] },
  schema: {
    create: z.object({ id_proof_name: name, mask_flag: ynEnum.default('N') }),
    update: z.object({ id_proof_name: name, mask_flag: ynEnum }).partial(),
  },
  searchable: ['id_proof_name'],
  sortable: ['id_proof_name', 'id_proof_id'],
  defaultSort: [{ column: 'id_proof_id' }],
  hooks: {
    beforeCreate: async (data, ctx, conn) => {
      await assertNoLiveDuplicate(
        'md_id_proof',
        'id_proof_id',
        { id_proof_name: data.id_proof_name as string },
        ctx,
        conn,
      );
      return data;
    },
    beforeUpdate: async (data, existing, ctx, conn) => {
      if (data.id_proof_name !== undefined) {
        await assertNoLiveDuplicate(
          'md_id_proof',
          'id_proof_id',
          { id_proof_name: data.id_proof_name as string },
          ctx,
          conn,
          existing.id_proof_id,
        );
      }
      return data;
    },
  },
};

export const idProofsRoutes = crudFactory(idProofsCrud);

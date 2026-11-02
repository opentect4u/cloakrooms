import { z } from 'zod';
import { crudFactory, type CrudConfig } from '../../core/crud/crudFactory';
import { dateOnly, percent } from '../../core/validation/fragments';
import { assertNoLiveDuplicate } from '../masters/refs';

const fields = {
  cgst_pct: percent,
  sgst_pct: percent,
  igst_pct: percent,
  effective_from: dateOnly,
};

/** Global GST rates, versioned by effective_from; Synergic maintains them, everyone reads. */
export const gstCrud: CrudConfig = {
  table: 'md_gst',
  pk: 'gst_id',
  scope: 'global',
  roles: { read: ['S', 'A', 'O'], write: ['S'] },
  schema: {
    create: z.object({
      ...fields,
      cgst_pct: percent.default('0'),
      sgst_pct: percent.default('0'),
      igst_pct: percent.default('0'),
    }),
    update: z.object(fields).partial(),
  },
  sortable: ['effective_from', 'gst_id'],
  defaultSort: [{ column: 'effective_from', dir: 'desc' }],
  hooks: {
    beforeCreate: async (data, ctx, conn) => {
      await assertNoLiveDuplicate(
        'md_gst',
        'gst_id',
        { effective_from: data.effective_from as string },
        ctx,
        conn,
      );
      return data;
    },
    beforeUpdate: async (data, existing, ctx, conn) => {
      if (data.effective_from !== undefined) {
        await assertNoLiveDuplicate(
          'md_gst',
          'gst_id',
          { effective_from: data.effective_from as string },
          ctx,
          conn,
          existing.gst_id,
        );
      }
      return data;
    },
  },
};

export const gstRoutes = crudFactory(gstCrud);

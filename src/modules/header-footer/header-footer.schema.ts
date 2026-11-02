import { z } from 'zod';
import { positiveId, ynEnum, ynFlag } from '../../core/validation/fragments';

const lineOrder = z.coerce.number().int().min(0).max(999);

/** Text lines only; logo rows (L) are created through the multipart logo upload. */
export const headerFooterCreate = z.object({
  line_type: z.enum(['H', 'F']),
  line_order: lineOrder.default(0),
  narration: z.string().trim().min(1).max(100),
  in_flag: ynEnum.default('Y'),
  out_flag: ynEnum.default('Y'),
  report_flag: ynEnum.default('N'),
});

export const headerFooterUpdate = z
  .object({
    line_order: lineOrder,
    narration: z.string().trim().min(1).max(100),
    in_flag: ynEnum,
    out_flag: ynEnum,
    report_flag: ynEnum,
  })
  .partial();

/** Text fields of the multipart logo upload. */
export const logoFields = z.object({
  line_order: lineOrder.optional(),
  in_flag: ynFlag.optional(),
  out_flag: ynFlag.optional(),
  report_flag: ynFlag.optional(),
  /** Super admin only. */
  customer_id: positiveId.optional(),
});
export type LogoFields = z.infer<typeof logoFields>;

export const LOGO_MAX_BYTES = 200 * 1024;

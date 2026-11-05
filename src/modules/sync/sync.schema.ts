import { z } from 'zod';
import {
  dateOnly,
  money,
  percent,
  positiveId,
  utcDateTime,
  uuid,
  ynEnum,
} from '../../core/validation/fragments';

/** Rows are validated one by one (a bad row becomes an EXCEPTION, it never blocks the batch). */
export const MAX_ROWS_PER_TABLE = 5000;
export const CHUNK_SIZE = 500;

const anyRow = z.record(z.string(), z.unknown());

export const pushBody = z.object({
  luggage: z.array(anyRow).max(MAX_ROWS_PER_TABLE).default([]),
  receipts: z.array(anyRow).max(MAX_ROWS_PER_TABLE).default([]),
});
export type PushBody = z.infer<typeof pushBody>;

const nullable = <T extends z.ZodType>(t: T) =>
  t
    .nullable()
    .optional()
    .transform((v) => v ?? null);

/** Device-only version counter; never stored, echoed back so the device can match results. */
const localVersion = z.union([z.number(), z.string().max(50)]).optional();

export const luggageRow = z
  .object({
    trans_id: uuid,
    local_version: localVersion,
    receipt_no: z.string().trim().min(1).max(20),
    receipt_dt: dateOnly,
    user_id_in: positiveId,
    /** Required on a check-in row; a QR check-out row (out_flag Y) may not know it. */
    device_id_in: nullable(z.string().trim().min(1).max(100)),
    shift_id_in: nullable(positiveId),
    date_time_in: utcDateTime,
    pasn_name: z.string().trim().min(1).max(200),
    pasn_mob_no: z.string().trim().min(1).max(15),
    pnr_no: z.string().trim().max(20).default(''),
    luggage_qty: z.coerce.number().int().min(1).max(999),
    /** Both or neither (ID proof is not in the QR; ID_PROOF_REQUIRED is enforced on the device). */
    id_proof_id: nullable(positiveId),
    id_number: nullable(z.string().trim().min(1).max(50)),
    user_id_out: nullable(positiveId),
    device_id_out: nullable(z.string().trim().min(1).max(100)),
    shift_id_out: nullable(positiveId),
    date_time_out: nullable(utcDateTime),
    out_flag: ynEnum.default('N'),
  })
  .superRefine((r, ctx) => {
    if (r.out_flag === 'N' && r.device_id_in === null) {
      ctx.addIssue({
        code: 'custom',
        path: ['device_id_in'],
        message: 'required on a check-in row',
      });
    }
    if ((r.id_proof_id === null) !== (r.id_number === null)) {
      ctx.addIssue({
        code: 'custom',
        path: [r.id_proof_id === null ? 'id_proof_id' : 'id_number'],
        message: 'id_proof_id and id_number go together',
      });
    }
    const outComplete =
      r.user_id_out !== null && r.device_id_out !== null && r.date_time_out !== null;
    if (r.out_flag === 'Y' && !outComplete) {
      ctx.addIssue({
        code: 'custom',
        path: ['out_flag'],
        message: 'checked-out row needs user_id_out, device_id_out and date_time_out',
      });
    }
    if (r.out_flag === 'N' && r.date_time_out !== null) {
      ctx.addIssue({
        code: 'custom',
        path: ['out_flag'],
        message: 'date_time_out given but out_flag is N',
      });
    }
    if (r.date_time_out !== null && r.date_time_out < r.date_time_in) {
      ctx.addIssue({ code: 'custom', path: ['date_time_out'], message: 'is before date_time_in' });
    }
  });
export type LuggageRow = z.infer<typeof luggageRow>;

export const receiptRow = z.object({
  trans_id: uuid,
  local_version: localVersion,
  bill_no: z.string().trim().min(1).max(20),
  bill_dt: dateOnly,
  user_id: positiveId,
  device_id: z.string().trim().min(1).max(100),
  shift_id: nullable(positiveId),
  luggage_qty: z.coerce.number().int().min(1).max(999),
  days_charged: z.coerce.number().int().min(1).max(65_535),
  block_hours: z.coerce.number().int().min(1).max(720),
  first_block_rate: money,
  next_block_rate: money,
  base_amt: money,
  penal_amt: money.default('0'),
  net_amt: money,
  taxable_amt: money,
  gst_pct: percent,
  cgst: money,
  sgst: money,
  igst: money,
  gst_flag: ynEnum.default('N'),
  pay_mode: z.enum(['C', 'U']),
  upi_ref: nullable(z.string().trim().max(50)),
  bill_printed: ynEnum.default('Y'),
});
export type ReceiptRow = z.infer<typeof receiptRow>;

export const PULL_PAGE_MAX = 1000;

export const pullQuery = z.object({
  /** The `server_time` of the previous completed pull (UTC). Omit for the first pull. */
  since: utcDateTime.optional(),
  /** `next_cursor` of the previous page while `has_more` is true (then omit `since`). */
  cursor: z.string().max(500).optional(),
  /** Luggage rows per page. */
  limit: z.coerce.number().int().min(1).max(PULL_PAGE_MAX).default(PULL_PAGE_MAX),
});
export type PullQuery = z.infer<typeof pullQuery>;

/** Decoded `cursor`: mode, original server_time, last (updated_at, trans_id) sent. */
export const pullCursor = z.object({
  m: z.enum(['open', 'changes']),
  s: utcDateTime,
  u: utcDateTime,
  t: z.string().max(36),
});
export type PullCursor = z.infer<typeof pullCursor>;

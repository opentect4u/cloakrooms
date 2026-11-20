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
import { rateSlabs } from '../rates/rates.schema';

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
    /** Extra bags stored free, never billed (Phase 13). Filled once while 0 (QR key x). */
    extra_lugage_qty: z.coerce.number().int().min(0).max(999).default(0),
    /** Advance taken at check-in and the ADVANCE_HOURS it covered (Phase 13, QR key a). */
    adv_amt: money.default('0'),
    adv_hours: z.coerce.number().int().min(0).max(720).default(0),
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

export const receiptRow = z
  .object({
    trans_id: uuid,
    local_version: localVersion,
    bill_no: z.string().trim().min(1).max(20),
    bill_dt: dateOnly,
    user_id: positiveId,
    device_id: z.string().trim().min(1).max(100),
    shift_id: nullable(positiveId),
    luggage_qty: z.coerce.number().int().min(1).max(999),
    days_charged: z.coerce.number().int().min(1).max(65_535),
    /** Slabs the bill was made with (Phase 13). Older apps send only the two-rate fields. */
    rate_slabs: rateSlabs.optional(),
    /** Two-rate snapshot: required without rate_slabs, else optional (the server derives it). */
    block_hours: z.coerce.number().int().min(1).max(720).optional(),
    first_block_rate: money.optional(),
    next_block_rate: money.optional(),
    base_amt: money,
    penal_amt: money.default('0'),
    net_amt: money,
    /** Advance taken at check-in, subtracted on this bill (must equal the bag's adv_amt). */
    adv_amt: money.default('0'),
    /** Collected at check-out = max(0, net_amt − adv_amt). Omitted (older app) → computed. */
    bal_amt: money.optional(),
    taxable_amt: money,
    gst_pct: percent,
    cgst: money,
    sgst: money,
    igst: money,
    /** N = 0 % GST. Omitted: the billing user's GST_FLAG setting (default Y). */
    gst_flag: ynEnum.optional(),
    /** Billing options the device used (Phase 11); omitted → the customer's settings. */
    grace_minutes: z.coerce.number().int().min(0).max(1440).optional(),
    gst_inclusive: ynEnum.optional(),
    penalty_gst_flag: ynEnum.optional(),
    pay_mode: z.enum(['C', 'U']),
    upi_ref: nullable(z.string().trim().max(50)),
    bill_printed: ynEnum.default('Y'),
  })
  .superRefine((r, ctx) => {
    if (r.rate_slabs) return;
    for (const f of ['block_hours', 'first_block_rate', 'next_block_rate'] as const) {
      if (r[f] === undefined) {
        ctx.addIssue({
          code: 'custom',
          path: [f],
          message: 'required when rate_slabs is not sent',
        });
      }
    }
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

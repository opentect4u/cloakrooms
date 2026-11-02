import { z } from 'zod';
import { dateOnly, money, positiveId, ynEnum } from '../../core/validation/fragments';

const optionalText = (max: number) => z.string().trim().max(max).optional();

const customerFields = {
  customer_name: z.string().trim().min(1).max(155),
  customer_addr: z.string().trim().min(1).max(200),
  state: optionalText(50),
  pin_code: z
    .string()
    .trim()
    .regex(/^\d{6}$/, '6-digit PIN code'),
  mobile_no: z
    .string()
    .trim()
    .regex(/^[0-9+\- ]{7,20}$/, 'phone number'),
  email: z.email().max(150).optional(),
  gst_no: z
    .string()
    .trim()
    .toUpperCase()
    .regex(/^\d{2}[A-Z0-9]{13}$/, '15-character GSTIN')
    .optional(),
  upi_id: z
    .string()
    .trim()
    .max(255)
    .regex(/^[\w.-]{2,}@[A-Za-z]{2,64}$/, 'UPI id like name@bank')
    .optional(),
  user_limit: z.coerce.number().int().min(1).max(1000),
  active_flag: ynEnum,
  remarks: optionalText(255),
};

/** renewal_dt is not writable here: it follows the latest registration. */
export const customerCreate = z.object({
  ...customerFields,
  user_limit: customerFields.user_limit.default(1),
  active_flag: ynEnum.default('Y'),
});
export const customerUpdate = z.object(customerFields).partial();

export const registrationFields = {
  reg_no: z.string().trim().min(1).max(30),
  start_date: dateOnly,
  end_date: dateOnly,
  user_count: z.coerce.number().int().min(1).max(1000),
  remarks: optionalText(255),
};
export const registrationCreate = z.object(registrationFields);
export const registrationUpdate = z.object(registrationFields).partial();

const paymentFields = {
  reg_id: positiveId.nullable(),
  payment_dt: dateOnly,
  base_amt: money,
  cgst: money,
  sgst: money,
  igst: money,
  /** Must equal base + cgst + sgst + igst; computed when omitted. */
  net_amt: money,
  pay_mode: z.enum(['C', 'U', 'B']),
  transaction_no: optionalText(50),
  remarks: optionalText(255),
};
export const paymentCreate = z.object({
  ...paymentFields,
  reg_id: paymentFields.reg_id.optional(),
  cgst: money.default('0'),
  sgst: money.default('0'),
  igst: money.default('0'),
  net_amt: money.optional(),
});
export const paymentUpdate = z.object(paymentFields).partial();

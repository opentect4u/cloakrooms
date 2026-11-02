/**
 * ID numbers of proofs with mask_flag = 'Y' (Aadhaar) are stored and printed with only the last
 * 4 characters visible (CLAUDE.md §10). Idempotent: masking a masked value changes nothing.
 * TODO(open-point): Aadhaar legal confirmation.
 */
export function maskIdNumber(idNumber: string): string {
  const visible = idNumber.replace(/[\s-]/g, '').slice(-4);
  return `XXXXXXXX${visible}`;
}

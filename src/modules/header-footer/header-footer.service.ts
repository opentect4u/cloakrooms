import type { RequestContext } from '../../core/context';
import { serializeRow } from '../../core/crud/crudFactory';
import { fetchOne, save, withTransaction } from '../../core/db/dml';
import { ApiError } from '../../core/http/ApiError';
import { tenantOf } from '../masters/refs';
import type { LogoFields } from './header-footer.schema';

const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

export function isPng(buf: Buffer): boolean {
  return buf.length > PNG_SIGNATURE.length && buf.subarray(0, 8).equals(PNG_SIGNATURE);
}

/**
 * One logo (line_type L) per customer: uploads replace the live logo row's image, or create it.
 * The bytes are checked, not the client-declared MIME type.
 */
export async function upsertLogo(
  ctx: RequestContext,
  image: Buffer | undefined,
  fields: LogoFields,
) {
  if (!image) throw ApiError.validation([{ path: 'logo', message: 'PNG file is required' }]);
  if (!isPng(image))
    throw ApiError.validation([{ path: 'logo', message: 'Only PNG images are accepted' }]);
  const { customer_id: bodyCustomerId, ...flags } = fields;
  const customerId = tenantOf({ customer_id: bodyCustomerId }, ctx);

  return withTransaction(async (conn) => {
    const where = { customer_id: customerId, line_type: 'L' };
    const existing = await fetchOne('md_header_footer', where, ctx, conn, { columns: ['id'] });
    let id: number;
    let created = false;
    if (existing) {
      id = Number(existing.id);
      await save('md_header_footer', { image, ...flags }, { flag: 'U', where: { id } }, ctx, conn);
    } else {
      created = true;
      ({ insertId: id } = await save(
        'md_header_footer',
        { customer_id: customerId, line_type: 'L', line_order: 0, ...flags, image },
        { flag: 'I' },
        ctx,
        conn,
      ));
    }
    const row = await fetchOne('md_header_footer', { id }, ctx, conn);
    return { created, row: row && serializeRow(row) };
  });
}

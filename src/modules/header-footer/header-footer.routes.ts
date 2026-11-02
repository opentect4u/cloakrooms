import { Router, type Request, type Response } from 'express';
import multer from 'multer';
import { crudFactory, type CrudConfig } from '../../core/crud/crudFactory';
import { asyncHandler } from '../../core/http/asyncHandler';
import { sendSuccess } from '../../core/http/response';
import { ctxOf, requireRole } from '../../core/middleware/requireRole';
import { validate } from '../../core/middleware/validate';
import { MASTER_ROLES } from '../masters/refs';
import {
  headerFooterCreate,
  headerFooterUpdate,
  LOGO_MAX_BYTES,
  logoFields,
  type LogoFields,
} from './header-footer.schema';
import { upsertLogo } from './header-footer.service';

const upload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: LOGO_MAX_BYTES, files: 1, fields: 10, parts: 12 },
});

export const headerFooterRoutes = Router();

/** POST /header-footer/logo — multipart field `logo` (PNG ≤ 200 KB), served back as base64. */
headerFooterRoutes.post(
  '/logo',
  requireRole(...MASTER_ROLES.write),
  upload.single('logo'),
  validate({ body: logoFields }),
  asyncHandler(async (req: Request, res: Response) => {
    const { created, row } = await upsertLogo(ctxOf(req), req.file?.buffer, req.body as LogoFields);
    sendSuccess(req, res, row, {
      status: created ? 201 : 200,
      message: created ? 'Logo uploaded' : 'Logo replaced',
    });
  }),
);

export const headerFooterCrud: CrudConfig = {
  table: 'md_header_footer',
  pk: 'id',
  scope: 'tenant',
  roles: MASTER_ROLES,
  schema: { create: headerFooterCreate, update: headerFooterUpdate },
  filterable: ['line_type'],
  sortable: ['line_type', 'line_order', 'id'],
  defaultSort: [{ column: 'line_type' }, { column: 'line_order' }],
};

headerFooterRoutes.use(crudFactory(headerFooterCrud));

/**
 * Customer tab "Receipt layout": header/footer text lines (generic forms in resource.routes), the
 * logo (PNG ≤ 200 KB, header-footer.service.upsertLogo) and a live preview of the thermal paper.
 */
import { Router, type NextFunction, type Request, type Response } from 'express';
import multer, { MulterError } from 'multer';
import type { RequestContext } from '../core/context';
import { crudService } from '../core/crud/crudService';
import { fetchOne } from '../core/db/dml';
import { ApiError } from '../core/http/ApiError';
import { headerFooterCrud } from '../modules/header-footer/header-footer.routes';
import { LOGO_MAX_BYTES } from '../modules/header-footer/header-footer.schema';
import { upsertLogo } from '../modules/header-footer/header-footer.service';
import { verifyCsrf } from './csrf';
import { flash } from './flash';
import { formFailure } from './forms';
import { handle, page } from './render';

const lines = crudService(headerFooterCrud);
const tctxOf = (res: Response) => res.locals.tctx as RequestContext;

const upload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: LOGO_MAX_BYTES, files: 1, fields: 5, parts: 6 },
}).single('logo');

export function layoutRoutes(): Router {
  const router = Router({ mergeParams: true });
  const base = (res: Response) => `${String(res.locals.base)}/header-footer`;

  router.get(
    '/header-footer',
    handle(async (_req, res) => {
      const ctx = tctxOf(res);
      const { rows } = await lines.list(ctx, {
        page: 1,
        limit: 200,
        sort: [{ column: 'line_type' }, { column: 'line_order' }, { column: 'id' }],
      });
      const text = rows.filter((r) => r.line_type !== 'L');
      const logo = rows.find((r) => r.line_type === 'L') ?? null;
      const paper = await fetchOne(
        'md_setting',
        { setting_name: 'PAPER_WIDTH', user_id: null },
        ctx,
        undefined,
        { columns: ['setting_value'] },
      );
      const pick = (type: string, flag: string) =>
        text.filter((r) => r.line_type === type && r[flag] === 'Y').map((r) => String(r.narration));
      page(res, 'admin/customers/header-footer', {
        title: 'Receipt layout',
        active: 'customers',
        tab: 'header-footer',
        headers: text.filter((r) => r.line_type === 'H'),
        footers: text.filter((r) => r.line_type === 'F'),
        logo,
        logoKb: logo?.image ? Math.ceil((String(logo.image).length * 3) / 4 / 1024) : 0,
        paper: String(paper?.setting_value ?? '58'),
        preview: {
          in: {
            header: pick('H', 'in_flag'),
            footer: pick('F', 'in_flag'),
            logo: logo?.in_flag === 'Y',
          },
          out: {
            header: pick('H', 'out_flag'),
            footer: pick('F', 'out_flag'),
            logo: logo?.out_flag === 'Y',
          },
        },
        maxKb: Math.round(LOGO_MAX_BYTES / 1024),
      });
    }),
  );

  router.post(
    '/header-footer/logo',
    (req: Request, res: Response, next: NextFunction) => {
      upload(req, res, (err: unknown) => {
        if (err instanceof MulterError) {
          flash(
            req,
            res,
            'danger',
            err.code === 'LIMIT_FILE_SIZE'
              ? `The logo must be ${Math.round(LOGO_MAX_BYTES / 1024)} KB or smaller.`
              : 'Upload one PNG file.',
          );
          return res.redirect(303, base(res));
        }
        next(err);
      });
    },
    verifyCsrf,
    handle(async (req, res) => {
      try {
        // No flags: a replaced logo keeps its flags, a new one gets the column defaults.
        const { created } = await upsertLogo(tctxOf(res), req.file?.buffer, {});
        flash(req, res, 'success', created ? 'Logo uploaded.' : 'Logo replaced.');
      } catch (err) {
        const failure = formFailure(err);
        if (!failure) throw err;
        flash(req, res, 'danger', failure.fields.logo ?? failure.form ?? 'Upload a PNG image.');
      }
      res.redirect(303, base(res));
    }),
  );

  router.post(
    '/header-footer/logo/delete',
    verifyCsrf,
    handle(async (req, res) => {
      const logo = await fetchOne('md_header_footer', { line_type: 'L' }, tctxOf(res), undefined, {
        columns: ['id'],
      });
      if (!logo) throw ApiError.notFound('No logo');
      await lines.remove(tctxOf(res), Number(logo.id));
      flash(req, res, 'success', 'Logo removed.');
      res.redirect(303, base(res));
    }),
  );

  return router;
}

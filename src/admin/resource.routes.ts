/**
 * Generic list/form pages for a Resource:
 *   GET  /<key>            list        GET  /<key>/new        create form
 *   POST /<key>            create      GET  /<key>/:rid/edit  edit form
 *   POST /<key>/:rid       update      POST /<key>/:rid/delete soft delete
 * Customer tabs (tariff, shifts, roster, receipt lines, subscriptions, payments) are mounted under
 * /admin/customers/:cid after loadCustomer; platform masters (GST, ID proofs) at /admin/<key>.
 */
import { Router, type Request, type Response } from 'express';
import type { RequestContext } from '../core/context';
import { crudRequestSchemas } from '../core/crud/crudFactory';
import { crudService } from '../core/crud/crudService';
import type { Where } from '../core/db/dml';
import { ApiError } from '../core/http/ApiError';
import { ctxOf } from '../core/middleware/requireRole';
import { toIstDate } from '../core/utils/time';
import { verifyCsrf } from './csrf';
import { cellOf, inputValues, type Field, type Lookups } from './fields';
import { flash } from './flash';
import { formFailure, formValues, runForm, withoutEmpty, type FormFailure } from './forms';
import { idParam, listQuery, pager, PAGE_SIZE } from './listing';
import { loadLookups, optionsOf } from './lookups';
import { handle, page } from './render';
import { GLOBAL_RESOURCES, RESOURCES, type Resource } from './resources';

interface Scope {
  ctx: RequestContext;
  parentId?: number;
  cid: number;
}

/** Header/footer tab lists text lines only; the logo row has its own card. */
const LIST_FILTERS: Record<string, Where> = {
  'header-footer': { line_type: { in: ['H', 'F'] } },
};

/** Fields as the form view needs them: select options resolved, create-only hidden on edit. */
export function formFields(fields: readonly Field[], lookups: Lookups, mode: 'create' | 'edit') {
  return fields
    .filter((f) => mode === 'create' || !f.createOnly)
    .map((f) => ({
      ...f,
      options: f.options ?? (f.optionsFrom ? optionsOf(lookups[f.optionsFrom]) : []),
    }));
}

interface Mount {
  /** Where the list lives (also the create action). */
  listPath: (res: Response) => string;
  scope: (req: Request, res: Response) => Scope;
  lookups: (req: Request, res: Response, s: Scope) => Promise<Lookups>;
  /** Sidebar entry and customer tab to highlight. */
  active: string;
  tab?: string;
}

function mountResource(router: Router, r: Resource, m: Mount) {
  const service = crudService(r.crud);
  const { createSchema } = crudRequestSchemas(r.crud);
  const common = (res: Response) => ({
    active: m.active,
    tab: m.tab,
    r,
    listBase: m.listPath(res),
  });

  const renderForm = async (
    req: Request,
    res: Response,
    mode: 'create' | 'edit',
    values: Record<string, string>,
    failure: FormFailure | null,
    rid?: number,
  ) => {
    const s = m.scope(req, res);
    const lookups = await m.lookups(req, res, s);
    const listPath = m.listPath(res);
    page(
      res,
      'admin/customers/resource-form',
      {
        ...common(res),
        title: `${mode === 'create' ? 'Add' : 'Edit'} ${r.singular}`,
        mode,
        fields: formFields(r.fields, lookups, mode),
        values,
        errors: failure?.fields ?? {},
        formError: failure?.form ?? null,
        action: mode === 'create' ? listPath : `${listPath}/${rid}`,
        cancel: listPath,
        deleteAction: mode === 'edit' ? `${listPath}/${rid}/delete` : null,
      },
      failure ? 400 : 200,
    );
  };

  router.get(
    `/${r.key}`,
    handle(async (req, res) => {
      const s = m.scope(req, res);
      const q = listQuery.parse(req.query);
      const searchable = (r.crud.searchable ?? []).length > 0;
      const limit = r.limit ?? PAGE_SIZE;
      const { rows, total } = await service.list(s.ctx, {
        parentId: s.parentId,
        filters: LIST_FILTERS[r.key],
        page: q.page,
        limit,
        search: searchable ? q.q : undefined,
      });
      const lookups = await m.lookups(req, res, s);
      const today = toIstDate();
      page(res, 'admin/customers/resource-list', {
        ...common(res),
        title: r.title,
        searchable,
        q: q.q ?? '',
        rows: rows.map((row) => ({
          id: row[r.crud.pk],
          cells: r.columns.map((c) => cellOf(c, row, lookups)),
          badges: r.badges?.(row, rows, today) ?? [],
          hint: r.hint?.(row) ?? '',
        })),
        pager: pager(m.listPath(res), req.query, q.page, limit, total),
      });
    }),
  );

  router.get(
    `/${r.key}/new`,
    handle((req, res) => renderForm(req, res, 'create', r.defaults?.() ?? {}, null)),
  );

  router.post(
    `/${r.key}`,
    verifyCsrf,
    handle(async (req, res) => {
      const s = m.scope(req, res);
      const values = formValues(req.body);
      const outcome = await runForm(createSchema, withoutEmpty(values), (data) =>
        service.create(s.ctx, data, s.parentId),
      );
      if (!outcome.ok) return renderForm(req, res, 'create', values, outcome.failure);
      flash(req, res, 'success', `${capitalise(r.singular)} added.`);
      res.redirect(303, m.listPath(res));
    }),
  );

  router.get(
    `/${r.key}/:rid/edit`,
    handle(async (req, res) => {
      const s = m.scope(req, res);
      const rid = idParam(req.params.rid);
      if (rid === null) throw ApiError.notFound();
      const row = await service.get(s.ctx, rid, s.parentId);
      await renderForm(req, res, 'edit', inputValues(r.fields, row), null, rid);
    }),
  );

  router.post(
    `/${r.key}/:rid`,
    verifyCsrf,
    handle(async (req, res) => {
      const s = m.scope(req, res);
      const rid = idParam(req.params.rid);
      if (rid === null) throw ApiError.notFound();
      const values = formValues(req.body);
      const outcome = await runForm(r.crud.schema.update, withoutEmpty(values), (data) =>
        service.update(s.ctx, rid, data, s.parentId),
      );
      if (!outcome.ok) return renderForm(req, res, 'edit', values, outcome.failure, rid);
      flash(req, res, 'success', `${capitalise(r.singular)} updated.`);
      res.redirect(303, m.listPath(res));
    }),
  );

  router.post(
    `/${r.key}/:rid/delete`,
    verifyCsrf,
    handle(async (req, res) => {
      const s = m.scope(req, res);
      const rid = idParam(req.params.rid);
      if (rid === null) throw ApiError.notFound();
      try {
        await service.remove(s.ctx, rid, s.parentId);
        flash(req, res, 'success', `${capitalise(r.singular)} removed.`);
      } catch (err) {
        const failure = formFailure(err);
        if (!failure) throw err;
        flash(req, res, 'danger', failure.form ?? 'Could not remove it.');
      }
      res.redirect(303, m.listPath(res));
    }),
  );
}

/** Customer tabs; mount under /admin/customers/:cid after loadCustomer. */
export function resourceRoutes(): Router {
  const router = Router({ mergeParams: true });
  for (const r of RESOURCES) {
    mountResource(router, r, {
      active: 'customers',
      tab: r.key,
      listPath: (res) => `${String(res.locals.base)}/${r.key}`,
      scope: (req, res) => {
        const cid = Number(res.locals.cid);
        return r.scope === 'tenant'
          ? { ctx: res.locals.tctx as RequestContext, cid }
          : { ctx: ctxOf(req), parentId: cid, cid };
      },
      lookups: (req, res, s) =>
        loadLookups(r.lookups, res.locals.tctx as RequestContext, ctxOf(req), s.cid),
    });
  }
  return router;
}

/** Platform masters (GST rates, ID proofs); mount at /admin. */
export function globalResourceRoutes(): Router {
  const router = Router();
  for (const r of GLOBAL_RESOURCES) {
    mountResource(router, r, {
      active: r.key,
      listPath: () => `/admin/${r.key}`,
      scope: (req) => ({ ctx: ctxOf(req), cid: 0 }),
      lookups: async () => ({}),
    });
  }
  return router;
}

const capitalise = (s: string) => s[0]!.toUpperCase() + s.slice(1);

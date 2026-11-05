/** /admin/reports — the API reports for one chosen customer, with filters, totals and paging. */
import { Router } from 'express';
import { z } from 'zod';
import { ctxOf } from '../core/middleware/requireRole';
import { addDays, toIstDate } from '../core/utils/time';
import { bagsInStoreQuery, rangeQuery } from '../modules/reports/reports.schema';
import { zodFieldErrors } from './forms';
import { idParam, pager } from './listing';
import { customerOptions, loadLookups, optionsOf } from './lookups';
import { handle, page } from './render';
import { formatCell, formatTotal, REPORTS, reportByKey } from './reportsMeta';
import { tenantCtx } from './tenant';

const REPORT_PAGE_SIZE = 50;

const pickQuery = z.object({
  report: z.string().optional().catch(undefined),
  customer_id: z.string().optional().catch(undefined),
});

/** Query keys passed through to the report schema (strings; the schema coerces). */
const PASS = ['from', 'to', 'user_id', 'shift_id', 'pay_mode', 'page'] as const;

export function reportsRoutes(): Router {
  const router = Router();

  router.get(
    '/',
    handle(async (req, res) => {
      const ctx = ctxOf(req);
      const pick = pickQuery.parse(req.query);
      const def = reportByKey.get(pick.report ?? '') ?? REPORTS[0]!;
      const customers = await customerOptions(ctx);
      const cid = idParam(pick.customer_id);
      const customer = cid ? customers.find(([id]) => Number(id) === cid) : undefined;

      const today = toIstDate();
      const raw: Record<string, string> = {};
      for (const k of PASS) {
        const v = req.query[k];
        if (typeof v === 'string' && v.trim() !== '') raw[k] = v.trim();
      }
      if (def.query === 'range') {
        raw.from ??= addDays(today, -6);
        raw.to ??= today;
      }

      const base = {
        title: 'Reports',
        active: 'reports',
        reports: REPORTS,
        def,
        customers,
        cid: customer ? cid : null,
        values: raw,
        errors: {} as Record<string, string>,
        result: null as unknown,
        userOptions: [] as [string, string][],
        shiftOptions: [] as [string, string][],
      };
      if (!customer) return page(res, 'admin/reports', base);

      const tctx = tenantCtx(ctx, cid!);
      const lookups = await loadLookups(['users', 'shifts'], tctx, ctx, cid!);
      const schema = def.query === 'range' ? rangeQuery : bagsInStoreQuery;
      const parsed = schema.safeParse({ ...raw, customer_id: cid, limit: REPORT_PAGE_SIZE });
      const view = {
        ...base,
        userOptions: optionsOf(lookups.users),
        shiftOptions: optionsOf(lookups.shifts),
      };
      if (!parsed.success) {
        return page(res, 'admin/reports', { ...view, errors: zodFieldErrors(parsed.error) }, 400);
      }
      const { rows, meta } = await def.run(ctx, parsed.data as never);
      page(res, 'admin/reports', {
        ...view,
        result: {
          rows: rows.map((row) => def.columns.map((c) => formatCell(c, row))),
          totals: def.totals.map((t) => ({ ...t, text: formatTotal(t.kind, meta.totals[t.key]) })),
          pager: pager('/admin/reports', req.query, parsed.data.page, REPORT_PAGE_SIZE, meta.total),
        },
      });
    }),
  );

  return router;
}

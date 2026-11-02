import { Router, type Request, type Response } from 'express';
import type { ZodType } from 'zod';
import type { RequestContext } from '../../core/context';
import { asyncHandler } from '../../core/http/asyncHandler';
import { sendSuccess } from '../../core/http/response';
import { ctxOf } from '../../core/middleware/requireRole';
import { validate } from '../../core/middleware/validate';
import { adminOnly, reportGate } from './reports.gate';
import { bagsInStoreQuery, exceptionIdParams, exceptionsQuery, rangeQuery } from './reports.schema';
import * as reports from './reports.service';

/** Mount behind auth + `limiters.reports`. */
export const reportsRoutes = Router();

reportsRoutes.use(reportGate);

type Report = (ctx: RequestContext, q: never) => Promise<{ rows: unknown[]; meta: object }>;

function report(path: string, schema: ZodType, run: Report) {
  reportsRoutes.get(
    path,
    validate({ query: schema }),
    asyncHandler(async (req: Request, res: Response) => {
      const { rows, meta } = await run(ctxOf(req), req.query as never);
      sendSuccess(req, res, rows, { meta: { ...meta, filters: req.query } });
    }),
  );
}

report('/collection-summary', rangeQuery, reports.collectionSummary);
report('/check-in-register', rangeQuery, reports.checkInRegister);
report('/check-out-register', rangeQuery, reports.checkOutRegister);
report('/bags-in-store', bagsInStoreQuery, reports.bagsInStore);
report('/gst', rangeQuery, reports.gstReport);
report('/user-shift-collection', rangeQuery, reports.userShiftCollection);
report('/unprinted-bills', rangeQuery, reports.unprintedBills);
report('/exceptions', exceptionsQuery, reports.exceptionsReport);

reportsRoutes.patch(
  '/exceptions/:id/resolve',
  adminOnly,
  validate({ params: exceptionIdParams }),
  asyncHandler(async (req: Request, res: Response) => {
    const row = await reports.resolveException(ctxOf(req), Number(req.params.id));
    sendSuccess(req, res, row, { message: 'Exception resolved' });
  }),
);

import { Router, type Request, type Response } from 'express';
import { asyncHandler } from '../../core/http/asyncHandler';
import { sendSuccess } from '../../core/http/response';
import { ctxOf, requireRole } from '../../core/middleware/requireRole';
import { validate } from '../../core/middleware/validate';
import { luggageQuery, transIdParams, type LuggageQuery } from './luggage.schema';
import { getLuggage, searchLuggage } from './luggage.service';

/** Mount behind auth + general limiter. Customer users only (own tenant via the DML layer). */
export const luggageRoutes = Router();

luggageRoutes.use(requireRole('A', 'O'));

luggageRoutes.get(
  '/',
  validate({ query: luggageQuery }),
  asyncHandler(async (req: Request, res: Response) => {
    const { rows, meta } = await searchLuggage(ctxOf(req), req.query as unknown as LuggageQuery);
    sendSuccess(req, res, rows, { meta });
  }),
);

luggageRoutes.get(
  '/:trans_id',
  validate({ params: transIdParams }),
  asyncHandler(async (req: Request, res: Response) => {
    sendSuccess(req, res, await getLuggage(ctxOf(req), req.params.trans_id as string));
  }),
);

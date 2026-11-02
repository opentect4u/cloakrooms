import { Router, type Request, type Response } from 'express';
import { asyncHandler } from '../../core/http/asyncHandler';
import { sendSuccess } from '../../core/http/response';
import { authenticate } from '../../core/middleware/auth';
import { limiters } from '../../core/middleware/rateLimit';
import { ctxOf, requireRole } from '../../core/middleware/requireRole';
import { validate } from '../../core/middleware/validate';
import { pullQuery, pushBody, type PullQuery, type PushBody } from './sync.schema';
import * as service from './sync.service';

export const syncRoutes = Router();

// A force-logged-out session may still push its pending rows (CLAUDE.md §6).
syncRoutes.post(
  '/push',
  ...authenticate({ allowForced: true }),
  limiters.sync,
  requireRole('A', 'O'),
  validate({ body: pushBody }),
  asyncHandler(async (req: Request, res: Response) => {
    const data = await service.push(ctxOf(req), req.body as PushBody);
    const exceptions = data.results.filter((r) => r.status === 'EXCEPTION').length;
    sendSuccess(req, res, data, {
      message: exceptions ? `Synced with ${exceptions} exception(s)` : 'Synced',
    });
  }),
);

syncRoutes.get(
  '/pull',
  ...authenticate(),
  limiters.sync,
  requireRole('A', 'O'),
  validate({ query: pullQuery }),
  asyncHandler(async (req: Request, res: Response) => {
    sendSuccess(req, res, await service.pull(ctxOf(req), req.query as unknown as PullQuery));
  }),
);

import { Router, type Request, type Response } from 'express';
import { asyncHandler } from '../../core/http/asyncHandler';
import { sendSuccess } from '../../core/http/response';
import { authenticate } from '../../core/middleware/auth';
import { limiters } from '../../core/middleware/rateLimit';
import { ctxOf, requireRole } from '../../core/middleware/requireRole';
import { validate } from '../../core/middleware/validate';
import { createVersionBody, versionQuery, type CreateVersionInput } from './app-version.schema';
import { createVersion, latestVersion } from './app-version.service';

export const appVersionRoutes = Router();

/** Public: the app checks for updates before anyone logs in. */
appVersionRoutes.get(
  '/version',
  validate({ query: versionQuery }),
  asyncHandler(async (req: Request, res: Response) => {
    const { current } = req.query as { current?: number };
    sendSuccess(req, res, await latestVersion(current));
  }),
);

appVersionRoutes.post(
  '/version',
  ...authenticate(),
  limiters.general,
  requireRole('S'),
  validate({ body: createVersionBody }),
  asyncHandler(async (req: Request, res: Response) => {
    const row = await createVersion(ctxOf(req), req.body as CreateVersionInput);
    sendSuccess(req, res, row, { status: 201, message: 'Version published' });
  }),
);

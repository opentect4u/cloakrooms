import { Router } from 'express';
import { asyncHandler } from '../../core/http/asyncHandler';
import { authenticate } from '../../core/middleware/auth';
import { limiters } from '../../core/middleware/rateLimit';
import { validate } from '../../core/middleware/validate';
import * as controller from './auth.controller';
import { changePasswordBody, loginBody, refreshBody } from './auth.schema';

export const authRoutes = Router();

authRoutes.post(
  '/login',
  limiters.login,
  validate({ body: loginBody }),
  asyncHandler(controller.login),
);
authRoutes.post(
  '/refresh',
  limiters.refresh,
  validate({ body: refreshBody }),
  asyncHandler(controller.refresh),
);
// A force-logged-out session may still log out (after its final push).
authRoutes.post(
  '/logout',
  authenticate({ allowForced: true, allowPasswordChange: true }),
  limiters.general,
  asyncHandler(controller.logout),
);
authRoutes.get(
  '/me',
  authenticate({ allowPasswordChange: true }),
  limiters.general,
  asyncHandler(controller.me),
);
authRoutes.post(
  '/change-password',
  authenticate({ allowPasswordChange: true }),
  limiters.password,
  validate({ body: changePasswordBody }),
  asyncHandler(controller.changePassword),
);

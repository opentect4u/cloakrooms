import { Router } from 'express';
import { asyncHandler } from '../../core/http/asyncHandler';
import { authenticate } from '../../core/middleware/auth';
import { limiters } from '../../core/middleware/rateLimit';
import { requireRole } from '../../core/middleware/requireRole';
import { validate } from '../../core/middleware/validate';
import * as c from './users.controller';
import {
  allowBody,
  createUserBody,
  idParams,
  listUsersQuery,
  resetPasswordBody,
  updateUserBody,
} from './users.schema';

export const usersRoutes = Router();

usersRoutes.use(authenticate(), limiters.general, requireRole('A', 'S'));

usersRoutes.get('/', validate({ query: listUsersQuery }), asyncHandler(c.list));
usersRoutes.get('/:id', validate({ params: idParams }), asyncHandler(c.get));
usersRoutes.post('/', validate({ body: createUserBody }), asyncHandler(c.create));
usersRoutes.patch(
  '/:id',
  validate({ params: idParams, body: updateUserBody }),
  asyncHandler(c.update),
);
usersRoutes.delete('/:id', validate({ params: idParams }), asyncHandler(c.remove));
usersRoutes.post('/:id/force-logout', validate({ params: idParams }), asyncHandler(c.forceLogout));
usersRoutes.post(
  '/:id/reset-password',
  validate({ params: idParams, body: resetPasswordBody }),
  asyncHandler(c.resetPassword),
);
usersRoutes.patch(
  '/:id/allow',
  validate({ params: idParams, body: allowBody }),
  asyncHandler(c.setAllow),
);

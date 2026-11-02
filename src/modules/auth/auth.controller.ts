import type { Request, Response } from 'express';
import { sendSuccess } from '../../core/http/response';
import { ctxOf } from '../../core/middleware/requireRole';
import type { LoginInput } from './auth.schema';
import * as authService from './auth.service';

export async function login(req: Request, res: Response) {
  const data = await authService.login(req.body as LoginInput);
  req.log?.info({ user_id: data.user.user_id, app_version: req.body.app_version }, 'login');
  sendSuccess(req, res, data, { message: 'Logged in' });
}

export async function refresh(req: Request, res: Response) {
  sendSuccess(req, res, await authService.refresh(req.body.refreshToken as string), {
    message: 'Token refreshed',
  });
}

export async function logout(req: Request, res: Response) {
  await authService.logout(ctxOf(req));
  sendSuccess(req, res, null, { message: 'Logged out' });
}

export async function me(req: Request, res: Response) {
  sendSuccess(req, res, await authService.me(ctxOf(req)));
}

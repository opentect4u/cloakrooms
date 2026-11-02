import type { Request, Response } from 'express';
import { sendSuccess } from '../../core/http/response';
import { ctxOf } from '../../core/middleware/requireRole';
import type { CreateUserInput, ListUsersQuery, UpdateUserInput } from './users.schema';
import * as service from './users.service';

const idOf = (req: Request) => Number(req.params.id);

export async function list(req: Request, res: Response) {
  const { rows, meta } = await service.listUsers(
    ctxOf(req),
    req.query as unknown as ListUsersQuery,
  );
  sendSuccess(req, res, rows, { meta });
}

export async function get(req: Request, res: Response) {
  sendSuccess(req, res, await service.getUser(ctxOf(req), idOf(req)));
}

export async function create(req: Request, res: Response) {
  const user = await service.createUser(ctxOf(req), req.body as CreateUserInput);
  sendSuccess(req, res, user, { status: 201, message: 'Created' });
}

export async function update(req: Request, res: Response) {
  const user = await service.updateUser(ctxOf(req), idOf(req), req.body as UpdateUserInput);
  sendSuccess(req, res, user, { message: 'Updated' });
}

export async function remove(req: Request, res: Response) {
  await service.deleteUser(ctxOf(req), idOf(req));
  sendSuccess(req, res, { id: idOf(req) }, { message: 'Deleted' });
}

export async function forceLogout(req: Request, res: Response) {
  sendSuccess(req, res, await service.forceLogout(ctxOf(req), idOf(req)), {
    message: 'User logged out',
  });
}

export async function resetPassword(req: Request, res: Response) {
  const data = await service.resetPassword(ctxOf(req), idOf(req), req.body.password as string);
  sendSuccess(req, res, data, { message: 'Password reset' });
}

export async function setAllow(req: Request, res: Response) {
  const user = await service.setAllow(ctxOf(req), idOf(req), req.body.allow_flag as 'Y' | 'N');
  sendSuccess(req, res, user, { message: 'Updated' });
}

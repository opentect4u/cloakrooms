import type { Request, Response } from 'express';

/** Standard response envelope (CLAUDE.md §8). `code` is present on errors only. */
export interface Envelope<T = unknown> {
  success: boolean;
  message: string;
  code?: string;
  data: T;
  meta?: Record<string, unknown>;
  errors?: unknown;
  requestId: string;
}

export function sendSuccess<T>(
  req: Request,
  res: Response,
  data: T,
  opts: { message?: string; status?: number; meta?: Record<string, unknown> } = {},
): Response {
  const body: Envelope<T> = {
    success: true,
    message: opts.message ?? 'OK',
    data,
    ...(opts.meta && { meta: opts.meta }),
    requestId: req.requestId,
  };
  return res.status(opts.status ?? 200).json(body);
}

export function sendError(
  req: Request,
  res: Response,
  status: number,
  code: string,
  message: string,
  errors?: unknown,
): Response {
  const body: Envelope<null> = {
    success: false,
    message,
    code,
    data: null,
    ...(errors !== undefined && { errors }),
    requestId: req.requestId,
  };
  return res.status(status).json(body);
}

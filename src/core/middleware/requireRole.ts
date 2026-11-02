import type { Request, RequestHandler } from 'express';
import type { RequestContext, UserType } from '../context';
import { ApiError } from '../http/ApiError';

/** Must run after the auth middleware. */
export function requireRole(...roles: UserType[]): RequestHandler {
  return (req, _res, next) => {
    if (!req.auth) return next(ApiError.unauthorized());
    if (!roles.includes(req.auth.userType)) return next(ApiError.forbidden('Insufficient role'));
    next();
  };
}

/** The verified caller; throws 401 if a route forgot the auth middleware. */
export function ctxOf(req: Request): RequestContext {
  if (!req.auth) throw ApiError.unauthorized();
  return req.auth;
}

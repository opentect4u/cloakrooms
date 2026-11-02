import type { RequestHandler } from 'express';
import { ApiError } from '../http/ApiError';
import { sha256, verifyAccessToken } from '../security/tokens';
import { sessionGuard } from './sessionGuard';

const BEARER = /^Bearer ([A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+)$/;

/** Verifies the access JWT (signature, expiry, issuer, audience) and stashes its claims. */
export const verifyJwt: RequestHandler = (req, _res, next) => {
  const match = BEARER.exec(req.get('authorization') ?? '');
  if (!match?.[1]) return next(ApiError.unauthorized('Missing bearer token'));
  try {
    req.token = { claims: verifyAccessToken(match[1]), hash: sha256(match[1]) };
    next();
  } catch (err) {
    next(err);
  }
};

/**
 * auth → sessionGuard. `allowForced` lets a force-logged-out session through; only
 * POST /sync/push and POST /auth/logout use it (CLAUDE.md §6).
 */
export function authenticate(opts: { allowForced?: boolean } = {}): RequestHandler[] {
  return [verifyJwt, sessionGuard(opts)];
}

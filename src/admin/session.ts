import type { NextFunction, Request, RequestHandler, Response } from 'express';
import { ApiError } from '../core/http/ApiError';
import { sessionGuard, type SessionGuardOptions } from '../core/middleware/sessionGuard';
import { sha256, verifyAccessToken } from '../core/security/tokens';
import { refresh } from '../modules/auth/auth.service';
import { COOKIES, setAuthCookies } from './cookies';

/**
 * Panel authentication: the same access/refresh JWTs and td_user_session row as the API, carried
 * in httpOnly cookies instead of a Bearer header. The API's sessionGuard does the session checks,
 * so force-logout, rotation, PASSWORD_CHANGE_REQUIRED and expiry behave exactly as for devices.
 * Only super admins (S) get a panel session (login refuses the rest).
 */

type Tokens = { accessToken: string; refreshToken: string };

/**
 * Single flight per refresh token: two requests arriving together with an expired access token
 * must not both rotate (the second would present a used refresh token → TOKEN_REUSED → session
 * revoked). Per worker; kept briefly so the losing request reuses the winner's tokens.
 */
const inFlight = new Map<string, Promise<Tokens>>();

function refreshOnce(refreshToken: string): Promise<Tokens> {
  const key = sha256(refreshToken);
  let pending = inFlight.get(key);
  if (!pending) {
    pending = refresh(refreshToken);
    inFlight.set(key, pending);
    const drop = () => setTimeout(() => inFlight.delete(key), 15_000).unref();
    pending.then(drop, drop);
  }
  return pending;
}

const isExpired = (err: unknown) => err instanceof ApiError && err.code === 'TOKEN_EXPIRED';

async function attachToken(req: Request, res: Response) {
  const access = req.cookies?.[COOKIES.access];
  const refreshToken = req.cookies?.[COOKIES.refresh];
  if (typeof access === 'string' && access) {
    try {
      req.token = { claims: verifyAccessToken(access), hash: sha256(access) };
      return;
    } catch (err) {
      if (!isExpired(err) || typeof refreshToken !== 'string') throw err;
    }
  }
  if (typeof refreshToken !== 'string' || !refreshToken) {
    throw ApiError.unauthorized('Please sign in');
  }
  // Access cookie expired (or gone): rotate silently with the refresh cookie.
  const tokens = await refreshOnce(refreshToken);
  setAuthCookies(req, res, tokens);
  req.token = { claims: verifyAccessToken(tokens.accessToken), hash: sha256(tokens.accessToken) };
}

export function panelAuth(opts: SessionGuardOptions = {}): RequestHandler {
  const guard = sessionGuard(opts);
  return (req: Request, res: Response, next: NextFunction) => {
    attachToken(req, res)
      .then(() =>
        guard(req, res, (err?: unknown) => {
          if (err) return next(err);
          if (req.auth?.userType !== 'S') return next(ApiError.forbidden('Super admins only'));
          res.locals.me = req.auth;
          next();
        }),
      )
      .catch(next);
  };
}

import type { CookieOptions, Request, Response } from 'express';
import { env } from '../config/env';
import { ttlSeconds } from '../core/security/tokens';
import { ADMIN_PREFIX } from './paths';

export const COOKIES = {
  access: 'crb_at',
  refresh: 'crb_rt',
  csrf: 'crb_csrf',
  flash: 'crb_flash',
} as const;

/** httpOnly, SameSite=Strict, limited to /admin; Secure whenever the request came over TLS. */
export function cookieOptions(req: Request, maxAgeSec?: number): CookieOptions {
  return {
    httpOnly: true,
    sameSite: 'strict',
    secure: req.secure,
    path: ADMIN_PREFIX,
    ...(maxAgeSec !== undefined && { maxAge: maxAgeSec * 1000 }),
  };
}

export function setAuthCookies(
  req: Request,
  res: Response,
  tokens: { accessToken: string; refreshToken: string },
) {
  res.cookie(
    COOKIES.access,
    tokens.accessToken,
    cookieOptions(req, ttlSeconds(env.JWT_ACCESS_TTL)),
  );
  res.cookie(
    COOKIES.refresh,
    tokens.refreshToken,
    cookieOptions(req, ttlSeconds(env.JWT_REFRESH_TTL)),
  );
}

export function clearAuthCookies(req: Request, res: Response) {
  const { maxAge: _maxAge, ...opts } = cookieOptions(req);
  res.clearCookie(COOKIES.access, opts);
  res.clearCookie(COOKIES.refresh, opts);
}

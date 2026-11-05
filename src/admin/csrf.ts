import { createHmac, randomBytes, timingSafeEqual } from 'node:crypto';
import type { Request, RequestHandler, Response } from 'express';
import { env } from '../config/env';
import { ApiError } from '../core/http/ApiError';
import { cookieOptions, COOKIES } from './cookies';

/**
 * CSRF protection for the panel's forms (on top of SameSite=Strict cookies): a random value in an
 * httpOnly cookie, and an HMAC of it in every form (`_csrf`). A cross-site page can neither read
 * the cookie nor compute the HMAC.
 */
const KEY = createHmac('sha256', env.JWT_ACCESS_SECRET).update('admin-panel-csrf').digest();

const tokenFor = (seed: string) => createHmac('sha256', KEY).update(seed).digest('base64url');

function seedOf(req: Request, res: Response): string {
  const existing = req.cookies?.[COOKIES.csrf];
  if (typeof existing === 'string' && /^[A-Za-z0-9_-]{43}$/.test(existing)) return existing;
  const seed = randomBytes(32).toString('base64url');
  res.cookie(COOKIES.csrf, seed, cookieOptions(req));
  req.cookies = { ...req.cookies, [COOKIES.csrf]: seed };
  return seed;
}

/** Makes `csrfToken` available to every view. */
export const issueCsrf: RequestHandler = (req, res, next) => {
  res.locals.csrfToken = tokenFor(seedOf(req, res));
  next();
};

/** Rejects a state-changing request without a valid token (403, see `isCsrfError`). */
export const verifyCsrf: RequestHandler = (req, _res, next) => {
  if (req.method === 'GET' || req.method === 'HEAD') return next();
  const seed = req.cookies?.[COOKIES.csrf];
  const sent = (req.body as Record<string, unknown> | undefined)?._csrf ?? req.get('x-csrf-token');
  if (typeof seed !== 'string' || typeof sent !== 'string') return next(csrfError());
  const expected = Buffer.from(tokenFor(seed));
  const given = Buffer.from(sent);
  if (expected.length !== given.length || !timingSafeEqual(expected, given)) {
    return next(csrfError());
  }
  next();
};

/** Panel-only failure: FORBIDDEN with a marker, so the API's error-code contract stays as is. */
const csrfError = () =>
  new ApiError(403, 'FORBIDDEN', 'This form has expired. Please try again.', { csrf: true });

export const isCsrfError = (err: unknown) =>
  err instanceof ApiError && (err.details as { csrf?: boolean } | undefined)?.csrf === true;

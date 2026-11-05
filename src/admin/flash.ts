import type { Request, RequestHandler, Response } from 'express';
import { cookieOptions, COOKIES } from './cookies';

export type FlashType = 'success' | 'danger' | 'warning' | 'info';
export interface Flash {
  type: FlashType;
  message: string;
}

/** One-shot message shown on the next page (post → redirect → get). */
export function flash(req: Request, res: Response, type: FlashType, message: string) {
  const value = Buffer.from(JSON.stringify({ type, message: message.slice(0, 300) })).toString(
    'base64url',
  );
  res.cookie(COOKIES.flash, value, cookieOptions(req, 60));
}

/**
 * Reads the flash cookie into `res.locals.flash`. It is cleared only when a page renders
 * (`clearFlash`), so a message survives a redirect chain (e.g. → /admin → /admin/login).
 */
export const readFlash: RequestHandler = (req, res, next) => {
  const raw = req.cookies?.[COOKIES.flash];
  res.locals.flash = null;
  if (typeof raw === 'string') {
    try {
      const parsed = JSON.parse(Buffer.from(raw, 'base64url').toString('utf8')) as Flash;
      if (parsed && typeof parsed.message === 'string') res.locals.flash = parsed;
    } catch {
      // ignore a tampered or truncated cookie
    }
  }
  next();
};

/** Called when a page is rendered: the message has been shown. */
export function clearFlash(req: Request, res: Response) {
  if (req.cookies?.[COOKIES.flash] === undefined) return;
  const { maxAge: _maxAge, ...opts } = cookieOptions(req);
  res.clearCookie(COOKIES.flash, opts);
}

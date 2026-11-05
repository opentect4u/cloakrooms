import type { RequestHandler } from 'express';
import { ApiError } from '../http/ApiError';
import { asyncHandler } from '../http/asyncHandler';
import { getSession } from '../security/sessionStore';

const ended = (message: string) => new ApiError(401, 'SESSION_ENDED', message);

export interface SessionGuardOptions {
  /** Let a force-logged-out (F) session through. */
  allowForced?: boolean;
  /** Let a user who must change the password through (change-password, logout, me). */
  allowPasswordChange?: boolean;
}

/**
 * Checks that the token's session is still the user's live session (status A — or F when
 * `allowForced`) and that this exact token is the session's current one (rotation invalidates the
 * previous access token). Identity and tenant come from the DB row, never from the request.
 */
export function sessionGuard(opts: SessionGuardOptions = {}): RequestHandler {
  return asyncHandler(async (req, _res, next) => {
    if (!req.token) throw ApiError.unauthorized();
    const { claims, hash } = req.token;

    let session = await getSession(claims.sid);
    // A stale cache entry (e.g. refreshed in another worker) must not reject a valid new token.
    if (session && session.token_hash !== hash)
      session = await getSession(claims.sid, { fresh: true });

    if (!session || String(session.user_id) !== claims.sub) throw ended('Session not found');
    if (session.token_hash !== hash) throw ended('Session was renewed or replaced; log in again');
    if (session.status === 'L') throw ended('Logged out');
    if (session.status === 'F' && !opts.allowForced) {
      throw ended('Logged out by admin; sync pending data and log out');
    }
    // Only the new (A) session is held back: an F session from before an admin reset must still
    // be able to push its pending data and log out.
    if (session.status === 'A' && session.must_change_pwd === 'Y' && !opts.allowPasswordChange) {
      throw new ApiError(
        403,
        'PASSWORD_CHANGE_REQUIRED',
        'Change your password first (POST /api/v1/auth/change-password)',
      );
    }

    req.auth = {
      userId: session.user_id,
      loginId: session.login_id,
      customerId: session.customer_id,
      userType: session.user_type,
      sessionId: session.session_id,
      deviceId: session.device_id,
    };
    next();
  });
}

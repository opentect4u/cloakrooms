import type { RequestContext } from '../core/context';
import type { AccessClaims } from '../core/security/tokens';

declare global {
  namespace Express {
    interface Request {
      /** Correlation id, set by the requestId middleware. */
      requestId: string;
      /** Verified access-token claims + SHA-256 of the raw token (verifyJwt). */
      token?: { claims: AccessClaims; hash: string };
      /** Set by sessionGuard once the JWT and session are verified. */
      auth?: RequestContext;
    }
  }
}

export {};

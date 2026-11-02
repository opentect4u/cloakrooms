import { createHash, randomUUID } from 'node:crypto';
import jwt, { type SignOptions, TokenExpiredError } from 'jsonwebtoken';
import { env } from '../../config/env';
import type { UserType } from '../context';
import { ApiError } from '../http/ApiError';

/** JWT (HS256) issue/verify and token hashing (CLAUDE.md §6). */

const ISSUER = 'cloak-room-billing';
const ACCESS_AUDIENCE = 'access';
const REFRESH_AUDIENCE = 'refresh';

export interface AccessClaims {
  /** md_user.id */
  sub: string;
  /** customer_id, null for S */
  cid: number | null;
  typ: UserType;
  /** td_user_session.session_id */
  sid: string;
}

export interface RefreshClaims {
  sub: string;
  sid: string;
}

/** Only hashes of tokens are stored (md_user.session_token, td_user_session.*_hash). */
export function sha256(value: string): string {
  return createHash('sha256').update(value).digest('hex');
}

const ttl = (v: string) => v as NonNullable<SignOptions['expiresIn']>;

const UNIT_SECONDS = { s: 1, m: 60, h: 3600, d: 86_400 } as const;

/** '12h' → 43200. Format is enforced by env validation. */
export function ttlSeconds(value: string): number {
  const m = /^(\d+)([smhd])$/.exec(value);
  if (!m) throw new Error(`Invalid TTL: ${value}`);
  return Number(m[1]) * UNIT_SECONDS[m[2] as keyof typeof UNIT_SECONDS];
}

export function signAccessToken(claims: AccessClaims): string {
  return jwt.sign({ cid: claims.cid, typ: claims.typ, sid: claims.sid }, env.JWT_ACCESS_SECRET, {
    algorithm: 'HS256',
    expiresIn: ttl(env.JWT_ACCESS_TTL),
    subject: claims.sub,
    issuer: ISSUER,
    audience: ACCESS_AUDIENCE,
    // Unique per issue, so a rotated token never equals (and hashes like) the previous one.
    jwtid: randomUUID(),
  });
}

export function signRefreshToken(claims: RefreshClaims): string {
  return jwt.sign({ sid: claims.sid }, env.JWT_REFRESH_SECRET, {
    algorithm: 'HS256',
    expiresIn: ttl(env.JWT_REFRESH_TTL),
    subject: claims.sub,
    issuer: ISSUER,
    audience: REFRESH_AUDIENCE,
    jwtid: randomUUID(),
  });
}

function verify(token: string, secret: string, audience: string): jwt.JwtPayload {
  try {
    const payload = jwt.verify(token, secret, {
      algorithms: ['HS256'],
      issuer: ISSUER,
      audience,
    });
    if (typeof payload === 'string' || !payload.sub || typeof payload.sid !== 'string') {
      throw new Error('malformed claims');
    }
    return payload;
  } catch (err) {
    if (err instanceof TokenExpiredError) {
      throw new ApiError(401, 'TOKEN_EXPIRED', 'Token expired');
    }
    throw ApiError.unauthorized('Invalid token');
  }
}

export function verifyAccessToken(token: string): AccessClaims {
  const p = verify(token, env.JWT_ACCESS_SECRET, ACCESS_AUDIENCE);
  return { sub: p.sub as string, cid: (p.cid as number | null) ?? null, typ: p.typ, sid: p.sid };
}

export function verifyRefreshToken(token: string): RefreshClaims {
  const p = verify(token, env.JWT_REFRESH_SECRET, REFRESH_AUDIENCE);
  return { sub: p.sub as string, sid: p.sid };
}

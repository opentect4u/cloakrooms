import type { Request } from 'express';
import { ipKeyGenerator, rateLimit, type Store } from 'express-rate-limit';
import { RedisStore } from 'rate-limit-redis';
import { RATE_LIMITS } from '../../config/constants';
import { env } from '../../config/env';
import { ApiError } from '../http/ApiError';
import { getRedis } from '../redis';

export interface RateLimiterOptions {
  /** Prefix that keeps counters of different limiters apart. */
  name: string;
  /** Max requests per window. */
  limit: number;
  windowMs?: number;
  keyGenerator?: (req: Request) => string;
}

export const ipKey = (req: Request): string => `ip:${ipKeyGenerator(req.ip ?? '')}`;

/**
 * User id when authenticated, IP otherwise (CLAUDE.md §10): many counters share one station NAT,
 * so per-user limiters must be mounted after the auth middleware.
 */
export const userOrIpKey = (req: Request): string =>
  req.auth ? `u:${req.auth.userId}` : ipKey(req);

/** Login: per IP + attempted user_id, so one station can't lock out its colleagues. */
export const loginKey = (req: Request): string => {
  const userId = typeof req.body?.user_id === 'string' ? req.body.user_id : '';
  return `${ipKey(req)}|${userId.trim().toLowerCase().slice(0, 100)}`;
};

function storeFor(name: string): Store | undefined {
  const redis = getRedis();
  if (!redis) return undefined;
  /* v8 ignore start -- needs a Redis server */
  return new RedisStore({
    prefix: `rl:${name}:`,
    sendCommand: (...args: string[]) => redis.sendCommand(args),
  });
  /* v8 ignore stop */
}

export function createRateLimiter(opts: RateLimiterOptions) {
  const keyOf = opts.keyGenerator ?? userOrIpKey;
  const store = storeFor(opts.name);
  return rateLimit({
    windowMs: opts.windowMs ?? RATE_LIMITS.windowMs,
    limit: opts.limit * env.RATE_LIMIT_MULTIPLIER,
    standardHeaders: 'draft-7',
    legacyHeaders: false,
    keyGenerator: (req) => keyOf(req),
    ...(store && { store }),
    handler: (_req, _res, next) => {
      next(new ApiError(429, 'RATE_LIMITED', 'Too many requests, please slow down'));
    },
  });
}

/**
 * Coarse per-IP flood guard for the whole app (runs before auth). The per-user limits below are
 * mounted on authenticated routers.
 */
export const floodLimiter = createRateLimiter({
  name: 'flood',
  limit: RATE_LIMITS.ipFlood,
  keyGenerator: ipKey,
});

export const limiters = {
  login: createRateLimiter({ name: 'login', limit: RATE_LIMITS.login, keyGenerator: loginKey }),
  // Unauthenticated, so per IP; generous because a station NAT carries many devices.
  refresh: createRateLimiter({ name: 'refresh', limit: RATE_LIMITS.refresh, keyGenerator: ipKey }),
  /** Change password: per user, as strict as login (guessing the current password). */
  password: createRateLimiter({ name: 'password', limit: RATE_LIMITS.login }),
  general: createRateLimiter({ name: 'general', limit: RATE_LIMITS.general }),
  sync: createRateLimiter({ name: 'sync', limit: RATE_LIMITS.sync }),
  reports: createRateLimiter({ name: 'reports', limit: RATE_LIMITS.reports }),
};

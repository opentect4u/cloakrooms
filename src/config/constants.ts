import { readFileSync } from 'node:fs';
import { join } from 'node:path';

// Same relative depth from src/config and dist/config.
export const APP_VERSION: string = JSON.parse(
  readFileSync(join(__dirname, '../../package.json'), 'utf8'),
).version;

export const API_PREFIX = '/api/v1';

export const BODY_LIMIT = '1mb';
export const SYNC_BODY_LIMIT = '5mb';

export const REQUEST_ID_HEADER = 'x-request-id';

export const PAGINATION = { defaultLimit: 50, maxLimit: 200 } as const;

/** Requests per minute (CLAUDE.md §10). */
export const RATE_LIMITS = {
  windowMs: 60_000,
  /** Whole-app per-IP guard before auth; high because a station NAT carries many users. */
  ipFlood: 3000,
  login: 10,
  refresh: 120,
  sync: 120,
  general: 300,
  reports: 30,
} as const;

export const HTTP_TIMEOUTS = {
  // Above a typical reverse-proxy keep-alive (60 s) so the proxy closes idle sockets first.
  keepAliveMs: 65_000,
  headersMs: 66_000,
  requestMs: 120_000,
} as const;

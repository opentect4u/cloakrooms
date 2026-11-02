import pino from 'pino';
import { env, isProd, isTest } from '../config/env';

const usePretty = !isProd && !isTest;

/**
 * Never logged (CLAUDE.md §10). Request logs carry only id/method/path (no query string, headers
 * or body), so these guard objects logged explicitly — e.g. `{ err }`, whose mysql2 `sql` holds the
 * statement with its bound values (passenger data).
 */
export const REDACT_PATHS = [
  'req.headers.authorization',
  'req.headers.cookie',
  'req.headers["x-report-password"]',
  'err.sql',
  'err.parameters',
  '*.password',
  '*.password_hash',
  '*.token',
  '*.accessToken',
  '*.refreshToken',
  '*.session_token',
  '*.token_hash',
  '*.refresh_hash',
  '*.id_number',
  '*.REPORT_PWD',
];

export const logger = pino({
  level: env.LOG_LEVEL,
  timestamp: pino.stdTimeFunctions.isoTime,
  redact: { paths: REDACT_PATHS, censor: '[REDACTED]' },
  ...(usePretty && {
    transport: {
      target: 'pino-pretty',
      options: { colorize: true, translateTime: 'SYS:standard' },
    },
  }),
});

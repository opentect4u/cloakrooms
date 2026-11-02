import type { ErrorRequestHandler } from 'express';
import { MulterError } from 'multer';
import { ZodError } from 'zod';
import { isProd } from '../../config/env';
import { ApiError } from '../http/ApiError';
import { sendError } from '../http/response';

/** Errors raised by express.json() (body-parser) carry a `type`. */
interface BodyParserError extends Error {
  type?: string;
  status?: number;
}

interface MysqlError extends Error {
  code?: string;
  errno?: number;
  sqlMessage?: string;
}

const DB_UNAVAILABLE = new Set([
  'ECONNREFUSED',
  'ECONNRESET',
  'ETIMEDOUT',
  'PROTOCOL_CONNECTION_LOST',
  'ER_CON_COUNT_ERROR',
  'ER_LOCK_DEADLOCK',
  'ER_LOCK_WAIT_TIMEOUT',
]);

const DB_BAD_VALUE = new Set([
  'ER_DATA_TOO_LONG',
  'ER_TRUNCATED_WRONG_VALUE',
  'ER_TRUNCATED_WRONG_VALUE_FOR_FIELD',
  'ER_WARN_DATA_OUT_OF_RANGE',
  'ER_BAD_NULL_ERROR',
  'ER_NO_DEFAULT_FOR_FIELD',
  'WARN_DATA_TRUNCATED',
]);

export function mapMysqlError(err: MysqlError): ApiError | null {
  const code = err.code;
  if (!code) return null;
  if (code === 'ER_DUP_ENTRY') {
    const key = /for key '([^']+)'/.exec(err.sqlMessage ?? '')?.[1];
    return ApiError.conflict('Duplicate record', key ? { key } : undefined);
  }
  if (code === 'ER_NO_REFERENCED_ROW_2' || code === 'ER_NO_REFERENCED_ROW') {
    return ApiError.badRequest('Referenced record does not exist');
  }
  if (code === 'ER_ROW_IS_REFERENCED_2' || code === 'ER_ROW_IS_REFERENCED') {
    return ApiError.conflict('Record is referenced by other records');
  }
  if (DB_BAD_VALUE.has(code)) {
    return ApiError.validation(isProd ? undefined : { db: err.sqlMessage }, 'Invalid value');
  }
  if (DB_UNAVAILABLE.has(code)) {
    return new ApiError(503, 'SERVICE_UNAVAILABLE', 'Database busy or unavailable, retry shortly');
  }
  return null;
}

function normalize(err: unknown): ApiError {
  if (err instanceof ApiError) return err;

  if (err instanceof ZodError) {
    return ApiError.validation(
      err.issues.map((i) => ({ path: i.path.join('.'), message: i.message })),
    );
  }

  if (err instanceof MulterError) {
    return err.code === 'LIMIT_FILE_SIZE'
      ? new ApiError(413, 'PAYLOAD_TOO_LARGE', 'Uploaded file too large')
      : ApiError.badRequest(`Upload rejected: ${err.message}`);
  }

  const bpErr = err as BodyParserError;
  if (bpErr?.type === 'entity.too.large') {
    return new ApiError(413, 'PAYLOAD_TOO_LARGE', 'Request body too large');
  }
  if (bpErr?.type === 'entity.parse.failed') {
    return new ApiError(400, 'INVALID_JSON', 'Malformed JSON body');
  }
  if (bpErr?.type && bpErr.status && bpErr.status < 500) {
    return ApiError.badRequest(bpErr.message);
  }

  const dbErr = err && typeof err === 'object' ? mapMysqlError(err as MysqlError) : null;
  if (dbErr) return dbErr;

  return new ApiError(500, 'INTERNAL_ERROR', 'Internal server error');
}

export const errorHandler: ErrorRequestHandler = (err, req, res, _next) => {
  const apiErr = normalize(err);

  if (apiErr.status >= 500) {
    req.log?.error({ err }, 'request failed');
  }

  // Never leak stack traces in production (CLAUDE.md §10).
  const details =
    apiErr.details ??
    (!isProd && apiErr.status === 500 && err instanceof Error
      ? { error: err.message, stack: err.stack }
      : undefined);

  if (res.headersSent) {
    res.end();
    return;
  }
  sendError(req, res, apiErr.status, apiErr.code, apiErr.message, details);
};

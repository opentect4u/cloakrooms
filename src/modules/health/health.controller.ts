import type { Request, Response } from 'express';
import { APP_VERSION } from '../../config/constants';
import { pingDb } from '../../core/db/pool';
import { isRegistryLoaded } from '../../core/db/registry';
import { ApiError } from '../../core/http/ApiError';
import { sendSuccess } from '../../core/http/response';
import { toUtcString } from '../../core/utils/time';

/** Liveness: the process is up. Never touches the DB. */
export function health(req: Request, res: Response) {
  sendSuccess(req, res, {
    status: 'ok',
    version: APP_VERSION,
    uptime_sec: Math.round(process.uptime()),
    server_time: toUtcString(),
  });
}

/** Readiness: DB reachable and identifier registry loaded. */
export async function ready(req: Request, res: Response) {
  try {
    await pingDb();
  } catch {
    throw new ApiError(503, 'SERVICE_UNAVAILABLE', 'Database unreachable');
  }
  if (!isRegistryLoaded()) {
    throw new ApiError(503, 'SERVICE_UNAVAILABLE', 'Schema registry not loaded');
  }
  sendSuccess(req, res, { status: 'ready', db: 'ok', server_time: toUtcString() });
}

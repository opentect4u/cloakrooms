import { randomUUID } from 'node:crypto';
import type { RequestHandler } from 'express';
import { REQUEST_ID_HEADER } from '../../config/constants';

const SAFE_ID = /^[A-Za-z0-9_-]{8,64}$/;

/** Reuses a well-formed incoming X-Request-Id (e.g. from Nginx), otherwise generates one. */
export const requestId: RequestHandler = (req, res, next) => {
  const incoming = req.get(REQUEST_ID_HEADER);
  req.requestId = incoming && SAFE_ID.test(incoming) ? incoming : randomUUID();
  res.setHeader(REQUEST_ID_HEADER, req.requestId);
  next();
};

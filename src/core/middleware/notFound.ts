import type { RequestHandler } from 'express';
import { ApiError } from '../http/ApiError';

export const notFound: RequestHandler = (req, _res, next) => {
  next(ApiError.notFound(`Route ${req.method} ${req.path} not found`));
};

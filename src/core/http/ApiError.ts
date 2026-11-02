/** Every `code` the API can return in an error envelope (also published in the OpenAPI spec). */
export const ERROR_CODES = [
  'VALIDATION_ERROR',
  'BAD_REQUEST',
  'INVALID_JSON',
  'UNAUTHORIZED',
  'TOKEN_EXPIRED',
  'TOKEN_REUSED',
  'SESSION_ENDED',
  'FORBIDDEN',
  'USER_LIMIT_REACHED',
  'REPORT_PASSWORD_REQUIRED',
  'NOT_FOUND',
  'CONFLICT',
  'SESSION_ACTIVE',
  'SUBSCRIPTION_EXPIRED',
  'PAYLOAD_TOO_LARGE',
  'RATE_LIMITED',
  'INTERNAL_ERROR',
  'SERVICE_UNAVAILABLE',
] as const;

export type ErrorCode = (typeof ERROR_CODES)[number];

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: ErrorCode,
    message: string,
    public readonly details?: unknown,
  ) {
    super(message);
    this.name = 'ApiError';
  }

  static badRequest(message = 'Bad request', details?: unknown) {
    return new ApiError(400, 'BAD_REQUEST', message, details);
  }
  static validation(details: unknown, message = 'Validation failed') {
    return new ApiError(400, 'VALIDATION_ERROR', message, details);
  }
  static unauthorized(message = 'Authentication required') {
    return new ApiError(401, 'UNAUTHORIZED', message);
  }
  static forbidden(message = 'Forbidden') {
    return new ApiError(403, 'FORBIDDEN', message);
  }
  static notFound(message = 'Resource not found') {
    return new ApiError(404, 'NOT_FOUND', message);
  }
  static conflict(message = 'Conflict', details?: unknown) {
    return new ApiError(409, 'CONFLICT', message, details);
  }
}

import type { RequestHandler } from 'express';
import type { ZodType } from 'zod';
import { ApiError } from '../http/ApiError';

export interface RequestSchemas {
  params?: ZodType;
  query?: ZodType;
  body?: ZodType;
}

const PARTS = ['params', 'query', 'body'] as const;

/**
 * Parses params/query/body with zod and replaces them with the parsed values (unknown keys are
 * stripped by z.object). All issues across parts are reported together as VALIDATION_ERROR.
 */
export function validate(schemas: RequestSchemas): RequestHandler {
  return (req, _res, next) => {
    const issues: { location: string; path: string; message: string }[] = [];
    for (const part of PARTS) {
      const schema = schemas[part];
      if (!schema) continue;
      const result = schema.safeParse(req[part] ?? {});
      if (result.success) {
        (req as unknown as Record<string, unknown>)[part] = result.data;
      } else {
        for (const i of result.error.issues) {
          issues.push({ location: part, path: i.path.join('.'), message: i.message });
        }
      }
    }
    next(issues.length ? ApiError.validation(issues) : undefined);
  };
}

/** Form handling for the panel: urlencoded body → validated data, errors → per-field messages. */
import { ZodError, type ZodType } from 'zod';
import { ApiError } from '../core/http/ApiError';
import { mapMysqlError } from '../core/middleware/errorHandler';

export type FieldErrors = Record<string, string>;
export type FormValues = Record<string, string>;

/**
 * Flattens a urlencoded body: drops `_csrf`, keeps strings only and takes the last value of a
 * repeated key (a hidden "N" followed by a checked "Y" checkbox posts both).
 */
export function formValues(body: unknown): FormValues {
  const out: FormValues = {};
  for (const [key, raw] of Object.entries((body ?? {}) as Record<string, unknown>)) {
    if (key === '_csrf') continue;
    const value = Array.isArray(raw) ? raw[raw.length - 1] : raw;
    if (typeof value === 'string') out[key] = value;
  }
  return out;
}

/** Empty inputs mean "not given" (optional fields), never an empty string. */
export function withoutEmpty(values: FormValues): FormValues {
  return Object.fromEntries(Object.entries(values).filter(([, v]) => v.trim() !== ''));
}

export function zodFieldErrors(error: ZodError): FieldErrors {
  const out: FieldErrors = {};
  for (const issue of error.issues) {
    const key = String(issue.path[0] ?? '_form');
    out[key] ??= friendly(issue.code, issue.message);
  }
  return out;
}

/** zod's technical wording → what a person filling a form needs to read. */
function friendly(code: string, message: string): string {
  if (code === 'invalid_type' && /received undefined/.test(message)) return 'Required';
  if (code === 'invalid_value' && /expected one of/i.test(message))
    return 'Choose one of the options';
  if (/expected number, received nan/i.test(message)) return 'Enter a number';
  const tooSmall = /expected number to be >=?(\d+)/.exec(message);
  if (tooSmall) return `Must be at least ${tooSmall[1]}`;
  const tooBig = /expected number to be <=?(\d+)/.exec(message);
  if (tooBig) return `Must be at most ${tooBig[1]}`;
  const tooLong = /expected string to have <=(\d+) characters/.exec(message);
  if (tooLong) return `At most ${tooLong[1]} characters`;
  if (/invalid email/i.test(message)) return 'Enter a valid email address';
  return sentence(message);
}

const sentence = (s: string) => (s ? s[0]!.toUpperCase() + s.slice(1) : s);

export interface FormFailure {
  fields: FieldErrors;
  form?: string;
}

/**
 * Turns a service error into something a form can show; null when it is not a user error (the
 * panel error handler then renders it).
 */
export function formFailure(err: unknown): FormFailure | null {
  if (err instanceof ZodError) return { fields: zodFieldErrors(err) };
  const apiErr = err instanceof ApiError ? err : mapMysqlError(err as Error);
  if (!apiErr || apiErr.status >= 500 || apiErr.status === 401) return null;
  if (apiErr.code === 'VALIDATION_ERROR' && Array.isArray(apiErr.details)) {
    const fields: FieldErrors = {};
    for (const d of apiErr.details as { path?: string; message?: string }[]) {
      const key = String(d.path ?? '_form') || '_form';
      fields[key] ??= sentence(String(d.message ?? 'Invalid value'));
    }
    return { fields, form: fields._form };
  }
  if (apiErr.status === 409 && apiErr.code === 'CONFLICT') {
    return { fields: {}, form: 'A record with these values already exists.' };
  }
  return { fields: {}, form: apiErr.message };
}

export type Outcome<T> = { ok: true; data: T } | { ok: false; failure: FormFailure };

/** Validates `values` with `schema`, then runs `action`; user errors come back as a failure. */
export async function runForm<S extends ZodType, T>(
  schema: S,
  values: Record<string, unknown>,
  action: (data: ReturnType<S['parse']>) => Promise<T>,
): Promise<Outcome<T>> {
  const parsed = schema.safeParse(values);
  if (!parsed.success) return { ok: false, failure: { fields: zodFieldErrors(parsed.error) } };
  try {
    return { ok: true, data: await action(parsed.data as ReturnType<S['parse']>) };
  } catch (err) {
    const failure = formFailure(err);
    if (!failure) throw err;
    return { ok: false, failure };
  }
}

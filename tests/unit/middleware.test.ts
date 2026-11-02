import type { NextFunction, Request, Response } from 'express';
import { describe, expect, it, vi } from 'vitest';
import { z } from 'zod';
import { serializeRow } from '../../src/core/crud/crudFactory';
import { ApiError } from '../../src/core/http/ApiError';
import { asyncHandler } from '../../src/core/http/asyncHandler';
import { mapMysqlError } from '../../src/core/middleware/errorHandler';
import { loginKey, userOrIpKey } from '../../src/core/middleware/rateLimit';
import { ctxOf, requireRole } from '../../src/core/middleware/requireRole';
import { validate } from '../../src/core/middleware/validate';
import { pageMeta, sortParam } from '../../src/core/utils/pagination';
import { toUtcString } from '../../src/core/utils/time';
import {
  dateOnly,
  idParams,
  money,
  pagination,
  timeOfDay,
  utcDateTime,
  uuid,
  ynEnum,
} from '../../src/core/validation/fragments';
import { adminOf, operatorOf } from '../helpers/context';

function run(mw: (req: Request, res: Response, next: NextFunction) => void, req: Partial<Request>) {
  const next = vi.fn();
  mw(req as Request, {} as Response, next);
  return next.mock.calls[0]?.[0] as unknown;
}

describe('validate', () => {
  const mw = validate({
    params: z.object({ id: z.coerce.number().int() }),
    body: z.object({ name: z.string().min(1) }),
  });

  it('replaces parts with parsed data and strips unknown keys', () => {
    const req = { params: { id: '5' }, body: { name: 'x', evil: 1 } } as unknown as Request;
    expect(run(mw, req)).toBeUndefined();
    expect(req.params).toEqual({ id: 5 });
    expect(req.body).toEqual({ name: 'x' });
  });

  it('collects issues from every part', () => {
    const err = run(mw, { params: { id: 'x' }, body: {} } as unknown as Request) as ApiError;
    expect(err.code).toBe('VALIDATION_ERROR');
    expect((err.details as { location: string }[]).map((d) => d.location)).toEqual([
      'params',
      'body',
    ]);
  });

  it('treats a missing part as an empty object', () => {
    const q = validate({ query: z.object({ a: z.string().optional() }) });
    expect(run(q, {} as Request)).toBeUndefined();
  });
});

describe('requireRole / ctxOf', () => {
  it('401 without auth, 403 for wrong role, passes otherwise', () => {
    const mw = requireRole('A', 'S');
    expect((run(mw, {}) as ApiError).status).toBe(401);
    expect((run(mw, { auth: operatorOf(1) }) as ApiError).status).toBe(403);
    expect(run(mw, { auth: adminOf(1) })).toBeUndefined();
  });

  it('ctxOf returns the context or throws 401', () => {
    expect(ctxOf({ auth: adminOf(2) } as Request).customerId).toBe(2);
    expect(() => ctxOf({} as Request)).toThrow(ApiError);
  });
});

describe('asyncHandler', () => {
  it('forwards rejections to next', async () => {
    const boom = new Error('boom');
    const next = vi.fn();
    asyncHandler(async () => {
      throw boom;
    })({} as Request, {} as Response, next);
    await new Promise((r) => setImmediate(r));
    expect(next).toHaveBeenCalledWith(boom);
  });
});

describe('mapMysqlError', () => {
  it.each([
    [
      { code: 'ER_DUP_ENTRY', sqlMessage: "Duplicate entry 'x' for key 'md_user.uq_user_login'" },
      409,
      'CONFLICT',
    ],
    [{ code: 'ER_NO_REFERENCED_ROW_2' }, 400, 'BAD_REQUEST'],
    [{ code: 'ER_ROW_IS_REFERENCED_2' }, 409, 'CONFLICT'],
    [{ code: 'ER_DATA_TOO_LONG', sqlMessage: 'Data too long' }, 400, 'VALIDATION_ERROR'],
    [{ code: 'ECONNREFUSED' }, 503, 'SERVICE_UNAVAILABLE'],
  ])('%o → %i', (err, status, code) => {
    const mapped = mapMysqlError(err as Error & { code: string });
    expect(mapped?.status).toBe(status);
    expect(mapped?.code).toBe(code);
  });

  it('reports the duplicate key name, ignores unknown codes', () => {
    expect(
      mapMysqlError({ code: 'ER_DUP_ENTRY', sqlMessage: "for key 'a.b'" } as never)?.details,
    ).toEqual({ key: 'a.b' });
    expect(mapMysqlError({ code: 'ER_DUP_ENTRY' } as never)?.details).toBeUndefined();
    expect(mapMysqlError({ code: 'SOMETHING_ELSE' } as never)).toBeNull();
    expect(mapMysqlError(new Error('x'))).toBeNull();
  });
});

describe('rate-limit keys', () => {
  it('keys by user when authenticated, IP otherwise', () => {
    expect(userOrIpKey({ auth: adminOf(3), ip: '1.2.3.4' } as Request)).toBe('u:103');
    expect(userOrIpKey({ ip: '1.2.3.4' } as Request)).toBe('ip:1.2.3.4');
  });

  it('login key combines IP and normalised user_id', () => {
    expect(loginKey({ ip: '1.2.3.4', body: { user_id: ' Op1 ' } } as Request)).toBe(
      'ip:1.2.3.4|op1',
    );
    expect(loginKey({ ip: '1.2.3.4', body: { user_id: 5 } } as Request)).toBe('ip:1.2.3.4|');
    expect(loginKey({ ip: undefined, body: undefined } as unknown as Request)).toBe('ip:|');
  });
});

describe('zod fragments', () => {
  it('ynEnum, uuid, idParams, timeOfDay', () => {
    expect(ynEnum.safeParse('Y').success).toBe(true);
    expect(ynEnum.safeParse('y').success).toBe(false);
    expect(uuid.parse('3F2504E0-4F89-41D3-9A0C-0305E82C3301')).toBe(
      '3f2504e0-4f89-41d3-9a0c-0305e82c3301',
    );
    expect(idParams.parse({ id: '12' })).toEqual({ id: 12 });
    expect(idParams.safeParse({ id: '0' }).success).toBe(false);
    expect(timeOfDay.safeParse('23:59').success).toBe(true);
    expect(timeOfDay.safeParse('24:00').success).toBe(false);
  });

  it('utcDateTime and dateOnly reject impossible dates', () => {
    expect(utcDateTime.safeParse('2026-02-28 23:59:59').success).toBe(true);
    expect(utcDateTime.safeParse('2026-02-30 10:00:00').success).toBe(false);
    expect(utcDateTime.safeParse('2026-02-28T10:00:00Z').success).toBe(false);
    expect(dateOnly.safeParse('2028-02-29').success).toBe(true);
    expect(dateOnly.safeParse('2026-13-01').success).toBe(false);
  });

  it('money keeps a 2-decimal string', () => {
    expect(money.parse(50)).toBe('50');
    expect(money.parse('20.50')).toBe('20.50');
    expect(money.safeParse('1.234').success).toBe(false);
    expect(money.safeParse(-1).success).toBe(false);
  });

  it('pagination defaults and bounds', () => {
    expect(pagination.parse({})).toEqual({ page: 1, limit: 50 });
    expect(pagination.safeParse({ limit: '201' }).success).toBe(false);
  });
});

describe('sortParam / pageMeta / time / serializeRow', () => {
  const sort = sortParam(['name', 'id']);

  it('parses a whitelisted multi-column sort', () => {
    expect(sort.parse('name:desc,id')).toEqual([
      { column: 'name', dir: 'desc' },
      { column: 'id', dir: 'asc' },
    ]);
    expect(sort.safeParse('password_hash:asc').success).toBe(false);
    expect(sort.safeParse('name:sideways').success).toBe(false);
  });

  it('pageMeta, toUtcString, serializeRow', () => {
    expect(pageMeta(2, 10, 35)).toEqual({ page: 2, limit: 10, total: 35 });
    expect(toUtcString(new Date('2026-04-01T05:06:07.890Z'))).toBe('2026-04-01 05:06:07');
    expect(serializeRow({ a: 1, img: Buffer.from('hi') })).toEqual({ a: 1, img: 'aGk=' });
  });
});

/**
 * OpenAPI 3.0 document — the contract with the Android developer (CLAUDE.md §12). Request schemas
 * are the same zod objects the routes validate with; tests/integration/openapi.test.ts fails when a
 * route is missing here (or documented but not mounted).
 */
import {
  extendZodWithOpenApi,
  OpenAPIRegistry,
  OpenApiGeneratorV3,
} from '@asteasolutions/zod-to-openapi';
import { z, type ZodObject, type ZodType } from 'zod';
import { API_PREFIX, APP_VERSION } from '../config/constants';
import { crudRequestSchemas, type CrudConfig } from '../core/crud/crudFactory';
import { createVersionBody, versionQuery } from '../modules/app-version/app-version.schema';
import { loginBody, refreshBody } from '../modules/auth/auth.schema';
import {
  customersCrud,
  paymentsCrud,
  registrationsCrud,
} from '../modules/customers/customers.routes';
import { gstCrud } from '../modules/gst/gst.routes';
import { headerFooterCrud } from '../modules/header-footer/header-footer.routes';
import { logoFields } from '../modules/header-footer/header-footer.schema';
import { idProofsCrud } from '../modules/id-proofs/id-proofs.routes';
import { luggageQuery, transIdParams } from '../modules/luggage/luggage.schema';
import { ratesCrud } from '../modules/rates/rates.routes';
import {
  bagsInStoreQuery,
  exceptionIdParams,
  exceptionsQuery,
  rangeQuery,
} from '../modules/reports/reports.schema';
import { settingsCrud } from '../modules/settings/settings.routes';
import { shiftAssignmentsCrud } from '../modules/shift-assignments/shift-assignments.routes';
import { shiftsCrud } from '../modules/shifts/shifts.routes';
import { luggageRow as luggagePushRow, pullQuery, receiptRow as receiptPushRow } from '../modules/sync/sync.schema';
import {
  allowBody,
  createUserBody,
  idParams,
  listUsersQuery,
  resetPasswordBody,
  updateUserBody,
} from '../modules/users/users.schema';
import { API_DESCRIPTION } from './openapi.description';
import * as S from './openapi.schemas';

extendZodWithOpenApi(z);

type Method = 'get' | 'post' | 'patch' | 'delete';

interface Op {
  method: Method;
  /** Express-style path below /api/v1 (or absolute when it starts with /downloads). */
  path: string;
  tag: string;
  summary: string;
  description?: string;
  roles?: string;
  public?: boolean;
  params?: ZodObject;
  query?: ZodObject;
  body?: ZodType;
  multipart?: ZodType;
  data?: ZodType;
  meta?: ZodType;
  status?: number;
  errors?: number[];
  rawResponse?: { contentType: string; description: string };
}

const ERROR_TEXT: Record<number, string> = {
  400: 'VALIDATION_ERROR / BAD_REQUEST / INVALID_JSON',
  401: 'UNAUTHORIZED / TOKEN_EXPIRED / SESSION_ENDED',
  403: 'FORBIDDEN (role, tenant or feature not allowed)',
  404: 'NOT_FOUND (or another tenant’s record)',
  409: 'CONFLICT (duplicate unique value)',
  413: 'PAYLOAD_TOO_LARGE',
  429: 'RATE_LIMITED',
};

export function buildOpenApiDocument() {
  const registry = new OpenAPIRegistry();
  const bearer = registry.registerComponent('securitySchemes', 'bearerAuth', {
    type: 'http',
    scheme: 'bearer',
    bearerFormat: 'JWT',
  });
  // Shared shapes are named in openapi.schemas.ts (`.openapi('Name')`) and emitted as $ref.
  const errorSchema = S.errorEnvelope;

  const op = (o: Op) => {
    const fullPath = (o.path.startsWith('/downloads') ? o.path : `${API_PREFIX}${o.path}`).replace(
      /:(\w+)/g,
      '{$1}',
    );
    const status = o.status ?? 200;
    const errors = o.errors ?? (o.public ? [400, 429] : [400, 401, 403, 429]);
    const hasParams = o.params && Object.keys(o.params.shape).length > 0;
    registry.registerPath({
      method: o.method,
      path: fullPath,
      tags: [o.tag],
      summary: o.summary,
      description: [o.description, o.roles && `**Roles:** ${o.roles}`].filter(Boolean).join('\n\n'),
      security: o.public ? [] : [{ [bearer.name]: [] }],
      request: {
        ...(hasParams && { params: o.params }),
        ...(o.query && { query: o.query }),
        ...(o.body && { body: { content: { 'application/json': { schema: o.body } } } }),
        ...(o.multipart && { body: { content: { 'multipart/form-data': { schema: o.multipart } } } }),
      },
      responses: {
        [status]: o.rawResponse
          ? {
              description: o.rawResponse.description,
              content: { [o.rawResponse.contentType]: { schema: z.string().openapi({ format: 'binary' }) } },
            }
          : {
              description: 'Success',
              content: { 'application/json': { schema: S.envelope(o.data ?? z.null(), o.meta) } },
            },
        ...Object.fromEntries(
          errors.map((code) => [
            code,
            { description: ERROR_TEXT[code] ?? 'Error', content: { 'application/json': { schema: errorSchema } } },
          ]),
        ),
      },
    });
  };

  /** list / get / create / update / delete of a crudFactory router. */
  const crud = (base: string, cfg: CrudConfig, row: ZodType, tag: string, roles: { read: string; write: string }) => {
    const s = crudRequestSchemas(cfg);
    const item = `${base}/:id`;
    const tenantNote =
      cfg.scope === 'tenant'
        ? 'Scoped to the caller’s customer. A super admin must send `customer_id` in the body (create) and may filter by it (list).'
        : undefined;
    op({ method: 'get', path: base, tag, summary: `List ${tag.toLowerCase()}`, roles: roles.read, params: s.collectionParams, query: s.listQuery, data: z.array(row), meta: S.pageMeta, description: tenantNote, errors: [400, 401, 403, 404, 429] });
    op({ method: 'get', path: item, tag, summary: `Get one`, roles: roles.read, params: s.itemParams, data: row, errors: [400, 401, 403, 404, 429] });
    op({ method: 'post', path: base, tag, summary: `Create`, roles: roles.write, params: s.collectionParams, body: s.createSchema, data: row, status: 201, description: tenantNote, errors: [400, 401, 403, 404, 409, 429] });
    op({ method: 'patch', path: item, tag, summary: `Update (partial)`, roles: roles.write, params: s.itemParams, body: s.updateSchema, data: row, errors: [400, 401, 403, 404, 409, 429] });
    op({ method: 'delete', path: item, tag, summary: `Soft delete (is_deleted = Y)`, roles: roles.write, params: s.itemParams, data: z.object({ [cfg.pk]: z.number().int() }), errors: [400, 401, 403, 404, 429] });
  };

  // ---------------------------------------------------------------- health
  op({ method: 'get', path: '/health', tag: 'Health', summary: 'Liveness (no DB)', public: true, data: S.healthData, errors: [] });
  op({ method: 'get', path: '/ready', tag: 'Health', summary: 'Readiness (DB reachable, schema loaded)', public: true, data: S.readyData, errors: [503] });

  // ---------------------------------------------------------------- auth
  op({ method: 'post', path: '/auth/login', tag: 'Auth', summary: 'Log in on a device', public: true, body: loginBody, data: S.loginData, errors: [400, 401, 403, 409, 429], description: 'One active session per user. Same `device_id` again replaces the old session; another device → 409 SESSION_ACTIVE. Rate limit: 10/min per IP + user_id.' });
  op({ method: 'post', path: '/auth/refresh', tag: 'Auth', summary: 'Rotate access + refresh tokens', public: true, body: refreshBody, data: S.refreshData, errors: [400, 401, 403, 429], description: 'Both tokens are replaced. Presenting an already-used refresh token revokes the session (401 TOKEN_REUSED).' });
  op({ method: 'post', path: '/auth/logout', tag: 'Auth', summary: 'Log out (allowed for force-logged-out sessions)', roles: 'any', errors: [401, 429] });
  op({ method: 'get', path: '/auth/me', tag: 'Auth', summary: 'Current user, customer and session', roles: 'any', data: S.meData, errors: [401, 429] });

  // ---------------------------------------------------------------- users
  const usersRoles = 'A (own customer), S';
  op({ method: 'get', path: '/users', tag: 'Users', summary: 'List users', roles: usersRoles, query: listUsersQuery, data: z.array(S.userRow), meta: S.pageMeta });
  op({ method: 'get', path: '/users/:id', tag: 'Users', summary: 'Get a user', roles: usersRoles, params: idParams, data: S.userRow, errors: [400, 401, 403, 404, 429] });
  op({ method: 'post', path: '/users', tag: 'Users', summary: 'Create a user', roles: usersRoles, body: createUserBody, data: S.userRow, status: 201, errors: [400, 401, 403, 409, 429], description: 'user_code is required for A/O users (unique per customer) and absent for S. Allowed users of a customer may not exceed user_limit (409 USER_LIMIT_REACHED).' });
  op({ method: 'patch', path: '/users/:id', tag: 'Users', summary: 'Update name / code / type', roles: usersRoles, params: idParams, body: updateUserBody, data: S.userRow, errors: [400, 401, 403, 404, 409, 429] });
  op({ method: 'delete', path: '/users/:id', tag: 'Users', summary: 'Soft delete (also force-logs out)', roles: usersRoles, params: idParams, data: z.object({ id: z.number().int() }), errors: [400, 401, 403, 404, 429] });
  op({ method: 'post', path: '/users/:id/force-logout', tag: 'Users', summary: 'Force logout (device may still push + log out)', roles: usersRoles, params: idParams, data: z.object({ sessions_ended: z.number().int() }), errors: [400, 401, 403, 404, 429] });
  op({ method: 'post', path: '/users/:id/reset-password', tag: 'Users', summary: 'Set a new password (force-logs out)', roles: usersRoles, params: idParams, body: resetPasswordBody, data: z.object({ sessions_ended: z.number().int() }), errors: [400, 401, 403, 404, 429] });
  op({ method: 'patch', path: '/users/:id/allow', tag: 'Users', summary: 'Block (N, force-logs out) or allow (Y)', roles: usersRoles, params: idParams, body: allowBody, data: S.userRow, errors: [400, 401, 403, 404, 409, 429] });

  // ---------------------------------------------------------------- masters
  const masterRoles = { read: 'A, S', write: 'A, S' };
  crud('/rates', ratesCrud, S.rateRow, 'Rates', masterRoles);
  crud('/shifts', shiftsCrud, S.shiftRow, 'Shifts', masterRoles);
  crud('/shift-assignments', shiftAssignmentsCrud, S.shiftAssignmentRow, 'Shift assignments', masterRoles);
  op({
    method: 'post',
    path: '/header-footer/logo',
    tag: 'Header & footer',
    summary: 'Upload / replace the logo (PNG ≤ 200 KB)',
    roles: 'A, S',
    multipart: logoFields.extend({ logo: z.string().openapi({ type: 'string', format: 'binary' }) }),
    data: S.headerFooterRow,
    status: 201,
    errors: [400, 401, 403, 413, 429],
    description: 'Multipart field `logo`. One logo row (line_type L) per customer: returns 201 when created, 200 when replaced. Bytes must be a PNG.',
  });
  crud('/header-footer', headerFooterCrud, S.headerFooterRow, 'Header & footer', masterRoles);
  crud('/settings', settingsCrud, S.settingRow, 'Settings', masterRoles);
  crud('/gst', gstCrud, S.gstRow, 'GST', { read: 'S, A, O', write: 'S' });
  crud('/id-proofs', idProofsCrud, S.idProofRow, 'ID proofs', { read: 'S, A, O', write: 'S' });

  // ---------------------------------------------------------------- customers (S)
  const superOnly = { read: 'S', write: 'S' };
  op({ method: 'get', path: '/customers/:id/summary', tag: 'Customers', summary: 'Customer summary', roles: 'S', params: idParams, data: S.customerSummary, errors: [400, 401, 403, 404, 429] });
  crud('/customers/:customerId/registrations', registrationsCrud, S.registrationRow, 'Registrations', superOnly);
  crud('/customers/:customerId/payments', paymentsCrud, S.paymentRow, 'Customer payments', superOnly);
  crud('/customers', customersCrud, S.customerRow, 'Customers', superOnly);

  // ---------------------------------------------------------------- sync
  op({
    method: 'post',
    path: '/sync/push',
    tag: 'Sync',
    summary: 'Push unsynced luggage rows and bills',
    roles: 'A, O (also a force-logged-out session)',
    body: z.object({
      luggage: z.array(luggagePushRow).max(5000).optional(),
      receipts: z.array(receiptPushRow).max(5000).optional(),
    }),
    data: S.pushData,
    errors: [400, 401, 403, 413, 429],
    description: 'See “Sync push” above. Rows are validated one by one; invalid rows return status EXCEPTION, never a 400.',
  });
  op({ method: 'get', path: '/sync/pull', tag: 'Sync', summary: 'Pull masters, settings, counters and luggage changes', roles: 'A, O', query: pullQuery, data: S.pullData, description: 'See “Sync pull” above.' });

  // ---------------------------------------------------------------- luggage lookup
  op({ method: 'get', path: '/luggage', tag: 'Luggage', summary: 'Search bags (PNR, mobile, name, receipt no)', roles: 'A, O', query: luggageQuery, data: z.array(S.luggageRow), meta: S.pageMeta });
  op({ method: 'get', path: '/luggage/:trans_id', tag: 'Luggage', summary: 'One bag with its bill', roles: 'A, O', params: transIdParams, data: S.luggageDetail, errors: [400, 401, 403, 404, 429] });

  // ---------------------------------------------------------------- reports
  const reportRoles = 'A, S (S must pass customer_id); O only when REPORT_FLAG = Y';
  const reportNote = '`from`/`to` are IST dates (inclusive, ≤ 366 days). When REPORT_PWD_FLAG = Y send the report password in `X-Report-Password` (else 403 REPORT_PASSWORD_REQUIRED). Rate limit 30/min.';
  const report = (path: string, summary: string, query: ZodObject, def: { row: ZodType; totals: ZodType }) =>
    op({ method: 'get', path: `/reports${path}`, tag: 'Reports', summary, roles: reportRoles, description: reportNote, query, data: z.array(def.row), meta: S.reportMeta(def.totals) });
  report('/collection-summary', 'Day-wise collection (cash / UPI)', rangeQuery, S.reports.collectionSummary);
  report('/check-in-register', 'Check-in register', rangeQuery, S.reports.checkIn);
  report('/check-out-register', 'Check-out register with bills', rangeQuery, S.reports.checkOut);
  report('/bags-in-store', 'Bags currently in store', bagsInStoreQuery, S.reports.bagsInStore);
  report('/gst', 'GST by day and rate', rangeQuery, S.reports.gst);
  report('/user-shift-collection', 'Collection per user and shift', rangeQuery, S.reports.userShift);
  report('/unprinted-bills', 'Bills checked out without print', rangeQuery, S.reports.unprinted);
  report('/exceptions', 'Sync exceptions', exceptionsQuery, S.reports.exceptions);
  op({ method: 'patch', path: '/reports/exceptions/:id/resolve', tag: 'Reports', summary: 'Mark a sync exception resolved', roles: 'A, S', params: exceptionIdParams, data: S.exceptionRow, errors: [400, 401, 403, 404, 429] });

  // ---------------------------------------------------------------- app
  op({ method: 'get', path: '/app/version', tag: 'App', summary: 'Latest app release / update check', public: true, query: versionQuery, data: S.appVersionData, description: 'Send `current=<versionCode>` to get update_available and mandatory.' });
  op({ method: 'post', path: '/app/version', tag: 'App', summary: 'Publish a release', roles: 'S', body: createVersionBody, data: S.appVersionRow, status: 201, errors: [400, 401, 403, 409, 429] });
  op({
    method: 'get',
    path: '/downloads/:file',
    tag: 'App',
    summary: 'Download an APK',
    public: true,
    params: z.object({ file: z.string().regex(/^[\w.-]+\.apk$/) }),
    errors: [],
    rawResponse: { contentType: 'application/vnd.android.package-archive', description: 'APK file (cached 1 day, ETag)' },
  });

  return new OpenApiGeneratorV3(registry.definitions).generateDocument({
    openapi: '3.0.3',
    info: { title: 'Cloak Room Billing API', version: APP_VERSION, description: API_DESCRIPTION },
    servers: [{ url: '/', description: 'This server' }],
  });
}

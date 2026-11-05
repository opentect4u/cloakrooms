/**
 * /admin/exceptions — rows the server flagged during sync. Without a customer: open/total per
 * customer across the platform; with one: the exceptions report (reports.service) + resolve.
 */
import { Router } from 'express';
import type { RowDataPacket } from 'mysql2/promise';
import { z } from 'zod';
import { raw } from '../core/db/dml';
import { ApiError } from '../core/http/ApiError';
import { ctxOf } from '../core/middleware/requireRole';
import { exceptionsQuery } from '../modules/reports/reports.schema';
import { exceptionsReport, resolveException } from '../modules/reports/reports.service';
import { verifyCsrf } from './csrf';
import { flash } from './flash';
import { formValues, zodFieldErrors } from './forms';
import { idParam, pager, PAGE_SIZE } from './listing';
import { customerOptions } from './lookups';
import { handle, page, safeNext } from './render';
import { istTime } from './reportsMeta';
import { tenantCtx } from './tenant';

/** Plain-language meaning of the exception codes (sync.results). */
export const EXCEPTION_HELP: Record<string, string> = {
  VALIDATION_ERROR: 'The device sent a row the server could not accept.',
  INVALID_REFERENCE: 'The row points to a user, shift or ID proof the server does not know.',
  TENANT_MISMATCH: 'The row belongs to another customer.',
  DUPLICATE_RECEIPT_NO: 'Another check-in already uses this receipt number on that day.',
  DUPLICATE_CHECKOUT: 'The bag was checked out twice with different details.',
  BILL_WITHOUT_LUGGAGE: 'A bill arrived for a bag the server has never seen.',
  DUPLICATE_BILL: 'A second, different bill arrived for the same bag.',
  DUPLICATE_BILL_NO: 'Another bill already uses this bill number on that day.',
  CHECKOUT_MISSING: 'A bill arrived but the bag is not marked as checked out.',
  NO_RATE: 'No tariff was in force on the check-in date.',
  AMOUNT_MISMATCH: 'The device’s bill differs from the server’s calculation. The bill was kept.',
  DB_ERROR: 'The server could not store the row.',
};

const pickQuery = z.object({
  customer_id: z.string().optional().catch(undefined),
  resolved: z.enum(['Y', 'N']).optional().catch(undefined),
  table_name: z.enum(['td_luggage_in_out', 'td_receipt']).optional().catch(undefined),
  page: z.coerce.number().int().min(1).catch(1),
  from: z.string().optional().catch(undefined),
  to: z.string().optional().catch(undefined),
});

export function exceptionsRoutes(): Router {
  const router = Router();

  router.get(
    '/',
    handle(async (req, res) => {
      const ctx = ctxOf(req);
      const q = pickQuery.parse(req.query);
      const cid = idParam(q.customer_id);
      const customers = await customerOptions(ctx);
      const customer = cid ? customers.find(([id]) => Number(id) === cid) : undefined;

      if (!customer) {
        const summary = await raw<RowDataPacket[]>(
          `SELECT e.customer_id, c.customer_name,
                  CAST(COALESCE(SUM(e.resolved_flag = 'N'), 0) AS UNSIGNED) AS open,
                  COUNT(*) AS total, MAX(e.created_at) AS latest
             FROM td_sync_exception e JOIN md_customer c ON c.customer_id = e.customer_id
            WHERE c.is_deleted = 'N'
            GROUP BY e.customer_id, c.customer_name
            ORDER BY open DESC, latest DESC
            LIMIT 200`,
        );
        return page(res, 'admin/exceptions', {
          title: 'Sync exceptions',
          active: 'exceptions',
          customers,
          cid: null,
          q,
          summary,
          help: EXCEPTION_HELP,
        });
      }

      const resolved = req.query.resolved === undefined ? 'N' : q.resolved;
      const parsed = exceptionsQuery.safeParse({
        customer_id: cid,
        page: q.page,
        limit: PAGE_SIZE,
        resolved,
        table_name: q.table_name,
        from: q.from || undefined,
        to: q.to || undefined,
      });
      const errors = parsed.success ? {} : zodFieldErrors(parsed.error);
      const result = parsed.success ? await exceptionsReport(ctx, parsed.data) : null;
      page(
        res,
        'admin/exceptions',
        {
          title: 'Sync exceptions',
          active: 'exceptions',
          customers,
          cid,
          customerName: customer[1],
          q: { ...q, resolved: resolved ?? '' },
          errors,
          help: EXCEPTION_HELP,
          totals: result?.meta.totals ?? {},
          rows: (result?.rows ?? []).map((r) => {
            const reason = String(r.reason ?? '');
            const code = /^([A-Z_]+):?\s*(.*)$/.exec(reason);
            return {
              ...r,
              code: code?.[1] ?? 'EXCEPTION',
              detail: code?.[2] ?? reason,
              when: r.created_at_ist ? istTime(String(r.created_at_ist)) : '',
              payloadText: prettyPayload(r.payload),
            };
          }),
          pager: result
            ? pager('/admin/exceptions', req.query, q.page, PAGE_SIZE, result.meta.total)
            : null,
          back: req.originalUrl,
        },
        parsed.success ? 200 : 400,
      );
    }),
  );

  router.post(
    '/:id/resolve',
    verifyCsrf,
    handle(async (req, res) => {
      const id = idParam(req.params.id);
      const v = formValues(req.body);
      const cid = idParam(v.customer_id);
      if (id === null || cid === null) throw ApiError.notFound('Exception not found');
      // Tenant context: an id of another customer is a 404, not a cross-tenant write.
      await resolveException(tenantCtx(ctxOf(req), cid), id);
      flash(req, res, 'success', 'Marked as reviewed.');
      res.redirect(303, safeNext(v.back, `/admin/exceptions?customer_id=${cid}`));
    }),
  );

  return router;
}

function prettyPayload(payload: unknown): string {
  if (payload === null || payload === undefined) return '';
  if (typeof payload === 'string') {
    try {
      return JSON.stringify(JSON.parse(payload), null, 2);
    } catch {
      return payload;
    }
  }
  return JSON.stringify(payload, null, 2);
}

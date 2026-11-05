/**
 * /admin/admins — Synergic super admins (user_type S), via users.service with the super admin
 * context. Only S rows are reachable here; your own account cannot be blocked, deleted or signed
 * out from this page (change your own password from the user menu).
 */
import { Router, type Request, type Response } from 'express';
import { z } from 'zod';
import type { RequestContext } from '../core/context';
import { ApiError } from '../core/http/ApiError';
import { ctxOf } from '../core/middleware/requireRole';
import { maskDevice } from '../modules/auth/auth.service';
import { allowBody, password, resetPasswordBody } from '../modules/users/users.schema';
import {
  createUser,
  deleteUser,
  getUser,
  listUsers,
  resetPassword,
  setAllow,
  updateUser,
} from '../modules/users/users.service';
import { verifyCsrf } from './csrf';
import type { Field } from './fields';
import { flash } from './flash';
import { formFailure, formValues, runForm, withoutEmpty, type FormFailure } from './forms';
import { idParam, listQuery, pager, PAGE_SIZE } from './listing';
import { handle, page } from './render';

const createAdminBody = z.object({
  user_name: z.string().trim().min(1).max(100),
  user_id: z
    .string()
    .trim()
    .min(3)
    .max(100)
    .regex(/^[A-Za-z0-9._@-]+$/, 'letters, digits and . _ @ - only'),
  password,
});
const renameBody = z.object({ user_name: z.string().trim().min(1).max(100) });

const NAME: Field = {
  name: 'user_name',
  label: 'Full name',
  type: 'text',
  required: true,
  maxlength: 100,
  col: 'col-12',
  icon: 'person',
};
const CREATE_FIELDS: Field[] = [
  NAME,
  {
    name: 'user_id',
    label: 'Login id',
    type: 'text',
    required: true,
    maxlength: 100,
    col: 'col-sm-6',
    icon: 'at',
    autocomplete: 'off',
    placeholder: 'priya.synergic',
  },
  {
    name: 'password',
    label: 'Temporary password',
    type: 'password',
    required: true,
    col: 'col-sm-6',
    autocomplete: 'new-password',
    help: 'They choose their own at first sign-in.',
  },
];
const PASSWORD_FIELDS: Field[] = [
  {
    name: 'password',
    label: 'New temporary password',
    type: 'password',
    required: true,
    col: 'col-12',
    autocomplete: 'new-password',
    help: 'At least 8 characters with a letter and a digit. Their session ends now.',
  },
];

export function adminsRoutes(): Router {
  const router = Router();

  router.get(
    '/',
    handle(async (req, res) => {
      const ctx = ctxOf(req);
      const q = listQuery.parse(req.query);
      const { rows, meta } = await listUsers(ctx, {
        page: q.page,
        limit: PAGE_SIZE,
        search: q.q,
        user_type: 'S',
        sort: [{ column: 'user_name', dir: 'asc' }],
      });
      page(res, 'admin/admins', {
        title: 'Super admins',
        active: 'admins',
        q: q.q ?? '',
        selfId: ctx.userId,
        rows: rows.map((u) => ({
          ...u,
          online: Boolean(u.session_started_at),
          device: u.device_id ? maskDevice(String(u.device_id)) : '',
          onPanel: u.device_id === 'admin-panel',
        })),
        pager: pager('/admin/admins', req.query, q.page, PAGE_SIZE, meta.total),
      });
    }),
  );

  const form = (
    res: Response,
    o: {
      heading: string;
      sub?: string;
      fields: Field[];
      action: string;
      submitLabel: string;
      tip?: string;
    },
    values: Record<string, string>,
    failure: FormFailure | null,
  ) =>
    page(
      res,
      'admin/simple-form',
      {
        title: o.heading,
        active: 'admins',
        backLabel: 'Super admins',
        cancel: '/admin/admins',
        values,
        errors: failure?.fields ?? {},
        formError: failure?.form ?? null,
        ...o,
      },
      failure ? 400 : 200,
    );

  const NEW = {
    heading: 'Add a super admin',
    sub: 'Super admins see and manage every station on the platform.',
    fields: CREATE_FIELDS,
    action: '/admin/admins',
    submitLabel: 'Create super admin',
    tip: 'Give each person their own account: one account can be signed in on one device at a time (panel or app).',
  };

  router.get('/new', (_req, res) => form(res, NEW, {}, null));

  router.post(
    '/',
    verifyCsrf,
    handle(async (req, res) => {
      const values = formValues(req.body);
      const outcome = await runForm(createAdminBody, withoutEmpty(values), (data) =>
        createUser(ctxOf(req), { ...data, user_type: 'S', allow_flag: 'Y' }),
      );
      if (!outcome.ok) return form(res, NEW, values, outcome.failure);
      flash(
        req,
        res,
        'success',
        `${String(outcome.data.user_name)} can now sign in as “${String(outcome.data.user_id)}”.`,
      );
      res.redirect(303, '/admin/admins');
    }),
  );

  /** A super admin other than the caller (or the caller when `allowSelf`), else 404/400. */
  async function target(req: Request, allowSelf = false) {
    const ctx = ctxOf(req);
    const id = idParam(req.params.id);
    if (id === null) throw ApiError.notFound('User not found');
    const user = await getUser(ctx, id);
    if (user.user_type !== 'S') throw ApiError.notFound('User not found');
    if (!allowSelf && id === ctx.userId) {
      throw ApiError.badRequest('Use “Change password” in your own menu for your account');
    }
    return { ctx, user, id };
  }

  router.get(
    '/:id/edit',
    handle(async (req, res) => {
      const { user, id } = await target(req, true);
      form(
        res,
        {
          heading: `Edit ${String(user.user_name)}`,
          sub: `Login id: ${String(user.user_id)}`,
          fields: [NAME],
          action: `/admin/admins/${id}`,
          submitLabel: 'Save',
        },
        { user_name: String(user.user_name) },
        null,
      );
    }),
  );

  router.post(
    '/:id',
    verifyCsrf,
    handle(async (req, res) => {
      const { ctx, user, id } = await target(req, true);
      const values = formValues(req.body);
      const outcome = await runForm(renameBody, withoutEmpty(values), (data) =>
        updateUser(ctx, id, data),
      );
      if (!outcome.ok) {
        return form(
          res,
          {
            heading: `Edit ${String(user.user_name)}`,
            fields: [NAME],
            action: `/admin/admins/${id}`,
            submitLabel: 'Save',
          },
          values,
          outcome.failure,
        );
      }
      flash(req, res, 'success', 'Saved.');
      res.redirect(303, '/admin/admins');
    }),
  );

  const passwordForm = (
    res: Response,
    user: Record<string, unknown>,
    failure: FormFailure | null,
  ) =>
    form(
      res,
      {
        heading: `Reset password for ${String(user.user_name)}`,
        sub: 'Their current session ends; they choose a new password at next sign-in.',
        fields: PASSWORD_FIELDS,
        action: `/admin/admins/${String(user.id)}/password`,
        submitLabel: 'Reset password',
      },
      {},
      failure,
    );

  router.get(
    '/:id/password',
    handle(async (req, res) => passwordForm(res, (await target(req)).user, null)),
  );

  router.post(
    '/:id/password',
    verifyCsrf,
    handle(async (req, res) => {
      const { ctx, user, id } = await target(req);
      const outcome = await runForm(resetPasswordBody, formValues(req.body), (data) =>
        resetPassword(ctx, id, data.password),
      );
      if (!outcome.ok) return passwordForm(res, user, outcome.failure);
      flash(req, res, 'success', `Password reset for ${String(user.user_name)}.`);
      res.redirect(303, '/admin/admins');
    }),
  );

  const action = (
    path: string,
    run: (ctx: RequestContext, user: Record<string, unknown>, req: Request) => Promise<string>,
  ) =>
    router.post(
      `/:id/${path}`,
      verifyCsrf,
      handle(async (req, res) => {
        try {
          const { ctx, user } = await target(req);
          flash(req, res, 'success', await run(ctx, user, req));
        } catch (err) {
          const failure = formFailure(err);
          if (!failure || (err instanceof ApiError && err.status === 404)) throw err;
          flash(req, res, 'danger', failure.form ?? 'Not possible.');
        }
        res.redirect(303, '/admin/admins');
      }),
    );

  action('allow', async (ctx, user, req) => {
    const { allow_flag } = allowBody.parse(formValues(req.body));
    await setAllow(ctx, Number(user.id), allow_flag);
    return allow_flag === 'Y'
      ? `${String(user.user_name)} is enabled.`
      : `${String(user.user_name)} is blocked and signed out.`;
  });
  // No "sign out" for super admins: forcing another super admin's session out was declined
  // (CLAUDE.md §13 A7, panel decision 2026-11-05 option a). Block/reset/delete end it as a side
  // effect, exactly as the API does.
  action('delete', async (ctx, user) => {
    await deleteUser(ctx, Number(user.id));
    return `${String(user.user_name)} was deleted.`;
  });

  return router;
}

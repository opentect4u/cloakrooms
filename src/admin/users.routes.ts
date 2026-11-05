/** Customer tab "Users": the station's admins (A) and counter users (O), via users.service. */
import { Router, type Request, type Response } from 'express';
import { z } from 'zod';
import type { RequestContext } from '../core/context';
import { ApiError } from '../core/http/ApiError';
import { maskDevice } from '../modules/auth/auth.service';
import {
  allowBody,
  createUserBody,
  resetPasswordBody,
  updateUserBody,
} from '../modules/users/users.schema';
import {
  createUser,
  deleteUser,
  forceLogout,
  getUser,
  listUsers,
  resetPassword,
  setAllow,
  updateUser,
} from '../modules/users/users.service';
import { verifyCsrf } from './csrf';
import { inputValues, type Field } from './fields';
import { flash } from './flash';
import { formFailure, formValues, runForm, withoutEmpty, type FormFailure } from './forms';
import { idParam, listQuery, pager, PAGE_SIZE } from './listing';
import { handle, page } from './render';

const USER_TYPES = [
  ['O', 'Counter user'],
  ['A', 'Station admin'],
] as const;

export const USER_FIELDS: Field[] = [
  {
    name: 'user_name',
    label: 'Full name',
    type: 'text',
    required: true,
    maxlength: 100,
    col: 'col-12',
    icon: 'person',
    placeholder: 'Ravi Kumar',
  },
  {
    name: 'user_id',
    label: 'Login id',
    type: 'text',
    required: true,
    maxlength: 100,
    col: 'col-sm-6',
    icon: 'at',
    createOnly: true,
    autocomplete: 'off',
    help: 'Letters, digits and . _ @ - (e.g. howrah.ravi).',
  },
  {
    name: 'user_code',
    label: 'User code',
    type: 'text',
    required: true,
    maxlength: 10,
    col: 'col-sm-6',
    placeholder: 'U03',
    help: 'Prefix of this user’s receipt and bill numbers (U03-00125).',
  },
  {
    name: 'user_type',
    label: 'Role',
    type: 'select',
    options: USER_TYPES,
    required: true,
    col: 'col-sm-6',
  },
  {
    name: 'password',
    label: 'Temporary password',
    type: 'password',
    required: true,
    col: 'col-sm-6',
    createOnly: true,
    autocomplete: 'new-password',
    help: 'At least 8 characters with a letter and a digit. The user changes it at first sign-in.',
  },
  {
    name: 'allow_flag',
    label: 'Enabled',
    type: 'switch',
    col: 'col-sm-6',
    createOnly: true,
    help: 'Counts towards the licensed users.',
  },
];

/** Panel users are station staff only: super admins are managed on their own page. */
const panelCreateUser = createUserBody.extend({ user_type: z.enum(['A', 'O']) });

const tctxOf = (res: Response) => res.locals.tctx as RequestContext;

export function usersRoutes(): Router {
  const router = Router({ mergeParams: true });
  const base = (res: Response) => `${String(res.locals.base)}/users`;

  router.get(
    '/users',
    handle(async (req, res) => {
      const q = listQuery.parse(req.query);
      const type = z.enum(['A', 'O']).optional().catch(undefined).parse(req.query.type);
      const status = z.enum(['Y', 'N']).optional().catch(undefined).parse(req.query.status);
      const { rows, meta } = await listUsers(tctxOf(res), {
        page: q.page,
        limit: PAGE_SIZE,
        search: q.q,
        user_type: type,
        allow_flag: status,
        sort: [{ column: 'user_name', dir: 'asc' }],
      });
      const all = await listUsers(tctxOf(res), { page: 1, limit: 1, allow_flag: 'Y' });
      page(res, 'admin/customers/users', {
        title: 'Users',
        active: 'customers',
        tab: 'users',
        q: q.q ?? '',
        type: type ?? '',
        status: status ?? '',
        allowed: all.meta.total,
        rows: rows.map((u) => ({
          ...u,
          online: Boolean(u.session_started_at),
          device: u.device_id ? maskDevice(String(u.device_id)) : '',
        })),
        pager: pager(base(res), req.query, q.page, PAGE_SIZE, meta.total),
      });
    }),
  );

  const renderForm = (
    res: Response,
    mode: 'create' | 'edit',
    values: Record<string, string>,
    failure: FormFailure | null,
    user?: Record<string, unknown>,
  ) =>
    page(
      res,
      'admin/customers/user-form',
      {
        title: mode === 'create' ? 'Add user' : 'Edit user',
        active: 'customers',
        tab: 'users',
        mode,
        user: user ?? null,
        fields: USER_FIELDS.filter((f) => mode === 'create' || !f.createOnly),
        values,
        errors: failure?.fields ?? {},
        formError: failure?.form ?? null,
        action: mode === 'create' ? base(res) : `${base(res)}/${String(user?.id)}`,
        cancel: base(res),
      },
      failure ? 400 : 200,
    );

  router.get('/users/new', (_req, res) =>
    renderForm(res, 'create', { user_type: 'O', allow_flag: 'Y' }, null),
  );

  router.post(
    '/users',
    verifyCsrf,
    handle(async (req, res) => {
      const values = formValues(req.body);
      const outcome = await runForm(panelCreateUser, withoutEmpty(values), (data) =>
        createUser(tctxOf(res), data),
      );
      if (!outcome.ok) return renderForm(res, 'create', values, outcome.failure);
      flash(
        req,
        res,
        'success',
        `${String(outcome.data.user_name)} can now sign in as “${String(outcome.data.user_id)}” and will be asked to choose a new password.`,
      );
      res.redirect(303, base(res));
    }),
  );

  const userOr404 = async (req: Request, res: Response) => {
    const uid = idParam(req.params.uid);
    if (uid === null) throw ApiError.notFound('User not found');
    return getUser(tctxOf(res), uid);
  };

  router.get(
    '/users/:uid/edit',
    handle(async (req, res) => {
      const user = await userOr404(req, res);
      renderForm(res, 'edit', inputValues(USER_FIELDS, user), null, user);
    }),
  );

  router.post(
    '/users/:uid',
    verifyCsrf,
    handle(async (req, res) => {
      const user = await userOr404(req, res);
      const values = formValues(req.body);
      const outcome = await runForm(updateUserBody, withoutEmpty(values), (data) =>
        updateUser(tctxOf(res), Number(user.id), data),
      );
      if (!outcome.ok) return renderForm(res, 'edit', values, outcome.failure, user);
      flash(req, res, 'success', 'User updated.');
      res.redirect(303, base(res));
    }),
  );

  const renderPassword = (
    res: Response,
    user: Record<string, unknown>,
    failure: FormFailure | null,
  ) =>
    page(
      res,
      'admin/customers/user-password',
      {
        title: 'Reset password',
        active: 'customers',
        tab: 'users',
        user,
        errors: failure?.fields ?? {},
        formError: failure?.form ?? null,
        action: `${base(res)}/${String(user.id)}/password`,
        cancel: base(res),
      },
      failure ? 400 : 200,
    );

  router.get(
    '/users/:uid/password',
    handle(async (req, res) => renderPassword(res, await userOr404(req, res), null)),
  );

  router.post(
    '/users/:uid/password',
    verifyCsrf,
    handle(async (req, res) => {
      const user = await userOr404(req, res);
      const outcome = await runForm(resetPasswordBody, formValues(req.body), (data) =>
        resetPassword(tctxOf(res), Number(user.id), data.password),
      );
      if (!outcome.ok) return renderPassword(res, user, outcome.failure);
      flash(
        req,
        res,
        'success',
        `Password reset for ${String(user.user_name)}. Their device was signed out; they choose a new password at next sign-in.`,
      );
      res.redirect(303, base(res));
    }),
  );

  /** One-click actions: allow/block, force logout, delete (confirmed in the browser). */
  const action = (
    path: string,
    run: (ctx: RequestContext, user: Record<string, unknown>, req: Request) => Promise<string>,
  ) =>
    router.post(
      `/users/:uid/${path}`,
      verifyCsrf,
      handle(async (req, res) => {
        const user = await userOr404(req, res);
        try {
          flash(req, res, 'success', await run(tctxOf(res), user, req));
        } catch (err) {
          const failure = formFailure(err);
          if (!failure) throw err;
          flash(
            req,
            res,
            'danger',
            failure.form ?? Object.values(failure.fields)[0] ?? 'Not possible.',
          );
        }
        res.redirect(303, base(res));
      }),
    );

  action('allow', async (ctx, user, req) => {
    const { allow_flag } = allowBody.parse(formValues(req.body));
    await setAllow(ctx, Number(user.id), allow_flag);
    return allow_flag === 'Y'
      ? `${String(user.user_name)} is enabled.`
      : `${String(user.user_name)} is blocked and was signed out.`;
  });

  action('force-logout', async (ctx, user) => {
    const { sessions_ended } = await forceLogout(ctx, Number(user.id));
    return sessions_ended
      ? `${String(user.user_name)} was signed out. The device can still upload pending data.`
      : `${String(user.user_name)} had no active session.`;
  });

  action('delete', async (ctx, user) => {
    await deleteUser(ctx, Number(user.id));
    return `${String(user.user_name)} was deleted.`;
  });

  return router;
}

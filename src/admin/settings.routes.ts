/** Customer tab "Settings": customer-wide values and per-user overrides (settings crud hooks). */
import { Router, type Response } from 'express';
import { z } from 'zod';
import type { RequestContext } from '../core/context';
import { crudService } from '../core/crud/crudService';
import type { Row } from '../core/db/dml';
import { ApiError } from '../core/http/ApiError';
import { SETTING_NAMES, type SettingName } from '../modules/settings/settings.schema';
import { settingsCrud } from '../modules/settings/settings.routes';
import { verifyCsrf } from './csrf';
import { flash } from './flash';
import { formFailure, formValues } from './forms';
import { idParam } from './listing';
import { loadLookups, optionsOf } from './lookups';
import { handle, page } from './render';
import { SETTING_GROUPS, SETTING_META, settingText } from './settingsMeta';

const settings = crudService(settingsCrud);
const tctxOf = (res: Response) => res.locals.tctx as RequestContext;
const nameSchema = z.enum(SETTING_NAMES);

async function allSettings(ctx: RequestContext): Promise<Row[]> {
  const { rows } = await settings.list(ctx, { page: 1, limit: 200 });
  return rows;
}

/** Creates the row or updates the live one (customer-wide when userId is null). */
async function upsert(
  ctx: RequestContext,
  name: SettingName,
  value: string,
  userId: number | null,
) {
  const rows = await allSettings(ctx);
  const existing = rows.find(
    (r) => r.setting_name === name && (r.user_id === null ? null : Number(r.user_id)) === userId,
  );
  if (existing) {
    await settings.update(ctx, Number(existing.setting_id), { setting_value: value });
  } else {
    await settings.create(ctx, { setting_name: name, setting_value: value, user_id: userId });
  }
}

const describe = (err: unknown) => {
  const failure = formFailure(err);
  if (!failure) throw err;
  return (
    failure.fields.setting_value ??
    failure.form ??
    Object.values(failure.fields)[0] ??
    'Invalid value'
  );
};

export function settingsRoutes(): Router {
  const router = Router({ mergeParams: true });
  const base = (res: Response) => `${String(res.locals.base)}/settings`;

  router.get(
    '/settings',
    handle(async (req, res) => {
      const rows = await allSettings(tctxOf(res));
      const lookups = await loadLookups(
        ['users'],
        tctxOf(res),
        tctxOf(res),
        Number(res.locals.cid),
      );
      const customerWide = new Map(
        rows.filter((r) => r.user_id === null).map((r) => [String(r.setting_name), r]),
      );
      const groups = SETTING_GROUPS.map((group) => ({
        group,
        items: SETTING_NAMES.filter((n) => SETTING_META[n].group === group).map((name) => {
          const row = customerWide.get(name);
          const value = row ? String(row.setting_value) : '';
          return {
            name,
            meta: SETTING_META[name],
            value: name === 'REPORT_PWD' ? '' : value,
            isSet: Boolean(row),
            text: settingText(name, row ? value : null),
          };
        }),
      }));
      const overrides = rows
        .filter((r) => r.user_id !== null)
        .map((r) => {
          const name = String(r.setting_name) as SettingName;
          return {
            id: r.setting_id,
            user: lookups.users?.get(Number(r.user_id)) ?? `User #${String(r.user_id)}`,
            label: SETTING_META[name]?.label ?? name,
            text: settingText(name, String(r.setting_value)),
          };
        });
      page(res, 'admin/customers/settings', {
        title: 'Settings',
        active: 'customers',
        tab: 'settings',
        groups,
        overrides,
        userOptions: optionsOf(lookups.users),
        settingOptions: SETTING_NAMES.map((n) => [n, SETTING_META[n].label]),
        meta: SETTING_META,
        focus: typeof req.query.saved === 'string' ? req.query.saved : '',
      });
    }),
  );

  router.post(
    '/settings/overrides',
    verifyCsrf,
    handle(async (req, res) => {
      const v = formValues(req.body);
      const userId = idParam(v.user_id);
      const name = nameSchema.safeParse(v.setting_name);
      if (userId === null || !name.success) {
        flash(req, res, 'danger', 'Choose a user and a setting.');
        return res.redirect(303, `${base(res)}#overrides`);
      }
      try {
        await upsert(tctxOf(res), name.data, v.setting_value ?? '', userId);
        flash(req, res, 'success', `Override saved: ${SETTING_META[name.data].label}.`);
      } catch (err) {
        flash(req, res, 'danger', describe(err));
      }
      res.redirect(303, `${base(res)}#overrides`);
    }),
  );

  router.post(
    '/settings/overrides/:sid/delete',
    verifyCsrf,
    handle(async (req, res) => {
      const sid = idParam(req.params.sid);
      if (sid === null) throw ApiError.notFound();
      const row = await settings.get(tctxOf(res), sid);
      if (row.user_id === null) throw ApiError.notFound();
      await settings.remove(tctxOf(res), sid);
      flash(req, res, 'success', 'Override removed; the customer-wide value applies again.');
      res.redirect(303, `${base(res)}#overrides`);
    }),
  );

  router.post(
    '/settings/:name',
    verifyCsrf,
    handle(async (req, res) => {
      const name = nameSchema.safeParse(req.params.name);
      if (!name.success) throw ApiError.notFound();
      const value = formValues(req.body).setting_value ?? '';
      if (name.data === 'REPORT_PWD' && value === '') {
        flash(req, res, 'info', 'Report password unchanged.');
        return res.redirect(303, `${base(res)}#${name.data}`);
      }
      try {
        await upsert(tctxOf(res), name.data, value, null);
        flash(req, res, 'success', `${SETTING_META[name.data].label}: saved.`);
      } catch (err) {
        flash(req, res, 'danger', describe(err));
      }
      res.redirect(303, `${base(res)}?saved=${name.data}#${name.data}`);
    }),
  );

  return router;
}

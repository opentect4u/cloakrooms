/** /admin/releases — Android app releases: list, publish, withdraw (app-version.service). */
import { readdir } from 'node:fs/promises';
import path from 'node:path';
import { Router, type Response } from 'express';
import { env } from '../config/env';
import { ApiError } from '../core/http/ApiError';
import { ctxOf } from '../core/middleware/requireRole';
import { createVersionBody } from '../modules/app-version/app-version.schema';
import {
  createVersion,
  listVersions,
  withdrawVersion,
} from '../modules/app-version/app-version.service';
import { verifyCsrf } from './csrf';
import type { Field } from './fields';
import { flash } from './flash';
import { formValues, runForm, withoutEmpty, type FormFailure } from './forms';
import { idParam, listQuery, pager, PAGE_SIZE } from './listing';
import { handle, page } from './render';

/** APK files already in DOWNLOADS_DIR, offered as /downloads/<file> suggestions. */
async function apkFiles(): Promise<string[]> {
  try {
    const files = await readdir(path.resolve(env.DOWNLOADS_DIR));
    return files.filter((f) => /^[\w.-]+\.apk$/.test(f)).map((f) => `/downloads/${f}`);
  } catch {
    return [];
  }
}

function releaseFields(apks: string[]): Field[] {
  return [
    {
      name: 'version_code',
      label: 'Version code',
      type: 'number',
      required: true,
      min: 1,
      col: 'col-sm-6',
      help: 'Android versionCode (a whole number that grows with every release).',
    },
    {
      name: 'version_name',
      label: 'Version name',
      type: 'text',
      required: true,
      maxlength: 20,
      col: 'col-sm-6',
      placeholder: '1.2.0',
    },
    {
      name: 'apk_url',
      label: 'APK link',
      type: 'text',
      required: true,
      maxlength: 255,
      col: 'col-12',
      icon: 'link-45deg',
      placeholder: '/downloads/cloakroom-1.2.0.apk',
      datalist: apks,
      help: 'An https link, or /downloads/<file>.apk for a file placed in this server’s downloads folder.',
    },
    {
      name: 'mandatory_flag',
      label: 'Mandatory update',
      type: 'switch',
      col: 'col-12',
      help: 'Older app versions are refused at sign-in (426 UPDATE_REQUIRED) until they update.',
    },
    { name: 'release_notes', label: 'What’s new', type: 'textarea', col: 'col-12' },
  ];
}

export function releasesRoutes(): Router {
  const router = Router();

  router.get(
    '/',
    handle(async (req, res) => {
      const q = listQuery.parse(req.query);
      const { rows, total } = await listVersions(ctxOf(req), q.page, PAGE_SIZE);
      page(res, 'admin/releases', {
        title: 'App releases',
        active: 'releases',
        rows,
        latestCode: q.page === 1 ? (rows[0]?.version_code ?? null) : null,
        pager: pager('/admin/releases', req.query, q.page, PAGE_SIZE, total),
      });
    }),
  );

  const renderForm = async (
    res: Response,
    values: Record<string, string>,
    failure: FormFailure | null,
  ) =>
    page(
      res,
      'admin/release-form',
      {
        title: 'Publish a release',
        active: 'releases',
        fields: releaseFields(await apkFiles()),
        values,
        errors: failure?.fields ?? {},
        formError: failure?.form ?? null,
      },
      failure ? 400 : 200,
    );

  router.get(
    '/new',
    handle((_req, res) => renderForm(res, { mandatory_flag: 'N' }, null)),
  );

  router.post(
    '/',
    verifyCsrf,
    handle(async (req, res) => {
      const values = formValues(req.body);
      const outcome = await runForm(createVersionBody, withoutEmpty(values), (data) =>
        createVersion(ctxOf(req), data),
      );
      if (!outcome.ok) return renderForm(res, values, outcome.failure);
      flash(
        req,
        res,
        'success',
        `Version ${String(outcome.data?.version_name)} published${outcome.data?.mandatory_flag === 'Y' ? ' as a mandatory update' : ''}.`,
      );
      res.redirect(303, '/admin/releases');
    }),
  );

  router.post(
    '/:id/withdraw',
    verifyCsrf,
    handle(async (req, res) => {
      const id = idParam(req.params.id);
      if (id === null) throw ApiError.notFound('Release not found');
      await withdrawVersion(ctxOf(req), id);
      flash(req, res, 'success', 'Release withdrawn.');
      res.redirect(303, '/admin/releases');
    }),
  );

  return router;
}

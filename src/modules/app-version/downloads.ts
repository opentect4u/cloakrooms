import { resolve } from 'node:path';
import express, { Router } from 'express';
import { ApiError } from '../../core/http/ApiError';

const APK_PATH = /^\/[\w.-]+\.apk$/;

/**
 * Static APK hosting at /downloads (public). Only flat `*.apk` names are served — no directories,
 * dotfiles or other types. Versioned file names are cached for a day by clients and proxies.
 */
export function downloadsRouter(dir: string): Router {
  const router = Router();
  router.use((req, _res, next) => next(APK_PATH.test(req.path) ? undefined : ApiError.notFound()));
  router.use(
    express.static(resolve(dir), {
      index: false,
      dotfiles: 'deny',
      redirect: false,
      maxAge: '1d',
      etag: true,
      lastModified: true,
      setHeaders: (res) => {
        res.setHeader('Content-Type', 'application/vnd.android.package-archive');
        res.setHeader('Content-Disposition', 'attachment');
      },
    }),
  );
  return router;
}

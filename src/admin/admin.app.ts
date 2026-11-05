/**
 * Super admin web panel (Phase 12): server-rendered EJS at /admin, calling the same services as
 * the API. Mounted by createApp() before the 404 handler.
 */
import cookieParser from 'cookie-parser';
import express, {
  Router,
  type ErrorRequestHandler,
  type Express,
  type Request,
  type Response,
} from 'express';
import helmet from 'helmet';
import { APP_VERSION } from '../config/constants';
import { isProd } from '../config/env';
import { ApiError } from '../core/http/ApiError';
import { mapMysqlError } from '../core/middleware/errorHandler';
import { authPages, loginErrorMessage } from './auth.routes';
import { adminsRoutes } from './admins.routes';
import { bagsRoutes } from './bags.routes';
import { customersRoutes } from './customers.routes';
import { exceptionsRoutes } from './exceptions.routes';
import { releasesRoutes } from './releases.routes';
import { reportsRoutes } from './reports.routes';
import { globalResourceRoutes } from './resource.routes';
import { clearAuthCookies } from './cookies';
import { isCsrfError, issueCsrf } from './csrf';
import { dashboard } from './dashboard.service';
import { flash, readFlash } from './flash';
import { ADMIN_PREFIX, ADMIN_PUBLIC_DIR, ADMIN_VIEWS_DIR, VENDOR_DIRS } from './paths';
import { handle, loadMe, page, safeNext } from './render';
import { panelAuth } from './session';
import { viewHelpers } from './viewHelpers';

/** Changes on every boot, so browsers refetch assets after a deploy (long-lived cache in prod). */
const ASSET_VERSION = `${APP_VERSION}-${Date.now().toString(36)}`;

/** Panel CSP: own origin only, no inline script or style (styles come from files). */
const panelHelmet = helmet({
  contentSecurityPolicy: {
    directives: {
      'default-src': ["'self'"],
      'script-src': ["'self'"],
      'style-src': ["'self'"],
      'img-src': ["'self'", 'data:'],
      'font-src': ["'self'"],
      'connect-src': ["'self'"],
      'form-action': ["'self'"],
      'frame-ancestors': ["'none'"],
      'base-uri': ["'self'"],
      'object-src': ["'none'"],
      // The panel may be opened over plain http on a LAN IP during development.
      'upgrade-insecure-requests': null,
    },
  },
});

export function mountAdmin(app: Express) {
  app.set('views', ADMIN_VIEWS_DIR);
  app.set('view engine', 'ejs');
  Object.assign(app.locals, viewHelpers, { assetV: ASSET_VERSION });
  app.use(ADMIN_PREFIX, adminRouter());
}

function adminRouter(): Router {
  const router = Router();
  const staticOpts = { maxAge: isProd ? '7d' : 0, fallthrough: false, index: false };

  router.use(panelHelmet);
  router.use('/assets/vendor/bootstrap', express.static(VENDOR_DIRS.bootstrap, staticOpts));
  router.use('/assets/vendor/icons', express.static(VENDOR_DIRS.icons, staticOpts));
  router.use('/assets/vendor/font', express.static(VENDOR_DIRS.font, staticOpts));
  router.use('/assets', express.static(ADMIN_PUBLIC_DIR, staticOpts));

  router.use(express.urlencoded({ extended: false, limit: '100kb', parameterLimit: 200 }));
  router.use(cookieParser());
  router.use(readFlash);
  router.use(issueCsrf);

  router.use(authPages);

  router.get(
    '/',
    panelAuth(),
    loadMe,
    handle(async (_req, res) => {
      page(res, 'admin/dashboard', {
        title: 'Dashboard',
        active: 'dashboard',
        d: await dashboard(),
      });
    }),
  );

  const secure = [panelAuth(), loadMe];
  router.use('/customers', ...secure, customersRoutes());
  router.use('/reports', ...secure, reportsRoutes());
  router.use('/exceptions', ...secure, exceptionsRoutes());
  router.use('/bags', ...secure, bagsRoutes());
  router.use('/releases', ...secure, releasesRoutes());
  router.use('/admins', ...secure, adminsRoutes());
  router.use(...secure, globalResourceRoutes());

  router.use(panelAuth(), loadMe, (_req, res) => {
    page(res, 'admin/error', { title: 'Not found', status: 404, message: 'Page not found.' }, 404);
  });
  router.use(panelErrorHandler);
  return router;
}

const SIGN_IN_AGAIN: Record<string, string> = {
  SESSION_ENDED: 'ended',
  TOKEN_REUSED: 'ended',
  TOKEN_EXPIRED: 'expired',
};

function toApiError(err: unknown): ApiError | null {
  if (err instanceof ApiError) return err;
  return mapMysqlError(err as Error);
}

export const panelErrorHandler: ErrorRequestHandler = (err, req: Request, res: Response, next) => {
  if (res.headersSent) return next(err);
  const apiErr = toApiError(err);

  // Rate-limited sign-in: show it on the sign-in form.
  if (req.path === '/login' && req.method === 'POST' && apiErr?.status === 429) {
    return page(
      res,
      'admin/login',
      {
        title: 'Sign in',
        error: loginErrorMessage(apiErr),
        userId: typeof req.body?.user_id === 'string' ? req.body.user_id.slice(0, 100) : '',
        next: safeNext(req.body?.next, ''),
        reason: '',
      },
      429,
    );
  }

  if (apiErr?.status === 401) {
    clearAuthCookies(req, res);
    const reason = SIGN_IN_AGAIN[apiErr.code] ?? '';
    const back = req.method === 'GET' ? `&next=${encodeURIComponent(req.originalUrl)}` : '';
    return res.redirect(303, `${ADMIN_PREFIX}/login?reason=${reason}${back}`);
  }
  if (apiErr?.code === 'PASSWORD_CHANGE_REQUIRED') {
    return res.redirect(303, `${ADMIN_PREFIX}/change-password`);
  }
  if (isCsrfError(apiErr)) {
    flash(req, res, 'warning', apiErr?.message ?? 'Please try again.');
    return res.redirect(303, safeNext(req.get('referer')?.replace(/^https?:\/\/[^/]+/, '')));
  }

  // http-errors from express.static / body-parser carry their own 4xx status.
  const httpStatus = (err as { status?: unknown })?.status;
  const status =
    apiErr?.status ??
    (typeof httpStatus === 'number' && httpStatus >= 400 && httpStatus < 500 ? httpStatus : 500);
  if (status >= 500) req.log?.error({ err }, 'admin panel error');
  const message =
    status >= 500
      ? 'Something went wrong on our side. Please try again.'
      : (apiErr?.message ?? 'Request failed');
  page(
    res,
    'admin/error',
    {
      title: status === 404 ? 'Not found' : 'Something went wrong',
      status,
      message,
      details: !isProd && status >= 500 ? String((err as Error)?.stack ?? err) : null,
      requestId: req.requestId,
    },
    status,
  );
};

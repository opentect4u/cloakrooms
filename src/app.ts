import compression from 'compression';
import cors from 'cors';
import express from 'express';
import helmet from 'helmet';
import hpp from 'hpp';
import { pinoHttp } from 'pino-http';
import { API_PREFIX, BODY_LIMIT, SYNC_BODY_LIMIT } from './config/constants';
import { env } from './config/env';
import { logger } from './core/logger';
import { errorHandler } from './core/middleware/errorHandler';
import { notFound } from './core/middleware/notFound';
import { floodLimiter } from './core/middleware/rateLimit';
import { requestId } from './core/middleware/requestId';
import { mountAdmin } from './admin/admin.app';
import { docsRouter } from './docs/docs.routes';
import { downloadsRouter } from './modules/app-version/downloads';
import { apiRoutes } from './routes';

function parseTrustProxy(value: string): boolean | number | string {
  if (/^\d+$/.test(value)) return Number(value);
  return value;
}

export function createApp() {
  const app = express();

  app.disable('x-powered-by');
  app.set('trust proxy', parseTrustProxy(env.TRUST_PROXY));

  // Order matters (BUILD_PROMPT Phase 0).
  app.use(requestId);
  app.use(
    pinoHttp({
      logger,
      genReqId: (req) => (req as express.Request).requestId,
      customLogLevel: (_req, res, err) =>
        err || res.statusCode >= 500 ? 'error' : res.statusCode >= 400 ? 'warn' : 'info',
      serializers: {
        // Path only: query strings carry PII (mobile / PNR / name searches).
        req: (req: { id: string; method: string; url: string }) => ({
          id: req.id,
          method: req.method,
          path: req.url.split('?')[0],
        }),
      },
    }),
  );
  app.use(helmet());
  app.use(
    cors({
      // Android clients send no Origin header; browsers must be on the allow-list.
      origin: (origin, cb) => cb(null, !origin || env.CORS_ORIGINS.includes(origin)),
      credentials: false,
      maxAge: 600,
    }),
  );
  app.use(compression());
  app.use(hpp());
  // Sync batches get a larger body limit; the global parser then skips the already-parsed body.
  app.use(`${API_PREFIX}/sync`, express.json({ limit: SYNC_BODY_LIMIT }));
  app.use(express.json({ limit: BODY_LIMIT }));
  // Per-IP flood guard; per-user limits (general/sync/reports) sit behind auth on each router.
  app.use(floodLimiter);

  app.use('/downloads', downloadsRouter(env.DOWNLOADS_DIR));
  if (env.API_DOCS_ENABLED) app.use('/api/docs', docsRouter());
  // API responses carry tokens and passenger data: never store them in any cache.
  app.use(API_PREFIX, (_req, res, next) => {
    res.setHeader('Cache-Control', 'no-store');
    next();
  });
  app.use(API_PREFIX, apiRoutes);
  // Super admin web panel (server-rendered, cookie session, own CSP and error pages).
  if (env.ADMIN_PANEL_ENABLED) mountAdmin(app);

  app.use(notFound);
  app.use(errorHandler);

  return app;
}

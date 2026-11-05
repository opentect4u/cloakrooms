import { Router } from 'express';
import helmet from 'helmet';
import swaggerUi from 'swagger-ui-express';
import { buildOpenApiDocument } from './openapi';

let cached: ReturnType<typeof buildOpenApiDocument> | undefined;
/** Built on first use and kept (the document is static for a given build). */
export const openApiDocument = () => (cached ??= buildOpenApiDocument());

/** `/api/docs` (Swagger UI) and `/api/docs/openapi.json`. */
export function docsRouter(): Router {
  const router = Router();
  router.get('/openapi.json', (_req, res) => {
    res.json(openApiDocument());
  });
  // Swagger UI needs inline scripts/styles; relax CSP for these pages only.
  router.use(
    helmet({
      contentSecurityPolicy: {
        directives: {
          'script-src': ["'self'", "'unsafe-inline'"],
          'style-src': ["'self'", "'unsafe-inline'"],
          'img-src': ["'self'", 'data:'],
          // Over plain http on a LAN IP this would rewrite the UI's own JS/CSS to https → blank page.
          'upgrade-insecure-requests': null,
        },
      },
    }),
  );
  const options = {
    customSiteTitle: 'Cloak Room Billing API',
    swaggerOptions: { persistAuthorization: true, displayRequestDuration: true },
  };
  router.use(
    '/',
    swaggerUi.serveFiles(openApiDocument(), options),
    swaggerUi.setup(openApiDocument(), options),
  );
  return router;
}

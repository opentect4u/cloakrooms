import { Router, type RequestHandler } from 'express';
import { authenticate } from './core/middleware/auth';
import { limiters } from './core/middleware/rateLimit';
import { appVersionRoutes } from './modules/app-version/app-version.routes';
import { authRoutes } from './modules/auth/auth.routes';
import { reportsRoutes } from './modules/reports/reports.routes';
import { customersRoutes } from './modules/customers/customers.routes';
import { gstRoutes } from './modules/gst/gst.routes';
import { headerFooterRoutes } from './modules/header-footer/header-footer.routes';
import { healthRoutes } from './modules/health/health.routes';
import { idProofsRoutes } from './modules/id-proofs/id-proofs.routes';
import { luggageRoutes } from './modules/luggage/luggage.routes';
import { syncRoutes } from './modules/sync/sync.routes';
import { ratesRoutes } from './modules/rates/rates.routes';
import { settingsRoutes } from './modules/settings/settings.routes';
import { shiftAssignmentsRoutes } from './modules/shift-assignments/shift-assignments.routes';
import { shiftsRoutes } from './modules/shifts/shifts.routes';
import { usersRoutes } from './modules/users/users.routes';

export const apiRoutes = Router();

/** auth → session → per-user general rate limit; role checks live in each router. */
const secured = (router: RequestHandler): RequestHandler[] => [
  ...authenticate(),
  limiters.general,
  router,
];

apiRoutes.use(healthRoutes);
apiRoutes.use('/auth', authRoutes);
apiRoutes.use('/users', usersRoutes);

// Tenant masters (A, S)
apiRoutes.use('/rates', secured(ratesRoutes));
apiRoutes.use('/shifts', secured(shiftsRoutes));
apiRoutes.use('/shift-assignments', secured(shiftAssignmentsRoutes));
apiRoutes.use('/header-footer', secured(headerFooterRoutes));
apiRoutes.use('/settings', secured(settingsRoutes));

// Global masters (S writes, everyone reads)
apiRoutes.use('/gst', secured(gstRoutes));
apiRoutes.use('/id-proofs', secured(idProofsRoutes));

// Platform (S)
apiRoutes.use('/customers', secured(customersRoutes));

// Devices (A, O): sync has its own auth chain (forced sessions may push) and the sync limiter.
apiRoutes.use('/sync', syncRoutes);
apiRoutes.use('/luggage', secured(luggageRoutes));

// Reports (A, S; O when REPORT_FLAG) — own 30/min limiter instead of the general one.
apiRoutes.use('/reports', ...authenticate(), limiters.reports, reportsRoutes);

// App updates: GET public, POST super admin.
apiRoutes.use('/app', appVersionRoutes);

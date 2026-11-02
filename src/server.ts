import cluster from 'node:cluster';
import { createServer } from 'node:http';
import { availableParallelism } from 'node:os';
import { createApp } from './app';
import { HTTP_TIMEOUTS } from './config/constants';
import { env, isProd } from './config/env';
import { closeDb, db, pingDb } from './core/db/pool';
import { loadRegistry } from './core/db/registry';
import { logger } from './core/logger';
import { onShutdown, runShutdownHooks } from './core/shutdown';

const SHUTDOWN_MSG = 'shutdown';

function workerCount(): number {
  if (env.CLUSTER_WORKERS > 0) return env.CLUSTER_WORKERS;
  return isProd ? availableParallelism() : 1;
}

const DB_BOOT_ATTEMPTS = 5;
const DB_BOOT_DELAY_MS = 2000;

/** Health-checks the pool and loads the identifier registry; retries while MySQL starts up. */
async function bootDb(): Promise<void> {
  onShutdown('mysql pool', closeDb);
  for (let attempt = 1; ; attempt++) {
    try {
      await pingDb();
      await loadRegistry(db);
      logger.info('database ready, registry loaded');
      return;
    } catch (err) {
      if (attempt >= DB_BOOT_ATTEMPTS) throw err;
      logger.warn({ err, attempt }, 'database not ready, retrying');
      await new Promise((r) => setTimeout(r, DB_BOOT_DELAY_MS));
    }
  }
}

async function startWorker(): Promise<void> {
  await bootDb();
  const app = createApp();
  const server = createServer(app);

  server.keepAliveTimeout = HTTP_TIMEOUTS.keepAliveMs;
  server.headersTimeout = HTTP_TIMEOUTS.headersMs;
  server.requestTimeout = HTTP_TIMEOUTS.requestMs;

  server.listen(env.PORT, () => {
    logger.info({ port: env.PORT, env: env.NODE_ENV }, 'server listening');
  });

  let shuttingDown = false;
  const shutdown = (reason: string) => {
    if (shuttingDown) return;
    shuttingDown = true;
    logger.info({ reason }, 'graceful shutdown started');

    const force = setTimeout(() => {
      logger.error('shutdown timed out, forcing exit');
      process.exit(1);
    }, env.SHUTDOWN_TIMEOUT_MS);
    force.unref();

    // Stop accepting, let in-flight requests drain, then release resources.
    server.close(async (err) => {
      if (err) logger.error({ err }, 'error closing http server');
      await runShutdownHooks();
      logger.info('shutdown complete');
      process.exit(err ? 1 : 0);
    });
    server.closeIdleConnections();
  };

  process.on('SIGTERM', () => shutdown('SIGTERM'));
  process.on('SIGINT', () => shutdown('SIGINT'));
  process.on('message', (msg) => {
    if (msg === SHUTDOWN_MSG) shutdown('primary');
  });
  process.on('unhandledRejection', (reason) => {
    logger.error({ err: reason }, 'unhandled promise rejection');
  });
  process.on('uncaughtException', (err) => {
    logger.fatal({ err }, 'uncaught exception');
    shutdown('uncaughtException');
  });
}

function startPrimary(count: number): void {
  logger.info({ workers: count }, 'primary starting workers');
  let stopping = false;

  if (!env.REDIS_URL) {
    // The in-memory rate-limit store is per worker: effective limits become N × configured.
    logger.warn({ workers: count }, 'REDIS_URL not set: rate limits are enforced per worker');
  }
  for (let i = 0; i < count; i++) cluster.fork();

  cluster.on('exit', (worker, code, signal) => {
    if (stopping) return;
    logger.error({ pid: worker.process.pid, code, signal }, 'worker died, restarting');
    cluster.fork();
  });

  const stop = (reason: string) => {
    if (stopping) return;
    stopping = true;
    logger.info({ reason }, 'primary stopping workers');
    for (const worker of Object.values(cluster.workers ?? {})) worker?.send(SHUTDOWN_MSG);

    const force = setTimeout(() => process.exit(1), env.SHUTDOWN_TIMEOUT_MS + 2000);
    force.unref();
    const check = setInterval(() => {
      if (Object.keys(cluster.workers ?? {}).length === 0) {
        clearInterval(check);
        process.exit(0);
      }
    }, 200);
  };

  process.on('SIGTERM', () => stop('SIGTERM'));
  process.on('SIGINT', () => stop('SIGINT'));
}

const count = workerCount();
if (count > 1 && cluster.isPrimary) {
  startPrimary(count);
} else {
  startWorker().catch(async (err) => {
    logger.fatal({ err }, 'worker failed to start');
    await runShutdownHooks();
    process.exit(1);
  });
}

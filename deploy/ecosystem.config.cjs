/**
 * PM2 alternative to Docker:  npm ci && npm run build && pm2 start deploy/ecosystem.config.cjs
 * PM2 runs one process per CPU, so the app's own node:cluster is switched off (CLUSTER_WORKERS=1).
 * Logs go to stdout; rotate them with `pm2 install pm2-logrotate` (max_size 50M, retain 14).
 */
module.exports = {
  apps: [
    {
      name: 'cloakroom-api',
      script: 'dist/server.js',
      cwd: __dirname + '/..',
      exec_mode: 'cluster',
      instances: 'max',
      env: {
        NODE_ENV: 'production',
        CLUSTER_WORKERS: '1',
      },
      max_memory_restart: '512M',
      kill_timeout: 20000, // > SHUTDOWN_TIMEOUT_MS so in-flight requests drain
      listen_timeout: 30000,
      merge_logs: true,
      time: true,
    },
  ],
};

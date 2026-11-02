import { config as loadDotenv } from 'dotenv';
import { z } from 'zod';

loadDotenv({ quiet: true });

const PLACEHOLDER_SECRETS = ['change_me_min_32_chars', 'change_me_min_32_chars_too'];

const ttl = z.string().regex(/^\d+[smhd]$/, 'must look like 15m, 12h, 30d');

const envSchema = z
  .object({
    NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
    PORT: z.coerce.number().int().min(1).max(65535).default(4000),

    DB_HOST: z.string().min(1).default('localhost'),
    DB_PORT: z.coerce.number().int().min(1).max(65535).default(3306),
    DB_USER: z.string().min(1).default('root'),
    DB_PASSWORD: z.string().default(''),
    DB_NAME: z.string().min(1).default('cloak_room_billing'),
    DB_POOL_LIMIT: z.coerce.number().int().min(1).max(500).default(20),

    JWT_ACCESS_SECRET: z.string().min(32),
    JWT_REFRESH_SECRET: z.string().min(32),
    JWT_ACCESS_TTL: ttl.default('12h'),
    JWT_REFRESH_TTL: ttl.default('30d'),

    CORS_ORIGINS: z
      .string()
      .default('')
      .transform((v) =>
        v
          .split(',')
          .map((o) => o.trim())
          .filter(Boolean),
      ),
    REDIS_URL: z
      .string()
      .optional()
      .transform((v) => (v ? v : undefined))
      .pipe(z.url().optional()),

    SUPER_ADMIN_USER_ID: z.string().min(3).default('superadmin'),
    SUPER_ADMIN_PASSWORD: z.string().min(8).default('ChangeMe@123'),

    LOG_LEVEL: z
      .enum(['fatal', 'error', 'warn', 'info', 'debug', 'trace', 'silent'])
      .default('info'),

    CLUSTER_WORKERS: z.coerce.number().int().min(0).max(256).default(0),
    TRUST_PROXY: z.string().default('loopback'),
    SHUTDOWN_TIMEOUT_MS: z.coerce.number().int().min(1000).default(15000),
    /** Scales every rate limit (load testing only; must stay 1 in production). */
    RATE_LIMIT_MULTIPLIER: z.coerce.number().int().min(1).max(100_000).default(1),
    /** Serve Swagger UI at /api/docs and the spec at /api/docs/openapi.json. */
    API_DOCS_ENABLED: z
      .enum(['true', 'false'])
      .default('true')
      .transform((v) => v === 'true'),
    /** Folder served at /downloads (APK files). Relative paths resolve from the working dir. */
    DOWNLOADS_DIR: z.string().min(1).default('downloads'),
  })
  .superRefine((env, ctx) => {
    if (env.NODE_ENV !== 'production') return;
    for (const key of ['JWT_ACCESS_SECRET', 'JWT_REFRESH_SECRET'] as const) {
      if (PLACEHOLDER_SECRETS.includes(env[key])) {
        ctx.addIssue({ code: 'custom', path: [key], message: 'placeholder secret in production' });
      }
    }
    if (env.JWT_ACCESS_SECRET === env.JWT_REFRESH_SECRET) {
      ctx.addIssue({
        code: 'custom',
        path: ['JWT_REFRESH_SECRET'],
        message: 'must differ from JWT_ACCESS_SECRET',
      });
    }
    if (env.RATE_LIMIT_MULTIPLIER !== 1) {
      ctx.addIssue({
        code: 'custom',
        path: ['RATE_LIMIT_MULTIPLIER'],
        message: 'must be 1 in production',
      });
    }
    if (env.SUPER_ADMIN_PASSWORD === 'ChangeMe@123') {
      ctx.addIssue({
        code: 'custom',
        path: ['SUPER_ADMIN_PASSWORD'],
        message: 'default password in production',
      });
    }
  });

export type Env = z.infer<typeof envSchema>;

export function parseEnv(source: NodeJS.ProcessEnv): Env {
  const result = envSchema.safeParse(source);
  if (!result.success) {
    const lines = result.error.issues.map((i) => `  - ${i.path.join('.')}: ${i.message}`);
    throw new Error(`Invalid environment configuration:\n${lines.join('\n')}`);
  }
  return result.data;
}

function loadEnv(): Env {
  try {
    return parseEnv(process.env);
  } catch (err) {
    // Fail fast: the process must not start with a bad configuration.
    console.error((err as Error).message);
    process.exit(1);
  }
}

export const env: Env = loadEnv();
export const isProd = env.NODE_ENV === 'production';
export const isTest = env.NODE_ENV === 'test';

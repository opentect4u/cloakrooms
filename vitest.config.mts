import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    include: ['tests/**/*.test.ts'],
    environment: 'node',
    globalSetup: ['tests/globalSetup.ts'],
    env: {
      NODE_ENV: 'test',
      LOG_LEVEL: 'silent',
      JWT_ACCESS_SECRET: 'test_access_secret_at_least_32_characters_long',
      JWT_REFRESH_SECRET: 'test_refresh_secret_at_least_32_characters_long',
      CORS_ORIGINS: 'http://localhost:3000',
      // Integration tests never touch the dev database.
      DB_NAME: 'cloak_room_billing_test',
      DOWNLOADS_DIR: 'tests/fixtures/downloads',
    },
    coverage: {
      provider: 'v8',
      include: ['src/**/*.ts'],
      exclude: ['src/server.ts', 'src/types/**'],
      thresholds: {
        // BUILD_PROMPT Phase 8: overall ≥ 80 %.
        statements: 80,
        lines: 80,
        functions: 80,
        branches: 80,
        // BUILD_PROMPT Phase 2: core ≥ 90 %.
        'src/core/**': { statements: 90, lines: 90, functions: 90, branches: 85 },
        // CLAUDE.md §3: domain formulas are 100 % unit-tested.
        'src/domain/**': { statements: 100, lines: 100, functions: 100, branches: 100 },
      },
    },
  },
});

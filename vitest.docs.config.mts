import { defineConfig } from 'vitest/config';
import base from './vitest.config.mts';

/** `npm run docs:frontend` — regenerates docs/FRONTEND_API_GUIDE.md from real API calls. */
export default defineConfig({
  test: {
    ...base.test,
    include: ['tests/docs/frontend-guide.gen.ts'],
    coverage: { enabled: false },
  },
});

import path from 'node:path';

/** Project root: this file is src/admin/paths.ts (tsx) or dist/admin/paths.js (build). */
export const ROOT_DIR = path.resolve(__dirname, '..', '..');

export const ADMIN_PREFIX = '/admin';
export const ADMIN_VIEWS_DIR = path.join(ROOT_DIR, 'views');
export const ADMIN_PUBLIC_DIR = path.join(ROOT_DIR, 'public', 'admin');

/** Vendored front-end packages, served from node_modules (no CDN: strict CSP, offline LAN). */
export const VENDOR_DIRS = {
  bootstrap: path.join(ROOT_DIR, 'node_modules', 'bootstrap', 'dist'),
  icons: path.join(ROOT_DIR, 'node_modules', 'bootstrap-icons', 'font'),
  font: path.join(ROOT_DIR, 'node_modules', '@fontsource-variable', 'plus-jakarta-sans'),
} as const;

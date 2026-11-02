// CI guard: hard DELETE / TRUNCATE is forbidden anywhere in source, scripts or migrations (CLAUDE.md §9).
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

const ROOTS = ['src', 'scripts', 'db'];
const EXTS = ['.ts', '.js', '.mjs', '.sql'];
const PATTERN = /\b(delete\s+from|truncate\s+table)\b/i;
const SELF = 'check-no-delete.mjs';

function walk(dir) {
  let out = [];
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) out = out.concat(walk(p));
    else if (EXTS.some((e) => p.endsWith(e)) && !p.endsWith(SELF)) out.push(p);
  }
  return out;
}

const hits = [];
for (const root of ROOTS) {
  let files;
  try {
    files = walk(root);
  } catch {
    continue; // root folder not present yet
  }
  for (const file of files) {
    readFileSync(file, 'utf8')
      .split(/\r?\n/)
      .forEach((line, i) => {
        if (PATTERN.test(line)) hits.push(`${relative('.', file)}:${i + 1}: ${line.trim()}`);
      });
  }
}

if (hits.length) {
  console.error('Hard DELETE/TRUNCATE found (forbidden, use softDelete):\n' + hits.join('\n'));
  process.exit(1);
}
console.log('check-no-delete: OK');

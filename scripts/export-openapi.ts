/**
 * `npm run openapi` → docs/openapi.json (the Android contract) + docs/postman_collection.json.
 * The Postman collection is generated from the OpenAPI document: one folder per tag, bearer auth
 * from `{{accessToken}}` (set automatically by the Login request), example bodies from schemas.
 */
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { buildOpenApiDocument } from '../src/docs/openapi';

type Json = Record<string, unknown>;
type Schema = {
  type?: string;
  enum?: unknown[];
  default?: unknown;
  properties?: Record<string, Schema>;
  items?: Schema;
  anyOf?: Schema[];
  oneOf?: Schema[];
  allOf?: Schema[];
  nullable?: boolean;
  pattern?: string;
  format?: string;
  minimum?: number;
};

const SAMPLE_BY_NAME: Record<string, unknown> = {
  user_id: 'superadmin',
  password: 'ChangeMe@123',
  device_id: 'android-device-01',
  trans_id: '3f2504e0-4f89-41d3-9a0c-0305e82c3301',
  receipt_no: 'U03-00125',
  bill_no: 'U03-00007',
};

/** Small, deterministic example value for a JSON schema. */
export function sample(schema: Schema | undefined, name = '', depth = 0): unknown {
  if (!schema || depth > 6) return null;
  if (name in SAMPLE_BY_NAME) return SAMPLE_BY_NAME[name];
  if (schema.default !== undefined) return schema.default;
  if (schema.enum?.length) return schema.enum[0];
  const alt = schema.anyOf ?? schema.oneOf ?? schema.allOf;
  if (alt?.length) return sample(alt[0], name, depth + 1);
  switch (schema.type) {
    case 'object':
      return Object.fromEntries(
        Object.entries(schema.properties ?? {}).map(([k, v]) => [k, sample(v, k, depth + 1)]),
      );
    case 'array':
      return [sample(schema.items, name, depth + 1)];
    case 'integer':
    case 'number':
      return Math.max(1, schema.minimum ?? 1);
    case 'boolean':
      return false;
    case 'string':
      if (schema.pattern?.includes('\\d{2}:\\d{2}:\\d{2}$')) return '2026-05-01 10:00:00';
      if (schema.pattern?.startsWith('^\\d{4}-')) return '2026-05-01';
      if (schema.format === 'uuid') return SAMPLE_BY_NAME.trans_id;
      return name || 'string';
    default:
      return null;
  }
}

function toPostman(doc: ReturnType<typeof buildOpenApiDocument>) {
  const folders = new Map<string, Json[]>();
  for (const [path, methods] of Object.entries(doc.paths ?? {})) {
    for (const [method, raw] of Object.entries(methods as Record<string, Json>)) {
      const op = raw as {
        summary?: string;
        tags?: string[];
        security?: unknown[];
        parameters?: { name: string; in: string; required?: boolean; schema?: Schema }[];
        requestBody?: { content: Record<string, { schema: Schema }> };
      };
      const pmPath = path.replace(/\{(\w+)\}/g, ':$1');
      const params = op.parameters ?? [];
      const query = params
        .filter((p) => p.in === 'query')
        .map((p) => ({
          key: p.name,
          value: String(sample(p.schema, p.name) ?? ''),
          disabled: !p.required,
        }));
      const variable = params
        .filter((p) => p.in === 'path')
        .map((p) => ({ key: p.name, value: String(sample(p.schema, p.name) ?? '1') }));
      const json = op.requestBody?.content['application/json']?.schema;
      const multipart = op.requestBody?.content['multipart/form-data']?.schema;
      const isLogin = pmPath === '/api/v1/auth/login';

      const item: Json = {
        name: `${op.summary ?? pmPath}`,
        request: {
          method: method.toUpperCase(),
          ...(op.security && op.security.length === 0 && { auth: { type: 'noauth' } }),
          header: json ? [{ key: 'Content-Type', value: 'application/json' }] : [],
          url: {
            raw: `{{baseUrl}}${pmPath}`,
            host: ['{{baseUrl}}'],
            path: pmPath.split('/').filter(Boolean),
            ...(query.length && { query }),
            ...(variable.length && { variable }),
          },
          ...(json && {
            body: {
              mode: 'raw',
              raw: JSON.stringify(sample(json), null, 2),
              options: { raw: { language: 'json' } },
            },
          }),
          ...(multipart && {
            body: {
              mode: 'formdata',
              formdata: Object.keys(multipart.properties ?? {}).map((k) =>
                k === 'logo'
                  ? { key: k, type: 'file', src: [] }
                  : { key: k, value: '', type: 'text', disabled: true },
              ),
            },
          }),
        },
        ...(isLogin && {
          event: [
            {
              listen: 'test',
              script: {
                type: 'text/javascript',
                exec: [
                  'const d = pm.response.json().data;',
                  "if (d) { pm.collectionVariables.set('accessToken', d.accessToken); pm.collectionVariables.set('refreshToken', d.refreshToken); }",
                ],
              },
            },
          ],
        }),
      };
      const tag = op.tags?.[0] ?? 'Other';
      folders.set(tag, [...(folders.get(tag) ?? []), item]);
    }
  }
  return {
    info: {
      name: doc.info.title,
      description:
        'Generated from docs/openapi.json by `npm run openapi`. Run “Log in on a device” first.',
      schema: 'https://schema.getpostman.com/json/collection/v2.1.0/collection.json',
    },
    auth: { type: 'bearer', bearer: [{ key: 'token', value: '{{accessToken}}', type: 'string' }] },
    variable: [
      { key: 'baseUrl', value: 'http://localhost:4000' },
      { key: 'accessToken', value: '' },
      { key: 'refreshToken', value: '' },
    ],
    item: [...folders].map(([name, item]) => ({ name, item })),
  };
}

const doc = buildOpenApiDocument();
writeFileSync(join('docs', 'openapi.json'), `${JSON.stringify(doc, null, 2)}\n`);
writeFileSync(
  join('docs', 'postman_collection.json'),
  `${JSON.stringify(toPostman(doc), null, 2)}\n`,
);
const ops = Object.values(doc.paths ?? {}).reduce((n, m) => n + Object.keys(m as object).length, 0);
console.log(`docs/openapi.json (${ops} operations) and docs/postman_collection.json written.`);
// Importing the app modules opens timers (rate-limit stores); nothing else to wait for.
process.exit(0);

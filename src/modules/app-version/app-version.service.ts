import type { RowDataPacket } from 'mysql2/promise';
import { SYSTEM_CONTEXT, type RequestContext } from '../../core/context';
import { fetch, fetchOne, raw, save, softDelete } from '../../core/db/dml';
import { ApiError } from '../../core/http/ApiError';
import type { CreateVersionInput } from './app-version.schema';

const COLUMNS = [
  'id',
  'version_code',
  'version_name',
  'apk_url',
  'mandatory_flag',
  'release_notes',
  'created_at',
];

/**
 * Latest live release, plus — when the caller says which versionCode it runs — whether an update
 * exists and whether it is mandatory (any newer release flagged mandatory forces the update).
 */
export async function latestVersion(current?: number) {
  const { rows } = await fetch(
    'md_app_version',
    { columns: COLUMNS, orderBy: [{ column: 'version_code', dir: 'desc' }], page: 1, limit: 1 },
    SYSTEM_CONTEXT,
  );
  const latest = rows[0] ?? null;
  if (current === undefined || !latest) return { latest, update_available: null, mandatory: null };

  const [m] = await raw<RowDataPacket[]>(
    `SELECT COUNT(*) AS n FROM md_app_version
      WHERE is_deleted = 'N' AND mandatory_flag = 'Y' AND version_code > ?`,
    [current],
  );
  return {
    latest,
    update_available: Number(latest.version_code) > current,
    mandatory: Number(m?.n ?? 0) > 0,
  };
}

export async function createVersion(ctx: RequestContext, input: CreateVersionInput) {
  const { insertId } = await save('md_app_version', { ...input }, { flag: 'I' }, ctx);
  return fetchOne('md_app_version', { id: insertId }, ctx, undefined, { columns: COLUMNS });
}

/** Live releases, newest first (admin panel). */
export function listVersions(ctx: RequestContext, page: number, limit: number) {
  return fetch(
    'md_app_version',
    { columns: COLUMNS, orderBy: [{ column: 'version_code', dir: 'desc' }], page, limit },
    ctx,
  );
}

/**
 * Withdraws a release (soft delete): devices stop being offered it, and a mistakenly mandatory
 * release stops refusing logins (426) of older versions.
 */
export async function withdrawVersion(ctx: RequestContext, id: number) {
  const affected = await softDelete('md_app_version', { id }, ctx);
  if (!affected) throw ApiError.notFound('Release not found');
}

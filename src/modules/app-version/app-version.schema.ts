import { z } from 'zod';
import { ynEnum } from '../../core/validation/fragments';

export const versionQuery = z.object({
  /** The installed app's versionCode; enables update_available / mandatory. */
  current: z.coerce.number().int().min(0).optional(),
});

export const createVersionBody = z.object({
  version_code: z.coerce.number().int().min(1).max(2_100_000_000),
  version_name: z.string().trim().min(1).max(20),
  /** Absolute https URL or a path under /downloads/ (served by this API). */
  apk_url: z
    .string()
    .trim()
    .max(255)
    .refine(
      (v) => /^https:\/\/\S+$/.test(v) || /^\/downloads\/[\w.-]+\.apk$/.test(v),
      'https URL or /downloads/<file>.apk',
    ),
  mandatory_flag: ynEnum.default('N'),
  release_notes: z.string().max(5000).optional(),
});
export type CreateVersionInput = z.infer<typeof createVersionBody>;

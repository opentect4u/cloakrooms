import { z } from 'zod';
import { password } from '../users/users.schema';

export const loginBody = z.object({
  user_id: z.string().trim().min(1).max(100),
  password: z.string().min(1).max(200),
  device_id: z.string().trim().min(1).max(100),
  app_version: z.string().trim().max(20).optional(),
  /** Android versionCode. When a newer mandatory release exists, login is refused (426). */
  version_code: z.coerce.number().int().min(1).optional(),
});
export type LoginInput = z.infer<typeof loginBody>;

export const refreshBody = z.object({
  refreshToken: z.string().min(1).max(4096),
});

export const changePasswordBody = z
  .object({
    current_password: z.string().min(1).max(200),
    new_password: password,
  })
  .refine((b) => b.current_password !== b.new_password, {
    path: ['new_password'],
    message: 'must differ from the current password',
  });
export type ChangePasswordInput = z.infer<typeof changePasswordBody>;

import { z } from 'zod';

export const loginBody = z.object({
  user_id: z.string().trim().min(1).max(100),
  password: z.string().min(1).max(200),
  device_id: z.string().trim().min(1).max(100),
  app_version: z.string().trim().max(20).optional(),
});
export type LoginInput = z.infer<typeof loginBody>;

export const refreshBody = z.object({
  refreshToken: z.string().min(1).max(4096),
});

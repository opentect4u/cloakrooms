import { Algorithm, hash, verify } from '@node-rs/argon2';

// argon2id with OWASP-recommended minimums (19 MiB, t=2, p=1).
const OPTIONS = {
  algorithm: Algorithm.Argon2id,
  memoryCost: 19_456,
  timeCost: 2,
  parallelism: 1,
} as const;

export function hashPassword(plain: string): Promise<string> {
  return hash(plain, OPTIONS);
}

export async function verifyPassword(hashed: string, plain: string): Promise<boolean> {
  try {
    return await verify(hashed, plain);
  } catch {
    // Malformed hash in the DB must read as "wrong password", not a 500.
    return false;
  }
}

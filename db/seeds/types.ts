import type { Connection } from 'mysql2/promise';

export const SEED_USER = 'system';

export interface Seed {
  name: string;
  /** Must be idempotent: re-running never duplicates or overwrites existing rows. Returns a summary line. */
  run(conn: Connection): Promise<string>;
}

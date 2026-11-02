/**
 * Seed runner (BUILD_PROMPT Phase 1): `npm run seed` after `npm run migrate`.
 * All seeds run in one transaction and are idempotent, so re-running is always safe.
 */
import { idProofsSeed } from '../db/seeds/001_id_proofs';
import { gstSeed } from '../db/seeds/002_gst';
import { superAdminSeed } from '../db/seeds/003_super_admin';
import type { Seed } from '../db/seeds/types';
import { dbName, openConnection } from './lib/connection';

const SEEDS: Seed[] = [idProofsSeed, gstSeed, superAdminSeed];

async function main() {
  const conn = await openConnection();
  try {
    await conn.beginTransaction();
    try {
      for (const seed of SEEDS) {
        console.log(`  ${seed.name}: ${await seed.run(conn)}`);
      }
      await conn.commit();
    } catch (err) {
      await conn.rollback();
      throw err;
    }
    console.log(`Database ${dbName()}: seeds complete.`);
  } finally {
    await conn.end();
  }
}

main().catch((err: Error) => {
  console.error(`Seed failed: ${err.message}`);
  process.exit(1);
});

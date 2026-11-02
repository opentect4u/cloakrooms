import type { ResultSetHeader } from 'mysql2/promise';
import { SEED_USER, type Seed } from './types';

const ID_PROOFS: ReadonlyArray<[name: string, mask: 'Y' | 'N']> = [
  // TODO(open-point): Aadhaar legal confirmation (store/print last 4 digits only).
  ['Aadhaar Card', 'Y'],
  ['Driving Licence', 'N'],
  ['Passport', 'N'],
  ['PAN Card', 'N'],
  ['Ration Card', 'N'],
  ['Voter ID', 'N'],
];

export const idProofsSeed: Seed = {
  name: 'id_proofs',
  async run(conn) {
    let inserted = 0;
    for (const [name, mask] of ID_PROOFS) {
      const [res] = await conn.query<ResultSetHeader>(
        `INSERT INTO md_id_proof (id_proof_name, mask_flag, created_by, created_at)
         SELECT ?, ?, ?, UTC_TIMESTAMP() FROM DUAL
         WHERE NOT EXISTS (SELECT 1 FROM md_id_proof WHERE id_proof_name = ?)`,
        [name, mask, SEED_USER, name],
      );
      inserted += res.affectedRows;
    }
    return `${inserted} inserted, ${ID_PROOFS.length - inserted} already present`;
  },
};

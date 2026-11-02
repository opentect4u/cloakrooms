import type { ResultSetHeader } from 'mysql2/promise';
import { SEED_USER, type Seed } from './types';

// GST on cloak-room services from the GST rollout date (CLAUDE.md §11: 9 + 9 + 0).
const GST = { cgst_pct: '9.00', sgst_pct: '9.00', igst_pct: '0.00', effective_from: '2017-07-01' };

export const gstSeed: Seed = {
  name: 'gst',
  async run(conn) {
    const [res] = await conn.query<ResultSetHeader>(
      `INSERT INTO md_gst (cgst_pct, sgst_pct, igst_pct, effective_from, created_by, created_at)
       SELECT ?, ?, ?, ?, ?, UTC_TIMESTAMP() FROM DUAL
       WHERE NOT EXISTS (SELECT 1 FROM md_gst WHERE effective_from = ?)`,
      [GST.cgst_pct, GST.sgst_pct, GST.igst_pct, GST.effective_from, SEED_USER, GST.effective_from],
    );
    return res.affectedRows ? `inserted rate from ${GST.effective_from}` : 'already present';
  },
};

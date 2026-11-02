import type { ResultSetHeader, RowDataPacket } from 'mysql2/promise';
import { env } from '../../src/config/env';
import { hashPassword } from '../../src/core/security/password';
import { SEED_USER, type Seed } from './types';

export const superAdminSeed: Seed = {
  name: 'super_admin',
  async run(conn) {
    const [rows] = await conn.query<RowDataPacket[]>('SELECT id FROM md_user WHERE user_id = ?', [
      env.SUPER_ADMIN_USER_ID,
    ]);
    // Never reset an existing admin's password from a seed run.
    if (rows.length) return `"${env.SUPER_ADMIN_USER_ID}" already exists (password unchanged)`;

    const [res] = await conn.query<ResultSetHeader>(
      `INSERT INTO md_user
         (customer_id, user_id, user_name, user_code, password_hash, user_type, allow_flag,
          created_by, created_at)
       VALUES (NULL, ?, 'Synergic Super Admin', NULL, ?, 'S', 'Y', ?, UTC_TIMESTAMP())`,
      [env.SUPER_ADMIN_USER_ID, await hashPassword(env.SUPER_ADMIN_PASSWORD), SEED_USER],
    );
    return `created "${env.SUPER_ADMIN_USER_ID}" (id ${res.insertId})`;
  },
};

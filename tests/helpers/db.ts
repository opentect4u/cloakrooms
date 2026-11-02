import { randomUUID } from 'node:crypto';
import { save } from '../../src/core/db/dml';
import { superAdmin } from './context';

/** Creates a throw-away tenant in the test DB and returns its customer_id. */
export async function createCustomer(name = `Test ${randomUUID().slice(0, 8)}`): Promise<number> {
  const { insertId } = await save(
    'md_customer',
    {
      customer_name: name,
      customer_addr: 'Platform 1',
      pin_code: '700001',
      mobile_no: '9000000000',
      renewal_dt: '2099-12-31',
    },
    { flag: 'I' },
    superAdmin,
  );
  return insertId;
}

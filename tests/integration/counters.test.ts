import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { fetchOne, raw } from '../../src/core/db/dml';
import { closeDb, db } from '../../src/core/db/pool';
import { loadRegistry } from '../../src/core/db/registry';
import { periodKey } from '../../src/domain/numbering';
import {
  bumpCounter,
  getCurrentCounters,
  resetModeOf,
} from '../../src/modules/counters/counters.service';
import { seedCustomer, seedUser } from '../helpers/auth';
import { adminOf, superAdmin } from '../helpers/context';

beforeAll(async () => {
  await loadRegistry(db);
});
afterAll(async () => {
  await closeDb();
});

async function counterRow(userId: number, series: 'R' | 'B', key: string) {
  return fetchOne('md_user_counter', { user_id: userId, series, period_key: key }, superAdmin);
}

describe('bumpCounter', () => {
  it('creates the row, only ever raises it, and leaves it untouched on re-push', async () => {
    const cid = await seedCustomer();
    const user = await seedUser(cid);
    const ctx = adminOf(cid);
    const key = 'FY2026-27';

    await bumpCounter(ctx, { userId: user.id, series: 'R', periodKey: key, no: 5 });
    expect(await counterRow(user.id, 'R', key)).toMatchObject({
      last_no: 5,
      customer_id: cid,
      sync_flag: 'N',
    });

    await bumpCounter(ctx, { userId: user.id, series: 'R', periodKey: key, no: 3 });
    expect((await counterRow(user.id, 'R', key))?.last_no).toBe(5);

    // Mark as synced: an identical / lower re-push must not touch the row.
    await raw("UPDATE md_user_counter SET sync_flag = 'Y' WHERE user_id = ?", [user.id]);
    const before = await counterRow(user.id, 'R', key);
    await bumpCounter(adminOf(cid, 'someone-else'), {
      userId: user.id,
      series: 'R',
      periodKey: key,
      no: 5,
    });
    expect(await counterRow(user.id, 'R', key)).toEqual(before);

    await bumpCounter(adminOf(cid, 'pusher'), {
      userId: user.id,
      series: 'R',
      periodKey: key,
      no: 9,
    });
    expect(await counterRow(user.id, 'R', key)).toMatchObject({
      last_no: 9,
      updated_by: 'pusher',
      sync_flag: 'N',
    });

    // Bill series and other periods are independent.
    await bumpCounter(ctx, { userId: user.id, series: 'B', periodKey: key, no: 2 });
    await bumpCounter(ctx, { userId: user.id, series: 'R', periodKey: 'FY2027-28', no: 1 });
    expect((await counterRow(user.id, 'B', key))?.last_no).toBe(2);
    expect((await counterRow(user.id, 'R', key))?.last_no).toBe(9);
  });

  it('is safe under concurrent pushes', async () => {
    const cid = await seedCustomer();
    const user = await seedUser(cid);
    const ctx = adminOf(cid);
    await Promise.all(
      [7, 3, 12, 1, 12, 9, 4].map((no) =>
        bumpCounter(ctx, { userId: user.id, series: 'B', periodKey: '2026-05', no }),
      ),
    );
    expect((await counterRow(user.id, 'B', '2026-05'))?.last_no).toBe(12);
  });

  it('refuses users of another tenant, super admins and invalid numbers', async () => {
    const cid = await seedCustomer();
    const foreign = await seedUser(await seedCustomer());
    const bump = { userId: foreign.id, series: 'R' as const, periodKey: '2026-05', no: 1 };
    await expect(bumpCounter(adminOf(cid), bump)).rejects.toMatchObject({ status: 400 });
    await expect(bumpCounter(superAdmin, bump)).rejects.toMatchObject({ status: 403 });
    const own = await seedUser(cid);
    await expect(
      bumpCounter(adminOf(cid), { ...bump, userId: own.id, no: 0 }),
    ).rejects.toMatchObject({ status: 400 });
  });
});

describe('getCurrentCounters', () => {
  it('reads the current period for the reset mode (default Y)', async () => {
    const cid = await seedCustomer();
    const user = await seedUser(cid);
    const ctx = { ...adminOf(cid), userId: user.id };
    await bumpCounter(ctx, { userId: user.id, series: 'B', periodKey: periodKey('D'), no: 42 });

    expect(await getCurrentCounters(ctx, 'D')).toEqual({
      reset: 'D',
      period_key: periodKey('D'),
      receipt_last_no: 0,
      bill_last_no: 42,
    });
    expect((await getCurrentCounters(ctx, undefined)).period_key).toBe(periodKey('Y'));
    expect(resetModeOf('X')).toBe('Y');
    expect(resetModeOf('M')).toBe('M');
  });
});

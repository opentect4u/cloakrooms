import type { RequestContext } from '../../core/context';
import type { Executor } from '../../core/db/dml';
import { bumpCounter, type CounterBump } from '../counters/counters.service';

/** Keeps the highest pushed number per (user, series, period) so each counter is bumped once. */
export class CounterCollector {
  private readonly max = new Map<string, CounterBump>();

  add(bump: CounterBump): void {
    const key = `${bump.userId}|${bump.series}|${bump.periodKey}`;
    const prev = this.max.get(key);
    if (!prev || bump.no > prev.no) this.max.set(key, bump);
  }

  get size(): number {
    return this.max.size;
  }

  async flush(ctx: RequestContext, conn: Executor): Promise<void> {
    // Stable order keeps lock acquisition consistent across concurrent pushes (no deadlocks).
    const bumps = [...this.max.entries()].sort(([a], [b]) => a.localeCompare(b));
    for (const [, bump] of bumps) await bumpCounter(ctx, bump, conn);
    this.max.clear();
  }
}

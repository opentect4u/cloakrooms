import { logger } from './logger';

type Hook = { name: string; fn: () => Promise<void> | void };

const hooks: Hook[] = [];

/** Registers cleanup run on graceful shutdown, in reverse registration order (e.g. DB pool close). */
export function onShutdown(name: string, fn: Hook['fn']): void {
  hooks.push({ name, fn });
}

export async function runShutdownHooks(): Promise<void> {
  for (const hook of [...hooks].reverse()) {
    try {
      await hook.fn();
      logger.info({ hook: hook.name }, 'shutdown hook done');
    } catch (err) {
      logger.error({ err, hook: hook.name }, 'shutdown hook failed');
    }
  }
}

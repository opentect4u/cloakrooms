import { createClient } from 'redis';
import { env } from '../config/env';
import { logger } from './logger';
import { onShutdown } from './shutdown';

const makeClient = (url: string) => createClient({ url });
type Redis = ReturnType<typeof makeClient>;

let client: Redis | undefined;

/** Shared Redis client, only when REDIS_URL is set (rate-limit store). Connects lazily. */
export function getRedis(): Redis | undefined {
  if (!env.REDIS_URL) return undefined;
  /* v8 ignore start -- needs a Redis server */
  if (!client) {
    const c = makeClient(env.REDIS_URL);
    c.on('error', (err) => logger.error({ err }, 'redis error'));
    // Commands issued before the connection is ready are queued by the client.
    c.connect().catch((err) => logger.error({ err }, 'redis connect failed'));
    onShutdown('redis', async () => {
      if (c.isOpen) await c.quit();
    });
    client = c;
  }
  return client;
  /* v8 ignore stop */
}

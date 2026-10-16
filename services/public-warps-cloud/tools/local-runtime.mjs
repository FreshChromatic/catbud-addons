import { Miniflare, convertV4MiniflareOptions } from 'miniflare';
import { readFile, readdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const root = new URL('../', import.meta.url);
import { localRateLimits } from '../src/burst-guard.mjs';

export async function localRuntime(port, stateDirectory = new URL('.local-state/d1', root)) {
  const runtime = new Miniflare(
    convertV4MiniflareOptions({
      modules: true,
      rootPath: fileURLToPath(root),
      scriptPath: fileURLToPath(new URL('build/test-worker/worker.js', root)),
      compatibilityDate: '2026-10-11',
      compatibilityFlags: ['nodejs_compat'],
      host: '127.0.0.1',
      ...(port ? { port } : {}),
      d1Databases: { DB: 'catbud-public-warps-local' },
      ratelimits: localRateLimits,
      resourcePersistencePath: fileURLToPath(stateDirectory),
      bindings: {
        POSTING_ENABLED: 'true',
        VERIFY_DAILY_LIMIT: '2600',
        PREVIEW_DAILY_LIMIT: '1000',
        STORAGE_LIMIT_BYTES: '335544320',
      },
    }),
  );
  try {
    const database = await runtime.getD1Database('DB');
    await database.exec('CREATE TABLE IF NOT EXISTS local_migrations (name TEXT PRIMARY KEY)');
    const migrations = (await readdir(new URL('migrations/', root)))
      .filter((name) => name.endsWith('.sql'))
      .sort();
    for (const name of migrations) {
      const applied = await database
        .prepare('SELECT name FROM local_migrations WHERE name=?')
        .bind(name)
        .first();
      if (!applied) {
        const sql = await readFile(new URL('migrations/' + name, root), 'utf8');
        await database.exec(sql.replaceAll('\n', ' '));
        await database.prepare('INSERT INTO local_migrations(name) VALUES (?)').bind(name).run();
      }
    }
    return { runtime, database };
  } catch (error) {
    await runtime.dispose();
    throw error;
  }
}

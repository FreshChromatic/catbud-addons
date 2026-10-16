import { randomBytes } from 'node:crypto';
import { resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { DatabaseBackup } from './database-backup.mjs';
import { mkdir, writeFile, readFile, stat } from 'node:fs/promises';
import { localRuntime } from './local-runtime.mjs';
import { WarpApplication } from '../src/worker.mjs';
import { StorageCapacity } from '../src/storage-capacity.mjs';
import { DatabaseUsage, MeasuredDatabase } from '../src/database-usage.mjs';

const args = process.argv.slice(2);
const serverIndex = args.indexOf('--server');
let serverId = 'catbud';
if (serverIndex !== -1) {
  serverId = args[serverIndex + 1];
  args.splice(serverIndex, 2);
}
if (!['catbud', 'catrealm'].includes(serverId)) {
  throw new Error('--server must be catbud or catrealm');
}
const stateIndex = args.indexOf('--state');
const stateDirectory =
  stateIndex === -1
    ? new URL('../.local-state/d1', import.meta.url)
    : pathToFileURL(resolve(args[stateIndex + 1] ?? ''));
if (stateIndex !== -1) {
  if (!args[stateIndex + 1]) {
    throw new Error('--state requires a directory.');
  }
  args.splice(stateIndex, 2);
}
const [command, target, destination] = args;
const commands = [
  'serve',
  'backup',
  'recover',
  'migrate',
  'status',
  'list',
  'grant',
  'revoke',
  'approve',
  'reject',
  'disable',
  'restore',
  'rollback',
  'unpublish',
  'metadata',
];
if (!commands.includes(command)) {
  console.error(
    'Usage: node tools/local.mjs ' + commands.join('|') + ' [player UUID or contributor ID]',
  );
  process.exitCode = 1;
} else if (command === 'recover') {
  if (!target || !destination) {
    throw new Error('recover requires a backup file and a new state directory.');
  }
  await DatabaseBackup.recover(resolve(target), resolve(destination));
  console.log('Backup recovered to a new directory: ' + resolve(destination));
} else {
  const { runtime, database: originalDatabase } = await localRuntime(
    command === 'serve' ? 8788 : undefined,
    stateDirectory,
  );
  const usage = new DatabaseUsage('admin');
  const database =
    command === 'serve' ? originalDatabase : new MeasuredDatabase(originalDatabase, usage);
  const adminCredential = randomBytes(32).toString('hex');
  const application = new WarpApplication({
    DB: database,
    VERIFY_DAILY_LIMIT: '2600',
    ADMIN_CREDENTIAL: adminCredential,
  });
  try {
    if (command === 'backup') {
      if (!target) {
        throw new Error('backup requires a new destination directory.');
      }
      const file = await DatabaseBackup.create(fileURLToPath(stateDirectory), resolve(target));
      console.log('Database backup saved: ' + file);
    } else if (command === 'serve') {
      console.log('Local public-warps service: ' + (await runtime.ready));
      let checking = false;
      const interval = setInterval(async () => {
        if (checking) {
          return;
        }
        checking = true;
        try {
          await new WarpApplication({ DB: database, VERIFY_DAILY_LIMIT: '2600' }).scheduled();
        } catch {
          console.error('Scheduled verification failed; existing qualification is retained.');
        } finally {
          checking = false;
        }
      }, 60000);
      await new Promise((resolve) => {
        process.once('SIGINT', resolve);
        process.once('SIGTERM', resolve);
      });
      clearInterval(interval);
    } else if (command === 'migrate') {
      console.log('Local database migrations are current.');
    } else {
      let metadata;
      if (command === 'metadata') {
        if (!destination || (await stat(destination)).size > 8192) {
          throw new Error('metadata requires a JSON file of at most 8 KiB.');
        }
        metadata = JSON.parse(await readFile(destination, 'utf8'));
      }
      const response = await application.handle(
        new Request('https://local.test/internal/admin', {
          method: 'POST',
          headers: {
            Authorization: 'Bearer ' + adminCredential,
            'Content-Type': 'application/json',
          },
          body: JSON.stringify({ command, target, serverId, metadata }),
        }),
      );
      const result = await response.json();
      if (!response.ok) {
        throw new Error(result.error ?? 'Local administration failed');
      }
      if (command === 'grant') {
        const directory = new URL('../.local-state/credentials/', import.meta.url);
        await mkdir(directory, { recursive: true });
        const output = new URL(target + '-contributor-credential.json', directory);
        await writeFile(output, JSON.stringify(result, null, 2), { mode: 0o600 });
        console.log('Credential saved locally: ' + output.pathname);
      } else {
        console.log(JSON.stringify(result, null, 2));
      }
    }
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  } finally {
    if (command !== 'serve') {
      console.log(JSON.stringify(usage.report()));
    }
    await runtime.dispose();
  }
}

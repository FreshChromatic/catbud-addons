import { readFile, writeFile, mkdir, stat, copyFile } from 'node:fs/promises';
import { resolve } from 'node:path';

const directory = new URL('../.local-state/cloud/', import.meta.url);
const args = process.argv.slice(2);
const serverOption = args.findIndex((value) => value === '--server');
let serverId = 'catbud';
if (serverOption >= 0) {
  serverId = args[serverOption + 1];
  args.splice(serverOption, 2);
}
if (!['catbud', 'catrealm'].includes(serverId)) {
  throw new Error('--server must be catbud or catrealm');
}
const [command, target, argument] = args;
const supported = [
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
  'configure',
  'contributor',
];
if (!supported.includes(command)) {
  throw new Error(
    'Usage: npm run cloud -- ' +
      supported.join('|') +
      ' [target] [metadata file or contributor ID]',
  );
}
const deployment = JSON.parse(await readFile(new URL('deployment.json', directory), 'utf8'));
const endpoint = new URL(deployment.serviceUrl);
if (
  endpoint.protocol !== 'https:' ||
  endpoint.username ||
  endpoint.password ||
  endpoint.search ||
  endpoint.hash
) {
  throw new Error('Cloud endpoint must be a plain HTTPS URL');
}
if (command === 'configure') {
  if (!target || !/^[A-Za-z0-9_-]{1,64}$/.test(argument ?? '')) {
    throw new Error('configure requires a game settings file and contributor ID');
  }
  const credential = JSON.parse(
    await readFile(new URL(argument + '-contributor-credential.json', directory), 'utf8'),
  );
  const destination = resolve(target);
  const settings = JSON.parse(await readFile(destination, 'utf8'));
  if (!/^[0-9a-f]{64}$/.test(credential.contributorCredential ?? '')) {
    throw new Error('Invalid contributor credential');
  }
  await copyFile(destination, destination + '.before-cloud-' + Date.now());
  await writeFile(
    destination,
    JSON.stringify(
      {
        ...settings,
        serviceUrl: endpoint.origin,
        protocol: 'cloud',
        contributorCredential: credential.contributorCredential,
      },
      null,
      2,
    ),
    { mode: 0o600 },
  );
  console.log('Game cloud settings saved; restart the game: ' + destination);
} else {
  const administrator = JSON.parse(
    await readFile(new URL('administrator.json', directory), 'utf8'),
  );
  if (!/^[0-9a-f]{64}$/.test(administrator.credential ?? '')) {
    throw new Error('Invalid administrator credential');
  }
  let metadata;
  if (command === 'metadata' || command === 'contributor') {
    if (!argument || (await stat(argument)).size > 8192) {
      throw new Error('metadata requires a JSON file of at most 8 KiB');
    }
    metadata = JSON.parse(await readFile(argument, 'utf8'));
  }
  const response = await fetch(new URL('/internal/admin', endpoint), {
    method: 'POST',
    redirect: 'error',
    headers: {
      Authorization: 'Bearer ' + administrator.credential,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      requestId: crypto.randomUUID(),
      command,
      target,
      serverId,
      metadata,
      ...(command === 'contributor' ? metadata : {}),
    }),
    signal: AbortSignal.timeout(30000),
  });
  if (!response.ok) {
    throw new Error('Cloud administration failed: HTTP ' + response.status);
  }
  const result = await response.json();
  if (command === 'grant') {
    if (
      !/^[A-Za-z0-9_-]{1,64}$/.test(target ?? '') ||
      !/^[0-9a-f]{64}$/.test(result.contributorCredential ?? '')
    ) {
      throw new Error('Invalid contributor response');
    }
    await mkdir(directory, { recursive: true });
    const destination = new URL(target + '-contributor-credential.json', directory);
    await writeFile(destination, JSON.stringify(result, null, 2), { mode: 0o600 });
    console.log('Contributor credential saved privately: ' + destination.pathname);
  } else {
    console.log(JSON.stringify(result, null, 2));
  }
}

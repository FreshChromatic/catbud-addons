import { createServer } from 'node:http';
import { writeFile } from 'node:fs/promises';
import { localRuntime } from './local-runtime.mjs';
import { WarpApplication } from '../src/worker.mjs';
import { ServerScope } from '../src/server-scope.mjs';
import { identity } from '../src/protocol.mjs';

const [serverId, uuid, name] = process.argv.slice(2);
ServerScope.require(serverId);
const player = identity({ uuid, name });
const origin = 'http://127.0.0.1:8789';
const state = new URL('../.local-state/owner-verification/d1/', import.meta.url);
const { runtime, database } = await localRuntime(undefined, state);
const now = Date.now();
await database
  .prepare(
    `INSERT INTO server_warps
  (server_id,uuid,name,published,pass_state,checked_at,next_check,availability,description)
  VALUES (?,?,?,0,'active',?,?,'available','獨立本機驗收傳點')
  ON CONFLICT(server_id,uuid) DO UPDATE SET pass_state='active',checked_at=excluded.checked_at,next_check=excluded.next_check`,
  )
  .bind(serverId, player.uuid, player.name, now, now + 3600000)
  .run();
await database
  .prepare('UPDATE server_warps SET published=0 WHERE server_id=? AND uuid=? AND revision=0')
  .bind(serverId, player.uuid)
  .run();
await database
  .prepare('UPDATE server_warps SET published=1 WHERE server_id=? AND uuid=? AND published=0')
  .bind(serverId, player.uuid)
  .run();

const qualification = async (url) => {
  if (String(url) !== ServerScope.playerUrl(serverId, player.uuid))
    return new Response(null, { status: 404 });
  return Response.json({ ...player, pass: true });
};

async function status() {
  const warp = await database
    .prepare(
      'SELECT name,display_name,description,revision FROM server_warps WHERE server_id=? AND uuid=?',
    )
    .bind(serverId, player.uuid)
    .first();
  const verified = await database
    .prepare(
      "SELECT COUNT(*) AS count FROM server_audit WHERE server_id=? AND uuid=? AND action='owner_metadata' AND actor=?",
    )
    .bind(serverId, player.uuid, 'player:' + player.uuid)
    .first('count');
  return { isolated: true, serverId, uuid: player.uuid, officialAccountSaved: verified > 0, warp };
}

const server = createServer(async (incoming, outgoing) => {
  try {
    const url = new URL(incoming.url, origin);
    if (url.pathname === '/test-status' && incoming.method === 'GET') {
      outgoing.setHeader('Content-Type', 'application/json');
      outgoing.end(JSON.stringify(await status()));
      return;
    }
    const prefix = `/v2/servers/${serverId}/`;
    const route = url.pathname.slice(prefix.length);
    const allowed =
      url.pathname.startsWith(prefix) &&
      [
        'warps',
        'changes',
        'owner/challenge',
        'owner/verify',
        'owner/warp',
        'owner/metadata',
      ].includes(route);
    if (!allowed) {
      outgoing.writeHead(404).end();
      return;
    }
    const chunks = [];
    let size = 0;
    for await (const chunk of incoming) {
      size += chunk.length;
      if (size > 8192) {
        outgoing.writeHead(413).end();
        return;
      }
      chunks.push(chunk);
    }
    const request = new Request(url, {
      method: incoming.method,
      headers: incoming.headers,
      ...(!['GET', 'HEAD'].includes(incoming.method) ? { body: Buffer.concat(chunks) } : {}),
    });
    const app = new WarpApplication(
      { DB: database, POSTING_ENABLED: 'true' },
      Date.now(),
      qualification,
      () => {},
    );
    const response = await app.handle(request);
    outgoing.writeHead(response.status, Object.fromEntries(response.headers));
    outgoing.end(Buffer.from(await response.arrayBuffer()));
    if (route === 'owner/metadata' && response.status === 200) {
      await writeFile(
        new URL('../.local-state/owner-verification/result.json', import.meta.url),
        JSON.stringify({ completedAt: new Date().toISOString(), ...(await status()) }, null, 2) +
          '\n',
      );
      console.log('Official account saved metadata in the isolated local database.');
    }
  } catch {
    if (!outgoing.headersSent) outgoing.writeHead(500);
    outgoing.end();
    console.error('Isolated verification request failed. No credentials were logged.');
  }
});

server.on('error', async (error) => {
  console.error('Cannot start isolated verification server:', error.code);
  await runtime.dispose();
  process.exitCode = 1;
});
server.listen(8789, '127.0.0.1', () => {
  console.log('Isolated owner verification:', origin);
  console.log('Test account:', player.name, player.uuid, serverId);
  console.log(
    'Only pass eligibility is simulated. Official certificate verification is unchanged.',
  );
});
async function stop() {
  server.close();
  await runtime.dispose();
}
process.once('SIGINT', stop);
process.once('SIGTERM', stop);

import { createServer } from 'node:http';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { localRuntime } from '../tools/local-runtime.mjs';
import { WarpApplication } from '../src/worker.mjs';
import { PublicationService } from '../src/publication-service.mjs';
import { ManagementActions } from '../src/management-actions.mjs';
import { ServerQualification } from '../src/server-qualification.mjs';
import { Budgets } from '../src/budgets.mjs';
import { sha256 } from '../src/protocol.mjs';

const player = { uuid: 'a29a6851-0d71-411c-953c-b87bab0d12f3', name: 'Neko_1212' };
const credential = 'a'.repeat(64);
const directory = await mkdtemp(join(tmpdir(), 'catbud-cloud-client-'));
const { runtime, database } = await localRuntime(undefined, pathToFileURL(directory));
await database
  .prepare('INSERT INTO contributors(id,token_hash,created_at) VALUES (?,?,?)')
  .bind('client-fixture', await sha256(credential), Date.now())
  .run();
await database
  .prepare(
    "INSERT INTO contributor_servers VALUES ('client-fixture','catbud'),('client-fixture','catrealm')",
  )
  .run();
let pass = true;
const application = () =>
  new WarpApplication(
    { DB: database, POSTING_ENABLED: 'true' },
    Date.now(),
    async (url) =>
      Response.json({ ...player, pass: new URL(url).hostname === 'api.catbud.net' ? pass : false }),
    () => {},
  );
const server = createServer(async (incoming, outgoing) => {
  try {
    const chunks = [];
    let size = 0;
    for await (const chunk of incoming) {
      size += chunk.length;
      if (size > 131072) {
        outgoing.writeHead(413).end();
        return;
      }
      chunks.push(chunk);
    }
    const path = incoming.url;
    if (path.startsWith('/fixture/')) {
      if (incoming.headers.authorization !== 'Bearer ' + credential) {
        outgoing.writeHead(401).end();
        return;
      }
      if (path === '/fixture/approve') {
        const submission = await database
          .prepare(
            "SELECT id FROM server_submissions WHERE server_id='catbud' AND uuid=? AND state='pending' AND kind='preview'",
          )
          .bind(player.uuid)
          .first();
        const publication = new PublicationService(database);
        const pending = await publication.pending(submission.id);
        await publication.approve(pending.id, pending.current_revision, 'fixture-administrator');
      } else if (path === '/fixture/disable') {
        const warp = await database
          .prepare("SELECT revision FROM server_warps WHERE server_id='catbud' AND uuid=?")
          .bind(player.uuid)
          .first();
        await new ManagementActions(database, {}, Date.now()).warp(
          'catbud',
          player.uuid,
          'disable',
          { expectedRevision: warp.revision },
          'fixture-administrator',
        );
        await database
          .prepare("UPDATE contributors SET role='trusted' WHERE id='client-fixture'")
          .run();
      } else if (path === '/fixture/no-pass') {
        pass = false;
        await new ServerQualification(
          database,
          new Budgets(database),
          {},
          application().qualifications.fetcher,
        ).check('catbud', player, true);
      } else {
        outgoing.writeHead(404).end();
        return;
      }
      outgoing.writeHead(200).end('{}');
      return;
    }
    const request = new Request('http://127.0.0.1' + path, {
      method: incoming.method,
      headers: incoming.headers,
      ...(size ? { body: Buffer.concat(chunks) } : {}),
    });
    const response = await application().handle(request);
    outgoing.writeHead(response.status, Object.fromEntries(response.headers.entries()));
    outgoing.end(Buffer.from(await response.arrayBuffer()));
  } catch {
    outgoing.writeHead(500).end('Fixture failed');
  }
});
await new Promise((resolveReady) => server.listen(0, '127.0.0.1', resolveReady));
process.stdout.write(JSON.stringify({ port: server.address().port }) + '\n');
await new Promise((resolveStop) => {
  process.stdin.once('data', resolveStop);
  process.stdin.once('end', resolveStop);
});
await new Promise((resolveClosed) => server.close(resolveClosed));
await runtime.dispose();
const expectedParent = resolve(tmpdir());
if (!resolve(directory).startsWith(join(expectedParent, 'catbud-cloud-client-'))) {
  throw new Error('Unexpected fixture cleanup directory');
}
await rm(directory, { recursive: true, force: true });

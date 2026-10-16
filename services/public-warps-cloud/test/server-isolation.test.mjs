import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { gzipSync } from 'node:zlib';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';
import { Budgets } from '../src/budgets.mjs';
import { sha256 } from '../src/protocol.mjs';
import { ServerQualification } from '../src/server-qualification.mjs';
import { ServerWarpList } from '../src/server-warp-list.mjs';
import { ContributorPolicy } from '../src/contributor-policy.mjs';
import { PublicationService } from '../src/publication-service.mjs';
import { WarpApplication } from '../src/worker.mjs';
import { ManagementQueries } from '../src/management-queries.mjs';
import { ManagementActions } from '../src/management-actions.mjs';
import { ServerMaintenance } from '../src/server-maintenance.mjs';
import { ManagementStatus } from '../src/management-status.mjs';
import { requireManagementRequest } from '../src/management-request.mjs';

test('legacy migration, server qualifications, scoped access and publication remain isolated', async (context) => {
  const runtime = new Miniflare(
    convertV4MiniflareOptions({
      modules: true,
      script: 'export default {fetch(){return new Response("ok")}}',
      compatibilityDate: '2026-10-11',
      d1Databases: ['DB'],
    }),
  );
  context.after(() => runtime.dispose());
  const database = await runtime.getD1Database('DB');
  const migrations = [
    '0001_public_warps.sql',
    '0002_review_guards.sql',
    '0003_change_retention.sql',
  ];
  for (const migration of migrations) {
    const sql = await readFile(new URL('../migrations/' + migration, import.meta.url), 'utf8');
    await database.exec(sql.replaceAll('\n', ' '));
  }
  const uuid = '257205c3-c703-4034-8af8-14ee22720ceb';
  const player = { uuid, name: 'YuzuCha323' };
  const credential = 'a'.repeat(64);
  await database
    .prepare('INSERT INTO warps(uuid,name,published) VALUES (?,?,1)')
    .bind(uuid, player.name)
    .run();
  await database
    .prepare('INSERT INTO contributors(id,token_hash,created_at) VALUES (?,?,1)')
    .bind('owner', await sha256(credential))
    .run();
  await database.exec(
    (
      await readFile(new URL('../migrations/0004_server_namespaces.sql', import.meta.url), 'utf8')
    ).replaceAll('\n', ' '),
  );
  await database.exec(
    (
      await readFile(new URL('../migrations/0005_namespace_cutover.sql', import.meta.url), 'utf8')
    ).replaceAll('\n', ' '),
  );
  await database.exec(
    (
      await readFile(new URL('../migrations/0006_management_requests.sql', import.meta.url), 'utf8')
    ).replaceAll('\n', ' '),
  );
  assert.equal(
    await database
      .prepare("SELECT COUNT(*) AS n FROM server_warps WHERE server_id='catbud'")
      .first('n'),
    1,
  );
  assert.equal(
    await database
      .prepare("SELECT COUNT(*) AS n FROM server_warps WHERE server_id='catrealm'")
      .first('n'),
    0,
  );
  await database
    .prepare('INSERT INTO server_warps(server_id,uuid,name) VALUES (?,?,?)')
    .bind('catrealm', uuid, player.name)
    .run();

  await database.exec(
    (
      await readFile(new URL('../migrations/0007_owner_editing.sql', import.meta.url), 'utf8')
    ).replaceAll('\n', ' '),
  );
  const request = new Request('https://warps.test', {
    headers: { Authorization: 'Bearer ' + credential },
  });
  const policy = new ContributorPolicy(database);
  assert.equal((await policy.require(request, 'catbud')).trusted, false);
  await assert.rejects(policy.require(request, 'catrealm'), (error) => error.status === 403);
  await database.prepare("UPDATE contributors SET role='trusted' WHERE id='owner'").run();
  assert.equal((await policy.require(request, 'catbud')).trusted, true);

  const now = Date.now();
  const visited = [];
  const env = { DB: database };
  const qualification = new ServerQualification(
    database,
    new Budgets(database, now),
    env,
    async function (url) {
      assert.equal(this, globalThis);
      visited.push(new URL(url).hostname);
      return Response.json({ ...player, pass: new URL(url).hostname === 'api.catbud.net' });
    },
  );
  assert.equal((await qualification.check('catbud', player)).active, true);
  assert.equal((await qualification.check('catrealm', player)).active, false);
  assert.deepEqual(visited, ['api.catbud.net', 'api.catrealm.net']);
  await qualification.check('catrealm', player);
  assert.equal(visited.length, 2, 'qualification caches are independent and reusable');

  const current = await database
    .prepare("SELECT revision FROM server_warps WHERE server_id='catbud' AND uuid=?")
    .bind(uuid)
    .first('revision');
  await database
    .prepare(
      `INSERT INTO server_submissions(id,server_id,uuid,contributor,kind,base_revision,created_at,expires_at)
    VALUES ('valid-launch','catbud',?,'owner','launch',?,?,?)`,
    )
    .bind(uuid, current, now, now + 60000)
    .run();
  const publication = new PublicationService(database, now, env);
  assert.equal((await publication.approve('valid-launch', current, 'owner', true)).published, true);
  assert.equal(
    (await publication.approve('valid-launch', current, 'owner', true)).published,
    true,
    'retry is idempotent',
  );
  assert.equal(
    await database
      .prepare("SELECT COUNT(*) AS n FROM server_audit WHERE action='automatic_publish'")
      .first('n'),
    1,
  );
  await database
    .prepare(
      `INSERT INTO server_submissions(id,server_id,uuid,contributor,kind,created_at,expires_at)
    VALUES ('other-server','catrealm',?,'owner','launch',?,?)`,
    )
    .bind(uuid, now, now + 60000)
    .run();
  await assert.rejects(
    publication.approve('other-server', 0, 'owner', true),
    (error) => error.status === 409,
  );
  assert.equal(
    await database
      .prepare("SELECT state FROM server_submissions WHERE id='other-server'")
      .first('state'),
    'pending',
  );

  const lists = new ServerWarpList(database, now);
  const catbud = await (
    await lists.list('catbud', new Request('https://warps.test/v2/servers/catbud/warps'))
  ).json();
  const catrealm = await (
    await lists.list('catrealm', new Request('https://warps.test/v2/servers/catrealm/warps'))
  ).json();
  assert.equal(catbud.warps.length, 1);
  assert.equal(catrealm.warps.length, 0);
  assert.equal(catrealm.revision, 0);
  assert.ok(catbud.revision > 0);
  const changes = await (
    await lists.changes(
      'catbud',
      new Request('https://warps.test/v2/servers/catbud/changes?since=0'),
    )
  ).json();
  assert.equal(changes.serverId, 'catbud');
  assert.equal(changes.revision, catbud.revision);
  assert.ok(changes.etag.startsWith('"catbud:'));
  assert.ok(changes.changes.every((change) => Number.isSafeInteger(change.revision)));
  const application = new WarpApplication(
    { DB: database, POSTING_ENABLED: 'true' },
    now,
    async () => Response.json({ ...player, pass: true }),
    () => {},
  );
  const post = (serverId, body, authorized = true) =>
    application.handle(
      new Request(`https://warps.test/v2/servers/${serverId}/observations`, {
        method: 'POST',
        headers: authorized ? { Authorization: 'Bearer ' + credential } : {},
        body: JSON.stringify(body),
      }),
    );
  assert.equal(
    (await post('catbud', { ...player, reason: 'successful_arrival' }, false)).status,
    401,
  );
  assert.equal((await post('catrealm', { ...player, reason: 'successful_arrival' })).status, 403);
  const arrival = await post('catbud', {
    ...player,
    reason: 'successful_arrival',
    submissionId: crypto.randomUUID(),
  });
  assert.equal(arrival.status, 200);
  assert.equal((await arrival.json()).state, 'published');
  await database.prepare("UPDATE contributors SET role='contributor' WHERE id='owner'").run();
  const observationId = crypto.randomUUID();
  const ordinary = await post('catbud', {
    ...player,
    reason: 'valid_launch',
    submissionId: observationId,
  });
  assert.equal(ordinary.status, 202);
  assert.equal((await ordinary.json()).state, 'pending_review');
  const bytes = gzipSync(
    JSON.stringify({
      version: 4,
      width: 50,
      height: 50,
      depth: 50,
      dimension: 'minecraft:overworld',
      palette: ['minecraft:stone'],
      blocks: Array(125000).fill(0),
      entities: [],
      eyeX: 25.5,
      eyeY: 26.62,
      eyeZ: 25.5,
      viewYaw: 90,
      viewPitch: 0,
    }),
  );
  const uploadRequest = (route, body) =>
    application.handle(
      new Request('https://warps.test/v2/servers/catbud/' + route, {
        method: 'POST',
        headers: { Authorization: 'Bearer ' + credential },
        body,
      }),
    );
  const preflight = await uploadRequest(
    'submissions/preflight',
    JSON.stringify({
      ...player,
      hash: await sha256(bytes),
      size: 65536,
    }),
  );
  assert.equal(preflight.status, 200, await preflight.clone().text());
  const ticket = (await preflight.json()).ticket;
  const uploaded = await uploadRequest('uploads/' + ticket, bytes);
  assert.equal(uploaded.status, 202, await uploaded.clone().text());
  const candidate = await uploaded.json();
  const replay = await post('catbud', {
    ...player,
    reason: 'valid_launch',
    submissionId: observationId,
  });
  assert.equal(replay.status, 202);
  assert.equal((await replay.json()).state, 'rejected');
  const statusResponse = await application.handle(
    new Request(`https://warps.test/v2/servers/catbud/submissions/${candidate.submissionId}`, {
      headers: { Authorization: 'Bearer ' + credential },
    }),
  );
  assert.equal(statusResponse.status, 200);
  assert.equal((await statusResponse.json()).state, 'pending_review');

  assert.equal(candidate.state, 'pending_review');
  const management = new ManagementQueries(database, now);
  assert.equal((await management.preview('catbud', candidate.hash)).status, 200);
  await assert.rejects(
    management.preview('catrealm', candidate.hash),
    (error) => error.status === 404,
  );
  await assert.rejects(
    management.submission('catrealm', candidate.submissionId),
    (error) => error.status === 404,
  );
  await assert.rejects(lists.preview('catbud', candidate.hash), (error) => error.status === 404);
  const pending = await publication.pending(candidate.submissionId);
  await publication.approve(pending.id, pending.current_revision, 'administrator');
  assert.equal((await lists.preview('catbud', candidate.hash)).status, 200);
  await assert.rejects(lists.preview('catrealm', candidate.hash), (error) => error.status === 404);
  const repeated = await uploadRequest('uploads/' + ticket, bytes);
  assert.equal((await repeated.json()).published, true);
  assert.equal(
    await database.prepare("SELECT value FROM metadata WHERE key='reserved_bytes'").first('value'),
    0,
  );
  const actions = new ManagementActions(database, env, now);
  const warp = await management.warp('catbud', uuid);
  await actions.warp(
    'catbud',
    uuid,
    'metadata',
    {
      expectedRevision: warp.revision,
      metadata: { displayName: '測試傳點', description: '用途說明', tags: ['商店'] },
    },
    'owner@example.test',
  );
  assert.equal((await management.warp('catbud', uuid)).display_name, '測試傳點');
  assert.equal((await management.warp('catrealm', uuid)).display_name, null);
  await assert.rejects(
    actions.warp(
      'catbud',
      uuid,
      'unpublish',
      { expectedRevision: warp.revision },
      'owner@example.test',
    ),
    (error) => error.status === 409,
  );
  await actions.contributor(
    'owner',
    { role: 'contributor', enabled: false, servers: ['catbud'] },
    'owner@example.test',
  );
  await assert.rejects(policy.require(request, 'catbud'), (error) => error.status === 403);
  const legacyList = await application.handle(new Request('https://warps.test/v1/warps'));
  assert.equal(legacyList.status, 200);
  assert.equal((await legacyList.json()).serverId, 'catbud');
  const garbageHash = 'f'.repeat(64);
  await database
    .prepare('INSERT INTO previews(hash,bytes,size,created_at) VALUES (?,?,?,?)')
    .bind(garbageHash, [1, 2, 3], 3, now)
    .run();
  await new ServerMaintenance(database, now).clean();
  assert.equal(
    await database.prepare('SELECT hash FROM previews WHERE hash=?').bind(garbageHash).first(),
    null,
  );
  assert.equal(
    (await lists.preview('catbud', candidate.hash)).status,
    200,
    'maintenance retains scoped published previews',
  );
  assert.equal(
    await database.prepare("SELECT value FROM metadata WHERE key='stored_bytes'").first('value'),
    await database.prepare('SELECT COALESCE(SUM(size),0) AS size FROM previews').first('size'),
  );
  const scheduledHosts = [];
  await database
    .prepare("UPDATE server_warps SET published=1,first_valid_at=? WHERE server_id='catrealm'")
    .bind(now)
    .run();
  await database.prepare('UPDATE server_warps SET next_check=0,verification_lease=0').run();
  await new WarpApplication(
    env,
    now + 3600001,
    async (url) => {
      scheduledHosts.push(new URL(url).hostname);
      return Response.json({ ...player, pass: true });
    },
    () => {},
  ).scheduled();
  assert.deepEqual(scheduledHosts.sort(), ['api.catbud.net', 'api.catrealm.net']);
  const requestId = crypto.randomUUID();
  await requireManagementRequest(database, 'administrator', requestId);
  const reviewed = await management.warp('catbud', uuid);
  await actions.warp(
    'catbud',
    uuid,
    'restore',
    { expectedRevision: reviewed.revision },
    'administrator',
    requestId,
  );
  await assert.rejects(
    requireManagementRequest(database, 'administrator', requestId),
    (error) => error.status === 409,
  );
  await assert.rejects(
    actions.contributor(
      'owner',
      { role: 'trusted', enabled: true, servers: ['catbud'] },
      'administrator',
      true,
      requestId,
    ),
    (error) => error.status === 409,
  );
  assert.equal(
    await database.prepare("SELECT enabled FROM contributors WHERE id='owner'").first('enabled'),
    0,
    'replayed request cannot rotate or enable a contributor',
  );
  const status = await new ManagementStatus(database, { POSTING_ENABLED: 'true' }, now).inspect();
  assert.deepEqual(
    status.servers.map((row) => row.id),
    ['catbud', 'catrealm'],
  );
  assert.equal(status.servers[0].published, 1);
  assert.equal(status.postingEnabled, true);
  assert.equal(status.daily.qualification.limit, 2600);
  const searched = await management.list('catbud', 'warps', 0, '用途說明');
  assert.equal(searched.total, 1);
  assert.equal(searched.items[0].uuid, uuid);
  assert.equal((await management.list('catrealm', 'warps', 0, '用途說明')).total, 0);
  for (let index = 0; index < 26; index++) {
    await database
      .prepare("INSERT INTO server_warps(server_id,uuid,name,published) VALUES ('catbud',?,?,1)")
      .bind(crypto.randomUUID(), 'Page_' + String(index).padStart(2, '0'))
      .run();
  }
  const firstPage = await management.list('catbud', 'warps', 0, 'Page_');
  assert.equal(firstPage.total, 26);
  assert.equal(firstPage.items.length, 25);
  assert.equal(firstPage.nextOffset, 25);
  const secondPage = await management.list('catbud', 'warps', 25, 'Page_');
  assert.equal(secondPage.items.length, 1);
  assert.equal(secondPage.nextOffset, null);
  for (const category of ['submissions', 'contributors', 'audit']) {
    assert.ok(Number.isSafeInteger((await management.list('catbud', category, 0, 'owner')).total));
  }
  assert.deepEqual((await database.prepare('PRAGMA foreign_key_check').all()).results, []);
});

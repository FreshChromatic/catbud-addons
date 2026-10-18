import { now, tag, signedRelease } from './addon-release-fixture.mjs';
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';
import { AddonRepository } from '../src/addons/addon-repository.mjs';
import { AddonPublicApplication } from '../src/addons/addon-public-application.mjs';
import { ReleasePublicationService } from '../src/addons/release-publication-service.mjs';
import { NoticeService } from '../src/addons/notice-service.mjs';
import { PublicCache } from '../src/public-cache.mjs';
import { WarpApplication } from '../src/worker.mjs';
import { AddonMaintenance } from '../src/addons/addon-maintenance.mjs';
import { verifyManifest } from '../src/addons/release-manifest.mjs';

async function fixture(context) {
  const runtime = new Miniflare(
    convertV4MiniflareOptions({
      modules: true,
      script: 'export default { fetch() { return new Response("ok"); } }',
      compatibilityDate: '2026-10-11',
      d1Databases: ['DB'],
    }),
  );
  context.after(() => runtime.dispose());
  const database = await runtime.getD1Database('DB');
  await database.exec(
    (
      await readFile(new URL('../migrations/0008_addon_updates.sql', import.meta.url), 'utf8')
    ).replaceAll('\n', ' '),
  );
  return {
    database,
    repository: new AddonRepository(database, now),
    publicApi: new AddonPublicApplication(database, now),
  };
}

test('incomplete release matrices cannot be promoted', async (context) => {
  const { repository, database } = await fixture(context);
  const release = signedRelease(['core', 'magic_tower']);
  const service = new ReleasePublicationService(
    repository,
    { ADDON_RELEASE_KEYS: JSON.stringify(release.keys) },
    release.fetcher,
  );
  await service.import({ requestId: crypto.randomUUID(), expectedRevision: 0, tag }, 'admin@test');
  await assert.rejects(
    service.change(
      tag,
      'promote',
      { requestId: crypto.randomUUID(), expectedRevision: 1 },
      'admin@test',
    ),
    { status: 400 },
  );
  assert.equal(
    await database.prepare('SELECT COUNT(*) AS n FROM addon_release_channels').first('n'),
    0,
  );
});

test('concurrent management writes cannot partially promote or overwrite another revision', async (context) => {
  const { repository, database } = await fixture(context);
  const release = signedRelease();
  const service = new ReleasePublicationService(
    repository,
    { ADDON_RELEASE_KEYS: JSON.stringify(release.keys) },
    release.fetcher,
  );
  await service.import({ requestId: crypto.randomUUID(), expectedRevision: 0, tag }, 'admin@test');
  const outcomes = await Promise.allSettled(
    [1, 2].map(() =>
      service.change(
        tag,
        'promote',
        { requestId: crypto.randomUUID(), expectedRevision: 1 },
        'admin@test',
      ),
    ),
  );
  assert.equal(outcomes.filter((result) => result.status === 'fulfilled').length, 1);
  assert.equal(outcomes.find((result) => result.status === 'rejected').reason.status, 409);
  assert.equal((await repository.state()).revision, 2);
  assert.equal(
    await database.prepare('SELECT COUNT(*) AS n FROM addon_release_channels').first('n'),
    1,
  );
});

test('addon routes work without warp tables and cache bypass sees withdrawals immediately', async (context) => {
  const { repository, database } = await fixture(context);
  const release = signedRelease();
  const service = new ReleasePublicationService(
    repository,
    { ADDON_RELEASE_KEYS: JSON.stringify(release.keys) },
    release.fetcher,
  );
  await service.import({ requestId: crypto.randomUUID(), expectedRevision: 0, tag }, 'admin@test');
  await service.change(
    tag,
    'promote',
    { requestId: crypto.randomUUID(), expectedRevision: 1 },
    'admin@test',
  );
  let upstreamCalls = 0;
  const application = new WarpApplication(
    { DB: database },
    now,
    async () => {
      upstreamCalls++;
      throw new Error('Qualification API must not be called');
    },
    () => {},
  );
  const entries = new Map();
  const cache = {
    match: async (key) => entries.get(key.url)?.clone(),
    put: async (key, value) => entries.set(key.url, value.clone()),
  };
  const pending = [];
  const scope = { waitUntil: (task) => pending.push(task) };
  const cached = new PublicCache(cache, application);
  const url = 'https://api.test/addons/v1/catalog?minecraft=26.2&channel=stable';
  assert.equal((await cached.handle(new Request(url + '&ignored=one'), scope)).status, 200);
  await Promise.all(pending);
  assert.equal(
    (await cached.handle(new Request(url + '&ignored=two'), scope)).headers.get(
      'X-Catbud-Release-Id',
    ),
    tag,
  );
  assert.equal(entries.size, 1);
  await service.change(
    tag,
    'withdraw',
    { requestId: crypto.randomUUID(), expectedRevision: 2 },
    'admin@test',
  );
  const fresh = await cached.handle(
    new Request(url, { headers: { 'Cache-Control': 'no-cache' } }),
    scope,
  );
  assert.equal((await fresh.json()).state, 'no_compatible_release');
  assert.equal(upstreamCalls, 0);
});

test('daily revalidation leases prevent repeated upstream requests and changed artifacts stop recommendations', async (context) => {
  const { repository, database, publicApi } = await fixture(context);
  const release = signedRelease();
  const env = { DB: database, ADDON_RELEASE_KEYS: JSON.stringify(release.keys) };
  const service = new ReleasePublicationService(repository, env, release.fetcher);
  await service.import({ requestId: crypto.randomUUID(), expectedRevision: 0, tag }, 'admin@test');
  await service.change(
    tag,
    'promote',
    { requestId: crypto.randomUUID(), expectedRevision: 1 },
    'admin@test',
  );
  let requests = 0;
  const fetcher = (...args) => {
    requests++;
    return release.fetcher(...args);
  };
  await new AddonMaintenance(env, now, fetcher).run();
  const checked = requests;
  await new AddonMaintenance(env, now + 60000, fetcher).run();
  assert.equal(requests, checked);
  release.assets[0].digest = 'sha256:' + 'f'.repeat(64);
  await new AddonMaintenance(env, now + 86400001, fetcher).run();
  const response = await publicApi.handle(
    new Request('https://api.test/addons/v1/catalog?minecraft=26.2'),
  );
  assert.equal((await response.json()).state, 'release_unavailable');
  assert.equal(response.headers.get('X-Catbud-Release-Id'), null);
});

test('signed manifests reject tampering and untrusted keys', async () => {
  const release = signedRelease();
  assert.equal((await verifyManifest(release.envelope, release.keys)).releaseId, tag);
  await assert.rejects(
    verifyManifest(
      { ...release.envelope, manifest: release.envelope.manifest.replace('1.0.4', '1.0.5') },
      release.keys,
    ),
  );
  await assert.rejects(verifyManifest(release.envelope, {}));
});

test('import, promotion, withdrawal, idempotency and optimistic concurrency are transactional', async (context) => {
  const { database, repository, publicApi } = await fixture(context);
  const release = signedRelease();
  const service = new ReleasePublicationService(
    repository,
    { ADDON_RELEASE_KEYS: JSON.stringify(release.keys) },
    release.fetcher,
  );
  const value = { requestId: crypto.randomUUID(), expectedRevision: 0, tag };
  const imported = await service.import(value, 'admin@test');
  assert.equal(imported.revision, 1);
  assert.deepEqual(await service.import(value, 'admin@test'), imported);
  const promotion = { requestId: crypto.randomUUID(), expectedRevision: 1 };
  await service.change(tag, 'promote', promotion, 'admin@test');
  let response = await publicApi.handle(
    new Request('https://api.test/addons/v1/catalog?minecraft=26.2'),
  );
  assert.equal(response.headers.get('X-Catbud-Release-Id'), tag);
  const etag = response.headers.get('ETag');
  assert.equal((await response.json()).state, 'available');
  response = await publicApi.handle(
    new Request('https://api.test/addons/v1/catalog?minecraft=26.2', {
      headers: { 'If-None-Match': etag },
    }),
  );
  assert.equal(response.status, 304);
  await assert.rejects(
    service.change(
      tag,
      'withdraw',
      { requestId: crypto.randomUUID(), expectedRevision: 1 },
      'admin@test',
    ),
    { status: 409 },
  );
  assert.equal(
    await database.prepare('SELECT COUNT(*) AS n FROM addon_release_channels').first('n'),
    1,
  );
  assert.equal((await repository.state()).revision, 2);
  await service.change(
    tag,
    'withdraw',
    { requestId: crypto.randomUUID(), expectedRevision: 2 },
    'admin@test',
  );
  response = await publicApi.handle(
    new Request('https://api.test/addons/v1/catalog?minecraft=26.2'),
  );
  assert.equal((await response.json()).state, 'no_compatible_release');
  assert.equal(await database.prepare('SELECT COUNT(*) AS n FROM addon_releases').first('n'), 1);
});

test('changed attachments cannot be promoted and missing modules cannot create partial recommendations', async (context) => {
  const { repository } = await fixture(context);
  const release = signedRelease();
  const service = new ReleasePublicationService(
    repository,
    { ADDON_RELEASE_KEYS: JSON.stringify(release.keys) },
    release.fetcher,
  );
  await service.import({ requestId: crypto.randomUUID(), expectedRevision: 0, tag }, 'admin@test');
  release.assets[0].digest = 'sha256:' + 'c'.repeat(64);
  await assert.rejects(
    service.change(
      tag,
      'promote',
      { requestId: crypto.randomUUID(), expectedRevision: 1 },
      'admin@test',
    ),
    { status: 400 },
  );
  assert.equal((await repository.state()).revision, 1);
});

test('announcements respect draft, server, time, withdrawal and revision checks', async (context) => {
  const { repository, publicApi } = await fixture(context);
  const service = new NoticeService(repository);
  const id = crypto.randomUUID();
  const content = {
    title: '維護',
    body: '公共傳點維護中',
    severity: 'important',
    modules: ['public_warps'],
    minecraftVersions: ['26.2'],
    servers: ['catrealm'],
    startsAt: now - 1,
    expiresAt: now + 100000,
    link: '',
    notifyOnRevision: true,
  };
  await service.write(
    id,
    'save',
    { requestId: crypto.randomUUID(), expectedRevision: 0, content },
    'admin@test',
  );
  const request = (server) =>
    publicApi.handle(
      new Request('https://api.test/addons/v1/notices?minecraft=26.2&server=' + server),
    );
  assert.equal((await (await request('catrealm')).json()).notices.length, 0);
  await service.write(
    id,
    'publish',
    { requestId: crypto.randomUUID(), expectedRevision: 1 },
    'admin@test',
  );
  assert.equal((await (await request('catbud')).json()).notices.length, 0);
  assert.equal((await (await request('catrealm')).json()).notices[0].title, '維護');
  await service.write(
    id,
    'withdraw',
    { requestId: crypto.randomUUID(), expectedRevision: 2 },
    'admin@test',
  );
  assert.equal((await (await request('catrealm')).json()).notices.length, 0);
  assert.equal(
    (
      await publicApi.handle(
        new Request('https://api.test/addons/v1/catalog?minecraft=26.2&channel=beta'),
      )
    ).status,
    400,
  );
});

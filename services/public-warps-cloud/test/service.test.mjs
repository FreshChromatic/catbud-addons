import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { gzipSync } from 'node:zlib';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';
import { WarpApplication } from '../src/worker.mjs';
import { ReviewService } from '../src/review-service.mjs';
import { Maintenance } from '../src/maintenance.mjs';
import { Budgets } from '../src/budgets.mjs';
import { canonicalPreview } from '../src/preview-codec.mjs';
import { sha256 } from '../src/protocol.mjs';
import { ContributorManagement } from '../src/contributor-management.mjs';
import { WarpMetadata } from '../src/warp-metadata.mjs';
import { localRateLimits } from '../src/burst-guard.mjs';
import { StorageCapacity } from '../src/storage-capacity.mjs';
import { PlayerQualification } from '../src/player-qualification.mjs';

const uuid = 'a29a6851-0d71-411c-953c-b87bab0d12f3';
const player = { uuid, name: 'Neko_1212' };
const credential = 'a'.repeat(64);

test('qualification supports the Workers fetch receiver requirement', async (context) => {
  const { application, database, env, request } = await fixture(context);
  assert.equal((await request('/v1/discoveries', { players: [player] })).status, 202);
  const qualifications = new PlayerQualification(database, application.budgets, env, function () {
    assert.equal(this, globalThis);
    return Promise.resolve(Response.json({ ...player, pass: true }));
  });
  assert.equal((await qualifications.check(player, true)).active, true);
});

test('cloud administration requires a separate secret and preserves revocable contributor access', async (context) => {
  const administrator = 'b'.repeat(64);
  const { application, database, request } = await fixture(context, {
    ADMIN_CREDENTIAL: administrator,
  });
  assert.equal(
    (await request('/internal/admin', { command: 'grant', target: 'owner' })).status,
    403,
  );
  assert.equal(
    await database.prepare("SELECT COUNT(*) AS n FROM contributors WHERE id='owner'").first('n'),
    0,
  );
  const admin = (body) =>
    application.handle(
      new Request('https://warps.test/internal/admin', {
        method: 'POST',
        headers: { Authorization: 'Bearer ' + administrator },
        body: JSON.stringify(body),
      }),
    );
  const granted = await admin({ command: 'grant', target: 'owner' });
  assert.equal(granted.status, 200);
  const record = await granted.json();
  assert.match(record.contributorCredential, /^[0-9a-f]{64}$/);
  assert.equal(
    await database
      .prepare("SELECT token_hash FROM contributors WHERE id='owner'")
      .first('token_hash'),
    await sha256(record.contributorCredential),
  );
  assert.equal((await admin({ command: 'revoke', target: 'owner' })).status, 200);
  assert.equal(
    await database.prepare("SELECT enabled FROM contributors WHERE id='owner'").first('enabled'),
    0,
  );
  assert.equal((await admin({ command: 'arbitrary_sql', target: 'owner' })).status, 400);
});

test('cloud review checks current upstream qualification before publication', async (context) => {
  const administrator = 'b'.repeat(64);
  const { application, database, request } = await fixture(context, {
    ADMIN_CREDENTIAL: administrator,
  });
  assert.equal((await request('/v1/discoveries', { players: [player] })).status, 202);
  application.qualifications.fetcher = async () => Response.json({ ...player, pass: false });
  const response = await application.handle(
    new Request('https://warps.test/internal/admin', {
      method: 'POST',
      headers: { Authorization: 'Bearer ' + administrator },
      body: JSON.stringify({ command: 'approve', target: uuid }),
    }),
  );
  assert.ok(response.status >= 400);
  assert.equal(
    await database
      .prepare('SELECT published FROM warps WHERE uuid=?')
      .bind(uuid)
      .first('published'),
    0,
  );
});

function scene(state = 'minecraft:stone') {
  return gzipSync(
    JSON.stringify({
      version: 4,
      width: 50,
      height: 50,
      depth: 50,
      dimension: 'minecraft:overworld',
      palette: [state],
      blocks: Array(125000).fill(0),
      eyeX: 25.5,
      eyeY: 26.62,
      eyeZ: 25.5,
      viewYaw: 90,
      viewPitch: 0,
      entities: [],
    }),
  );
}

async function fixture(context, values = {}, rateLimits = localRateLimits) {
  const runtime = new Miniflare(
    convertV4MiniflareOptions({
      modules: true,
      scriptPath: new URL('../build/test-worker/worker.js', import.meta.url).pathname.replace(
        /^\/([A-Z]:)/,
        '$1',
      ),
      compatibilityDate: '2026-10-11',
      compatibilityFlags: ['nodejs_compat'],
      d1Databases: ['DB'],
      ratelimits: rateLimits,
      bindings: { POSTING_ENABLED: 'true' },
    }),
  );
  context.after(() => runtime.dispose());
  const database = await runtime.getD1Database('DB');
  const migration = await readFile(
    new URL('../migrations/0001_public_warps.sql', import.meta.url),
    'utf8',
  );
  // D1 exec is line-oriented; SQL statements may contain trigger bodies.
  const { DatabaseSync } = await import('node:sqlite');
  const parser = new DatabaseSync(':memory:');
  parser.exec(migration);
  parser.close();
  await database.exec(migration.replaceAll('\n', ' '));
  await database.exec(
    (
      await readFile(new URL('../migrations/0002_review_guards.sql', import.meta.url), 'utf8')
    ).replaceAll('\n', ' '),
  );
  const now = Date.now();
  await database.exec(
    (
      await readFile(new URL('../migrations/0003_change_retention.sql', import.meta.url), 'utf8')
    ).replaceAll('\n', ' '),
  );
  await database
    .prepare('INSERT INTO contributors(id,token_hash,created_at) VALUES (?,?,?)')
    .bind('contributor', await sha256(credential), now)
    .run();
  const env = { DB: database, POSTING_ENABLED: 'true', ...values };
  const usage = [];
  const application = new WarpApplication(
    env,
    now,
    async () => Response.json({ ...player, pass: true }),
    (report) => usage.push(report),
  );
  const request = (path, body, authorized = true) =>
    application.handle(
      new Request('https://warps.test' + path, {
        method: body === undefined ? 'GET' : 'POST',
        headers: authorized ? { Authorization: 'Bearer ' + credential } : {},
        body:
          body === undefined
            ? undefined
            : typeof body === 'string' || body instanceof Uint8Array
              ? body
              : JSON.stringify(body),
      }),
    );
  return { runtime, database, application, env, now, request, usage };
}

test('actual D1 usage covers a complete client flow without adding a usage table', async (context) => {
  const { database, application, request, usage, now } = await fixture(context);
  assert.equal((await request('/v1/discoveries', { players: [player] })).status, 202);
  await application.scheduled();
  const bytes = scene();
  const preflight = await request('/v1/submissions/preflight', {
    ...player,
    hash: await sha256(bytes),
    size: 65536,
  });
  const ticket = (await preflight.json()).ticket;
  assert.ok(ticket);
  const uploaded = await request('/v1/submissions/' + ticket, bytes);
  assert.equal(uploaded.status, 202);
  const hash = (await uploaded.json()).hash;
  await application.measure('admin', (scoped) =>
    new ReviewService(scoped.database, now).approve(uuid),
  );
  assert.equal((await request('/v1/warps')).status, 200);
  assert.equal((await request('/v1/previews/' + hash)).status, 200);
  assert.deepEqual(
    usage.map((report) => report.operation),
    ['discovery', 'scheduled', 'preflight', 'upload', 'admin', 'list', 'preview'],
  );
  for (const report of usage) {
    assert.equal(report.unknown, 0);
    assert.ok(report.queries > 0);
    assert.ok(report.rowsRead > 0);
    assert.ok(report.databaseBytes > 0);
    const serialized = JSON.stringify(report);
    assert.ok(!serialized.includes(credential));
    assert.ok(!serialized.includes(uuid));
    assert.ok(!serialized.includes(player.name));
  }
  assert.ok(usage.find((report) => report.operation === 'upload').rowsWritten > 0);
  assert.equal(usage.find((report) => report.operation === 'preview').rowsWritten, 0);
  assert.equal(
    (
      await database
        .prepare("SELECT COUNT(*) AS total FROM sqlite_master WHERE name LIKE '%usage%'")
        .first()
    ).total,
    0,
  );
  await writeFile(
    new URL('../build/local-d1-usage.jsonl', import.meta.url),
    usage.map((report) => JSON.stringify(report)).join('\n') + '\n',
  );
});

test('physical D1 size pauses growth without breaking published downloads or clearing qualification', async (context) => {
  const { database, env, application, now, request } = await fixture(context);
  const bytes = scene();
  const hash = await sha256(bytes);
  await database
    .prepare('INSERT INTO previews(hash,bytes,size,created_at) VALUES (?,?,?,?)')
    .bind(hash, Array.from(bytes), bytes.length, now)
    .run();
  await database
    .prepare(
      "INSERT INTO warps(uuid,name,published,snapshot_hash,pass_state,checked_at) VALUES (?,?,1,?,'active',?)",
    )
    .bind(uuid, player.name, hash, now)
    .run();
  const initial = await new StorageCapacity(database).inspect();
  assert.ok(initial.bytes > initial.storedBytes);
  await database.exec('CREATE TABLE capacity_fixture(bytes BLOB)');
  await database.prepare('INSERT INTO capacity_fixture(bytes) VALUES (zeroblob(65536))').run();
  const filled = await new StorageCapacity(database).inspect();
  assert.ok(filled.bytes >= initial.bytes + 65536);
  env.DATABASE_SOFT_LIMIT_BYTES = String(initial.bytes);
  env.DATABASE_HARD_LIMIT_BYTES = String(filled.bytes + 65536);
  const budgetsBefore = await database.prepare('SELECT COUNT(*) AS total FROM budgets').first();
  const denied = await request('/v1/discoveries', {
    players: [{ ...player, uuid: 'a29a6851-0d71-411c-953c-b87bab0d12f4' }],
  });
  assert.equal(denied.status, 503);
  assert.equal(denied.headers.get('Retry-After'), '3600');
  assert.deepEqual(
    await database.prepare('SELECT COUNT(*) AS total FROM budgets').first(),
    budgetsBefore,
  );
  assert.equal((await request('/v1/warps')).status, 200);
  assert.deepEqual(
    new Uint8Array(await (await request('/v1/previews/' + hash)).arrayBuffer()),
    new Uint8Array(bytes),
  );
  await database.exec('DELETE FROM capacity_fixture');
  const deleted = await new StorageCapacity(database).inspect();
  assert.ok(deleted.bytes >= initial.bytes);
  assert.equal((await request('/v1/discoveries', { players: [player] })).status, 503);
  env.DATABASE_HARD_LIMIT_BYTES = String(initial.bytes);
  let upstreamCalls = 0;
  application.qualifications.fetcher = async () => {
    upstreamCalls++;
    return Response.json({ ...player, pass: false });
  };
  await application.scheduled();
  assert.equal(upstreamCalls, 0);
  const unchanged = await database
    .prepare('SELECT pass_state,checked_at FROM warps WHERE uuid=?')
    .bind(uuid)
    .first();
  assert.deepEqual(unchanged, { pass_state: 'active', checked_at: now });
  assert.equal((await request('/v1/previews/' + hash)).status, 200);
});

test('missing database size fails closed for submissions', async () => {
  const capacity = new StorageCapacity({
    prepare: () => ({ all: async () => ({ meta: {}, results: [] }) }),
  });
  await assert.rejects(capacity.requireGrowth(), (error) => error.status === 503);
});

test('restricted capacity approves existing content without creating history and hard capacity rejects approval', async (context) => {
  const { database, now } = await fixture(context);
  const hashes = ['a'.repeat(64), 'b'.repeat(64)];
  for (const hash of hashes) {
    const bytes = scene();
    await database
      .prepare('INSERT INTO previews(hash,bytes,size,created_at) VALUES (?,?,?,?)')
      .bind(hash, Array.from(bytes), bytes.length, now)
      .run();
  }
  await database
    .prepare(
      "INSERT INTO warps(uuid,name,published,snapshot_hash,pass_state,checked_at) VALUES (?,?,1,?,'active',?)",
    )
    .bind(uuid, player.name, hashes[0], now)
    .run();
  const submit = () =>
    database
      .prepare(
        "INSERT INTO submissions(uuid,contributor,hash,kind,created_at,expires_at) VALUES (?,'contributor',?,'preview',?,?)",
      )
      .bind(uuid, hashes[1], now, now + 600000)
      .run();
  await submit();
  await new ReviewService(database, now, { DATABASE_SOFT_LIMIT_BYTES: '1' }).approve(uuid);
  const approved = await database
    .prepare('SELECT snapshot_hash,previous_hash FROM warps WHERE uuid=?')
    .bind(uuid)
    .first();
  assert.deepEqual(approved, { snapshot_hash: hashes[1], previous_hash: null });
  await submit();
  const blocked = new ReviewService(database, now, {
    DATABASE_SOFT_LIMIT_BYTES: '1',
    DATABASE_HARD_LIMIT_BYTES: '1',
  });
  await assert.rejects(blocked.approve(uuid), (error) => error.status === 503);
  assert.equal(
    (await database.prepare('SELECT COUNT(*) AS total FROM submissions').first()).total,
    1,
  );
  assert.equal((await database.prepare('SELECT COUNT(*) AS total FROM audit').first()).total, 1);
});

test('approval preserves old history expiry unless it actually retains a new previous preview', async (context) => {
  const { database, now } = await fixture(context);
  const hashes = ['a'.repeat(64), 'b'.repeat(64), 'c'.repeat(64)];
  for (const hash of hashes) {
    const bytes = scene();
    await database
      .prepare('INSERT INTO previews(hash,bytes,size,created_at) VALUES (?,?,?,?)')
      .bind(hash, Array.from(bytes), bytes.length, now)
      .run();
  }
  const expiry = now + 600000;
  await database
    .prepare(
      `INSERT INTO warps(uuid,name,published,snapshot_hash,previous_hash,previous_until,pass_state,checked_at)
      VALUES (?,?,1,?,?,?,'active',?)`,
    )
    .bind(uuid, player.name, hashes[1], hashes[0], expiry, now)
    .run();
  const history = () =>
    database
      .prepare('SELECT snapshot_hash,previous_hash,previous_until FROM warps WHERE uuid=?')
      .bind(uuid)
      .first();
  const submit = async (kind, hash, time, env = {}) => {
    await database
      .prepare(
        `INSERT INTO submissions(uuid,contributor,hash,kind,created_at,expires_at)
        VALUES (?,'contributor',?,?,?,?)`,
      )
      .bind(uuid, hash, kind, time, time + 600000)
      .run();
    await new ReviewService(database, time, env).approve(uuid);
  };
  await submit('discovery', null, now);
  assert.equal((await history()).previous_until, expiry);
  await submit('preview', hashes[1], now + 1000);
  assert.equal((await history()).previous_until, expiry);
  await submit('preview', hashes[2], now + 2000, { DATABASE_SOFT_LIMIT_BYTES: '1' });
  assert.deepEqual(await history(), {
    snapshot_hash: hashes[2],
    previous_hash: hashes[0],
    previous_until: expiry,
  });
  await new Maintenance(database, expiry).clean();
  assert.deepEqual(await history(), {
    snapshot_hash: hashes[2],
    previous_hash: null,
    previous_until: 0,
  });
  assert.equal((await database.prepare('SELECT COUNT(*) AS total FROM previews').first()).total, 1);
  const bytes = scene();
  await database
    .prepare('INSERT INTO previews(hash,bytes,size,created_at) VALUES (?,?,?,?)')
    .bind(hashes[1], Array.from(bytes), bytes.length, now)
    .run();
  await submit('preview', hashes[1], expiry + 1000);
  assert.deepEqual(await history(), {
    snapshot_hash: hashes[1],
    previous_hash: hashes[2],
    previous_until: expiry + 1000 + 604800000,
  });
});

test('Worker burst bindings reject cached reads and anonymous writes before reaching D1', async (context) => {
  const limits = structuredClone(localRateLimits);
  limits.PUBLIC_BURST.simple.limit = 2;
  limits.WRITE_BURST.simple.limit = 2;
  const { runtime, database } = await fixture(context, {}, limits);
  const headers = { 'CF-Connecting-IP': '203.0.113.1' };
  for (let attempt = 0; attempt < 2; attempt++) {
    assert.equal(
      (await runtime.dispatchFetch('https://warps.test/v1/warps', { headers })).status,
      200,
    );
    assert.equal(
      (await runtime.dispatchFetch('https://warps.test/v1/admin', { method: 'POST', headers }))
        .status,
      404,
    );
  }
  const read = await runtime.dispatchFetch('https://warps.test/v1/warps', { headers });
  const write = await runtime.dispatchFetch('https://warps.test/v1/admin', {
    method: 'POST',
    headers,
  });
  assert.equal(read.status, 429);
  assert.equal(write.status, 429);
  assert.equal(write.headers.get('Retry-After'), '60');
  assert.equal((await database.prepare('SELECT COUNT(*) AS total FROM budgets').first()).total, 0);
});

test('real D1 keeps discovered names private until review and rejects anonymous writes', async (context) => {
  const fixtureData = await fixture(context);
  const { request, database, runtime, now } = fixtureData;
  assert.equal((await request('/v1/discoveries', { players: [player] }, false)).status, 401);
  assert.equal((await request('/v1/discoveries', { players: [player] })).status, 202);
  assert.equal((await (await request('/v1/warps')).json()).warps.length, 0);
  await fixtureData.application.scheduled();
  await new ReviewService(database, now).approve(uuid);
  const list = await runtime.dispatchFetch('https://warps.test/v1/warps');
  assert.equal(list.status, 200);
  assert.equal((await list.json()).warps[0].name, player.name);
  assert.equal((await runtime.dispatchFetch('https://warps.test/v1/admin')).status, 404);
});

test('ticket upload is bounded, replay-safe, private and independently canonicalized', async (context) => {
  const { request, database, now, env } = await fixture(context);
  await request('/v1/discoveries', { players: [player] });
  const bytes = scene();
  const response = await request('/v1/submissions/preflight', {
    ...player,
    hash: await sha256(bytes),
    size: 65536,
  });
  assert.equal(response.status, 200, await response.clone().text());
  const { ticket } = await response.json();
  assert.equal(
    (await database.prepare("SELECT value FROM metadata WHERE key='reserved_bytes'").first()).value,
    65536,
  );
  const upload = await request('/v1/submissions/' + ticket, bytes);
  assert.equal(upload.status, 202, await upload.clone().text());
  const result = await upload.json();
  assert.equal((await request('/v1/previews/' + result.hash)).status, 404);
  await request('/v1/submissions/' + ticket, bytes);
  assert.equal((await database.prepare('SELECT COUNT(*) AS total FROM previews').first()).total, 1);
  assert.equal(
    (await database.prepare("SELECT value FROM metadata WHERE key='reserved_bytes'").first()).value,
    0,
  );
  await new ReviewService(database, now).approve(uuid);
  const downloaded = await request('/v1/previews/' + result.hash);
  assert.equal(downloaded.status, 200);
  const normalized = await canonicalPreview(new Uint8Array(await downloaded.arrayBuffer()));
  assert.equal(normalized.hash, result.hash);
  env.DATABASE_SOFT_LIMIT_BYTES = '1';
  env.DATABASE_HARD_LIMIT_BYTES = '1';
  const replay = await request('/v1/submissions/' + ticket, bytes);
  assert.equal(replay.status, 200);
  assert.equal((await replay.json()).hash, result.hash);
});

test('atomic D1 reservations cannot exceed shared capacity under concurrency', async (context) => {
  const { application, database } = await fixture(context, { STORAGE_LIMIT_BYTES: '65536' });
  const players = Array.from({ length: 2 }, (_, index) => ({
    uuid: `a29a6851-0d71-411c-953c-b87bab0d12f${index}`,
    name: 'Player' + index,
  }));
  for (const target of players) {
    await application.repository.remember(target);
  }
  const reservations = await Promise.allSettled(
    players.map((target) =>
      application.repository.reservePreview(target, 'contributor', {
        hash: 'b'.repeat(64),
        size: 65536,
      }),
    ),
  );
  assert.equal(reservations.filter((result) => result.status === 'fulfilled').length, 1);
  assert.equal(
    (await database.prepare("SELECT value FROM metadata WHERE key='reserved_bytes'").first()).value,
    65536,
  );
  await new Maintenance(database, Date.now() + 600001).clean();
  assert.equal(
    (await database.prepare("SELECT value FROM metadata WHERE key='reserved_bytes'").first()).value,
    0,
  );
});

test('authoritative hourly pass checks retain records and cannot confuse identity mismatch with no pass', async (context) => {
  const { database, env, now } = await fixture(context);
  await database
    .prepare('INSERT INTO warps(uuid,name,published) VALUES (?,?,1)')
    .bind(uuid, player.name)
    .run();
  let calls = 0;
  const inactive = new WarpApplication(env, now, async () => {
    calls++;
    return Response.json({ ...player, pass: false });
  });
  await inactive.scheduled();
  let record = await database.prepare('SELECT * FROM warps WHERE uuid=?').bind(uuid).first();
  assert.equal(record.pass_state, 'inactive');
  assert.equal(record.next_check, now + 3600000);
  await inactive.scheduled();
  assert.equal(calls, 1);
  const mismatch = new WarpApplication(env, now + 3600000, async () =>
    Response.json({ ...player, name: 'OtherPlayer', pass: true }),
  );
  await mismatch.scheduled();
  record = await database.prepare('SELECT * FROM warps WHERE uuid=?').bind(uuid).first();
  assert.equal(record.pass_state, 'inactive');
  assert.equal(record.checked_at, now);
  assert.equal(record.published, 1);
});

test('modified clients cannot publish a fabricated unavailable flag', async (context) => {
  const { request, database, now } = await fixture(context);
  await database
    .prepare('INSERT INTO warps(uuid,name,published,pass_state,checked_at) VALUES (?,?,1,?,?)')
    .bind(uuid, player.name, 'active', now)
    .run();
  assert.equal(
    (await request('/v1/availability-reports', { ...player, reason: 'target_no_pass_or_set' }))
      .status,
    202,
  );
  const record = await database
    .prepare('SELECT availability FROM warps WHERE uuid=?')
    .bind(uuid)
    .first();
  assert.equal(record.availability, 'unknown');
  await new ReviewService(database, now).availability(uuid, false);
  const list = await (await request('/v1/warps')).json();
  assert.equal(list.warps[0].availability, 'unavailable');
});

test('review fails atomically without a current pass and cannot approve a submission twice', async (context) => {
  const { request, database, application, now } = await fixture(context);
  await request('/v1/discoveries', { players: [player] });
  const review = new ReviewService(database, now);
  await assert.rejects(review.approve(uuid), (error) => error.status === 409);
  assert.equal(
    (await database.prepare('SELECT published FROM warps WHERE uuid=?').bind(uuid).first())
      .published,
    0,
  );
  assert.equal(
    (await database.prepare('SELECT COUNT(*) AS total FROM submissions').first()).total,
    1,
  );
  assert.equal((await database.prepare('SELECT COUNT(*) AS total FROM audit').first()).total, 0);
  await application.scheduled();
  await review.approve(uuid);
  await assert.rejects(review.approve(uuid), (error) => error.status === 409);
  assert.equal((await database.prepare('SELECT COUNT(*) AS total FROM audit').first()).total, 1);
  assert.equal(
    (await database.prepare('SELECT COUNT(*) AS total FROM review_guards').first()).total,
    0,
  );
});

test('revocation immediately denies writes and releases reserved storage', async (context) => {
  const { request, database, application } = await fixture(context);
  await application.repository.remember(player);
  await application.repository.reservePreview(player, 'contributor', {
    hash: 'b'.repeat(64),
    size: 65536,
  });
  await new ContributorManagement(database).revoke('contributor');
  assert.equal((await request('/v1/discoveries', { players: [player] })).status, 403);
  assert.equal(
    (await database.prepare("SELECT value FROM metadata WHERE key='reserved_bytes'").first()).value,
    0,
  );
});

test('rollback restores retained preview, unpublishing hides it, and emits a removal revision', async (context) => {
  const { request, database, now } = await fixture(context);
  const first = await canonicalPreview(scene());
  const second = await canonicalPreview(scene('minecraft:dirt'));
  for (const preview of [first, second]) {
    await database
      .prepare('INSERT INTO previews(hash,bytes,size,created_at) VALUES (?,?,?,?)')
      .bind(preview.hash, Array.from(preview.bytes), preview.bytes.length, now)
      .run();
  }
  await database
    .prepare(
      `INSERT INTO warps(uuid,name,published,snapshot_hash,previous_hash,previous_until)
    VALUES (?,?,1,?,?,?)`,
    )
    .bind(uuid, player.name, second.hash, first.hash, now + 604800000)
    .run();
  const review = new ReviewService(database, now);
  await review.rollback(uuid);
  assert.equal((await (await request('/v1/warps')).json()).warps[0].snapshotHash, first.hash);
  assert.equal((await request('/v1/previews/' + first.hash)).status, 200);
  await review.unpublish(uuid);
  assert.equal((await (await request('/v1/warps')).json()).warps.length, 0);
  assert.equal((await request('/v1/previews/' + first.hash)).status, 404);
  assert.equal(
    (await database.prepare('SELECT deleted FROM changes ORDER BY revision DESC LIMIT 1').first())
      .deleted,
    1,
  );
});

test('canonical hashes ignore palette ordering and reject decompression bombs', async () => {
  const { gunzipSync } = await import('node:zlib');
  const snapshot = JSON.parse(gunzipSync(scene()).toString());
  snapshot.palette = ['minecraft:dirt', 'minecraft:stone'];
  snapshot.blocks.fill(1);
  const same = structuredClone(snapshot);
  same.palette.reverse();
  same.blocks.fill(0);
  assert.equal(
    (await canonicalPreview(gzipSync(JSON.stringify(snapshot)))).hash,
    (await canonicalPreview(gzipSync(JSON.stringify(same)))).hash,
  );
  await assert.rejects(
    canonicalPreview(gzipSync(' '.repeat(1048577))),
    (error) => error.status === 400,
  );
});

test('admin metadata changes list revisions without changing player identity or qualification', async (context) => {
  const { database, request, now } = await fixture(context);
  await database
    .prepare('INSERT INTO warps(uuid,name,published,pass_state,checked_at) VALUES (?,?,1,?,?)')
    .bind(uuid, player.name, 'active', now)
    .run();
  const first = await request('/v1/warps');
  const metadata = new WarpMetadata(database, now);
  await metadata.update(uuid, {
    displayName: '公共農場',
    description: '提供小麥與南瓜',
    tags: ['農場', '食物', '農場'],
  });
  const next = await request('/v1/warps');
  assert.notEqual(first.headers.get('etag'), next.headers.get('etag'));
  const warp = (await next.json()).warps[0];
  assert.equal(warp.name, player.name);
  assert.equal(warp.displayName, '公共農場');
  assert.deepEqual(warp.tags, ['農場', '食物']);
  assert.equal(warp.passState, 'active');
  assert.equal(warp.checkedAt, now);
  await assert.rejects(
    metadata.update(uuid, { displayName: 'fake', passState: 'active', tags: [] }),
    (error) => error.status === 400,
  );
  await assert.rejects(
    metadata.update(uuid, { tags: ['x'.repeat(25)] }),
    (error) => error.status === 400,
  );
  await assert.rejects(
    metadata.update('b29a6851-0d71-411c-953c-b87bab0d12f3', { tags: [] }),
    (error) => error.status === 404,
  );
  assert.equal(
    (await database.prepare("SELECT COUNT(*) AS total FROM audit WHERE action='metadata'").first())
      .total,
    1,
  );
});

test('delta reads report edits and tombstones and reject expired or changing cursors', async (context) => {
  const { database, request, now } = await fixture(context);
  await database
    .prepare('INSERT INTO warps(uuid,name,published) VALUES (?,?,1)')
    .bind(uuid, player.name)
    .run();
  await new WarpMetadata(database, now).update(uuid, { displayName: '公共農場', tags: ['農場'] });
  const first = await (await request('/v1/changes?since=0')).json();
  assert.equal(first.changes.length, 1);
  assert.equal(first.changes[0].warp.displayName, '公共農場');
  const since = first.revision;
  await new ReviewService(database, now).unpublish(uuid);
  const changes = await (await request('/v1/changes?since=' + since)).json();
  assert.equal(changes.changes[0].uuid, uuid);
  assert.equal(changes.changes[0].deleted, true);
  assert.equal(changes.changes[0].warp, null);
  assert.equal((await request('/v1/changes?since=0&revision=' + since)).status, 409);
  assert.equal((await request('/v1/changes?since=1.5')).status, 400);
  await database
    .prepare('UPDATE changes SET created_at=?')
    .bind(now - 604800001)
    .run();
  await new Maintenance(database, now).clean();
  assert.equal((await request('/v1/changes?since=0')).status, 410);
  const current = await (await request('/v1/changes?since=' + changes.revision)).json();
  assert.deepEqual(current.changes, []);
});

test('Worker public cache serves repeated reads and uncached requests observe current D1 state', async (context) => {
  const { database, runtime, now } = await fixture(context);
  await database
    .prepare('INSERT INTO warps(uuid,name,published) VALUES (?,?,1)')
    .bind(uuid, player.name)
    .run();
  const url = 'https://warps.test/v1/warps';
  const first = await runtime.dispatchFetch(url);
  assert.equal((await first.clone().json()).warps[0].name, player.name);
  const cache = (await runtime.getCaches()).default;
  let cached;
  for (let attempt = 0; attempt < 10; attempt++) {
    cached = await cache.match(url);
    if (cached) {
      break;
    }
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
  assert.ok(cached, 'Worker stored a reusable public response');
  await new WarpMetadata(database, now).update(uuid, { displayName: 'New label', tags: [] });
  const stale = await runtime.dispatchFetch(url);
  assert.equal((await stale.json()).warps[0].displayName, null);
  const conditional = await runtime.dispatchFetch(url, {
    cache: 'force-cache',
    headers: { 'If-None-Match': first.headers.get('etag') },
  });
  assert.equal(
    conditional.status,
    304,
    JSON.stringify({
      first: first.headers.get('etag'),
      cached: cached.headers.get('etag'),
      conditional: conditional.headers.get('etag'),
    }),
  );
  const fresh = await runtime.dispatchFetch(url, { headers: { 'Cache-Control': 'no-cache' } });
  assert.equal((await fresh.json()).warps[0].displayName, 'New label');
});

test('change pagination freezes its revision and detects mutation between pages', async (context) => {
  const { database, request } = await fixture(context);
  await database
    .prepare('INSERT INTO warps(uuid,name,published) VALUES (?,?,1)')
    .bind(uuid, player.name)
    .run();
  await database.batch(
    Array.from({ length: 101 }, (_, index) =>
      database.prepare('UPDATE warps SET display_name=? WHERE uuid=?').bind('Name ' + index, uuid),
    ),
  );
  const first = await (await request('/v1/changes?since=0')).json();
  assert.equal(first.changes.length, 100);
  assert.equal(first.next, 100);
  const path = '/v1/changes?since=0&after=' + first.next + '&revision=' + first.revision;
  const second = await (await request(path)).json();
  assert.equal(second.changes.length, 1);
  assert.equal(second.next, null);
  await database.prepare('UPDATE warps SET pass_state=? WHERE uuid=?').bind('inactive', uuid).run();
  assert.equal((await request(path)).status, 409);
});

test('shared entity appearance preserves equipment visuals while removing nested private data', async () => {
  const { gunzipSync } = await import('node:zlib');
  const snapshot = JSON.parse(gunzipSync(scene()));
  snapshot.entities = [
    {
      type: 'minecraft:armor_stand',
      x: 1,
      y: 1,
      z: 1,
      yaw: 0,
      pitch: 0,
      appearance: {
        equipment:
          '{head:{id:"minecraft:leather_helmet",count:1,Owner:"private-owner",components:{"minecraft:dyed_color":123,"minecraft:item_name":"private-name","minecraft:lore":["private-lore"],"minecraft:container":[{private:1}],"minecraft:trim":{material:"minecraft:gold",pattern:"minecraft:coast",secret:"private-secret"}}}}',
        Pose: '{Head:[1f,2f,3f],secret:"private-pose"}',
      },
    },
  ];
  const result = await canonicalPreview(gzipSync(JSON.stringify(snapshot)));
  const restored = JSON.parse(gunzipSync(result.bytes));
  const appearance = restored.entities[0].appearance;
  assert.ok(appearance.equipment.includes('minecraft:leather_helmet'));
  assert.ok(appearance.equipment.includes('minecraft:dyed_color'));
  assert.ok(appearance.equipment.includes('minecraft:gold'));
  assert.equal(JSON.stringify(appearance).includes('private'), false);
  assert.ok(appearance.Pose.includes('[1f,2f,3f]'));
  assert.equal((await canonicalPreview(result.bytes)).hash, result.hash);
  snapshot.entities[0].appearance.equipment = '{head:{';
  await assert.rejects(
    canonicalPreview(gzipSync(JSON.stringify(snapshot))),
    (error) => error.status === 400,
  );
});

test('derived equipment glint is stable across canonicalization and malicious tag nesting is bounded', async () => {
  const { sharedAppearance } = await import('../src/shared-appearance.mjs');
  const input = {
    Item: '{id:"minecraft:diamond_sword",count:1,components:{"minecraft:enchantments":{"minecraft:unbreaking":3,secret:"private"}}}',
  };
  const clean = sharedAppearance(input);
  assert.ok(clean.Item.includes('minecraft:enchantment_glint_override'));
  assert.equal(clean.Item.includes('private'), false);
  assert.deepEqual(sharedAppearance(clean), clean);
  assert.throws(
    () => sharedAppearance({ equipment: '{x:'.repeat(18) + '1' + '}'.repeat(18) }),
    (error) => error.status === 400,
  );
  assert.throws(
    () => sharedAppearance({ equipment: '{head:{id:"minecraft:stone",id:"minecraft:dirt"}}' }),
    (error) => error.status === 400,
  );
});

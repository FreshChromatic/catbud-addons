import test from 'node:test';
import assert from 'node:assert/strict';
import { gzipSync } from 'node:zlib';
import { createWarpService, ApiError, validateSnapshot, verifyPlayer } from '../server.mjs';
import { managePreview, cleanSnapshots } from '../snapshot-management.mjs';
const uuid = 'a29a6851-0d71-411c-953c-b87bab0d12f3';
function snapshot(state = 'minecraft:stone') {
  return gzipSync(
    JSON.stringify({
      version: 1,
      width: 10,
      height: 16,
      depth: 10,
      dimension: 'minecraft:overworld',
      palette: [state],
      blocks: Array(1600).fill(0),
    }),
  ).toString('base64');
}
for (const [version, size] of [
  [2, 15],
  [3, 30],
  [4, 50],
]) {
  test(`v${version} shares a ${size}-cube, camera and bounded visual entities with another client`, async () => {
    const scene = {
      version,
      width: size,
      height: size,
      depth: size,
      dimension: 'minecraft:overworld',
      palette: ['minecraft:air'],
      blocks: Array(size ** 3).fill(0),
      eyeX: 7.5,
      eyeY: 8.62,
      eyeZ: 7.5,
      viewYaw: 90,
      viewPitch: 0,
      entities: [
        {
          type: 'minecraft:sheep',
          x: 9,
          y: 7,
          z: 8,
          yaw: 0,
          pitch: 0,
          appearance: { Color: '14b' },
          ignoredAbsolutePosition: 999,
        },
      ],
    };
    const encode = (value) => gzipSync(JSON.stringify(value)).toString('base64');
    const clean = validateSnapshot(encode(scene));
    assert.equal(clean.snapshot.entities[0].ignoredAbsolutePosition, undefined);
    assert.throws(() => validateSnapshot(encode({ ...scene, eyeY: size + 1 })), ApiError);
    assert.throws(
      () =>
        validateSnapshot(
          encode({
            ...scene,
            entities: [{ ...scene.entities[0], appearance: { Passengers: '[]' } }],
          }),
        ),
      ApiError,
    );
    assert.throws(() => validateSnapshot(encode(null)), ApiError);
    const s = createWarpService({ verify: async (id, name) => ({ uuid: id, name }) });
    await new Promise((r) => s.server.listen(0, '127.0.0.1', r));
    const base = 'http://127.0.0.1:' + s.server.address().port;
    try {
      const first = await (
        await fetch(base + '/v1/reports', {
          method: 'POST',
          body: JSON.stringify({
            uuid,
            name: 'Neko_1212',
            bossbar: true,
            actionbar: true,
            snapshot: encode(scene),
          }),
        })
      ).json();
      const list = await (await fetch(base + '/v1/warps')).json();
      assert.equal(list.warps[0].snapshotHash, first.snapshotHash);
      const bytes = Buffer.from(
        await (await fetch(base + '/v1/snapshots/' + first.snapshotHash)).arrayBuffer(),
      );
      const downloaded = validateSnapshot(bytes.toString('base64')).snapshot;
      assert.equal(downloaded.blocks.length, size ** 3);
      assert.equal(downloaded.entities[0].appearance.Color, '14b');
      assert.equal(downloaded.viewYaw, 90);
    } finally {
      await s.close();
    }
  });
}
test('two clients share a verified warp and bounded preview; conflicting report cannot overwrite it', async () => {
  let checks = 0;
  const s = createWarpService({
    verify: async (id, name) => {
      checks++;
      return { uuid: id, name };
    },
  });
  await new Promise((r) => s.server.listen(0, '127.0.0.1', r));
  const base = 'http://127.0.0.1:' + s.server.address().port;
  const post = (body) =>
    fetch(base + '/v1/reports', { method: 'POST', body: JSON.stringify(body) });
  try {
    const report = {
      uuid,
      name: 'Neko_1212',
      bossbar: true,
      actionbar: true,
      snapshot: snapshot(),
    };
    assert.equal((await post({ ...report, actionbar: false })).status, 400);
    const first = await (await post(report)).json();
    assert.equal(first.accepted, true);
    const listResponse = await fetch(base + '/v1/warps');
    const list = await listResponse.json();
    assert.equal(list.warps[0].uuid, uuid);
    const downloaded = Buffer.from(
      await (await fetch(base + '/v1/snapshots/' + first.snapshotHash)).arrayBuffer(),
    );
    assert.equal(validateSnapshot(downloaded.toString('base64')).snapshot.blocks.length, 1600);
    assert.equal(
      (
        await fetch(base + '/v1/warps', {
          headers: { 'If-None-Match': listResponse.headers.get('etag') },
        })
      ).status,
      304,
    );
    const conflicting = await (
      await post({ ...report, snapshot: snapshot('minecraft:dirt') })
    ).json();
    assert.equal(conflicting.previewCandidate, true);
    assert.equal(conflicting.snapshotHash, first.snapshotHash);
    assert.equal(checks, 1);
    assert.equal(s.db.prepare('SELECT COUNT(*) AS n FROM warps').get().n, 1);
  } finally {
    await s.close();
  }
});
test('unknown player and false pass never become public entries', async () => {
  const s = createWarpService({
    verify: async () => {
      throw new ApiError(422, 'Player has no active pass');
    },
  });
  await new Promise((r) => s.server.listen(0, '127.0.0.1', r));
  try {
    const response = await fetch('http://127.0.0.1:' + s.server.address().port + '/v1/reports', {
      method: 'POST',
      body: JSON.stringify({ uuid, name: 'Neko_1212', bossbar: true, actionbar: true }),
    });
    assert.equal(response.status, 422);
    assert.equal(s.db.prepare('SELECT COUNT(*) AS n FROM warps').get().n, 0);
  } finally {
    await s.close();
  }
});
test('invalid dimensions, indices, and non-snapshot fields cannot leak through', () => {
  const s = validateSnapshot(snapshot());
  assert.equal(s.snapshot.height, 16);
  assert.throws(() => validateSnapshot('bad!'), ApiError);
  const invalid = { ...s.snapshot, height: 100 };
  assert.throws(
    () => validateSnapshot(gzipSync(JSON.stringify(invalid)).toString('base64')),
    ApiError,
  );
  assert.throws(
    () =>
      validateSnapshot(
        gzipSync(JSON.stringify({ ...s.snapshot, blocks: Array(1600).fill(2) })).toString('base64'),
      ),
    ApiError,
  );
});
test('upstream verification requires exact boolean pass and matching authoritative identity', async () => {
  const fetcher = (body) => async () => new Response(JSON.stringify(body), { status: 200 });
  assert.equal(
    (await verifyPlayer(uuid, 'Neko_1212', fetcher({ uuid, name: 'neko_1212', pass: true }))).name,
    'neko_1212',
  );
  await assert.rejects(
    verifyPlayer(uuid, 'Neko_1212', fetcher({ uuid, name: 'neko_1212', pass: 'true' })),
    /Unrecognized/,
  );
  await assert.rejects(
    verifyPlayer(uuid, 'Neko_1212', fetcher({ uuid, name: 'neko_1212', pass: false })),
    /no active pass/,
  );
  await assert.rejects(
    verifyPlayer(uuid, 'Other', fetcher({ uuid, name: 'neko_1212', pass: true })),
    /identity mismatch/,
  );
});
test('cached invalid reports do not exhaust upstream budget; aged lists refresh status', async () => {
  let time = 1000000,
    checks = 0;
  const s = createWarpService({
    now: () => time,
    verificationBudget: 2,
    verify: async (id, name) => {
      checks++;
      if (name === 'Invalid') {
        throw new ApiError(404, 'Catbud player not found');
      }
      return { uuid: id, name };
    },
  });
  await new Promise((r) => s.server.listen(0, '127.0.0.1', r));
  const base = 'http://127.0.0.1:' + s.server.address().port;
  const post = (name) =>
    fetch(base + '/v1/reports', {
      method: 'POST',
      body: JSON.stringify({ uuid, name, bossbar: true, actionbar: true }),
    });
  try {
    assert.equal((await post('Invalid')).status, 404);
    assert.equal((await post('Invalid')).status, 404);
    assert.equal(checks, 1);
    assert.equal((await post('Valid')).status, 200);
    assert.equal((await post('Another')).status, 429);
    const before = await fetch(base + '/v1/warps');
    assert.equal((await before.json()).warps[0].status, 'verified_report');
    time += 86400001;
    const aged = await fetch(base + '/v1/warps', {
      headers: { 'If-None-Match': before.headers.get('etag') },
    });
    assert.equal(aged.status, 200);
    assert.equal((await aged.json()).warps[0].status, 'stale');
  } finally {
    await s.close();
  }
});
test('upstream oversized body is rejected before JSON parsing', async () => {
  await assert.rejects(
    verifyPlayer(uuid, 'Valid', async () => new Response(' '.repeat(1024 * 1024 + 1))),
    /too large/,
  );
});
test('administrator can approve, revert, remove and reject previews with bounded history', async () => {
  let time = Date.now();
  const s = createWarpService({
    now: () => time,
    verify: async (id, name) => ({ uuid: id, name }),
  });
  await new Promise((r) => s.server.listen(0, '127.0.0.1', r));
  const base = 'http://127.0.0.1:' + s.server.address().port;
  const report = (state) =>
    fetch(base + '/v1/reports', {
      method: 'POST',
      body: JSON.stringify({
        uuid,
        name: 'Valid',
        bossbar: true,
        actionbar: true,
        snapshot: snapshot(state),
      }),
    });
  const current = () =>
    s.db.prepare('SELECT snapshot_hash FROM warps WHERE uuid=?').get(uuid).snapshot_hash;
  try {
    const first = await (await report('minecraft:stone')).json();
    await report('minecraft:dirt');
    const replacement = s.db.prepare('SELECT hash FROM candidates WHERE uuid=?').get(uuid).hash;
    managePreview(s.db, 'approve', uuid, time);
    assert.equal(current(), replacement);
    assert.equal((await fetch(base + '/v1/snapshots/' + first.snapshotHash)).status, 404);
    managePreview(s.db, 'rollback', uuid, time);
    assert.equal(current(), first.snapshotHash);
    managePreview(s.db, 'remove', uuid, time);
    assert.equal(current(), null);
    managePreview(s.db, 'rollback', uuid, time);
    assert.equal(current(), first.snapshotHash);
    await report('minecraft:glass');
    managePreview(s.db, 'reject', uuid, time);
    assert.equal(current(), first.snapshotHash);
    assert.equal(s.db.prepare('SELECT COUNT(*) AS n FROM candidates').get().n, 0);
    await report('minecraft:oak_planks');
    time += 7 * 86400000 + 1;
    cleanSnapshots(s.db, time);
    assert.equal(s.db.prepare('SELECT COUNT(*) AS n FROM candidates').get().n, 0);
    assert.equal(s.db.prepare('SELECT COUNT(*) AS n FROM snapshots').get().n, 1);
  } finally {
    await s.close();
  }
});

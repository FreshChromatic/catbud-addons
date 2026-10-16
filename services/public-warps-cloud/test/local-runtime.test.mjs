import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { gzipSync } from 'node:zlib';
import { localRuntime } from '../tools/local-runtime.mjs';
import { DatabaseBackup } from '../tools/database-backup.mjs';
import { ContributorManagement } from '../src/contributor-management.mjs';
import { sha256 } from '../src/protocol.mjs';

test('local D1 survives restart and a live backup restores public downloads and contributor revocation', async (context) => {
  const directory = await mkdtemp(join(tmpdir(), 'catbud-d1-backup-'));
  context.after(() => rm(directory, { recursive: true, force: true }));
  const state = join(directory, 'state');
  let live;
  try {
    live = await localRuntime(undefined, pathToFileURL(state));
    const credential = await new ContributorManagement(live.database).grant('backup-test');
    const uuid = 'a29a6851-0d71-411c-953c-b87bab0d12f3';
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
        eyeX: 25,
        eyeY: 25,
        eyeZ: 25,
        viewYaw: 0,
        viewPitch: 0,
      }),
    );
    const hash = await sha256(bytes);
    await live.database
      .prepare('INSERT INTO previews(hash,bytes,size,created_at) VALUES (?,?,?,?)')
      .bind(hash, Array.from(bytes), bytes.length, Date.now())
      .run();
    await live.database
      .prepare('INSERT INTO warps(uuid,name,published,snapshot_hash) VALUES (?,?,1,?)')
      .bind(uuid, 'Neko_1212', hash)
      .run();
    await live.runtime.dispose();
    live = await localRuntime(undefined, pathToFileURL(state));
    assert.equal(
      (await live.database.prepare('SELECT name FROM warps WHERE uuid=?').bind(uuid).first()).name,
      'Neko_1212',
    );
    await new ContributorManagement(live.database).revoke('backup-test');
    const file = await DatabaseBackup.create(state, join(directory, 'backup'));
    const restoredState = join(directory, 'recovered');
    await DatabaseBackup.recover(file, restoredState);
    await live.runtime.dispose();
    live = await localRuntime(undefined, pathToFileURL(restoredState));
    const response = await live.runtime.dispatchFetch('http://warps.test/v1/warps');
    assert.equal((await response.json()).warps[0].snapshotHash, hash);
    const preview = await live.runtime.dispatchFetch('http://warps.test/v1/previews/' + hash);
    assert.deepEqual(new Uint8Array(await preview.arrayBuffer()), new Uint8Array(bytes));
    const denied = await live.runtime.dispatchFetch('http://warps.test/v1/discoveries', {
      method: 'POST',
      headers: { Authorization: 'Bearer ' + credential.contributorCredential },
      body: JSON.stringify({ players: [{ uuid, name: 'Neko_1212' }] }),
    });
    assert.equal(denied.status, 403);
    await assert.rejects(
      DatabaseBackup.recover(file, restoredState),
      (error) => error.code === 'EEXIST',
    );
    const corrupt = join(directory, 'b'.repeat(64) + '.sqlite');
    await writeFile(corrupt, 'not a database');
    await assert.rejects(DatabaseBackup.recover(corrupt, join(directory, 'broken')));
  } finally {
    await live?.runtime.dispose();
  }
});

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';
import { ServerMaintenance } from '../src/server-maintenance.mjs';
import { WarpApplication } from '../src/worker.mjs';
import { DatabaseUsage, MeasuredDatabase } from '../src/database-usage.mjs';
import { sqliteDatabase } from './sqlite-d1-fixture.mjs';

const now = Date.UTC(2026, 9, 21);
const hash = (number) => number.toString(16).padStart(64, '0');

async function fixture(context) {
  let database;
  if (process.env.PUBLIC_WARPS_TEST_SQLITE === '1') {
    database = sqliteDatabase(context);
  } else {
    const runtime = new Miniflare(
      convertV4MiniflareOptions({
        modules: true,
        script: 'export default {fetch(){return new Response("ok")}}',
        compatibilityDate: '2026-10-11',
        d1Databases: ['DB'],
      }),
    );
    context.after(() => runtime.dispose());
    database = await runtime.getD1Database('DB');
  }
  const directory = new URL('../migrations/', import.meta.url);
  for (const file of (await readdir(directory)).filter((name) => name.endsWith('.sql')).sort()) {
    await database.exec((await readFile(new URL(file, directory), 'utf8')).replaceAll('\n', ' '));
  }
  await database.prepare("INSERT INTO contributors(id,token_hash,created_at) VALUES ('owner','token',?)").bind(now).run();
  return database;
}

async function seedPreviews(database, count) {
  for (let start = 1; start <= count; start += 100) {
    await database.batch(
      Array.from({ length: Math.min(100, count - start + 1) }, (_, offset) =>
        database.prepare('INSERT INTO previews(hash,bytes,size,created_at) VALUES (?,X\'01\',1,?)')
          .bind(hash(start + offset), now),
      ),
    );
  }
}

test('cleanup preserves every current, historical and submission reference across both schemas', async (context) => {
  const database = await fixture(context);
  await seedPreviews(database, 8);
  await database.batch([
    database.prepare(`INSERT INTO server_warps(server_id,uuid,name,snapshot_hash,previous_hash,previous_until,first_valid_at)
      VALUES ('catrealm','server-warp','Player',?,?,?,1)`).bind(hash(1), hash(2), now + 3600000),
    database.prepare(`INSERT INTO warps(uuid,name,snapshot_hash,previous_hash)
      VALUES ('legacy-warp','Legacy',?,?)`).bind(hash(3), hash(4)),
    database.prepare(`INSERT INTO server_submissions(id,server_id,uuid,contributor,hash,kind,created_at,expires_at)
      VALUES ('server-submission','catrealm','server-warp','owner',?,'preview',?,?)`)
      .bind(hash(5), now, now + 3600000),
    database.prepare(`INSERT INTO submissions(uuid,contributor,hash,kind,created_at,expires_at)
      VALUES ('legacy-warp','owner',?,'preview',?,?)`).bind(hash(6), now, now + 3600000),
  ]);
  await new ServerMaintenance(database, now).clean();
  assert.deepEqual((await database.prepare('SELECT hash FROM previews ORDER BY hash').all()).results.map((r) => r.hash),
    [1, 2, 3, 4, 5, 6].map(hash));
  assert.equal(await database.prepare("SELECT value FROM metadata WHERE key='stored_bytes'").first('value'), 6);
});

test('persisted claims throttle concurrent instances and bound preview inspection across cursor wraps', async (context) => {
  const database = await fixture(context);
  await seedPreviews(database, 205);
  const protectedRows = Array.from({ length: 100 }, (_, index) =>
    database.prepare(`INSERT INTO server_warps(server_id,uuid,name,snapshot_hash,first_valid_at)
      VALUES ('catbud',?,'Player',?,1)`).bind('warp-' + index, hash(index + 1)),
  );
  await database.batch(protectedRows);
  await Promise.all([new ServerMaintenance(database, now).clean(), new ServerMaintenance(database, now).clean()]);
  assert.equal(await database.prepare('SELECT COUNT(*) AS n FROM previews').first('n'), 205);
  assert.equal(await database.prepare("SELECT cursor FROM maintenance_state WHERE key='server_cleanup'").first('cursor'), hash(100));
  await new ServerMaintenance(database, now + 60000).clean();
  assert.equal(await database.prepare('SELECT COUNT(*) AS n FROM previews').first('n'), 205);
  await new ServerMaintenance(database, now + ServerMaintenance.INTERVAL).clean();
  assert.equal(await database.prepare('SELECT COUNT(*) AS n FROM previews').first('n'), 105);
  await database.prepare('INSERT INTO previews(hash,bytes,size,created_at) VALUES (?,X\'01\',1,?)').bind(hash(0), now).run();
  await new ServerMaintenance(database, now + 2 * ServerMaintenance.INTERVAL).clean();
  assert.equal(await database.prepare('SELECT COUNT(*) AS n FROM previews').first('n'), 101);
  await new ServerMaintenance(database, now + 3 * ServerMaintenance.INTERVAL).clean();
  assert.equal(await database.prepare('SELECT hash FROM previews WHERE hash=?').bind(hash(0)).first(), null);
});

test('references added after candidate selection are checked again before deletion', async (context) => {
  const database = await fixture(context);
  await seedPreviews(database, 1);
  const statements = await new ServerMaintenance(database, now).previewCleanup('');
  await database.prepare(`INSERT INTO server_warps(server_id,uuid,name,snapshot_hash,first_valid_at)
    VALUES ('catbud','new-reference','Player',?,1)`).bind(hash(1)).run();
  await database.batch(statements);
  assert.ok(await database.prepare('SELECT hash FROM previews WHERE hash=?').bind(hash(1)).first());
});

test('change retention advances each namespace floor without deleting recent history', async (context) => {
  const database = await fixture(context);
  for (const server of ['catbud', 'catrealm']) {
    await database.batch([
      database.prepare('UPDATE server_namespaces SET revision=2 WHERE id=?').bind(server),
      database.prepare(`INSERT INTO server_changes(server_id,revision,uuid,deleted,created_at)
        VALUES (?,1,'old-player',0,?)`).bind(server, now - 604800001),
      database.prepare(`INSERT INTO server_changes(server_id,revision,uuid,deleted,created_at)
        VALUES (?,2,'recent-player',0,?)`).bind(server, now),
    ]);
  }
  await new ServerMaintenance(database, now).clean();
  const namespaces = (await database.prepare('SELECT changes_floor FROM server_namespaces').all()).results;
  assert.deepEqual(namespaces.map((row) => row.changes_floor), [1, 1]);
  const changes = (await database.prepare('SELECT revision FROM server_changes').all()).results;
  assert.deepEqual(changes.map((row) => row.revision), [2, 2]);
});

test('failed cleanup batches roll back deletions and cursor progress, then retry safely', async (context) => {
  const database = await fixture(context);
  await seedPreviews(database, 2);
  const failing = {
    prepare: (sql) => database.prepare(sql),
    batch: (statements) => database.batch([...statements,
      database.prepare("INSERT INTO maintenance_state(key) VALUES ('server_cleanup')")]),
  };
  await assert.rejects(new ServerMaintenance(failing, now).clean());
  assert.equal(await database.prepare('SELECT COUNT(*) AS n FROM previews').first('n'), 2);
  assert.equal(await database.prepare("SELECT cursor FROM maintenance_state WHERE key='server_cleanup'").first('cursor'), '');
  await new ServerMaintenance(database, now + ServerMaintenance.INTERVAL).clean();
  assert.equal(await database.prepare('SELECT COUNT(*) AS n FROM previews').first('n'), 0);
});

test('qualification checks still run when cleanup is throttled', async (context) => {
  const database = await fixture(context);
  await new ServerMaintenance(database, now).clean();
  const player = { uuid: 'a29a6851-0d71-411c-953c-b87bab0d12f3', name: 'Neko_1212' };
  await database.prepare(`INSERT INTO server_warps(server_id,uuid,name,published,first_valid_at)
    VALUES ('catbud',?,?,1,1)`).bind(player.uuid, player.name).run();
  let calls = 0;
  const application = new WarpApplication({ DB: database }, now + 60000, async () => {
    calls++;
    return Response.json({ ...player, pass: true });
  }, () => {});
  await application.scheduled();
  assert.equal(calls, 1);
  assert.equal(await database.prepare('SELECT checked_at FROM server_warps WHERE uuid=?').bind(player.uuid).first('checked_at'), now + 60000);
});

test('indexed bounded preview cleanup sharply reduces measured D1 row reads', async (context) => {
  if (process.env.PUBLIC_WARPS_TEST_SQLITE === '1') {
    context.skip('Requires the Cloudflare Workers runtime for D1 row accounting');
    return;
  }
  const database = await fixture(context);
  await seedPreviews(database, 200);
  for (let start = 1; start <= 200; start += 100) {
    await database.batch(Array.from({ length: 100 }, (_, index) =>
      database.prepare(`INSERT INTO server_warps(server_id,uuid,name,snapshot_hash,first_valid_at)
        VALUES ('catbud',?,'Player',?,1)`).bind('warp-' + (start + index), hash(start + index)),
    ));
  }
  await database.exec('DROP INDEX server_warps_current_hash; DROP INDEX server_warps_previous_hash');
  const old = await database.prepare(`DELETE FROM previews WHERE hash IN (SELECT hash FROM previews
    WHERE NOT EXISTS (SELECT 1 FROM server_warps WHERE snapshot_hash=hash OR previous_hash=hash)
    AND NOT EXISTS (SELECT 1 FROM server_submissions WHERE server_submissions.hash=previews.hash)
    AND NOT EXISTS (SELECT 1 FROM warps WHERE snapshot_hash=hash OR previous_hash=hash)
    AND NOT EXISTS (SELECT 1 FROM submissions WHERE submissions.hash=previews.hash) LIMIT 10)`).run();
  await database.exec(`CREATE INDEX server_warps_current_hash ON server_warps(snapshot_hash);
    CREATE INDEX server_warps_previous_hash ON server_warps(previous_hash)`);
  const usage = new DatabaseUsage('preview-cleanup', now);
  const measured = new MeasuredDatabase(database, usage);
  const statements = await new ServerMaintenance(measured, now).previewCleanup('');
  await measured.batch(statements);
  const reads = usage.rowsRead;
  context.diagnostic(JSON.stringify({ oldRowsRead: old.meta.rows_read, boundedRowsReadIncludingCandidates: reads }));
  assert.ok(reads < old.meta.rows_read / 10);
  assert.equal(await database.prepare('SELECT COUNT(*) AS n FROM previews').first('n'), 200);
});

test('preview reference checks and change deletion use indexed lookups', async (context) => {
  const database = await fixture(context);
  await seedPreviews(database, 1);
  const captured = [];
  const capture = {
    prepare(sql) {
      captured.push(sql);
      return database.prepare(sql);
    },
    batch: (statements) => database.batch(statements),
  };
  await new ServerMaintenance(capture, now).clean();
  const previewSql = captured.find((sql) => sql.startsWith('DELETE FROM previews'));
  const plan = (await database.prepare('EXPLAIN QUERY PLAN ' + previewSql).bind(hash(1)).all()).results
    .map((row) => row.detail).join('\n');
  assert.match(plan, /server_warps_current_hash/);
  assert.match(plan, /server_warps_previous_hash/);
  assert.doesNotMatch(plan, /SCAN (?:w|s|previews)\b/);
  const changesSql = captured.find((sql) => sql.startsWith('DELETE FROM server_changes'));
  const changesPlan = (await database.prepare('EXPLAIN QUERY PLAN ' + changesSql).all()).results
    .map((row) => row.detail).join('\n');
  assert.doesNotMatch(changesPlan, /SCAN (?:c|server_changes)\b/);
});

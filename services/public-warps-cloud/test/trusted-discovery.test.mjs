import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';
import { Budgets } from '../src/budgets.mjs';
import { ServerQualification } from '../src/server-qualification.mjs';
import { ServerSubmissions } from '../src/server-submissions.mjs';
import { ServerWarpList } from '../src/server-warp-list.mjs';

test('trusted completions publish without inventing arrivals and first failures remain excluded', async (context) => {
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
  for (const file of [
    '0001_public_warps.sql',
    '0002_review_guards.sql',
    '0003_change_retention.sql',
    '0004_server_namespaces.sql',
    '0005_namespace_cutover.sql',
    '0006_management_requests.sql',
    '0007_owner_editing.sql',
  ]) {
    await database.exec(
      (await readFile(new URL('../migrations/' + file, import.meta.url), 'utf8')).replaceAll(
        '\n',
        ' ',
      ),
    );
  }
  await database
    .prepare(
      "INSERT INTO contributors(id,token_hash,created_at,role) VALUES ('owner',?,1,'trusted')",
    )
    .bind('a'.repeat(64))
    .run();
  await database
    .prepare("INSERT INTO contributor_servers(contributor,server_id) VALUES ('owner','catbud')")
    .run();
  const now = Date.now();
  const budgets = new Budgets(database, now);
  const player = { uuid: '257205c3-c703-4034-8af8-14ee22720ceb', name: 'YuzuCha323' };
  let active = true;
  const qualification = new ServerQualification(database, budgets, {}, async () =>
    Response.json({ ...player, pass: active }),
  );
  const submissions = new ServerSubmissions(database, budgets, {}, qualification);
  const lists = new ServerWarpList(database, now);
  const trusted = { id: 'owner', trusted: true };
  const ordinary = { id: 'owner', trusted: false };
  const readWarp = () =>
    database
      .prepare("SELECT * FROM server_warps WHERE server_id='catbud' AND uuid=?")
      .bind(player.uuid)
      .first();

  assert.equal((await submissions.discover('catbud', [player], ordinary)).published, false);
  assert.equal((await readWarp()).published, 0);
  active = false;
  assert.equal((await submissions.discover('catbud', [player], trusted)).published, false);
  active = true;
  await database.prepare("UPDATE server_warps SET checked_at=0 WHERE server_id='catbud'").run();
  assert.equal((await submissions.discover('catbud', [player], trusted)).published, true);
  assert.equal((await readWarp()).first_valid_at, 0, 'a completion must not invent a teleport');
  assert.equal((await readWarp()).last_success, 0);
  assert.equal(
    (
      await (
        await lists.list('catrealm', new Request('https://warps.test/v2/servers/catrealm/warps'))
      ).json()
    ).warps.length,
    0,
  );

  await submissions.observe('catbud', { ...player, reason: 'target_no_pass_or_set' }, ordinary);
  assert.equal(
    (await readWarp()).published,
    1,
    'ordinary failure cannot remove a trusted publication',
  );
  await submissions.observe('catbud', { ...player, reason: 'target_no_pass_or_set' }, trusted);
  assert.equal((await readWarp()).published, 0);
  assert.equal(
    (await submissions.discover('catbud', [player], trusted)).results[0].recorded,
    false,
  );
  assert.equal((await readWarp()).published, 0, 'completion refresh cannot revive a first failure');

  await submissions.observe(
    'catbud',
    { ...player, reason: 'valid_launch', submissionId: crypto.randomUUID() },
    trusted,
  );
  assert.ok((await readWarp()).first_valid_at > 0);
  await submissions.observe(
    'catbud',
    { ...player, reason: 'target_no_pass_or_set', submissionId: crypto.randomUUID() },
    trusted,
  );
  assert.equal((await readWarp()).published, 1, 'previously valid warps remain listed');
  assert.equal((await readWarp()).availability, 'unavailable');
});

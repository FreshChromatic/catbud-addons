import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';
import { WarpApplication } from '../src/worker.mjs';
import { OfficialCertificateVerifier } from '../src/official-certificate-verifier.mjs';
import { OwnerCertificateFixture } from './owner-certificate-fixture.mjs';
import { sha256 } from '../src/protocol.mjs';
import { ServerMaintenance } from '../src/server-maintenance.mjs';
import { ManagementActions } from '../src/management-actions.mjs';

test('official ownership permits only same-server own metadata with bounded replay and expiry', async (context) => {
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
  const directory = new URL('../migrations/', import.meta.url);
  for (const file of (await readdir(directory)).filter((file) => file.endsWith('.sql')).sort()) {
    await database.exec((await readFile(new URL(file, directory), 'utf8')).replaceAll('\n', ' '));
  }
  const now = Date.now();
  const uuid = '257205c3-c703-4034-8af8-14ee22720ceb';
  const otherUuid = 'a809b017-f3e5-4203-96fb-32c434618d93';
  for (const server of ['catbud', 'catrealm']) {
    await database
      .prepare(
        `INSERT INTO server_warps(server_id,uuid,name,published,pass_state,checked_at,tags,availability)
      VALUES (?,?,'YuzuCha323',1,'active',?,'["Shop"]','unavailable')`,
      )
      .bind(server, uuid, now)
      .run();
  }
  await database
    .prepare(
      "INSERT INTO server_warps(server_id,uuid,name,published) VALUES ('catbud',?,'OtherPlayer',1)",
    )
    .bind(otherUuid)
    .run();
  let officialId = null;
  let proofReads = 0;
  const fixture = new OwnerCertificateFixture(now);
  const certificateVerifier = new OfficialCertificateVerifier([fixture.trust], now);
  const verifier = {
    async verify(...args) {
      proofReads++;
      return certificateVerifier.verify(...args);
    },
  };
  let activePass = true;
  let qualificationDown = false;
  const application = new WarpApplication(
    { DB: database, POSTING_ENABLED: 'true' },
    now,
    async () => {
      if (qualificationDown) throw new Error('qualification offline');
      return Response.json({ uuid, name: 'YuzuCha323', pass: activePass });
    },
    () => {},
  );
  application.ownerVerifier = verifier;
  const call = (route, value, token, server = 'catbud') =>
    application.handle(
      new Request(`https://warps.test/v2/servers/${server}/owner/${route}`, {
        method: value === undefined ? 'GET' : 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...(token ? { Authorization: 'Bearer ' + token } : {}),
        },
        ...(value === undefined ? {} : { body: JSON.stringify(value) }),
      }),
    );
  const newChallenge = async () => {
    const clientNonce = '1'.repeat(64);
    const response = await call('challenge', { uuid, name: 'YuzuCha323', clientNonce });
    assert.equal(response.status, 200, await response.clone().text());
    const result = await response.json();
    assert.equal(
      result.serverHash,
      (
        await sha256(
          [
            'catbud-owner-edit-v1',
            'https://warps.test',
            'catbud',
            uuid,
            clientNonce,
            result.serverNonce,
          ].join('\n'),
        )
      ).slice(0, 40),
    );
    return {
      challengeId: result.challengeId,
      proofKey: result.proofKey,
      ...(officialId ? fixture.proof(result, officialId) : {}),
    };
  };
  assert.equal((await call('metadata', { displayName: 'Forged' })).status, 401);
  const offline = await newChallenge();
  assert.equal(
    (await call('verify', offline)).status,
    401,
    'offline identity alone is not ownership',
  );
  officialId = otherUuid;
  assert.equal(
    (await call('verify', await newChallenge())).status,
    403,
    'a different premium account is rejected',
  );
  officialId = uuid;
  const proof = await newChallenge();
  assert.equal((await call('verify', { ...proof, proofKey: 'f'.repeat(64) })).status, 401);
  const beforeReads = proofReads;
  const verified = await Promise.all([call('verify', proof), call('verify', proof)]);
  assert.deepEqual(verified.map((response) => response.status).sort(), [200, 401]);
  assert.equal(proofReads, beforeReads + 1);
  const session = await verified.find((response) => response.status === 200).json();
  assert.equal((await call('warp', undefined, session.ownerToken, 'catrealm')).status, 401);
  const current = (await (await call('warp', undefined, session.ownerToken)).json()).warp;
  const value = {
    displayName: 'My market',
    description: 'Shops\nTrading area',
    expectedRevision: current.revision,
    requestId: crypto.randomUUID(),
  };
  assert.equal(
    (await call('metadata', { ...value, uuid: otherUuid }, session.ownerToken)).status,
    400,
  );
  assert.equal((await call('metadata', { ...value, tags: [] }, session.ownerToken)).status, 400);
  const saved = await call('metadata', value, session.ownerToken);
  assert.equal(saved.status, 200, await saved.clone().text());
  const warp = (await saved.json()).warp;
  assert.equal(warp.displayName, 'My market');
  assert.equal(warp.description, value.description);
  assert.deepEqual(warp.tags, ['Shop']);
  assert.equal(warp.availability, 'unavailable');
  const deniedContributor = await application.handle(
    new Request('https://warps.test/v2/servers/catbud/observations', {
      method: 'POST',
      headers: { Authorization: 'Bearer ' + session.ownerToken },
      body: JSON.stringify({ uuid, name: 'YuzuCha323', reason: 'successful_arrival' }),
    }),
  );
  assert.equal(deniedContributor.status, 403, 'owner credentials cannot submit observations');
  assert.equal((await call('metadata', value, session.ownerToken)).status, 409);
  assert.equal(
    (await call('metadata', { ...value, requestId: crypto.randomUUID() }, session.ownerToken))
      .status,
    409,
  );
  assert.equal(
    await database
      .prepare("SELECT display_name FROM server_warps WHERE server_id='catrealm' AND uuid=?")
      .bind(uuid)
      .first('display_name'),
    null,
  );
  assert.equal(
    await database
      .prepare(
        "SELECT COUNT(*) AS n FROM server_audit WHERE action='owner_metadata' AND source_role='owner'",
      )
      .first('n'),
    1,
  );
  await new ManagementActions(database, {}, now).warp(
    'catbud',
    uuid,
    'metadata',
    {
      expectedRevision: warp.revision,
      metadata: { displayName: 'Admin name', description: 'Line one\nLine two', tags: ['Shop'] },
    },
    'administrator',
    crypto.randomUUID(),
  );
  assert.equal(
    (
      await call(
        'metadata',
        { ...value, expectedRevision: warp.revision, requestId: crypto.randomUUID() },
        session.ownerToken,
      )
    ).status,
    409,
  );
  activePass = false;
  const beforePassCheck = (await (await call('warp', undefined, session.ownerToken)).json()).warp;
  assert.equal(
    (
      await call(
        'metadata',
        { ...value, expectedRevision: beforePassCheck.revision, requestId: crypto.randomUUID() },
        session.ownerToken,
      )
    ).status,
    422,
  );
  qualificationDown = true;
  const beforeOutage = (await (await call('warp', undefined, session.ownerToken)).json()).warp;
  assert.equal(
    (
      await call(
        'metadata',
        { ...value, expectedRevision: beforeOutage.revision, requestId: crypto.randomUUID() },
        session.ownerToken,
      )
    ).status,
    503,
  );
  assert.equal(
    await database
      .prepare("SELECT description FROM server_warps WHERE server_id='catbud' AND uuid=?")
      .bind(uuid)
      .first('description'),
    'Line one\nLine two',
  );
  const tampered = await newChallenge();
  tampered.certificate.expiresAt += 1;
  assert.equal((await call('verify', tampered)).status, 403);
  const wrongChallenge = await newChallenge();
  wrongChallenge.signature = Buffer.alloc(256).toString('base64');
  assert.equal((await call('verify', wrongChallenge)).status, 403);
  const expiredCertificate = await newChallenge();
  expiredCertificate.certificate.expiresAt = now - 1;
  assert.equal((await call('verify', expiredCertificate)).status, 401);
  const fakeIssuer = await newChallenge();
  fakeIssuer.certificate.signature = Buffer.alloc(256).toString('base64');
  assert.equal((await call('verify', fakeIssuer)).status, 403);
  await database
    .prepare('UPDATE owner_sessions SET expires_at=?')
    .bind(now - 1)
    .run();
  assert.equal((await call('warp', undefined, session.ownerToken)).status, 401);
  const expired = await newChallenge();
  await database
    .prepare('UPDATE owner_challenges SET expires_at=? WHERE id=?')
    .bind(now - 1, expired.challengeId)
    .run();
  assert.equal((await call('verify', expired)).status, 401);
  await new ServerMaintenance(database, now + 3600000).clean();
  assert.equal(await database.prepare('SELECT COUNT(*) AS n FROM owner_sessions').first('n'), 0);
  assert.equal(await database.prepare('SELECT COUNT(*) AS n FROM owner_challenges').first('n'), 0);
});

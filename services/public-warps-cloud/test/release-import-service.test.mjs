import test from 'node:test';
import assert from 'node:assert/strict';
import { ReleaseImportService } from '../src/addons/release-import-service.mjs';
import { signedRelease } from './addon-release-fixture.mjs';

const api = 'https://api.github.com/repos/FreshChromatic/catbud-addons/releases/tags/v1.0.2';

test('upstream failures identify missing releases, rate limits, HTTP failures and connection errors', async () => {
  for (const [status, headers, message] of [
    [404, {}, /找不到GitHub Release 資料.*HTTP 404/],
    [403, { 'X-RateLimit-Remaining': '0' }, /請求上限.*HTTP 403/],
    [429, {}, /請求上限.*HTTP 429/],
    [502, {}, /GitHub Release 資料.*HTTP 502/],
  ]) {
    const importer = new ReleaseImportService(
      {},
      async () => new Response('', { status, headers }),
    );
    await assert.rejects(
      importer.read(api, 4096),
      (error) => error.status === 503 && message.test(error.message),
    );
  }
  const offline = new ReleaseImportService({}, async () => {
    throw new Error('Network failure');
  });
  await assert.rejects(offline.read(api, 4096), {
    status: 503,
    message: 'GitHub Release 資料連線失敗或逾時，請稍後重試',
  });
});

test('GitHub credentials are confined to API requests and fresh reads do not trust cached redirects', async () => {
  const requests = [];
  const importer = new ReleaseImportService(
    { ADDON_GITHUB_TOKEN: 'test-credential' },
    async (url, options) => {
      requests.push({ url, headers: { ...options.headers }, redirect: options.redirect });
      return requests.length === 1
        ? new Response(null, {
            status: 302,
            headers: { Location: 'https://release-assets.githubusercontent.com/test' },
          })
        : Response.json({ ok: true });
    },
  );
  assert.deepEqual(await importer.read(api, 4096), { ok: true });
  assert.equal(requests[0].headers.Authorization, 'Bearer test-credential');
  assert.equal(requests[1].headers.Authorization, undefined);
  assert.equal(requests[0].headers['Cache-Control'], 'no-cache');
  assert.equal(requests[1].headers['Cache-Control'], 'no-cache');
  assert.equal(requests[0].redirect, 'manual');
  const malicious = new ReleaseImportService(
    {},
    async () =>
      new Response(null, {
        status: 302,
        headers: { Location: 'https://untrusted.test/manifest.json' },
      }),
  );
  await assert.rejects(malicious.read(api, 4096), {
    status: 400,
    message: 'Untrusted release source',
  });
});

test('missing assets identify the exact file that no longer matches the signed manifest', async () => {
  const release = signedRelease();
  const removed = release.assets.shift();
  const importer = new ReleaseImportService(
    { ADDON_RELEASE_KEYS: JSON.stringify(release.keys) },
    release.fetcher,
  );
  await assert.rejects(importer.import(release.manifest.tag), {
    status: 400,
    message: '版本附件缺少或與簽署清單不符：' + removed.name,
  });
});

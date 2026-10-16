import test from 'node:test';
import assert from 'node:assert/strict';
import worker from '../src/worker.mjs';
import { BurstGuard } from '../src/burst-guard.mjs';

test('burst rejection and binding outages never access D1 or disclose contributor credentials', async () => {
  let databaseAccesses = 0;
  const env = {
    get DB() {
      databaseAccesses++;
      throw new Error('D1 should not be reached');
    },
    ORIGIN_BURST: { limit: async () => ({ success: false }) },
  };
  const request = new Request('https://warps.test/v1/discoveries', {
    method: 'POST',
    headers: { Authorization: 'Bearer private-contributor-credential' },
  });
  const rejected = await worker.fetch(request, env, {});
  assert.equal(rejected.status, 429);
  assert.equal(databaseAccesses, 0);
  env.ORIGIN_BURST.limit = async () => {
    throw new Error('private-contributor-credential');
  };
  const unavailable = await worker.fetch(request, env, {});
  assert.equal(unavailable.status, 503);
  assert.equal(databaseAccesses, 0);
  assert.ok(!(await unavailable.text()).includes('private-contributor-credential'));
});

test('source limits use bounded hashed addresses and fixed fallback keys', async () => {
  const keys = [];
  const limiter = {
    limit: async ({ key }) => {
      keys.push(key);
      return { success: true };
    },
  };
  const guard = new BurstGuard({ ORIGIN_BURST: limiter, PUBLIC_BURST: limiter });
  assert.equal(
    await guard.reject(
      new Request('https://warps.test/v1/warps', {
        headers: { 'CF-Connecting-IP': '203.0.113.1' },
      }),
    ),
    null,
  );
  assert.equal(keys[0], 'requests');
  assert.match(keys[1], /^[0-9a-f]{64}$/);
  assert.equal(
    await guard.reject(
      new Request('https://warps.test/v1/warps', {
        headers: { 'CF-Connecting-IP': 'x'.repeat(1000) },
      }),
    ),
    null,
  );
  assert.equal(keys.at(-1), 'unknown-source');
});

import test from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPair, exportJWK, createLocalJWKSet, SignJWT } from 'jose';
import { AccessVerifier } from '../src/access-verifier.mjs';

test('Access signature, issuer, audience, expiry, allow list and session-bound CSRF are required', async () => {
  const now = Date.now();
  const { privateKey, publicKey } = await generateKeyPair('RS256');
  const jwk = { ...(await exportJWK(publicKey)), kid: 'test-key', alg: 'RS256' };
  const env = {
    ACCESS_TEAM_DOMAIN: 'https://test-team.cloudflareaccess.com',
    ACCESS_AUD: 'a'.repeat(64),
    ACCESS_ALLOWED_EMAILS: 'owner@example.test',
    CSRF_SECRET: 'b'.repeat(64),
    ADMIN_ORIGIN: 'https://admin.test',
  };
  const verifier = new AccessVerifier(env, now, createLocalJWKSet({ keys: [jwk] }));
  const sign = (claims = {}) =>
    new SignJWT({ email: 'owner@example.test', ...claims })
      .setProtectedHeader({ alg: 'RS256', kid: 'test-key' })
      .setIssuer(env.ACCESS_TEAM_DOMAIN)
      .setAudience(env.ACCESS_AUD)
      .setSubject('owner')
      .setIssuedAt(Math.floor(now / 1000))
      .setExpirationTime(Math.floor(now / 1000) + 60)
      .sign(privateKey);
  const request = (token, headers = {}) =>
    new Request('https://admin.test/api/session', {
      headers: { 'Cf-Access-Jwt-Assertion': token, ...headers },
    });
  const token = await sign();
  const identity = await verifier.require(request(token));
  assert.equal(identity.actor, 'owner@example.test');
  await assert.rejects(
    verifier.require(request(token.slice(0, -8) + 'AAAAAAAA')),
    (error) => error.status === 401,
  );
  await assert.rejects(
    new AccessVerifier(
      { ...env, ACCESS_AUD: 'c'.repeat(64) },
      now,
      createLocalJWKSet({ keys: [jwk] }),
    ).require(request(token)),
    (error) => error.status === 401,
  );
  await assert.rejects(
    new AccessVerifier(
      { ...env, ACCESS_TEAM_DOMAIN: 'https://other.cloudflareaccess.com' },
      now,
      createLocalJWKSet({ keys: [jwk] }),
    ).require(request(token)),
    (error) => error.status === 401,
  );
  await assert.rejects(
    new AccessVerifier(env, now + 120000, createLocalJWKSet({ keys: [jwk] })).require(
      request(token),
    ),
    (error) => error.status === 401,
  );
  await assert.rejects(
    verifier.require(request(await sign({ email: 'other@example.test' }))),
    (error) => error.status === 403,
  );
  const write = (headers = {}) =>
    request(token, {
      Origin: env.ADMIN_ORIGIN,
      'Content-Type': 'application/json',
      'X-CSRF-Token': identity.csrf,
      ...headers,
    });
  assert.doesNotThrow(() => verifier.requireWrite(write(), identity));
  assert.throws(
    () => verifier.requireWrite(write({ Origin: 'https://evil.test' }), identity),
    (error) => error.status === 403,
  );
  assert.throws(
    () => verifier.requireWrite(write({ 'X-CSRF-Token': '0'.repeat(64) }), identity),
    (error) => error.status === 403,
  );
});

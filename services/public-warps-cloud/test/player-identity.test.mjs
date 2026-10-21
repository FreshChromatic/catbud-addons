import assert from 'node:assert/strict';
import test from 'node:test';
import { ApiError, identity } from '../src/protocol.mjs';

test('player identities accept Bedrock names without accepting command arguments', () => {
  const uuid = 'a29a6851-0d71-411c-953c-b87bab0d12f3';
  assert.deepEqual(identity({ uuid, name: '.Steve' }), { uuid, name: '.Steve' });
  assert.deepEqual(identity({ uuid, name: 'Steve' }), { uuid, name: 'Steve' });
  for (const name of [
    '.',
    '..Steve',
    '.Steve other',
    '.Steve\n',
    'Steve/other',
    '.12345678901234567',
  ]) {
    assert.throws(() => identity({ uuid, name }), ApiError);
  }
});

import { ApiError } from './api-error.mjs';
import { NAME } from './player-identity.mjs';

export async function verifyPlayer(uuid, name, fetcher = fetch) {
  const response = await fetcher(`https://api.catbud.net/discord/player/${uuid}?detailed=true`, {
    signal: AbortSignal.timeout(10000),
  });
  if (response.status === 404) {
    throw new ApiError(404, 'Catbud player not found');
  }
  if (response.status === 429 || response.status >= 500) {
    throw new ApiError(503, 'Catbud temporarily unavailable');
  }
  if (!response.ok) {
    throw new ApiError(502, 'Catbud verification failed');
  }
  let bytes = 0,
    chunks = [];
  try {
    for await (const chunk of response.body) {
      bytes += chunk.length;
      if (bytes > 1024 * 1024) {
        throw new ApiError(502, 'Catbud response too large');
      }
      chunks.push(chunk);
    }
  } catch (e) {
    throw e instanceof ApiError ? e : new ApiError(502, 'Catbud response interrupted');
  }
  let player;
  try {
    player = JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } catch {
    throw new ApiError(502, 'Unrecognized Catbud player response');
  }
  // Explicit boolean only. Unknown API shapes fail closed until a real response establishes the contract.
  if (typeof player?.pass !== 'boolean') {
    throw new ApiError(502, 'Unrecognized Catbud player response');
  }
  if (player.pass !== true) {
    throw new ApiError(422, 'Player has no active pass');
  }
  // A real Catbud response supplies uuid/name/pass at the root; verify that authoritative association.
  if (
    typeof player.uuid !== 'string' ||
    typeof player.name !== 'string' ||
    player.uuid.replaceAll('-', '').toLowerCase() !== uuid.replaceAll('-', '').toLowerCase() ||
    player.name.toLowerCase() !== name.toLowerCase() ||
    !NAME.test(player.name)
  ) {
    throw new ApiError(422, 'Player identity mismatch');
  }
  return { uuid: uuid.toLowerCase(), name: player.name };
}

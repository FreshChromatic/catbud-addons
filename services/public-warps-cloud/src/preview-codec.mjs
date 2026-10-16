import { gunzipSync, gzipSync } from 'node:zlib';
import { validateSnapshotObject } from '../../public-warps/snapshot-codec.mjs';
import { ApiError, sha256 } from './protocol.mjs';
import { sharedAppearance } from './shared-appearance.mjs';

const FIELDS = new Set([
  'version',
  'width',
  'height',
  'depth',
  'dimension',
  'palette',
  'blocks',
  'entities',
  'eyeX',
  'eyeY',
  'eyeZ',
  'viewYaw',
  'viewPitch',
]);
const ENTITY_FIELDS = new Set([
  'type',
  'x',
  'y',
  'z',
  'yaw',
  'pitch',
  'appearance',
  'playerUUID',
  'playerName',
]);

export async function canonicalPreview(bytes) {
  if (bytes.length < 1 || bytes.length > 65536) {
    throw new ApiError(413, 'Compressed preview exceeds limit');
  }
  let snapshot;
  try {
    const expanded = gunzipSync(bytes, { maxOutputLength: 1048576 });
    snapshot = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(expanded));
  } catch {
    throw new ApiError(400, 'Invalid or oversized compressed preview');
  }
  if (!snapshot || Array.isArray(snapshot) || typeof snapshot !== 'object') {
    throw new ApiError(400, 'Invalid preview');
  }
  if (Object.keys(snapshot).some((key) => !FIELDS.has(key))) {
    throw new ApiError(400, 'Unknown preview field');
  }
  if (snapshot.version !== 4) {
    throw new ApiError(400, 'New submissions require the 50-cube format');
  }
  if (
    Array.isArray(snapshot.entities) &&
    snapshot.entities.some(
      (entity) => !entity || Object.keys(entity).some((key) => !ENTITY_FIELDS.has(key)),
    )
  ) {
    throw new ApiError(400, 'Unknown entity field');
  }
  let clean;
  try {
    clean = validateSnapshotObject(snapshot);
  } catch {
    throw new ApiError(400, 'Invalid preview contents');
  }
  clean.entities = clean.entities.filter((entity) => entity.type !== 'minecraft:player');
  if (clean.entities.length > 64) {
    throw new ApiError(413, 'Too many shared entities');
  }
  for (const entity of clean.entities) {
    if (entity.type === 'minecraft:text_display' || entity.appearance?.text != null) {
      throw new ApiError(400, 'Text entities are not shared');
    }
    entity.x = Math.round(entity.x * 16) / 16;
    entity.y = Math.round(entity.y * 16) / 16;
    entity.z = Math.round(entity.z * 16) / 16;
    entity.yaw = Math.round(entity.yaw / 5) * 5;
    entity.pitch = Math.round(entity.pitch / 5) * 5;
    if (entity.appearance) {
      entity.appearance = sharedAppearance(entity.appearance);
    }
  }
  const sortedPalette = [...new Set(clean.palette)].sort();
  const indexes = new Map(sortedPalette.map((state, index) => [state, index]));
  const remapping = clean.palette.map((state) => indexes.get(state));
  clean.blocks = clean.blocks.map((index) => remapping[index]);
  clean.palette = sortedPalette;
  clean.entities.sort(
    (left, right) =>
      left.type.localeCompare(right.type) ||
      left.x - right.x ||
      left.y - right.y ||
      left.z - right.z ||
      JSON.stringify(left.appearance).localeCompare(JSON.stringify(right.appearance)),
  );
  clean.viewYaw = 0;
  clean.viewPitch = 0;
  const canonical = JSON.stringify(clean);
  const encoded = gzipSync(canonical);
  if (encoded.length > 65536 || new TextEncoder().encode(canonical).length > 1048576) {
    throw new ApiError(413, 'Canonical preview exceeds limit');
  }
  return { hash: await sha256(canonical), bytes: encoded };
}

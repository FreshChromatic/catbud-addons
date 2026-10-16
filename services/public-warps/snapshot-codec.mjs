import { createHash } from 'node:crypto';
import { gunzipSync, gzipSync } from 'node:zlib';
import { ApiError } from './api-error.mjs';
import { UUID, NAME } from './player-identity.mjs';

const VISUAL_FIELDS = new Set([
  'variant',
  'Variant',
  'Color',
  'CollarColor',
  'Age',
  'Baby',
  'Saddle',
  'Saddled',
  'equipment',
  'Equipment',
  'ArmorItems',
  'HandItems',
  'Sheared',
  'Type',
  'type',
  'Size',
  'size',
  'VillagerData',
  'villager_data',
  'carriedBlockState',
  'carried_block_state',
  'Item',
  'item',
  'Facing',
  'facing',
  'ItemRotation',
  'item_rotation',
  'Invisible',
  'invisible',
  'ShowArms',
  'NoBasePlate',
  'Small',
  'Pose',
  'pose',
  'BlockState',
  'block_state',
  'painting_variant',
  'billboard',
  'transformation',
  'brightness',
  'view_range',
  'shadow_radius',
  'shadow_strength',
  'width',
  'height',
  'glow_color_override',
  'text',
  'line_width',
  'background',
  'text_opacity',
  'alignment',
]);
export function validateSnapshot(encoded) {
  const snapshot = decodeSnapshot(encoded);
  const clean = validateSnapshotObject(snapshot);
  const canonical = JSON.stringify(clean);
  return {
    bytes: gzipSync(canonical),
    hash: createHash('sha256').update(canonical).digest('hex'),
    snapshot: JSON.parse(canonical),
  };
}

export function validateSnapshotObject(snapshot) {
  const scene = validateDimensions(snapshot);
  validatePalette(snapshot);
  return canonicalSnapshot(snapshot, scene);
}

function decodeSnapshot(encoded) {
  const validEncoding =
    typeof encoded === 'string' &&
    encoded.length <= 175000 &&
    /^[A-Za-z0-9+/]*={0,2}$/.test(encoded);
  if (!validEncoding) {
    throw new ApiError(400, 'Invalid snapshot encoding');
  }
  const bytes = Buffer.from(encoded, 'base64');
  if (!bytes.length || bytes.length > 128 * 1024) {
    throw new ApiError(413, 'Snapshot too large');
  }
  let snapshot;
  try {
    const json = gunzipSync(bytes, { maxOutputLength: 1024 * 1024 }).toString('utf8');
    snapshot = JSON.parse(json);
  } catch {
    throw new ApiError(400, 'Invalid compressed snapshot');
  }
  if (!snapshot || typeof snapshot !== 'object' || Array.isArray(snapshot)) {
    throw new ApiError(400, 'Invalid snapshot object');
  }
  return snapshot;
}

function validateDimensions(snapshot) {
  const legacy =
    snapshot.version === 1 &&
    snapshot.width === 10 &&
    snapshot.depth === 10 &&
    Number.isInteger(snapshot.height) &&
    snapshot.height >= 1 &&
    snapshot.height <= 32;
  const scene =
    (snapshot.version === 2 && isCube(snapshot, 15)) ||
    (snapshot.version === 3 && isCube(snapshot, 30)) ||
    (snapshot.version === 4 && isCube(snapshot, 50));
  const validDimension =
    typeof snapshot.dimension === 'string' &&
    /^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(snapshot.dimension);
  const blockCount = snapshot.width * snapshot.height * snapshot.depth;
  const validPalette =
    Array.isArray(snapshot.palette) &&
    snapshot.palette.length > 0 &&
    snapshot.palette.length <= blockCount;
  const validBlocks = Array.isArray(snapshot.blocks) && snapshot.blocks.length === blockCount;
  if ((!legacy && !scene) || !validDimension || !validPalette || !validBlocks) {
    throw new ApiError(400, 'Invalid snapshot dimensions');
  }
  return scene;
}

function isCube(snapshot, size) {
  return snapshot.width === size && snapshot.height === size && snapshot.depth === size;
}

function validatePalette(snapshot) {
  for (const state of snapshot.palette) {
    const validState =
      typeof state === 'string' &&
      state.length <= 512 &&
      /^[a-z0-9_.-]+:[a-z0-9_./-]+(?:\[[a-z0-9_=,.-]+\])?$/.test(state);
    if (!validState) {
      throw new ApiError(400, 'Invalid block state');
    }
  }
  const validIndices = snapshot.blocks.every(
    (index) => Number.isInteger(index) && index >= 0 && index < snapshot.palette.length,
  );
  if (!validIndices) {
    throw new ApiError(400, 'Invalid block index');
  }
}

function withinBounds(coordinate, size) {
  return Number.isFinite(coordinate) && coordinate >= 0 && coordinate <= size;
}

function sceneEntities(snapshot) {
  const validEntities = Array.isArray(snapshot.entities) && snapshot.entities.length <= 256;
  const validPosition =
    withinBounds(snapshot.eyeX, snapshot.width) &&
    withinBounds(snapshot.eyeY, snapshot.height) &&
    withinBounds(snapshot.eyeZ, snapshot.depth);
  const validRotation = Number.isFinite(snapshot.viewYaw) && Number.isFinite(snapshot.viewPitch);
  if (!validEntities || !validPosition || !validRotation) {
    throw new ApiError(400, 'Invalid snapshot camera/entities');
  }
  return snapshot.entities.map((entity) => canonicalEntity(entity, snapshot));
}

function canonicalEntity(entity, snapshot) {
  if (!entity) {
    throw new ApiError(400, 'Invalid entity');
  }
  const validType =
    typeof entity.type === 'string' && /^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(entity.type);
  const validPosition =
    withinBounds(entity.x, snapshot.width) &&
    withinBounds(entity.y, snapshot.height) &&
    withinBounds(entity.z, snapshot.depth);
  const validRotation = Number.isFinite(entity.yaw) && Number.isFinite(entity.pitch);
  if (!validType || !validPosition || !validRotation) {
    throw new ApiError(400, 'Invalid entity');
  }
  const clean = {
    type: entity.type,
    x: entity.x,
    y: entity.y,
    z: entity.z,
    yaw: entity.yaw,
    pitch: entity.pitch,
  };
  if (entity.appearance != null) {
    validateAppearance(entity.appearance);
    clean.appearance = entity.appearance;
  }
  if (entity.type === 'minecraft:player') {
    if (!UUID.test(entity.playerUUID ?? '') || !NAME.test(entity.playerName ?? '')) {
      throw new ApiError(400, 'Invalid player identity');
    }
    clean.playerUUID = entity.playerUUID;
    clean.playerName = entity.playerName;
  } else if (entity.playerUUID != null || entity.playerName != null) {
    throw new ApiError(400, 'Unexpected player identity');
  }
  return clean;
}

function validateAppearance(appearance) {
  const validObject = typeof appearance === 'object' && !Array.isArray(appearance);
  if (!validObject || Object.keys(appearance).length > 64) {
    throw new ApiError(400, 'Invalid entity appearance');
  }
  let length = 0;
  for (const [key, value] of Object.entries(appearance)) {
    const allowedField =
      VISUAL_FIELDS.has(key) && typeof value === 'string' && value.length <= 4096;
    if (!allowedField) {
      throw new ApiError(400, 'Invalid entity appearance');
    }
    length += value.length;
  }
  if (length > 16384) {
    throw new ApiError(400, 'Invalid entity appearance');
  }
}

function canonicalSnapshot(snapshot, scene) {
  // Field order defines the content hash, including previously stored v1/v2 snapshots.
  const clean = {
    version: snapshot.version,
    width: snapshot.width,
    height: snapshot.height,
    depth: snapshot.depth,
    dimension: snapshot.dimension,
    palette: snapshot.palette,
    blocks: snapshot.blocks,
  };
  if (scene) {
    Object.assign(clean, {
      entities: sceneEntities(snapshot),
      eyeX: snapshot.eyeX,
      eyeY: snapshot.eyeY,
      eyeZ: snapshot.eyeZ,
      viewYaw: snapshot.viewYaw,
      viewPitch: snapshot.viewPitch,
    });
  }
  return clean;
}

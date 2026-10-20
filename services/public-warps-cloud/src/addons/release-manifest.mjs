import { ApiError } from '../protocol.mjs';

export const REPOSITORY = 'FreshChromatic/catbud-addons';
export const MINECRAFT = ['1.21.11', '26.1.2', '26.2', '26.3'];
export const MODULES = {
  core: 'catbud_core',
  magic_tower: 'catbud_addons',
  public_warps: 'catbud_public_warps',
  monster_wave: 'catbud_monster_wave',
};
const VERSION = /^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$/;

export function parseReleaseTag(tag) {
  const match =
    /^v((?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*))(?:-r([1-9][0-9]*))?$/.exec(
      tag ?? '',
    );
  const revision = match?.[2] === undefined ? 1 : Number(match[2]);
  if (!match || (match[2] !== undefined && revision < 2) || revision > 2147483647) {
    throw new Error(
      'A stable vMAJOR.MINOR.PATCH tag with an optional -r2 or later revision is required',
    );
  }
  return { version: match[1], revision };
}

export async function verifyManifest(envelope, keys) {
  try {
    if (
      !envelope ||
      typeof envelope.manifest !== 'string' ||
      envelope.manifest.length > 131072 ||
      !/^[a-z0-9-]{1,40}$/.test(envelope.keyId ?? '') ||
      typeof envelope.signature !== 'string' ||
      envelope.signature.length > 128 ||
      !keys[envelope.keyId]
    ) {
      throw new Error('Invalid envelope or unknown signing key');
    }
    const decode = (value) => Uint8Array.from(atob(value), (character) => character.charCodeAt(0));
    const key = await crypto.subtle.importKey(
      'spki',
      decode(keys[envelope.keyId]),
      'Ed25519',
      false,
      ['verify'],
    );
    if (
      !(await crypto.subtle.verify(
        'Ed25519',
        key,
        decode(envelope.signature),
        new TextEncoder().encode(envelope.manifest),
      ))
    ) {
      throw new Error('Invalid signature');
    }
    const manifest = JSON.parse(envelope.manifest);
    validateManifest(manifest);
    return manifest;
  } catch (error) {
    throw new ApiError(400, 'Release manifest verification failed: ' + error.message);
  }
}

export function validateManifest(value) {
  const root = `https://github.com/${REPOSITORY}/releases/`;
  const release = parseReleaseTag(value.tag);
  if (
    value.schemaVersion !== 1 ||
    value.repository !== REPOSITORY ||
    !VERSION.test(value.version) ||
    value.channel !== 'stable' ||
    release.version !== value.version ||
    value.releaseId !== value.tag ||
    value.releasePage !== root + 'tag/' + value.tag ||
    !/^[0-9a-f]{40}$/.test(value.commit) ||
    !Number.isFinite(Date.parse(value.publishedAt)) ||
    !Array.isArray(value.artifacts) ||
    value.artifacts.length < 1 ||
    value.artifacts.length > 16
  ) {
    throw new Error('Unsupported release metadata');
  }
  const seen = new Set();
  for (const artifact of value.artifacts) {
    const suffix = release.revision === 1 ? '' : '-r' + release.revision;
    const file = `catbud-${artifact.module}-${artifact.minecraft}-${value.version}${suffix}.jar`;
    const java = artifact.minecraft === '1.21.11' ? 21 : 25;
    if (
      !Object.hasOwn(MODULES, artifact.module) ||
      artifact.modId !== MODULES[artifact.module] ||
      !MINECRAFT.includes(artifact.minecraft) ||
      artifact.version !== value.version ||
      artifact.file !== file ||
      artifact.id !== artifact.module + ':' + artifact.minecraft ||
      seen.has(artifact.id) ||
      artifact.url !== root + 'download/' + value.tag + '/' + file ||
      !/^[0-9a-f]{64}$/.test(artifact.sha256) ||
      !Number.isSafeInteger(artifact.size) ||
      artifact.size < 1 ||
      artifact.size > 64 * 1024 * 1024 ||
      artifact.javaMinimum !== java ||
      !/^>=[0-9]+\.[0-9]+\.[0-9]+$/.test(artifact.loaderRange) ||
      !Array.isArray(artifact.provides) ||
      typeof artifact.requires !== 'object'
    ) {
      throw new Error('Invalid release artifact');
    }
    seen.add(artifact.id);
    const requires = {
      fabricloader: artifact.loaderRange,
      minecraft: '~' + artifact.minecraft,
      java: '>=' + java,
      'fabric-api': '*',
    };
    if (artifact.module !== 'core') {
      requires.catbud_core = '>=' + value.version;
    }
    if (
      Object.keys(artifact.requires).length !== Object.keys(requires).length ||
      Object.entries(requires).some(([id, range]) => artifact.requires[id] !== range)
    ) {
      throw new Error('Unsupported artifact dependencies');
    }
    const provided = new Map(artifact.provides.map((mod) => [mod.modId, mod.version]));
    if (
      provided.size !== artifact.provides.length ||
      provided.size < 1 ||
      provided.size > (artifact.module === 'core' ? 1 : 2) ||
      provided.get(artifact.modId) !== value.version ||
      [...provided].some(
        ([id, version]) =>
          ![artifact.modId, 'catbud_core'].includes(id) || version !== value.version,
      )
    ) {
      throw new Error('Invalid provided mod metadata');
    }
  }
  if (
    !value.notes ||
    Array.isArray(value.notes) ||
    Object.keys(value.notes).length > 4 ||
    Object.entries(value.notes).some(
      ([module, note]) =>
        !Object.hasOwn(MODULES, module) || typeof note !== 'string' || note.length > 4000,
    )
  ) {
    throw new Error('Invalid release notes');
  }
}

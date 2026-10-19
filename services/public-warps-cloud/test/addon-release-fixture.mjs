import { generateKeyPairSync, sign } from 'node:crypto';
import { MODULES } from '../src/addons/release-manifest.mjs';

export const now = Date.UTC(2026, 9, 18);
const version = '1.0.4';
export const tag = 'v' + version;
const root = 'https://github.com/FreshChromatic/catbud-addons/releases/';

export function signedRelease(modules = Object.keys(MODULES), embeddedCore = true) {
  const pair = generateKeyPairSync('ed25519');
  const keys = { test: pair.publicKey.export({ type: 'spki', format: 'der' }).toString('base64') };
  const artifacts = Object.entries(MODULES)
    .filter(([module]) => modules.includes(module))
    .map(([module, modId]) => {
      const file = `catbud-${module}-26.2-${version}.jar`;
      const requires = {
        fabricloader: '>=0.19.5',
        minecraft: '~26.2',
        java: '>=25',
        'fabric-api': '*',
      };
      const provides = [{ modId, version }];
      if (module !== 'core') {
        requires.catbud_core = '>=' + version;
        if (embeddedCore) {
          provides.push({ modId: 'catbud_core', version });
        }
      }
      return {
        id: module + ':26.2',
        module,
        modId,
        minecraft: '26.2',
        loaderRange: '>=0.19.5',
        javaMinimum: 25,
        version,
        file,
        url: root + 'download/' + tag + '/' + file,
        size: 10,
        sha256: 'a'.repeat(64),
        provides,
        requires,
      };
    });
  const manifest = {
    schemaVersion: 1,
    releaseId: tag,
    version,
    channel: 'stable',
    publishedAt: new Date(now).toISOString(),
    repository: 'FreshChromatic/catbud-addons',
    tag,
    releasePage: root + 'tag/' + tag,
    commit: 'b'.repeat(40),
    artifacts,
    notes: { core: '測試更新' },
  };
  const raw = JSON.stringify(manifest);
  const envelope = {
    manifest: raw,
    keyId: 'test',
    signature: sign(null, Buffer.from(raw), pair.privateKey).toString('base64'),
  };
  const assets = artifacts.map((artifact, id) => ({
    id,
    name: artifact.file,
    browser_download_url: artifact.url,
    size: artifact.size,
    digest: 'sha256:' + artifact.sha256,
    state: 'uploaded',
  }));
  for (const file of ['release-manifest.json', 'release-manifest.sig']) {
    assets.push({ name: file, browser_download_url: root + 'download/' + tag + '/' + file });
  }
  const fetcher = async (url) => {
    if (url.includes('/git/ref/tags/')) {
      return Response.json({ object: { type: 'commit', sha: manifest.commit } });
    }
    if (url.includes('api.github.com')) {
      return Response.json({ tag_name: tag, draft: false, prerelease: false, assets });
    }
    if (url.endsWith('release-manifest.json')) {
      return new Response(envelope.manifest);
    }
    if (url.endsWith('release-manifest.sig')) {
      return Response.json({ keyId: envelope.keyId, signature: envelope.signature });
    }
    throw new Error('Unexpected URL ' + url);
  };
  return { keys, manifest, envelope, assets, fetcher };
}

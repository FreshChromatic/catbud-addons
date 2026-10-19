import { ApiError, boundedBytes } from '../protocol.mjs';
import { REPOSITORY, verifyManifest } from './release-manifest.mjs';
import trustedReleaseKeys from '../../../../release/keys.json' with { type: 'json' };

export class ReleaseImportService {
  constructor(env, fetcher = fetch) {
    this.env = env;
    this.fetcher = fetcher;
  }

  async read(url, limit, json = true) {
    const headers = {
      'User-Agent': 'Catbud-Release-Importer/1',
      Accept: 'application/vnd.github+json',
      'Cache-Control': 'no-cache',
    };
    const initial = new URL(url);
    let source = 'GitHub 簽署清單附件';
    if (initial.hostname === 'api.github.com') {
      source = initial.pathname.includes('/git/') ? 'GitHub 版本標籤' : 'GitHub Release 資料';
    }
    if (this.env.ADDON_GITHUB_TOKEN && new URL(url).hostname === 'api.github.com') {
      headers.Authorization = 'Bearer ' + this.env.ADDON_GITHUB_TOKEN;
    }
    let response;
    for (let hop = 0; hop < 5; hop++) {
      const target = new URL(url);
      if (
        target.protocol !== 'https:' ||
        target.username ||
        target.password ||
        target.port ||
        ![
          'api.github.com',
          'github.com',
          'release-assets.githubusercontent.com',
          'objects.githubusercontent.com',
        ].includes(target.hostname)
      ) {
        throw new ApiError(400, 'Untrusted release source');
      }
      try {
        response = await this.fetcher.call(globalThis, url, {
          headers,
          redirect: 'manual',
          signal: AbortSignal.timeout(15000),
        });
      } catch {
        throw new ApiError(503, `${source}連線失敗或逾時，請稍後重試`);
      }
      if ([301, 302, 303, 307, 308].includes(response.status)) {
        await response.body?.cancel();
        url = new URL(response.headers.get('Location'), url).toString();
        delete headers.Authorization;
        continue;
      }
      if (response.status !== 200) {
        await response.body?.cancel();
        if (
          response.status === 429 ||
          (response.status === 403 && response.headers.get('X-RateLimit-Remaining') === '0')
        ) {
          throw new ApiError(503, `GitHub API 已達請求上限，請稍後重試（HTTP ${response.status}）`);
        }
        if (response.status === 404) {
          throw new ApiError(503, `找不到${source}（HTTP 404），請確認版本已公開且附件完整`);
        }
        throw new ApiError(503, `${source}暫時無法讀取（HTTP ${response.status}），請稍後重試`);
      }
      const text = new TextDecoder('utf-8', { fatal: true }).decode(
        await boundedBytes(response.body, limit),
      );
      return json ? JSON.parse(text) : text;
    }
    throw new ApiError(503, 'Too many GitHub redirects');
  }

  async import(tag) {
    if (!/^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$/.test(tag ?? '')) {
      throw new ApiError(400, 'A stable vMAJOR.MINOR.PATCH tag is required');
    }
    const release = await this.read(
      `https://api.github.com/repos/${REPOSITORY}/releases/tags/${tag}`,
      524288,
    );
    if (
      release.draft ||
      release.prerelease ||
      release.tag_name !== tag ||
      !Array.isArray(release.assets)
    ) {
      throw new ApiError(400, 'Only published stable releases can be imported');
    }
    const assets = new Map(release.assets.map((asset) => [asset.name, asset]));
    const root = `https://github.com/${REPOSITORY}/releases/download/${tag}/`;
    for (const file of ['release-manifest.json', 'release-manifest.sig']) {
      if (assets.get(file)?.browser_download_url !== root + file) {
        throw new ApiError(400, 'Missing signed release manifest');
      }
    }
    const manifest = await this.read(root + 'release-manifest.json', 131072, false);
    const signature = await this.read(root + 'release-manifest.sig', 1024);
    const envelope = { manifest, keyId: signature.keyId, signature: signature.signature };
    const parsed = await verifyManifest(
      envelope,
      this.env.ADDON_RELEASE_KEYS ? JSON.parse(this.env.ADDON_RELEASE_KEYS) : trustedReleaseKeys,
    );
    if (parsed.tag !== tag) {
      throw new ApiError(400, 'Manifest tag does not match release');
    }
    if ((await this.tagCommit(tag)) !== parsed.commit) {
      throw new ApiError(400, 'Signed build commit does not match the GitHub release tag');
    }
    const snapshot = [];
    for (const artifact of parsed.artifacts) {
      const asset = assets.get(artifact.file);
      if (
        !asset ||
        asset.browser_download_url !== artifact.url ||
        asset.size !== artifact.size ||
        asset.digest !== 'sha256:' + artifact.sha256 ||
        asset.state !== 'uploaded'
      ) {
        throw new ApiError(400, `版本附件缺少或與簽署清單不符：${artifact.file}`);
      }
      snapshot.push({ id: asset.id, file: artifact.file, size: asset.size, digest: asset.digest });
    }
    return { manifest: parsed, envelope, snapshot };
  }

  async tagCommit(tag) {
    const root = `https://api.github.com/repos/${REPOSITORY}/git/`;
    let reference = (await this.read(root + 'ref/tags/' + tag, 8192)).object;
    for (let depth = 0; depth < 4; depth++) {
      if (!reference || !/^[0-9a-f]{40}$/.test(reference.sha)) {
        throw new ApiError(400, 'Invalid release tag reference');
      }
      if (reference.type === 'commit') {
        return reference.sha;
      }
      if (reference.type !== 'tag') {
        throw new ApiError(400, 'Release tag does not resolve to a commit');
      }
      reference = (await this.read(root + 'tags/' + reference.sha, 32768)).object;
    }
    throw new ApiError(400, 'Release tag nesting exceeds limit');
  }
}

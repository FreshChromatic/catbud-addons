import { ApiError, json, sha256 } from '../protocol.mjs';
import { MINECRAFT } from './release-manifest.mjs';

export class AddonPublicApplication {
  constructor(database, now = Date.now()) {
    this.database = database;
    this.now = now;
  }

  async handle(request) {
    try {
      if (request.method !== 'GET') {
        throw new ApiError(405, 'Only GET is supported');
      }
      const url = new URL(request.url);
      const route = url.pathname.slice('/addons/v1/'.length);
      const minecraft = url.searchParams.get('minecraft');
      let result;
      const headers = { 'Cache-Control': 'public, max-age=60' };
      if (route === 'status') {
        const state = await this.database
          .prepare('SELECT checked_at,sync_error FROM addon_state WHERE id=1')
          .first();
        result = {
          schemaVersion: 1,
          lastVerifiedAt: state.checked_at,
          state: state.sync_error ? 'degraded' : 'available',
        };
      } else {
        if (!MINECRAFT.includes(minecraft)) {
          throw new ApiError(400, 'Unsupported Minecraft version');
        }
        if (route === 'catalog') {
          if ((url.searchParams.get('channel') ?? 'stable') !== 'stable') {
            throw new ApiError(400, 'Unsupported release channel');
          }
          const state = await this.database
            .prepare('SELECT revision FROM addon_state WHERE id=1')
            .first();
          const release = await this.database
            .prepare(
              `SELECT r.* FROM addon_release_channels c
            JOIN addon_releases r ON r.id=c.release_id WHERE c.minecraft=? AND c.channel='stable'`,
            )
            .bind(minecraft)
            .first();
          result = {
            schemaVersion: 1,
            catalogRevision: state.revision,
            minecraft,
            channel: 'stable',
            state: release
              ? release.validation_error
                ? 'release_unavailable'
                : 'available'
              : 'no_compatible_release',
            envelope: release && !release.validation_error ? JSON.parse(release.envelope) : null,
            checkedAt: release?.verified_at ?? 0,
          };
          if (result.envelope) {
            headers['X-Catbud-Release-Id'] = release.id;
          }
        } else if (route === 'notices') {
          result = await this.notices(url, minecraft);
        } else {
          throw new ApiError(404, 'Addon route not found');
        }
      }
      const serialized = JSON.stringify(result);
      headers.ETag = '"' + (await sha256(serialized)) + '"';
      if (request.headers.get('If-None-Match') === headers.ETag) {
        return new Response(null, { status: 304, headers });
      }
      return json(result, 200, headers);
    } catch (error) {
      return json(
        {
          error:
            error instanceof ApiError ? error.message : 'Addon service temporarily unavailable',
        },
        error instanceof ApiError ? error.status : 503,
      );
    }
  }

  async notices(url, minecraft) {
    const server = url.searchParams.get('server') ?? '';
    if (!['', 'catbud', 'catrealm'].includes(server)) {
      throw new ApiError(400, 'Invalid announcement server');
    }
    const cursor = url.searchParams.get('cursor') ?? '';
    if (cursor && !/^[0-9a-f-]{36}$/.test(cursor)) {
      throw new ApiError(400, 'Invalid announcement cursor');
    }
    const { results } = await this.database
      .prepare(
        `SELECT * FROM addon_notices
      WHERE state='published' AND starts_at<=? AND expires_at>? AND id>? ORDER BY id LIMIT 201`,
      )
      .bind(this.now, this.now, cursor)
      .all();
    const candidates = results.filter((row) => {
      const content = JSON.parse(row.content);
      return (
        (!content.minecraftVersions.length || content.minecraftVersions.includes(minecraft)) &&
        (!content.servers.length || content.servers.includes(server)) &&
        (row.starts_at >= this.now - 14 * 86400000 || content.severity === 'important')
      );
    });
    const page = candidates.slice(0, 20);
    const more = candidates.length > 20 || results.length > 200;
    const next = more ? (page.at(-1)?.id ?? results.at(-1)?.id ?? '') : '';
    return {
      schemaVersion: 1,
      serverTime: this.now,
      notices: page.map((row) => ({
        id: row.id,
        revision: row.revision,
        createdAt: row.created_at,
        ...JSON.parse(row.content),
      })),
      nextCursor: next,
    };
  }
}

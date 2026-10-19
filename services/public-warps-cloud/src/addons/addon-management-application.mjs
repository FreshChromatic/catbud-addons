import { ApiError, json, requestJson } from '../protocol.mjs';
import { AddonRepository } from './addon-repository.mjs';
import { ReleasePublicationService } from './release-publication-service.mjs';
import { NoticeService } from './notice-service.mjs';

export class AddonManagementApplication {
  constructor(env, now = Date.now(), fetcher = fetch) {
    this.repository = new AddonRepository(env.DB, now);
    this.releases = new ReleasePublicationService(this.repository, env, fetcher);
    this.notices = new NoticeService(this.repository);
  }

  async handle(request, identity) {
    const path = new URL(request.url).pathname.slice('/api/addons/'.length);
    const db = this.repository.database;
    if (request.method === 'GET') {
      const state = await this.repository.state();
      if (path === 'releases') {
        const { results } = await db
          .prepare('SELECT * FROM addon_releases ORDER BY imported_at DESC LIMIT 100')
          .all();
        const channels = await db
          .prepare('SELECT * FROM addon_release_channels ORDER BY minecraft')
          .all();
        return json({
          revision: state.revision,
          channels: channels.results,
          releases: results.map((row) => ({
            id: row.id,
            version: row.version,
            importedAt: row.imported_at,
            verifiedAt: row.verified_at,
            error: row.validation_error,
            manifest: JSON.parse(JSON.parse(row.envelope).manifest),
          })),
        });
      }
      if (path === 'notices') {
        const { results } = await db
          .prepare('SELECT * FROM addon_notices ORDER BY updated_at DESC LIMIT 100')
          .all();
        return json({
          revision: state.revision,
          notices: results.map((row) => ({
            id: row.id,
            revision: row.revision,
            state: row.state,
            ...JSON.parse(row.content),
          })),
        });
      }
      if (path === 'audit') {
        const { results } = await db
          .prepare(
            'SELECT request_id,actor,action,result,created_at FROM addon_operations ORDER BY created_at DESC LIMIT 100',
          )
          .all();
        return json({ revision: state.revision, operations: results });
      }
    }
    if (request.method !== 'POST') {
      throw new ApiError(404, 'Addon management route not found');
    }
    const value = await requestJson(request, 16384);
    if (path === 'releases/import') {
      return json(await this.releases.import(value, identity.actor));
    }
    const release = /^releases\/(v[0-9]+\.[0-9]+\.[0-9]+)\/(promote|withdraw|delete)$/.exec(path);
    if (release) {
      if (release[2] === 'delete') {
        return json(await this.releases.remove(release[1], value, identity.actor));
      }
      return json(await this.releases.change(release[1], release[2], value, identity.actor));
    }
    const notice = /^notices\/([0-9a-f-]{36})\/(save|publish|withdraw)$/.exec(path);
    if (notice) {
      return json(await this.notices.write(notice[1], notice[2], value, identity.actor));
    }
    throw new ApiError(404, 'Addon management route not found');
  }
}

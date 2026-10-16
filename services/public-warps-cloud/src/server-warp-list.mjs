import { ApiError, UUID, HASH, json } from './protocol.mjs';
import { ServerScope } from './server-scope.mjs';

export class ServerWarpList {
  constructor(database, now = Date.now()) {
    this.database = database;
    this.now = now;
  }

  publicWarp(row) {
    return {
      serverId: row.server_id,
      uuid: row.uuid,
      name: row.name,
      snapshotHash: row.snapshot_hash,
      displayName: row.display_name,
      description: row.description,
      tags: JSON.parse(row.tags),
      passState: row.pass_state,
      availability: row.availability,
      checkedAt: row.checked_at,
      nextCheck: row.next_check,
      verifiedAt: row.checked_at,
      lastSuccess: row.last_success,
      lastFailure: row.last_failure,
      revision: row.revision,
      status: this.now - row.checked_at >= 3600000 ? 'stale' : 'verified_report',
    };
  }

  async namespace(serverId) {
    ServerScope.require(serverId);
    const row = await this.database
      .prepare('SELECT revision,changes_floor FROM server_namespaces WHERE id=?')
      .bind(serverId)
      .first();
    if (!row) {
      throw new ApiError(503, 'Server namespace is not initialized');
    }
    return row;
  }

  revision(url, key, optional = false) {
    const raw = url.searchParams.get(key);
    if (raw === null && optional) {
      return null;
    }
    if (raw === null || !/^(0|[1-9][0-9]*)$/.test(raw) || !Number.isSafeInteger(Number(raw))) {
      throw new ApiError(400, 'Invalid revision parameter');
    }
    return Number(raw);
  }

  async list(serverId, request) {
    const url = new URL(request.url);
    const after = url.searchParams.get('after') ?? '';
    if (after && !UUID.test(after)) {
      throw new ApiError(400, 'Invalid list cursor');
    }
    const namespace = await this.namespace(serverId);
    const requested = this.revision(url, 'revision', true);
    if (requested !== null && requested !== namespace.revision) {
      throw new ApiError(409, 'Server list changed; restart pagination');
    }
    const etag = `"${serverId}:${namespace.revision}:${Math.floor(this.now / 3600000)}:${after}"`;
    if (request.headers.get('If-None-Match') === etag) {
      return new Response(null, { status: 304, headers: { ETag: etag } });
    }
    const { results } = await this.database
      .prepare(
        'SELECT * FROM server_warps WHERE server_id=? AND published=1 AND uuid>? ORDER BY uuid LIMIT 101',
      )
      .bind(serverId, after)
      .all();
    const rows = results.slice(0, 100);
    if ((await this.namespace(serverId)).revision !== namespace.revision) {
      throw new ApiError(409, 'Server list changed; restart pagination');
    }
    return json(
      {
        serverId,
        revision: namespace.revision,
        generatedAt: this.now,
        next: results.length > 100 ? rows.at(-1).uuid : null,
        warps: rows.map((row) => this.publicWarp(row)),
      },
      200,
      { ETag: etag, 'Cache-Control': 'public, max-age=60' },
    );
  }

  async changes(serverId, request) {
    const url = new URL(request.url);
    const since = this.revision(url, 'since');
    const after = this.revision(url, 'after', true) ?? since;
    const namespace = await this.namespace(serverId);
    if (since < namespace.changes_floor) {
      throw new ApiError(410, 'Server changes expired; download full list');
    }
    if (after < since || after > namespace.revision || since > namespace.revision) {
      throw new ApiError(400, 'Invalid server changes cursor');
    }
    const requested = this.revision(url, 'revision', true);
    if (requested !== null && requested !== namespace.revision) {
      throw new ApiError(409, 'Server changes changed; restart pagination');
    }
    const { results } = await this.database
      .prepare(
        `SELECT c.revision AS change_revision,c.uuid AS change_uuid,c.deleted,w.*
       FROM server_changes c LEFT JOIN server_warps w ON w.server_id=c.server_id AND w.uuid=c.uuid
       WHERE c.server_id=? AND c.revision>? AND c.revision<=?
       ORDER BY c.revision LIMIT 101`,
      )
      .bind(serverId, after, namespace.revision)
      .all();
    const rows = results.slice(0, 100);
    if ((await this.namespace(serverId)).revision !== namespace.revision) {
      throw new ApiError(409, 'Server changes changed; restart pagination');
    }
    const etag = `"${serverId}:${namespace.revision}:${Math.floor(this.now / 3600000)}:"`;
    return json(
      {
        serverId,
        revision: namespace.revision,
        etag,
        generatedAt: this.now,
        next: results.length > 100 ? rows.at(-1).change_revision : null,
        changes: rows.map((row) => ({
          revision: row.change_revision,
          uuid: row.change_uuid,
          deleted: !row.published,
          warp: row.published ? this.publicWarp(row) : null,
        })),
      },
      200,
      { ETag: etag, 'Cache-Control': 'public, max-age=60' },
    );
  }

  async preview(serverId, hash) {
    ServerScope.require(serverId);
    if (!HASH.test(hash)) {
      throw new ApiError(400, 'Invalid preview digest');
    }
    const row = await this.database
      .prepare(
        `SELECT bytes FROM previews WHERE hash=? AND EXISTS
       (SELECT 1 FROM server_warps WHERE server_id=? AND published=1 AND snapshot_hash=?)`,
      )
      .bind(hash, serverId, hash)
      .first();
    if (!row) {
      throw new ApiError(404, 'Published preview not found for this server');
    }
    return new Response(new Uint8Array(row.bytes), {
      headers: {
        'Content-Type': 'application/octet-stream',
        'Cache-Control': 'public, max-age=60',
        ETag: `"${serverId}:${hash}"`,
      },
    });
  }
}

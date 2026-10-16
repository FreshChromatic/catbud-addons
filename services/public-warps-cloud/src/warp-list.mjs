import { ApiError, UUID, json } from './protocol.mjs';

export class WarpList {
  constructor(database, now = Date.now()) {
    this.database = database;
    this.now = now;
  }

  async list(request) {
    const url = new URL(request.url);
    const after = url.searchParams.get('after') ?? '';
    if (after && !UUID.test(after)) {
      throw new ApiError(400, 'Invalid list cursor');
    }
    const revision = (
      await this.database.prepare("SELECT value FROM metadata WHERE key='revision'").first()
    ).value;
    const requestedRevision = url.searchParams.get('revision');
    if (requestedRevision !== null && requestedRevision !== String(revision)) {
      throw new ApiError(409, 'List changed; restart pagination');
    }
    const etag = `"${revision}:${Math.floor(this.now / 3600000)}:${after}"`;
    if (request.headers.get('If-None-Match') === etag) {
      return new Response(null, { status: 304, headers: { ETag: etag } });
    }
    const { results } = await this.database
      .prepare(
        `
      SELECT * FROM warps WHERE published=1 AND uuid>? ORDER BY uuid LIMIT 101
    `,
      )
      .bind(after)
      .all();
    const hasMore = results.length > 100;
    const rows = results.slice(0, 100);
    return json(
      {
        revision,
        generatedAt: this.now,
        next: hasMore ? rows.at(-1).uuid : null,
        warps: rows.map((row) => this.publicWarp(row)),
      },
      200,
      { ETag: etag, 'Cache-Control': 'public, max-age=60' },
    );
  }

  async changes(request) {
    const url = new URL(request.url);
    const since = this.revisionParameter(url, 'since');
    const after = url.searchParams.has('after') ? this.revisionParameter(url, 'after') : since;
    const metadata = await this.database
      .prepare("SELECT key,value FROM metadata WHERE key IN ('revision','changes_floor')")
      .all();
    const values = Object.fromEntries(metadata.results.map((row) => [row.key, row.value]));
    if (since < values.changes_floor || since > values.revision) {
      throw new ApiError(410, 'Change history unavailable; fetch the full list');
    }
    if (after < since || after > values.revision) {
      throw new ApiError(400, 'Invalid change cursor');
    }
    if (
      url.searchParams.has('revision') &&
      this.revisionParameter(url, 'revision') !== values.revision
    ) {
      throw new ApiError(409, 'List changed; restart synchronization');
    }
    const { results } = await this.database
      .prepare(
        `SELECT c.revision AS change_revision,c.uuid AS change_uuid,
      w.* FROM changes c LEFT JOIN warps w ON w.uuid=c.uuid
      WHERE c.revision>? AND c.revision<=? ORDER BY c.revision LIMIT 101`,
      )
      .bind(after, values.revision)
      .all();
    const rows = results.slice(0, 100);
    const etag = `"${values.revision}:${Math.floor(this.now / 3600000)}:"`;
    return json(
      {
        revision: values.revision,
        generatedAt: this.now,
        etag,
        next: results.length > 100 ? rows.at(-1).change_revision : null,
        changes: rows.map((row) => ({
          revision: row.change_revision,
          uuid: row.change_uuid,
          deleted: !row.published,
          warp: row.published ? this.publicWarp(row) : null,
        })),
      },
      200,
      { 'Cache-Control': 'public, max-age=60' },
    );
  }

  revisionParameter(url, key) {
    const raw = url.searchParams.get(key);
    if (raw === null || !/^(0|[1-9][0-9]*)$/.test(raw) || !Number.isSafeInteger(Number(raw))) {
      throw new ApiError(400, 'Invalid revision parameter');
    }
    return Number(raw);
  }

  publicWarp(row) {
    return {
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
}

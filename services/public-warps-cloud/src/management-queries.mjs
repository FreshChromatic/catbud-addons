import { ApiError, HASH, UUID } from './protocol.mjs';

export class ManagementQueries {
  constructor(database, now = Date.now()) {
    this.database = database;
    this.now = now;
  }

  async list(serverId, category, offset = 0, query = '') {
    if (!Number.isSafeInteger(offset) || offset < 0 || offset > 10000) {
      throw new ApiError(400, 'Invalid list offset');
    }
    const queries = {
      submissions: `SELECT s.*,w.name,w.pass_state,w.checked_at,w.revision AS current_revision,
        w.first_valid_at,w.snapshot_hash AS current_hash FROM server_submissions s JOIN server_warps w
        ON s.server_id=w.server_id AND s.uuid=w.uuid WHERE s.server_id=? AND s.state='pending'
        ORDER BY s.created_at,s.id LIMIT 26 OFFSET ?`,
      warps: `SELECT * FROM server_warps WHERE server_id=? AND published=1 ORDER BY name,uuid LIMIT 26 OFFSET ?`,
      audit: `SELECT * FROM server_audit WHERE (server_id=? OR server_id IS NULL) ORDER BY id DESC LIMIT 26 OFFSET ?`,
      contributors: `SELECT c.id,c.role,c.enabled,c.created_at,
        (SELECT json_group_array(server_id) FROM contributor_servers WHERE contributor=c.id) AS servers
        FROM contributors c JOIN contributor_servers s
        ON s.contributor=c.id WHERE s.server_id=? ORDER BY c.id LIMIT 26 OFFSET ?`,
    };
    if (!queries[category]) {
      throw new ApiError(404, 'Management list not found');
    }
    if (typeof query !== 'string' || query.length > 120) {
      throw new ApiError(400, 'Search must be at most 120 characters');
    }
    const searchColumns = {
      submissions:
        "w.name || ' ' || w.uuid || ' ' || COALESCE(w.display_name,'') || ' ' || s.contributor",
      warps:
        "name || ' ' || uuid || ' ' || COALESCE(display_name,'') || ' ' || COALESCE(description,'') || ' ' || tags",
      audit: "actor || ' ' || action || ' ' || COALESCE(uuid,'')",
      contributors: 'c.id',
    };
    const [select, order] = queries[category].split(' ORDER BY');
    const filtered = `${select} AND instr(lower(${searchColumns[category]}),lower(?))>0`;
    const listQuery = `${filtered} ORDER BY${order}`;
    const { results } = await this.database
      .prepare(listQuery)
      .bind(serverId, query.trim(), offset)
      .all();
    const total = await this.database
      .prepare(`SELECT COUNT(*) AS total FROM (${filtered})`)
      .bind(serverId, query.trim())
      .first('total');
    return {
      items: results.slice(0, 25),
      nextOffset: results.length > 25 ? offset + 25 : null,
      serverId,
      total,
    };
  }

  async submission(serverId, id) {
    const submission = await this.database
      .prepare('SELECT * FROM server_submissions WHERE server_id=? AND id=?')
      .bind(serverId, id)
      .first();
    if (!submission) {
      throw new ApiError(404, 'Submission not found on this server');
    }
    const current = await this.warp(serverId, submission.uuid);
    return {
      submission,
      current,
      expired: submission.expires_at <= this.now,
      changed: submission.base_revision !== current.revision,
    };
  }

  async warp(serverId, uuid) {
    if (!UUID.test(uuid ?? '')) {
      throw new ApiError(400, 'Invalid warp UUID');
    }
    const warp = await this.database
      .prepare('SELECT * FROM server_warps WHERE server_id=? AND uuid=?')
      .bind(serverId, uuid.toLowerCase())
      .first();
    if (!warp) {
      throw new ApiError(404, 'Warp not found on this server');
    }
    return warp;
  }

  async preview(serverId, hash) {
    if (!HASH.test(hash)) {
      throw new ApiError(400, 'Invalid preview hash');
    }
    const row = await this.database
      .prepare(
        `SELECT bytes FROM previews p WHERE p.hash=? AND (
      EXISTS (SELECT 1 FROM server_warps w WHERE w.server_id=? AND (w.snapshot_hash=p.hash OR
        (w.previous_hash=p.hash AND w.previous_until>?))) OR EXISTS
      (SELECT 1 FROM server_submissions s WHERE s.server_id=? AND s.hash=p.hash AND s.expires_at>?))`,
      )
      .bind(hash, serverId, this.now, serverId, this.now)
      .first();
    if (!row) {
      throw new ApiError(404, 'Preview not found on this server');
    }
    return new Response(new Uint8Array(row.bytes), {
      headers: { 'Content-Type': 'application/octet-stream' },
    });
  }
}

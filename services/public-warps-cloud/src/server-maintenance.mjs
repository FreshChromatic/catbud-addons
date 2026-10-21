export class ServerMaintenance {
  static INTERVAL = 15 * 60 * 1000;
  static PREVIEW_BATCH_SIZE = 100;

  constructor(database, now = Date.now()) {
    this.database = database;
    this.now = now;
  }

  async clean() {
    const database = this.database;
    const now = this.now;
    const claimed = await database
      .prepare(
        `UPDATE maintenance_state SET next_run=?
        WHERE key='server_cleanup' AND next_run<=? RETURNING cursor`,
      )
      .bind(now + ServerMaintenance.INTERVAL, now)
      .first();
    if (!claimed) {
      return;
    }
    const previews = await this.previewCleanup(claimed.cursor);
    await database.batch([
      database
        .prepare(
          'DELETE FROM owner_challenges WHERE id IN (SELECT id FROM owner_challenges WHERE expires_at<=? LIMIT 100)',
        )
        .bind(now),
      database
        .prepare(
          'DELETE FROM owner_sessions WHERE token_hash IN (SELECT token_hash FROM owner_sessions WHERE expires_at<=? LIMIT 100)',
        )
        .bind(now),
      database
        .prepare(
          `DELETE FROM server_upload_tickets WHERE id IN
        (SELECT id FROM server_upload_tickets WHERE expires_at<=?
        AND (consumed=0 OR expires_at<?) LIMIT 100)`,
        )
        .bind(now, now - 604800000),
      database
        .prepare(
          `UPDATE server_submissions SET state='expired',reason='Review expired'
        WHERE id IN (SELECT id FROM server_submissions
        WHERE state='pending' AND expires_at<=? LIMIT 100)`,
        )
        .bind(now),
      database
        .prepare(
          `DELETE FROM server_submissions WHERE id IN
        (SELECT id FROM server_submissions WHERE state!='pending' AND expires_at<?
        AND NOT EXISTS (SELECT 1 FROM server_upload_tickets t WHERE t.submission_id=server_submissions.id)
        LIMIT 100)`,
        )
        .bind(now - 604800000),
      database
        .prepare(
          `UPDATE server_warps SET previous_hash=NULL,previous_until=0
        WHERE (server_id,uuid) IN (SELECT server_id,uuid FROM server_warps
        WHERE previous_hash IS NOT NULL AND previous_until<=? LIMIT 100)`,
        )
        .bind(now),
      ...previews,
      database
        .prepare(
          `DELETE FROM budgets WHERE key IN
        (SELECT key FROM budgets WHERE expires_at<=? LIMIT 100)`,
        )
        .bind(now),
      database
        .prepare(
          `UPDATE server_namespaces SET changes_floor=MAX(changes_floor,
        COALESCE((SELECT MAX(revision) FROM (SELECT revision FROM server_changes
          WHERE server_id=server_namespaces.id AND created_at<? ORDER BY revision LIMIT 100)),changes_floor))`,
        )
        .bind(now - 604800000),
      // CROSS JOIN keeps namespaces outside the indexed revision-range lookup.
      database.prepare(`DELETE FROM server_changes WHERE (server_id,revision) IN
        (SELECT c.server_id,c.revision FROM server_namespaces n
        CROSS JOIN server_changes c WHERE c.server_id=n.id AND c.revision<=n.changes_floor)`),
      database
        .prepare(
          `DELETE FROM server_audit WHERE id IN
        (SELECT id FROM server_audit WHERE created_at<?
        OR id<(SELECT COALESCE(MAX(id),0)-10000 FROM server_audit) LIMIT 100)`,
        )
        .bind(now - 2592000000),
      database
        .prepare(
          `DELETE FROM server_warps WHERE (server_id,uuid) IN
        (SELECT w.server_id,w.uuid FROM server_warps w WHERE w.published=0 AND w.first_valid_at=0
        AND w.next_check<?
        AND NOT EXISTS (SELECT 1 FROM server_submissions s WHERE s.server_id=w.server_id AND s.uuid=w.uuid)
        AND NOT EXISTS (SELECT 1 FROM server_upload_tickets t WHERE t.server_id=w.server_id AND t.uuid=w.uuid)
        LIMIT 100)`,
        )
        .bind(now - 604800000),
    ]);
  }

  async previewCleanup(cursor) {
    const database = this.database;
    const { results } = await database
      .prepare('SELECT hash FROM previews WHERE hash>? ORDER BY hash LIMIT ?')
      .bind(cursor, ServerMaintenance.PREVIEW_BATCH_SIZE)
      .all();
    const next = results.length === ServerMaintenance.PREVIEW_BATCH_SIZE ? results.at(-1).hash : '';
    const statements = [];
    if (results.length) {
      const placeholders = results.map(() => '?').join(',');
      statements.push(
        database
          .prepare(
            `DELETE FROM previews WHERE hash IN (${placeholders})
            AND NOT EXISTS (SELECT 1 FROM server_warps w WHERE w.snapshot_hash=previews.hash)
            AND NOT EXISTS (SELECT 1 FROM server_warps w WHERE w.previous_hash=previews.hash)
            AND NOT EXISTS (SELECT 1 FROM server_submissions s WHERE s.hash=previews.hash)
            AND NOT EXISTS (SELECT 1 FROM warps w WHERE w.snapshot_hash=previews.hash)
            AND NOT EXISTS (SELECT 1 FROM warps w WHERE w.previous_hash=previews.hash)
            AND NOT EXISTS (SELECT 1 FROM submissions s WHERE s.hash=previews.hash)`,
          )
          .bind(...results.map((row) => row.hash)),
      );
    }
    statements.push(
      database
        .prepare("UPDATE maintenance_state SET cursor=? WHERE key='server_cleanup'")
        .bind(next),
    );
    return statements;
  }
}

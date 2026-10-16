export class ServerMaintenance {
  constructor(database, now = Date.now()) {
    this.database = database;
    this.now = now;
  }

  async clean() {
    const database = this.database;
    const now = this.now;
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
        AND (consumed=0 OR expires_at<?) LIMIT 10)`,
        )
        .bind(now, now - 604800000),
      database
        .prepare(
          `UPDATE server_submissions SET state='expired',reason='Review expired'
        WHERE id IN (SELECT id FROM server_submissions
        WHERE state='pending' AND expires_at<=? LIMIT 10)`,
        )
        .bind(now),
      database
        .prepare(
          `DELETE FROM server_submissions WHERE id IN
        (SELECT id FROM server_submissions WHERE state!='pending' AND expires_at<?
        AND NOT EXISTS (SELECT 1 FROM server_upload_tickets t WHERE t.submission_id=server_submissions.id)
        LIMIT 10)`,
        )
        .bind(now - 604800000),
      database
        .prepare(
          `UPDATE server_warps SET previous_hash=NULL,previous_until=0
        WHERE (server_id,uuid) IN (SELECT server_id,uuid FROM server_warps
        WHERE previous_hash IS NOT NULL AND previous_until<=? LIMIT 10)`,
        )
        .bind(now),
      database.prepare(`DELETE FROM previews WHERE hash IN (SELECT hash FROM previews
        WHERE NOT EXISTS (SELECT 1 FROM server_warps WHERE snapshot_hash=hash OR previous_hash=hash)
        AND NOT EXISTS (SELECT 1 FROM server_submissions WHERE server_submissions.hash=previews.hash)
        AND NOT EXISTS (SELECT 1 FROM warps WHERE snapshot_hash=hash OR previous_hash=hash)
        AND NOT EXISTS (SELECT 1 FROM submissions WHERE submissions.hash=previews.hash)
        LIMIT 10)`),
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
      database.prepare(`DELETE FROM server_changes WHERE revision<=
        (SELECT changes_floor FROM server_namespaces WHERE id=server_changes.server_id)`),
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
        LIMIT 10)`,
        )
        .bind(now - 604800000),
    ]);
  }
}

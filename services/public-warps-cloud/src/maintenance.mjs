export class Maintenance {
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
          'DELETE FROM upload_tickets WHERE id IN (SELECT id FROM upload_tickets WHERE expires_at<=? AND (consumed=0 OR expires_at<?) LIMIT 10)',
        )
        .bind(now, now - 604800000),
      database
        .prepare(
          'DELETE FROM submissions WHERE uuid IN (SELECT uuid FROM submissions WHERE expires_at<=? LIMIT 10)',
        )
        .bind(now),
      database
        .prepare(
          'UPDATE warps SET previous_hash=NULL,previous_until=0 WHERE uuid IN (SELECT uuid FROM warps WHERE previous_hash IS NOT NULL AND previous_until<=? LIMIT 10)',
        )
        .bind(now),
      database.prepare(`DELETE FROM previews WHERE hash IN (SELECT hash FROM previews
        WHERE NOT EXISTS (SELECT 1 FROM warps WHERE snapshot_hash=hash OR previous_hash=hash)
        AND NOT EXISTS (SELECT 1 FROM submissions WHERE submissions.hash=previews.hash)
        LIMIT 10)`),
      database
        .prepare(
          'DELETE FROM budgets WHERE key IN (SELECT key FROM budgets WHERE expires_at<=? LIMIT 100)',
        )
        .bind(now),
      database
        .prepare(
          'DELETE FROM availability_reports WHERE uuid IN (SELECT uuid FROM availability_reports WHERE created_at<? LIMIT 10)',
        )
        .bind(now - 604800000),
      database
        .prepare(
          `UPDATE metadata SET value=MAX(value,COALESCE((SELECT MAX(revision) FROM
          (SELECT revision FROM changes WHERE created_at<? ORDER BY revision LIMIT 100)),value))
          WHERE key='changes_floor'`,
        )
        .bind(now - 604800000),
      database.prepare(
        "DELETE FROM changes WHERE revision<=(SELECT value FROM metadata WHERE key='changes_floor')",
      ),
      database
        .prepare(
          'DELETE FROM audit WHERE id IN (SELECT id FROM audit WHERE created_at<? OR id<(SELECT COALESCE(MAX(id),0)-10000 FROM audit) LIMIT 100)',
        )
        .bind(now - 2592000000),
      database
        .prepare(
          `DELETE FROM warps WHERE uuid IN (SELECT uuid FROM warps WHERE published=0
        AND NOT EXISTS (SELECT 1 FROM submissions WHERE submissions.uuid=warps.uuid)
        AND NOT EXISTS (SELECT 1 FROM upload_tickets WHERE upload_tickets.uuid=warps.uuid)
        AND NOT EXISTS (SELECT 1 FROM availability_reports WHERE availability_reports.uuid=warps.uuid)
        AND next_check<? LIMIT 10)`,
        )
        .bind(now - 604800000),
    ]);
  }
}

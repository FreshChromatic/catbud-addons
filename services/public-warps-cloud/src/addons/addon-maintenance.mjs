import { ReleaseImportService } from './release-import-service.mjs';

export class AddonMaintenance {
  constructor(env, now = Date.now(), fetcher = fetch) {
    this.env = env;
    this.now = now;
    this.importer = new ReleaseImportService(env, fetcher);
  }

  async run() {
    const db = this.env.DB;
    const claimed = await db
      .prepare(
        `UPDATE addon_state SET lease_until=? WHERE id=1
      AND next_check<=? AND lease_until<=? RETURNING id`,
      )
      .bind(this.now + 300000, this.now, this.now)
      .first();
    if (!claimed) {
      return;
    }
    const { results } = await db
      .prepare(
        `SELECT DISTINCT r.* FROM addon_releases r
      JOIN addon_release_channels c ON c.release_id=r.id`,
      )
      .all();
    const errors = [];
    for (const release of results) {
      try {
        const checked = await this.importer.import(release.id);
        if (
          JSON.stringify(checked.envelope) !== release.envelope ||
          JSON.stringify(checked.snapshot) !== release.source_snapshot
        ) {
          throw new Error('Release attachments changed after approval');
        }
        await db
          .prepare("UPDATE addon_releases SET verified_at=?,validation_error='' WHERE id=?")
          .bind(this.now, release.id)
          .run();
      } catch (error) {
        const message = String(error.message).slice(0, 500);
        errors.push(message);
        await db
          .prepare('UPDATE addon_releases SET validation_error=? WHERE id=?')
          .bind(message, release.id)
          .run();
      }
    }
    await db
      .prepare(
        'UPDATE addon_state SET checked_at=?,next_check=?,lease_until=0,sync_error=? WHERE id=1',
      )
      .bind(
        this.now,
        this.now + (errors.length ? 3600000 : 86400000),
        errors.join('; ').slice(0, 1000),
      )
      .run();
  }
}

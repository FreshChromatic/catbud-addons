import { ApiError, UUID } from './protocol.mjs';
import { StorageCapacity } from './storage-capacity.mjs';

export class ReviewService {
  constructor(database, now = Date.now(), env = {}) {
    this.database = database;
    this.now = now;
    this.capacity = new StorageCapacity(database, env);
  }

  async approve(uuid) {
    if (!UUID.test(uuid)) {
      throw new ApiError(400, 'Invalid review target');
    }
    uuid = uuid.toLowerCase();
    const capacity = await this.capacity.requireUpdates();
    const operation = crypto.randomUUID();
    const guard = this.database
      .prepare(
        `INSERT INTO review_guards(id,valid)
      SELECT ?,CASE WHEN EXISTS (SELECT 1 FROM warps WHERE uuid=? AND pass_state='active'
      AND checked_at>? AND EXISTS (SELECT 1 FROM submissions WHERE uuid=warps.uuid AND expires_at>?)
      AND (published=1 OR (SELECT COUNT(*) FROM warps WHERE published=1)<5000)) THEN 1 ELSE 0 END`,
      )
      .bind(operation, uuid, this.now - 3600000, this.now);
    const update = this.database
      .prepare(
        `
      WITH retention AS MATERIALIZED (
      SELECT CASE WHEN snapshot_hash IS NOT NULL AND EXISTS
        (SELECT 1 FROM submissions WHERE uuid=warps.uuid AND kind='preview' AND hash!=warps.snapshot_hash)
        AND ?=1
        AND (SELECT COALESCE(SUM(size),0) FROM previews WHERE hash IN
          (SELECT previous_hash FROM warps WHERE previous_hash IS NOT NULL))+COALESCE(
            (SELECT size FROM previews WHERE hash=warps.snapshot_hash),0)<=33554432
        THEN 1 ELSE 0 END AS retain FROM warps WHERE uuid=?)
      UPDATE warps SET published=1,
      previous_hash=CASE WHEN (SELECT retain FROM retention)=1 THEN snapshot_hash ELSE previous_hash END,
      previous_until=CASE WHEN (SELECT retain FROM retention)=1 THEN ? ELSE previous_until END,
      snapshot_hash=COALESCE((SELECT hash FROM submissions WHERE uuid=warps.uuid AND kind='preview'),snapshot_hash)
      WHERE uuid=? AND EXISTS (SELECT 1 FROM submissions WHERE uuid=warps.uuid AND expires_at>?)
      AND (published=1 OR (SELECT COUNT(*) FROM warps WHERE published=1)<5000)
    `,
      )
      .bind(capacity.state === 'normal' ? 1 : 0, uuid, this.now + 604800000, uuid, this.now);
    try {
      await this.database.batch([
        guard,
        update,
        this.database.prepare('DELETE FROM submissions WHERE uuid=?').bind(uuid),
        this.database
          .prepare('INSERT INTO audit(uuid,action,created_at) VALUES (?,?,?)')
          .bind(uuid, 'approve', this.now),
        this.database.prepare('DELETE FROM review_guards WHERE id=?').bind(operation),
      ]);
    } catch (error) {
      if (String(error.message).includes('CHECK constraint failed')) {
        throw new ApiError(
          409,
          'Review requires a current active pass, a pending submission and publication capacity',
        );
      }
      throw error;
    }
  }

  async availability(uuid, available) {
    if (!UUID.test(uuid)) {
      throw new ApiError(400, 'Invalid review target');
    }
    await this.database.batch([
      this.database
        .prepare(
          `UPDATE warps SET availability=?,last_success=CASE WHEN ?=1 THEN ? ELSE last_success END,
        last_failure=CASE WHEN ?=0 THEN ? ELSE last_failure END WHERE uuid=?`,
        )
        .bind(
          available ? 'available' : 'unavailable',
          available ? 1 : 0,
          this.now,
          available ? 1 : 0,
          this.now,
          uuid,
        ),
      this.database.prepare('DELETE FROM availability_reports WHERE uuid=?').bind(uuid),
      this.database
        .prepare('INSERT INTO audit(uuid,action,created_at) VALUES (?,?,?)')
        .bind(uuid, available ? 'restore' : 'disable', this.now),
    ]);
  }

  async reject(uuid) {
    if (!UUID.test(uuid)) {
      throw new ApiError(400, 'Invalid review target');
    }
    await this.database.batch([
      this.database.prepare('DELETE FROM submissions WHERE uuid=?').bind(uuid),
      this.database
        .prepare('INSERT INTO audit(uuid,action,created_at) VALUES (?,?,?)')
        .bind(uuid, 'reject', this.now),
    ]);
  }

  async rollback(uuid) {
    if (!UUID.test(uuid)) {
      throw new ApiError(400, 'Invalid review target');
    }
    uuid = uuid.toLowerCase();
    const operation = crypto.randomUUID();
    try {
      await this.database.batch([
        this.database
          .prepare(
            `INSERT INTO review_guards(id,valid)
          SELECT ?,CASE WHEN EXISTS (SELECT 1 FROM warps WHERE uuid=? AND published=1
          AND previous_until>? AND EXISTS (SELECT 1 FROM previews WHERE hash=warps.previous_hash))
          THEN 1 ELSE 0 END`,
          )
          .bind(operation, uuid, this.now),
        this.database
          .prepare(
            `UPDATE warps SET snapshot_hash=previous_hash,
          previous_hash=snapshot_hash,previous_until=? WHERE uuid=?`,
          )
          .bind(this.now + 604800000, uuid),
        this.database
          .prepare('INSERT INTO audit(uuid,action,created_at) VALUES (?,?,?)')
          .bind(uuid, 'rollback', this.now),
        this.database.prepare('DELETE FROM review_guards WHERE id=?').bind(operation),
      ]);
    } catch (error) {
      if (String(error.message).includes('CHECK constraint failed')) {
        throw new ApiError(409, 'No retained previous preview is available');
      }
      throw error;
    }
  }

  async unpublish(uuid) {
    if (!UUID.test(uuid)) {
      throw new ApiError(400, 'Invalid review target');
    }
    await this.database.batch([
      this.database.prepare('UPDATE warps SET published=0 WHERE uuid=?').bind(uuid.toLowerCase()),
      this.database
        .prepare('INSERT INTO audit(uuid,action,created_at) VALUES (?,?,?)')
        .bind(uuid.toLowerCase(), 'unpublish', this.now),
    ]);
  }
}

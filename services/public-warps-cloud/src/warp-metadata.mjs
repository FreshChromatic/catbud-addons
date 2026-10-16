import { ApiError, UUID } from './protocol.mjs';
import { StorageCapacity } from './storage-capacity.mjs';

export class WarpMetadata {
  constructor(database, now = Date.now()) {
    this.database = database;
    this.now = now;
  }

  text(value, limit, nullable = false) {
    if (nullable && value === null) {
      return null;
    }
    if (typeof value !== 'string' || value.length > limit || /[\p{Cc}\p{Cf}]/u.test(value)) {
      throw new ApiError(400, 'Invalid metadata text');
    }
    return value.trim();
  }

  async update(uuid, value) {
    if (
      !UUID.test(uuid) ||
      !value ||
      Array.isArray(value) ||
      typeof value !== 'object' ||
      Object.keys(value).some((key) => !['displayName', 'description', 'tags'].includes(key))
    ) {
      throw new ApiError(400, 'Invalid warp metadata');
    }
    const displayName = this.text(value.displayName ?? null, 64, true);
    const description = this.text(value.description ?? null, 512, true);
    if (!Array.isArray(value.tags) || value.tags.length > 8) {
      throw new ApiError(400, 'Metadata requires at most eight tags');
    }
    const tags = [...new Set(value.tags.map((tag) => this.text(tag, 24)))];
    if (tags.some((tag) => !tag)) {
      throw new ApiError(400, 'Tags must not be blank');
    }
    const operation = crypto.randomUUID();
    await new StorageCapacity(this.database).requireUpdates();
    try {
      await this.database.batch([
        this.database
          .prepare(
            `INSERT INTO review_guards(id,valid)
          SELECT ?,CASE WHEN EXISTS (SELECT 1 FROM warps WHERE uuid=?) THEN 1 ELSE 0 END`,
          )
          .bind(operation, uuid.toLowerCase()),
        this.database
          .prepare('UPDATE warps SET display_name=?,description=?,tags=? WHERE uuid=?')
          .bind(displayName || null, description || null, JSON.stringify(tags), uuid.toLowerCase()),
        this.database
          .prepare('INSERT INTO audit(uuid,action,created_at) VALUES (?,?,?)')
          .bind(uuid.toLowerCase(), 'metadata', this.now),
        this.database.prepare('DELETE FROM review_guards WHERE id=?').bind(operation),
      ]);
    } catch (error) {
      if (String(error.message).includes('CHECK constraint failed')) {
        throw new ApiError(404, 'Warp is not recorded');
      }
      throw error;
    }
  }
}

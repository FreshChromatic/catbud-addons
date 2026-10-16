import { randomBytes } from 'node:crypto';
import { ApiError, sha256 } from './protocol.mjs';
import { StorageCapacity } from './storage-capacity.mjs';

export class ContributorManagement {
  constructor(database) {
    this.database = database;
  }

  validate(id) {
    if (typeof id !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(id)) {
      throw new ApiError(
        400,
        'Contributor ID must contain 1 to 64 letters, numbers, underscores or hyphens',
      );
    }
  }

  async grant(id) {
    this.validate(id);
    await new StorageCapacity(this.database).requireGrowth();
    const credential = randomBytes(32).toString('hex');
    await this.database
      .prepare(
        `INSERT INTO contributors(id,token_hash,created_at)
      VALUES (?,?,?) ON CONFLICT(id) DO UPDATE SET token_hash=excluded.token_hash,
      enabled=1,created_at=excluded.created_at`,
      )
      .bind(id, await sha256(credential), Date.now())
      .run();
    return { contributor: id, contributorCredential: credential };
  }

  async revoke(id) {
    this.validate(id);
    await this.database.batch([
      this.database.prepare('UPDATE contributors SET enabled=0 WHERE id=?').bind(id),
      this.database
        .prepare('DELETE FROM upload_tickets WHERE contributor=? AND consumed=0')
        .bind(id),
    ]);
  }
}

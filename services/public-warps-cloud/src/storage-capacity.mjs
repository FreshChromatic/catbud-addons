import { ApiError, setting } from './protocol.mjs';

export class StorageCapacity {
  constructor(database, env = {}) {
    this.database = database;
    this.softLimit = setting(env, 'DATABASE_SOFT_LIMIT_BYTES', 350000000);
    this.hardLimit = setting(env, 'DATABASE_HARD_LIMIT_BYTES', 400000000);
    if (
      this.softLimit > 350000000 ||
      this.hardLimit > 400000000 ||
      this.softLimit > this.hardLimit
    ) {
      throw new ApiError(503, 'Invalid database capacity configuration');
    }
  }

  async inspect() {
    const result = await this.database
      .prepare("SELECT key,value FROM metadata WHERE key IN ('stored_bytes','reserved_bytes')")
      .all();
    const bytes = result.meta?.size_after;
    if (!Number.isSafeInteger(bytes) || bytes < 1) {
      throw new ApiError(503, 'Database size measurement unavailable', 300);
    }
    const values = Object.fromEntries(result.results.map((row) => [row.key, row.value]));
    let state = 'normal';
    if (bytes >= this.hardLimit) {
      state = 'stopped';
    } else if (bytes >= this.softLimit) {
      state = 'restricted';
    }
    return {
      bytes,
      storedBytes: values.stored_bytes,
      reservedBytes: values.reserved_bytes,
      softLimit: this.softLimit,
      hardLimit: this.hardLimit,
      state,
    };
  }

  async requireGrowth() {
    const status = await this.inspect();
    if (status.state !== 'normal') {
      throw new ApiError(503, 'Database capacity reached; submissions paused', 3600);
    }
    return status;
  }

  async requireUpdates() {
    const status = await this.inspect();
    if (status.state === 'stopped') {
      throw new ApiError(503, 'Database capacity reached; updates paused', 3600);
    }
    return status;
  }
}

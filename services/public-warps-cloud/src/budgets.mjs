import { ApiError } from './protocol.mjs';

export class Budgets {
  constructor(database, now = Date.now()) {
    this.database = database;
    this.now = now;
  }

  async consume(key, limit, cost = 1, period = 86400000) {
    const bucket = Math.floor(this.now / period);
    const fullKey = key + ':' + bucket;
    const expires = (bucket + 2) * period;
    if (cost > limit) {
      throw new ApiError(429, 'Request exceeds service budget', 60);
    }
    const row = await this.database
      .prepare(
        `
      INSERT INTO budgets(key,used,expires_at) VALUES (?,?,?)
      ON CONFLICT(key) DO UPDATE SET used=used+excluded.used
      WHERE used+excluded.used<=?
      RETURNING used
    `,
      )
      .bind(fullKey, cost, expires, limit)
      .first();
    if (!row) {
      throw new ApiError(
        429,
        'Service budget exhausted',
        Math.ceil(((bucket + 1) * period - this.now) / 1000),
      );
    }
  }

  async preflight(actor) {
    await this.consume('preflight', 5000);
    await this.consume('actor-preflight:' + actor, 100);
    await this.consume('actor-minute:' + actor, 6, 1, 60000);
  }
}

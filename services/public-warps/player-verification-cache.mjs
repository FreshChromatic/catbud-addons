import { ApiError } from './api-error.mjs';

export class PlayerVerificationCache {
  constructor(verify, now, verificationBudget) {
    this.verify = verify;
    this.now = now;
    this.verificationBudget = verificationBudget;
    this.cache = new Map();
    this.inflight = new Map();
    this.activeVerifications = 0;
    this.budgetDay = Math.floor(now() / 86400000);
    this.budgetUsed = 0;
  }
  async check(uuid, name) {
    const key = uuid.toLowerCase() + ':' + name.toLowerCase();
    const old = this.cache.get(key);
    if (old && this.now() - old.at < (old.error ? 5 : 15) * 60000) {
      if (old.error) {
        throw old.error;
      }
      return old;
    }
    if (this.inflight.has(key)) {
      return this.inflight.get(key);
    }
    if (this.activeVerifications >= 4) {
      throw new ApiError(429, 'Verification busy');
    }
    const day = Math.floor(this.now() / 86400000);
    if (day !== this.budgetDay) {
      this.budgetDay = day;
      this.budgetUsed = 0;
    }
    if (this.budgetUsed >= this.verificationBudget) {
      throw new ApiError(429, 'Daily verification budget reached');
    }
    this.budgetUsed++;
    const promise = (async () => {
      this.activeVerifications++;
      try {
        const player = await this.verify(uuid, name);
        const result = { ...player, at: this.now() };
        if (this.cache.size > 1000) {
          this.cache.clear();
        }
        this.cache.set(key, result);
        return result;
      } catch (error) {
        if (error.status === 404 || error.status === 422) {
          if (this.cache.size > 1000) {
            this.cache.clear();
          }
          this.cache.set(key, { error, at: this.now() });
        }
        throw error;
      } finally {
        this.activeVerifications--;
        this.inflight.delete(key);
      }
    })();
    this.inflight.set(key, promise);
    return promise;
  }
}

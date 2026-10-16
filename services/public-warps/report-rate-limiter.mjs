import { ApiError } from './api-error.mjs';

export class ReportRateLimiter {
  constructor() {
    this.rates = new Map();
  }

  check(address, time) {
    const day = Math.floor(time / 86400000);
    const minute = Math.floor(time / 60000);
    for (const [key, value] of this.rates) {
      if (value.day < day) {
        this.rates.delete(key);
      }
    }
    if (this.rates.size > 10000) {
      throw new ApiError(429, 'Service capacity reached');
    }
    const rate = this.rates.get(address) ?? { day, minute, daily: 0, short: 0 };
    if (rate.day !== day) {
      rate.day = day;
      rate.daily = 0;
    }
    if (rate.minute !== minute) {
      rate.minute = minute;
      rate.short = 0;
    }
    this.rates.set(address, rate);
    const dailyExceeded = ++rate.daily > 200;
    if (dailyExceeded || ++rate.short > 6) {
      throw new ApiError(429, 'Report limit reached');
    }
  }
}

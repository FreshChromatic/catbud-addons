import { ApiError, json, sha256 } from './protocol.mjs';

export const localRateLimits = {
  ORIGIN_BURST: { namespace_id: '4690101', simple: { limit: 6000, period: 60 } },
  PUBLIC_BURST: { namespace_id: '4690102', simple: { limit: 600, period: 60 } },
  WRITE_BURST: { namespace_id: '4690103', simple: { limit: 120, period: 60 } },
  ADDON_BURST: { namespace_id: '4690104', simple: { limit: 300, period: 60 } },
};

export class BurstGuard {
  constructor(env) {
    this.env = env;
  }

  async require(binding, key) {
    if (typeof this.env[binding]?.limit !== 'function') {
      throw new ApiError(503, 'Request protection unavailable', 60);
    }
    const result = await this.env[binding].limit({ key });
    if (!result.success) {
      throw new ApiError(429, 'Request burst limit reached', 60);
    }
  }

  async reject(request) {
    try {
      if (new URL(request.url).pathname.startsWith('/addons/v1/')) {
        await this.require('ADDON_BURST', 'addon-requests');
      }
      await this.require('ORIGIN_BURST', 'requests');
      const address = request.headers.get('CF-Connecting-IP') ?? '';
      const valid = address.length <= 45 && /^[0-9a-f:.]+$/i.test(address);
      const key = valid ? await sha256(address) : 'unknown-source';
      await this.require(request.method === 'POST' ? 'WRITE_BURST' : 'PUBLIC_BURST', key);
      return null;
    } catch (error) {
      const expected = error instanceof ApiError;
      return json(
        { error: expected ? error.message : 'Request protection unavailable' },
        expected ? error.status : 503,
        { 'Retry-After': String(expected ? error.retryAfter : 60) },
      );
    }
  }
}

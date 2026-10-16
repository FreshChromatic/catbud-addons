import { ApiError } from './api-error.mjs';
import { UUID, NAME } from './player-identity.mjs';

export class WarpHttpHandler {
  constructor({ repository, reports, limiter, now }) {
    this.repository = repository;
    this.reports = reports;
    this.limiter = limiter;
    this.now = now;
  }

  async handle(req, res) {
    function send(status, value, headers = {}) {
      res.writeHead(status, {
        'Content-Type': 'application/json; charset=utf-8',
        'X-Content-Type-Options': 'nosniff',
        ...headers,
      });
      res.end(value === undefined ? undefined : JSON.stringify(value));
    }
    try {
      const url = new URL(req.url, 'http://localhost');
      if (req.method === 'GET' && url.pathname === '/v1/warps') {
        const etag = this.repository.etag();
        if (req.headers['if-none-match'] === etag) {
          return send(304, undefined, { ETag: etag });
        }
        return send(200, this.repository.list(), { ETag: etag });
      }
      if (req.method === 'GET' && /^\/v1\/snapshots\/[0-9a-f]{64}$/.test(url.pathname)) {
        const hash = url.pathname.split('/').at(-1);
        const bytes = this.repository.snapshot(hash);
        if (!bytes) {
          throw new ApiError(404, 'Snapshot not found');
        }
        res.writeHead(200, {
          'Content-Type': 'application/octet-stream',
          'Cache-Control': 'public, max-age=86400',
          'X-Content-Type-Options': 'nosniff',
          ETag: `"${hash}"`,
        });
        return res.end(bytes);
      }
      if (req.method !== 'POST' || url.pathname !== '/v1/reports') {
        throw new ApiError(404, 'Route not found');
      }
      const time = this.now();
      this.limiter.check(req.socket.remoteAddress, time);
      let length = 0,
        parts = [];
      for await (const chunk of req) {
        length += chunk.length;
        if (length > 192 * 1024) {
          throw new ApiError(413, 'Request too large');
        }
        parts.push(chunk);
      }
      let report;
      try {
        report = JSON.parse(Buffer.concat(parts).toString('utf8'));
      } catch {
        throw new ApiError(400, 'Invalid JSON');
      }
      if (
        !UUID.test(report.uuid ?? '') ||
        !NAME.test(report.name ?? '') ||
        report.bossbar !== true ||
        report.actionbar !== true
      ) {
        throw new ApiError(400, 'Invalid report');
      }
      const result = await this.reports.accept(report, time);
      send(200, result);
    } catch (e) {
      send(e.status ?? 503, { error: e.status ? e.message : 'Service unavailable' });
    }
  }
}

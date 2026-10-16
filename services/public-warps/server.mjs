import { WarpReportService } from './warp-report-service.mjs';
import { WarpHttpHandler } from './warp-http-handler.mjs';
import { WarpRepository } from './warp-repository.mjs';
import { PlayerVerificationCache } from './player-verification-cache.mjs';
import { ReportRateLimiter } from './report-rate-limiter.mjs';
import { createServer } from 'node:http';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
import { verifyPlayer } from './player-verification.mjs';
export { ApiError } from './api-error.mjs';
export { validateSnapshot } from './snapshot-codec.mjs';
export { verifyPlayer } from './player-verification.mjs';

export function createWarpService({
  database = ':memory:',
  verify = verifyPlayer,
  now = Date.now,
  verificationBudget = 1000,
} = {}) {
  const repository = new WarpRepository(database, now);
  const db = repository.db;
  const verifier = new PlayerVerificationCache(verify, now, verificationBudget);
  const limiter = new ReportRateLimiter();
  const reports = new WarpReportService(repository, verifier);
  const handler = new WarpHttpHandler({ repository, reports, limiter, now });
  const server = createServer((request, response) => handler.handle(request, response));
  return {
    server,
    db,
    close: async () => {
      await new Promise((r) => server.close(r));
      db.close();
    },
  };
}
if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  const service = createWarpService({
    database:
      process.env.CATBUD_WARPS_DB ?? fileURLToPath(new URL('public-warps.sqlite', import.meta.url)),
  });
  service.server.listen(Number(process.env.CATBUD_WARPS_PORT ?? 8787), '127.0.0.1', () =>
    console.log(
      'Catbud public warps local service: http://127.0.0.1:' + service.server.address().port,
    ),
  );
}

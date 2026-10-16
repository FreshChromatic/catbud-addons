import { ApiError, json } from './protocol.mjs';
import { Budgets } from './budgets.mjs';
import { PublicCache } from './public-cache.mjs';
import { StorageCapacity } from './storage-capacity.mjs';
import { BurstGuard } from './burst-guard.mjs';
import { DatabaseUsage, MeasuredDatabase } from './database-usage.mjs';
import { AdminApplication } from './admin-application.mjs';
import { ServerApplication } from './server-application.mjs';
import { LegacyApplication } from './legacy-application.mjs';
import { ServerMaintenance } from './server-maintenance.mjs';
import { ServerQualification } from './server-qualification.mjs';

export class WarpApplication {
  constructor(
    env,
    now = Date.now(),
    fetcher = fetch,
    reportUsage = (report) => console.log(JSON.stringify(report)),
  ) {
    this.env = env;
    this.database = env.DB;
    this.reportUsage = reportUsage;
    this.budgets = new Budgets(env.DB, now);
    this.qualifications = { fetcher };
  }

  async handle(request) {
    const path = new URL(request.url).pathname;
    let operation = path === '/internal/admin' ? 'admin' : path.split('/').at(-1);
    if (/\/(previews|snapshots)\//.test(path)) {
      operation = 'preview';
    } else if (/\/uploads\//.test(path)) {
      operation = 'upload';
    } else if (/\/submissions\/[0-9a-f-]{36}$/.test(path)) {
      operation = request.method === 'GET' ? 'submission-status' : 'upload';
    }
    return this.measure(operation, (application) => application.dispatch(request));
  }

  async measure(operation, work) {
    const usage = new DatabaseUsage(operation, this.budgets.now);
    const scoped = new WarpApplication(
      { ...this.env, DB: new MeasuredDatabase(this.database, usage) },
      this.budgets.now,
      this.qualifications.fetcher,
      this.reportUsage,
    );
    scoped.ownerVerifier = this.ownerVerifier;
    try {
      return await work(scoped);
    } finally {
      try {
        this.reportUsage(usage.report());
      } catch {
        console.warn('Public warps usage reporting failed');
      }
    }
  }

  async dispatch(request) {
    try {
      const path = new URL(request.url).pathname;
      if (path.startsWith('/v2/')) {
        return await new ServerApplication(this).handle(request);
      }
      if (path.startsWith('/v1/')) {
        return await new LegacyApplication(this).handle(request);
      }
      if (request.method === 'POST' && path === '/internal/admin') {
        return await new AdminApplication(this).handle(request);
      }
      throw new ApiError(404, 'Route not found');
    } catch (error) {
      const status = error instanceof ApiError ? error.status : 500;
      const message = error instanceof ApiError ? error.message : 'Service temporarily unavailable';
      const headers = error.retryAfter ? { 'Retry-After': String(error.retryAfter) } : {};
      return json({ error: message }, status, headers);
    }
  }

  async scheduled() {
    return this.measure('scheduled', (application) => application.checkScheduled());
  }

  async checkScheduled() {
    await new ServerMaintenance(this.database, this.budgets.now).clean();
    const capacity = await new StorageCapacity(this.database, this.env).inspect();
    if (capacity.state !== 'normal') {
      console.warn('Public warps database capacity', capacity.state, capacity.bytes);
    }
    if (capacity.state === 'stopped') {
      return;
    }
    const { results } = await this.database
      .prepare(
        `SELECT server_id,uuid,name FROM server_warps w
      WHERE (published=1 OR EXISTS (SELECT 1 FROM server_submissions s
        WHERE s.server_id=w.server_id AND s.uuid=w.uuid AND s.state='pending'))
      AND next_check<=? AND verification_lease<=? ORDER BY next_check,server_id LIMIT 3`,
      )
      .bind(this.budgets.now, this.budgets.now)
      .all();
    const qualification = new ServerQualification(
      this.database,
      this.budgets,
      this.env,
      this.qualifications.fetcher,
    );
    for (const player of results) {
      try {
        await qualification.check(player.server_id, player, true);
      } catch (error) {
        if (!(error instanceof ApiError)) {
          throw error;
        }
      }
    }
  }
}

export default {
  async fetch(request, env, context) {
    const rejected = await new BurstGuard(env).reject(request);
    if (rejected) {
      return rejected;
    }
    return new PublicCache(caches.default, new WarpApplication(env)).handle(request, context);
  },
  async scheduled(event, env, context) {
    context.waitUntil(new WarpApplication(env).scheduled());
  },
};

import { ApiError, json, requestJson } from './protocol.mjs';
import { ServerScope } from './server-scope.mjs';
import { ServerWarpList } from './server-warp-list.mjs';
import { ServerQualification } from './server-qualification.mjs';
import { ServerSubmissions } from './server-submissions.mjs';
import { ContributorPolicy } from './contributor-policy.mjs';
import { StorageCapacity } from './storage-capacity.mjs';
import { ServerPreviewUpload } from './server-preview-upload.mjs';
import { OwnerApplication } from './owner-application.mjs';

export class ServerApplication {
  constructor(application) {
    this.application = application;
    this.database = application.database;
    this.env = application.env;
    this.budgets = application.budgets;
    this.qualification = new ServerQualification(
      this.database,
      this.budgets,
      this.env,
      application.qualifications.fetcher,
    );
    this.submissions = new ServerSubmissions(
      this.database,
      this.budgets,
      this.env,
      this.qualification,
    );
    this.lists = new ServerWarpList(this.database, this.budgets.now);
    this.uploads = new ServerPreviewUpload(
      this.database,
      this.budgets,
      this.env,
      this.qualification,
      this.submissions,
    );
  }

  async handle(request) {
    const path = new URL(request.url).pathname;
    if (request.method === 'GET' && path === '/v2/servers') {
      return json({ servers: ServerScope.list() }, 200, { 'Cache-Control': 'public, max-age=60' });
    }
    const match = /^\/v2\/servers\/([^/]+)\/(.+)$/.exec(path);
    if (!match) {
      throw new ApiError(404, 'Route not found');
    }
    const serverId = ServerScope.require(match[1]).id;
    const route = match[2];
    if (route.startsWith('owner/')) {
      return new OwnerApplication(this.application, this.qualification).handle(
        serverId,
        route,
        request,
      );
    }
    if (request.method === 'GET') {
      if (/^submissions\/[0-9a-f-]{36}$/.test(route)) {
        const actor = await new ContributorPolicy(this.database).require(request, serverId);
        const row = await this.database
          .prepare('SELECT * FROM server_submissions WHERE server_id=? AND id=? AND contributor=?')
          .bind(serverId, route.split('/')[1], actor.id)
          .first();
        if (!row) throw new ApiError(404, 'Submission not found for this server and contributor');
        const state =
          row.state === 'pending' && row.expires_at <= this.budgets.now ? 'expired' : row.state;
        return json({
          serverId,
          submissionId: row.id,
          state: state === 'pending' ? 'pending_review' : state,
          reason: row.reason,
        });
      }
      if (route === 'warps') {
        return this.lists.list(serverId, request);
      }
      if (route === 'changes') {
        return this.lists.changes(serverId, request);
      }
      if (/^previews\/[0-9a-f]{64}$/.test(route)) {
        return this.lists.preview(serverId, route.split('/')[1]);
      }
    }
    const uploadRoute = /^uploads\/[0-9a-f-]{36}$/.test(route);
    if (
      request.method !== 'POST' ||
      (!['discoveries', 'observations', 'submissions/preflight'].includes(route) && !uploadRoute)
    ) {
      throw new ApiError(404, 'Route not found');
    }
    if (this.env.POSTING_ENABLED !== 'true') {
      throw new ApiError(503, 'Shared submissions are disabled', 3600);
    }
    const actor = await new ContributorPolicy(this.database).require(request, serverId);
    if (uploadRoute) {
      const ticket = await this.uploads.ticket(serverId, route.split('/')[1], actor);
      if (ticket.consumed) {
        await request.body?.cancel();
        return json(await this.uploads.result(ticket, actor));
      }
    }
    await new StorageCapacity(this.database, this.env).requireGrowth();
    await this.budgets.preflight(actor.id);
    if (uploadRoute) {
      return json(await this.uploads.upload(serverId, route.split('/')[1], request, actor), 202);
    }
    const value = await requestJson(request);
    if (route === 'submissions/preflight') {
      return json(await this.uploads.preflight(serverId, value, actor));
    }
    const result =
      route === 'discoveries'
        ? await this.submissions.discover(serverId, value.players, actor)
        : await this.submissions.observe(serverId, value, actor);
    return json(result, result.published ? 200 : 202);
  }
}

import { ApiError, json, requestJson } from './protocol.mjs';
import { AccessVerifier } from './access-verifier.mjs';
import { Budgets } from './budgets.mjs';
import { ServerScope } from './server-scope.mjs';
import { ServerQualification } from './server-qualification.mjs';
import { PublicationService } from './publication-service.mjs';
import { ManagementQueries } from './management-queries.mjs';
import { ManagementActions } from './management-actions.mjs';
import { ManagementStatus } from './management-status.mjs';
import { requireManagementRequest } from './management-request.mjs';

export class ManagementApplication {
  constructor(env, now = Date.now(), verifier = new AccessVerifier(env, now)) {
    this.env = env;
    this.now = now;
    this.verifier = verifier;
    this.queries = new ManagementQueries(env.DB, now);
    this.actions = new ManagementActions(env.DB, env, now);
  }

  async handle(request) {
    let response;
    try {
      const identity = await this.verifier.require(request);
      if (!['GET', 'HEAD', 'POST'].includes(request.method)) {
        throw new ApiError(405, 'Unsupported management method');
      }
      if (request.method === 'POST') {
        this.verifier.requireWrite(request, identity);
        await new Budgets(this.env.DB, this.now).consume('administrator-minute', 30, 1, 60000);
      }
      response = await this.dispatch(request, identity);
    } catch (error) {
      response = json(
        {
          error:
            error instanceof ApiError
              ? error.message
              : 'Management service temporarily unavailable',
        },
        error instanceof ApiError ? error.status : 500,
      );
    }
    const headers = new Headers(response.headers);
    headers.set('Cache-Control', 'no-store');
    headers.set(
      'Content-Security-Policy',
      "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; worker-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
    );
    headers.set('X-Content-Type-Options', 'nosniff');
    headers.set('Referrer-Policy', 'no-referrer');
    return new Response(response.body, { status: response.status, headers });
  }

  async dispatch(request, identity) {
    const url = new URL(request.url);
    if (!url.pathname.startsWith('/api/')) {
      if (request.method !== 'GET' && request.method !== 'HEAD') {
        throw new ApiError(404, 'Route not found');
      }
      return this.env.ASSETS.fetch(request);
    }
    if (request.method === 'GET' && url.pathname === '/api/session') {
      return json({
        email: identity.actor,
        expiresAt: identity.expiresAt,
        csrf: identity.csrf,
        servers: ServerScope.list(),
      });
    }
    if (request.method === 'GET' && url.pathname === '/api/status') {
      return json(await new ManagementStatus(this.env.DB, this.env, this.now).inspect());
    }
    const contributor = /^\/api\/contributors\/([A-Za-z0-9_-]{1,64})\/(grant|update)$/.exec(
      url.pathname,
    );
    if (request.method === 'POST' && contributor) {
      const value = await requestJson(request);
      await requireManagementRequest(this.env.DB, identity.actor, value.requestId);
      return json(
        await this.actions.contributor(
          contributor[1],
          value,
          identity.actor,
          contributor[2] === 'grant',
          value.requestId,
        ),
      );
    }
    const route = /^\/api\/servers\/([^/]+)\/(.+)$/.exec(url.pathname);
    if (!route) {
      throw new ApiError(404, 'Management route not found');
    }
    const serverId = ServerScope.require(route[1]).id;
    const path = route[2];
    if (request.method === 'GET') {
      if (['submissions', 'warps', 'audit', 'contributors'].includes(path)) {
        return json(
          await this.queries.list(
            serverId,
            path,
            Number(url.searchParams.get('offset') ?? 0),
            url.searchParams.get('q') ?? '',
          ),
        );
      }
      if (path.startsWith('submissions/')) {
        return json(await this.queries.submission(serverId, path.slice(12)));
      }
      if (path.startsWith('warps/')) {
        return json(await this.queries.warp(serverId, path.slice(6)));
      }
      if (path.startsWith('previews/')) {
        return this.queries.preview(serverId, path.slice(9));
      }
    }
    const action = /^(submissions|warps)\/([^/]+)\/([a-z]+)$/.exec(path);
    if (request.method !== 'POST' || !action) {
      throw new ApiError(404, 'Management route not found');
    }
    const value = await requestJson(request, 8192);
    await requireManagementRequest(this.env.DB, identity.actor, value.requestId);
    if (action[1] === 'warps') {
      return json(
        await this.actions.warp(
          serverId,
          action[2],
          action[3],
          value,
          identity.actor,
          value.requestId,
        ),
      );
    }
    const { submission, current } = await this.queries.submission(serverId, action[2]);
    const publication = new PublicationService(this.env.DB, this.now, this.env);
    if (action[3] === 'reject') {
      return json(
        await publication.reject(submission.id, identity.actor, value.reason, value.requestId),
      );
    }
    if (action[3] !== 'approve') {
      throw new ApiError(404, 'Management action not found');
    }
    await new ServerQualification(this.env.DB, new Budgets(this.env.DB, this.now), this.env).check(
      serverId,
      { uuid: current.uuid, name: current.name },
    );
    return json(
      await publication.approve(
        submission.id,
        value.expectedRevision,
        identity.actor,
        false,
        value.requestId,
      ),
    );
  }
}

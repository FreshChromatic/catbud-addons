import { timingSafeEqual } from 'node:crypto';
import { ApiError, requestJson, json } from './protocol.mjs';
import { ManagementActions } from './management-actions.mjs';
import { ManagementQueries } from './management-queries.mjs';
import { PublicationService } from './publication-service.mjs';
import { ServerQualification } from './server-qualification.mjs';
import { ServerScope } from './server-scope.mjs';
import { StorageCapacity } from './storage-capacity.mjs';
import { requireManagementRequest } from './management-request.mjs';

export class AdminApplication {
  constructor(application) {
    this.application = application;
  }

  async handle(request) {
    const app = this.application;
    const credential = app.env.ADMIN_CREDENTIAL;
    const supplied = request.headers.get('Authorization')?.replace(/^Bearer /, '') ?? '';
    if (
      !/^[0-9a-f]{64}$/.test(credential ?? '') ||
      !/^[0-9a-f]{64}$/.test(supplied) ||
      !timingSafeEqual(Buffer.from(credential), Buffer.from(supplied))
    ) {
      throw new ApiError(403, 'Administrator access required');
    }
    const value = await requestJson(request, 8192);
    const { command, target, metadata } = value;
    const serverId = ServerScope.require(value.serverId ?? 'catbud').id;
    const actions = new ManagementActions(app.database, app.env, app.budgets.now);
    const queries = new ManagementQueries(app.database, app.budgets.now);
    const actor = 'administrator-cli';
    if (command === 'status') {
      return json(await new StorageCapacity(app.database, app.env).inspect());
    }
    if (command === 'list') {
      const { results } = await app.database
        .prepare(
          `SELECT w.server_id,w.uuid,w.name,w.published,
        w.pass_state,w.availability,s.kind AS pending,s.expires_at
        FROM server_warps w LEFT JOIN server_submissions s
        ON w.server_id=s.server_id AND w.uuid=s.uuid AND s.state='pending'
        WHERE w.server_id=? ORDER BY w.uuid LIMIT 5200`,
        )
        .bind(serverId)
        .all();
      return json(results);
    }
    const requestId = value.requestId ?? crypto.randomUUID();
    await requireManagementRequest(app.database, actor, requestId);
    if (command === 'grant' || command === 'revoke' || command === 'contributor') {
      const old = await app.database
        .prepare('SELECT role FROM contributors WHERE id=?')
        .bind(target)
        .first();
      const { results } = await app.database
        .prepare('SELECT server_id FROM contributor_servers WHERE contributor=?')
        .bind(target)
        .all();
      return json(
        await actions.contributor(
          target,
          {
            role: value.role ?? old?.role ?? 'contributor',
            servers:
              value.servers ?? (results.length ? results.map((row) => row.server_id) : [serverId]),
            enabled: value.enabled ?? command !== 'revoke',
          },
          actor,
          command === 'grant',
          requestId,
        ),
      );
    }
    const warp = await queries.warp(serverId, target);
    if (command === 'approve' || command === 'reject') {
      const candidate = await app.database
        .prepare(
          `SELECT id FROM server_submissions
        WHERE server_id=? AND uuid=? AND state='pending' ORDER BY created_at DESC LIMIT 1`,
        )
        .bind(serverId, warp.uuid)
        .first();
      if (!candidate) {
        throw new ApiError(404, 'No pending submission for this target');
      }
      const publication = new PublicationService(app.database, app.budgets.now, app.env);
      if (command === 'reject') {
        return json(
          await publication.reject(
            candidate.id,
            actor,
            value.reason ?? 'Rejected by administrator',
            requestId,
          ),
        );
      }
      await new ServerQualification(
        app.database,
        app.budgets,
        app.env,
        app.qualifications.fetcher,
      ).check(serverId, warp);
      return json(
        await publication.approve(
          candidate.id,
          value.expectedRevision ?? warp.revision,
          actor,
          false,
          requestId,
        ),
      );
    }
    return json(
      await actions.warp(
        serverId,
        warp.uuid,
        command,
        {
          metadata,
          expectedRevision: value.expectedRevision ?? warp.revision,
        },
        actor,
        requestId,
      ),
    );
  }
}

import { ApiError, sha256 } from './protocol.mjs';
import { ServerScope } from './server-scope.mjs';

export class ContributorPolicy {
  constructor(database) {
    this.database = database;
  }

  async require(request, serverId) {
    ServerScope.require(serverId);
    const authorization = request.headers.get('Authorization') ?? '';
    if (!/^Bearer [0-9a-f]{64}$/.test(authorization)) {
      throw new ApiError(401, 'A contributor credential is required');
    }
    const actor = await this.database
      .prepare(
        `SELECT c.id,c.role FROM contributors c JOIN contributor_servers s ON s.contributor=c.id
       WHERE c.token_hash=? AND c.enabled=1 AND s.server_id=?`,
      )
      .bind(await sha256(authorization.slice(7)), serverId)
      .first();
    if (!actor) {
      throw new ApiError(403, 'Contributor access is revoked or not allowed for this server');
    }
    return { id: actor.id, trusted: actor.role === 'trusted' };
  }
}

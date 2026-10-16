import { ApiError, sha256 } from './protocol.mjs';

export class ContributorAuth {
  constructor(database) {
    this.database = database;
  }

  async require(request) {
    const header = request.headers.get('Authorization') ?? '';
    if (!/^Bearer [0-9a-f]{64}$/.test(header)) {
      throw new ApiError(401, 'A contributor credential is required');
    }
    const tokenHash = await sha256(header.slice(7));
    const actor = await this.database
      .prepare('SELECT id FROM contributors WHERE token_hash=? AND enabled=1')
      .bind(tokenHash)
      .first();
    if (!actor) {
      throw new ApiError(403, 'Contributor credential is invalid or revoked');
    }
    return actor.id;
  }
}

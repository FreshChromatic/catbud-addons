import { ApiError, boundedBytes, identity, setting } from './protocol.mjs';
import { ServerScope } from './server-scope.mjs';

export class ServerQualification {
  constructor(database, budgets, env, fetcher = fetch) {
    this.database = database;
    this.budgets = budgets;
    this.env = env;
    this.fetcher = fetcher;
  }

  async check(serverId, value, force = false) {
    ServerScope.require(serverId);
    const player = identity(value);
    const now = this.budgets.now;
    const old = await this.database
      .prepare('SELECT * FROM server_warps WHERE server_id=? AND uuid=?')
      .bind(serverId, player.uuid)
      .first();
    if (!old) {
      throw new ApiError(404, 'Player is not recorded for this server');
    }
    if (
      !force &&
      old.checked_at > now - 3600000 &&
      old.name.toLowerCase() === player.name.toLowerCase()
    ) {
      return { ...player, active: old.pass_state === 'active', checkedAt: old.checked_at };
    }
    const lease = await this.database
      .prepare(
        `UPDATE server_warps SET verification_lease=?
       WHERE server_id=? AND uuid=? AND verification_lease<=? RETURNING uuid`,
      )
      .bind(now + 30000, serverId, player.uuid, now)
      .first();
    if (!lease) {
      throw new ApiError(429, 'Player verification already pending', 30);
    }
    try {
      await this.budgets.consume('verification', setting(this.env, 'VERIFY_DAILY_LIMIT', 2600));
      const result = await this.fetchPlayer(serverId, player);
      await this.database
        .prepare(
          `UPDATE server_warps SET name=?,pass_state=?,checked_at=?,next_check=?,verification_lease=0
         WHERE server_id=? AND uuid=?`,
        )
        .bind(
          result.name,
          result.active ? 'active' : 'inactive',
          now,
          now + 3600000,
          serverId,
          player.uuid,
        )
        .run();
      return { ...result, checkedAt: now };
    } catch (error) {
      await this.database
        .prepare(
          'UPDATE server_warps SET verification_lease=0,next_check=? WHERE server_id=? AND uuid=?',
        )
        .bind(now + 300000, serverId, player.uuid)
        .run();
      throw error;
    }
  }

  async fetchPlayer(serverId, player) {
    let response;
    try {
      response = await this.fetcher.call(globalThis, ServerScope.playerUrl(serverId, player.uuid), {
        signal: AbortSignal.timeout(10000),
      });
    } catch {
      throw new ApiError(503, 'Server qualification temporarily unavailable', 300);
    }
    if (!response.ok) {
      await response.body?.cancel();
      throw new ApiError(
        response.status === 404 ? 404 : 503,
        'Server qualification unavailable',
        300,
      );
    }
    let result;
    try {
      result = JSON.parse(
        new TextDecoder('utf-8', { fatal: true }).decode(
          await boundedBytes(response.body, 1048576),
        ),
      );
    } catch {
      throw new ApiError(502, 'Unrecognized qualification response');
    }
    const sameUuid =
      typeof result?.uuid === 'string' &&
      result.uuid.replaceAll('-', '').toLowerCase() === player.uuid.replaceAll('-', '');
    const sameName =
      typeof result?.name === 'string' && result.name.toLowerCase() === player.name.toLowerCase();
    if (!sameUuid || !sameName || typeof result?.pass !== 'boolean') {
      throw new ApiError(502, 'Server identity or pass response mismatch');
    }
    return { uuid: player.uuid, name: result.name, active: result.pass };
  }
}

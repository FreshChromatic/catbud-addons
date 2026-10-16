import { ApiError, boundedBytes, identity, setting } from './protocol.mjs';

export class PlayerQualification {
  constructor(database, budgets, env, fetcher = fetch) {
    this.database = database;
    this.budgets = budgets;
    this.env = env;
    this.fetcher = fetcher;
  }

  async check(player, force = false) {
    const { uuid, name } = identity(player);
    const now = this.budgets.now;
    const old = await this.database.prepare('SELECT * FROM warps WHERE uuid=?').bind(uuid).first();
    const age = old?.pass_state === 'inactive' ? 300000 : 900000;
    if (!force && old?.name.toLowerCase() === name.toLowerCase() && old.checked_at > now - age) {
      return {
        uuid,
        name: old.name,
        active: old.pass_state === 'active',
        checkedAt: old.checked_at,
      };
    }
    const lease = await this.database
      .prepare(
        `
      UPDATE warps SET verification_lease=? WHERE uuid=? AND verification_lease<=?
      RETURNING uuid
    `,
      )
      .bind(now + 30000, uuid, now)
      .first();
    if (!lease) {
      throw new ApiError(429, 'Player verification already pending', 30);
    }
    try {
      await this.budgets.consume('verification', setting(this.env, 'VERIFY_DAILY_LIMIT', 2600));
      const result = await this.fetchPlayer(uuid, name);
      await this.database
        .prepare(
          `
        UPDATE warps SET name=?,pass_state=?,checked_at=?,next_check=?,verification_lease=0
        WHERE uuid=?
      `,
        )
        .bind(result.name, result.active ? 'active' : 'inactive', now, now + 3600000, uuid)
        .run();
      return { ...result, checkedAt: now };
    } catch (error) {
      await this.database
        .prepare('UPDATE warps SET verification_lease=0,next_check=? WHERE uuid=?')
        .bind(now + 300000, uuid)
        .run();
      throw error;
    }
  }

  async fetchPlayer(uuid, name) {
    let response;
    try {
      response = await this.fetcher.call(
        globalThis,
        `https://api.catbud.net/discord/player/${uuid}?detailed=true`,
        { signal: AbortSignal.timeout(10000) },
      );
    } catch (error) {
      const message = String(error?.message ?? 'Unknown network failure').replace(
        /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{32}/gi,
        '[player]',
      );
      console.warn('Catbud network failure', message.slice(0, 256));
      throw new ApiError(503, 'Catbud temporarily unavailable', 300);
    }
    if (!response.ok) {
      await response.body?.cancel();
      throw new ApiError(
        response.status === 404 ? 404 : 503,
        'Catbud verification unavailable',
        300,
      );
    }
    let player;
    try {
      player = JSON.parse(
        new TextDecoder('utf-8', { fatal: true }).decode(
          await boundedBytes(response.body, 1048576),
        ),
      );
    } catch {
      throw new ApiError(502, 'Unrecognized Catbud response');
    }
    const uuidMatches =
      typeof player?.uuid === 'string' &&
      player.uuid.replaceAll('-', '').toLowerCase() === uuid.replaceAll('-', '');
    const nameMatches =
      typeof player?.name === 'string' && player.name.toLowerCase() === name.toLowerCase();
    if (!uuidMatches || !nameMatches || typeof player?.pass !== 'boolean') {
      throw new ApiError(502, 'Catbud identity or pass response mismatch');
    }
    return { uuid, name: player.name, active: player.pass };
  }
}

import { ApiError, UUID, identity } from './protocol.mjs';
import { PublicationService } from './publication-service.mjs';
import { ServerScope } from './server-scope.mjs';

export class ServerSubmissions {
  constructor(database, budgets, env, qualification) {
    this.database = database;
    this.budgets = budgets;
    this.env = env;
    this.qualification = qualification;
  }

  async remember(serverId, value) {
    ServerScope.require(serverId);
    const player = identity(value);
    await this.database
      .prepare(
        `INSERT INTO server_warps(server_id,uuid,name,next_check)
       SELECT ?,?,?,? WHERE (SELECT COUNT(*) FROM server_warps)<5200
       ON CONFLICT(server_id,uuid) DO NOTHING`,
      )
      .bind(serverId, player.uuid, player.name, this.budgets.now)
      .run();
    const row = await this.database
      .prepare('SELECT * FROM server_warps WHERE server_id=? AND uuid=?')
      .bind(serverId, player.uuid)
      .first();
    if (!row) {
      throw new ApiError(429, 'Warp capacity reached', 3600);
    }
    return row;
  }

  async propose(serverId, player, actor, kind, payload, submissionId) {
    const id = submissionId ?? crypto.randomUUID();
    if (!UUID.test(id)) {
      throw new ApiError(400, 'Invalid submission identifier');
    }
    const previous = await this.database
      .prepare('SELECT * FROM server_submissions WHERE id=?')
      .bind(id)
      .first();
    if (previous) {
      if (
        previous.server_id !== serverId ||
        previous.uuid !== player.uuid ||
        previous.contributor !== actor.id ||
        previous.kind !== kind ||
        previous.payload !== JSON.stringify(payload)
      ) {
        throw new ApiError(409, 'Submission identifier is already used for different content');
      }
      return previous;
    }
    const row = await this.remember(serverId, player);
    const now = this.budgets.now;
    const guardId = crypto.randomUUID();
    try {
      await this.database.batch([
        this.database
          .prepare(
            `INSERT INTO review_guards(id,valid) SELECT ?,CASE WHEN
           (SELECT COUNT(*) FROM server_submissions WHERE state='pending')<200
           AND (SELECT COUNT(*) FROM server_submissions WHERE server_id=? AND uuid=?
             AND state='pending' AND contributor!=?)<5 THEN 1 ELSE 0 END`,
          )
          .bind(guardId, serverId, player.uuid, actor.id),
        this.database
          .prepare(
            `UPDATE server_submissions SET state='rejected',reason='Superseded by newer submission'
           WHERE server_id=? AND uuid=? AND contributor=? AND state='pending'`,
          )
          .bind(serverId, player.uuid, actor.id),
        this.database
          .prepare(
            `INSERT INTO server_submissions(id,server_id,uuid,contributor,kind,payload,base_revision,created_at,expires_at)
           VALUES (?,?,?,?,?,?,?,?,?)`,
          )
          .bind(
            id,
            serverId,
            player.uuid,
            actor.id,
            kind,
            JSON.stringify(payload),
            row.revision,
            now,
            now + 604800000,
          ),
        this.database.prepare('DELETE FROM review_guards WHERE id=?').bind(guardId),
      ]);
    } catch (error) {
      if (String(error.message).includes('CHECK constraint failed')) {
        throw new ApiError(429, 'Pending submission capacity reached', 3600);
      }
      throw error;
    }
    return this.database.prepare('SELECT * FROM server_submissions WHERE id=?').bind(id).first();
  }

  async discover(serverId, values, actor) {
    if (!Array.isArray(values) || values.length < 1 || values.length > 8) {
      throw new ApiError(400, 'Discovery batch must contain 1 to 8 players');
    }
    const results = [];
    for (const value of values) {
      const player = identity(value);
      const row = await this.remember(serverId, player);
      if (!row.first_valid_at && row.last_failure > 0) {
        results.push({ uuid: player.uuid, serverId, recorded: false, published: false });
        continue;
      }
      let submissionId = null;
      let published = Boolean(row.published);
      if (!row.published) {
        const existing = await this.database
          .prepare(
            `SELECT id,kind FROM server_submissions WHERE server_id=? AND uuid=? AND contributor=? AND state='pending'`,
          )
          .bind(serverId, player.uuid, actor.id)
          .first();
        if (existing) {
          submissionId = existing.id;
        } else {
          submissionId = (await this.propose(serverId, player, actor, 'discovery', {})).id;
        }
        if (actor.trusted && (!existing || existing.kind === 'discovery')) {
          if ((await this.qualification.check(serverId, player)).active) {
            const revision = await this.database
              .prepare('SELECT revision FROM server_warps WHERE server_id=? AND uuid=?')
              .bind(serverId, player.uuid)
              .first('revision');
            published = (
              await new PublicationService(this.database, this.budgets.now, this.env).approve(
                submissionId,
                revision,
                actor.id,
                true,
              )
            ).published;
          }
        }
      }
      results.push({
        uuid: player.uuid,
        serverId,
        recorded: true,
        published,
        submissionId,
      });
    }
    return { results, published: results.every((result) => result.published) };
  }

  async observe(serverId, value, actor) {
    const player = identity(value);
    const kinds = {
      valid_launch: 'launch',
      successful_arrival: 'arrival',
      target_no_pass_or_set: 'failure',
    };
    const kind = kinds[value.reason];
    if (!kind) {
      throw new ApiError(400, 'Unsupported observation reason');
    }
    const row = await this.remember(serverId, player);
    if (kind === 'failure' && !row.first_valid_at) {
      await this.database.batch([
        this.database
          .prepare(
            `UPDATE server_submissions SET state='rejected',reason='First teleport failed'
         WHERE server_id=? AND uuid=? AND contributor=? AND state='pending'`,
          )
          .bind(serverId, player.uuid, actor.id),
        this.database
          .prepare(
            `UPDATE server_warps SET published=0,availability='unavailable',last_failure=?
           WHERE server_id=? AND uuid=? AND first_valid_at=0 AND ?=1`,
          )
          .bind(this.budgets.now, serverId, player.uuid, actor.trusted ? 1 : 0),
      ]);
      return { accepted: true, published: false, state: 'rejected_first_attempt', serverId };
    }
    if (kind !== 'failure') {
      const qualification = await this.qualification.check(serverId, player);
      if (!qualification.active) {
        throw new ApiError(422, 'Player has no active pass on this server');
      }
    }
    const submission = await this.propose(serverId, player, actor, kind, {}, value.submissionId);
    if (submission.state === 'published') {
      return {
        accepted: true,
        published: true,
        state: 'published',
        serverId,
        submissionId: submission.id,
      };
    }
    if (submission.state !== 'pending') {
      return {
        accepted: true,
        published: false,
        state: submission.state,
        serverId,
        submissionId: submission.id,
      };
    }
    if (actor.trusted) {
      const revision = await this.database
        .prepare('SELECT revision FROM server_warps WHERE server_id=? AND uuid=?')
        .bind(serverId, player.uuid)
        .first('revision');
      return {
        ...(await new PublicationService(this.database, this.budgets.now, this.env).approve(
          submission.id,
          revision,
          actor.id,
          true,
        )),
        state: 'published',
      };
    }
    return {
      accepted: true,
      published: false,
      state: 'pending_review',
      serverId,
      submissionId: submission.id,
    };
  }
}

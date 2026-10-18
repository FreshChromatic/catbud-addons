import { ApiError, UUID, sha256 } from '../protocol.mjs';

export class AddonRepository {
  constructor(database, now = Date.now()) {
    this.database = database;
    this.now = now;
  }

  async state() {
    return this.database.prepare('SELECT * FROM addon_state WHERE id=1').first();
  }

  async replay(actor, value, action) {
    if (
      !UUID.test(value.requestId ?? '') ||
      !Number.isSafeInteger(value.expectedRevision) ||
      value.expectedRevision < 0
    ) {
      throw new ApiError(400, 'A requestId and expectedRevision are required');
    }
    const hash = await sha256(action + '\n' + JSON.stringify(value));
    const previous = await this.database
      .prepare('SELECT * FROM addon_operations WHERE request_id=?')
      .bind(value.requestId)
      .first();
    if (!previous) {
      return null;
    }
    if (previous.actor !== actor || previous.request_hash !== hash) {
      throw new ApiError(409, 'Request identifier already used');
    }
    return JSON.parse(previous.result);
  }

  async mutate(actor, value, action, statements, result) {
    const previous = await this.replay(actor, value, action);
    if (previous) {
      return previous;
    }
    const token = crypto.randomUUID();
    const output = { ...result, revision: value.expectedRevision + 1 };
    try {
      await this.database.batch([
        this.database
          .prepare(
            `INSERT INTO addon_guards(id,valid) SELECT ?,CASE WHEN revision=? THEN 1 ELSE 0 END FROM addon_state WHERE id=1`,
          )
          .bind(token, value.expectedRevision),
        ...statements,
        this.database.prepare('UPDATE addon_state SET revision=revision+1 WHERE id=1'),
        this.database
          .prepare(
            'INSERT INTO addon_operations(request_id,actor,request_hash,action,result,created_at) VALUES(?,?,?,?,?,?)',
          )
          .bind(
            value.requestId,
            actor,
            await sha256(action + '\n' + JSON.stringify(value)),
            action,
            JSON.stringify(output),
            this.now,
          ),
        this.database.prepare('DELETE FROM addon_guards WHERE id=?').bind(token),
      ]);
    } catch (error) {
      const duplicate = await this.replay(actor, value, action);
      if (duplicate) {
        return duplicate;
      }
      if ((await this.state()).revision !== value.expectedRevision) {
        throw new ApiError(409, 'Addon data changed; reload and review the current revision');
      }
      throw error;
    }
    return output;
  }
}

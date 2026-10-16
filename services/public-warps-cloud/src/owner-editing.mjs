import { ApiError } from './protocol.mjs';
import { metadataValue } from './metadata-value.mjs';
import { requireManagementRequest } from './management-request.mjs';
import { StorageCapacity } from './storage-capacity.mjs';
import { ServerWarpList } from './server-warp-list.mjs';

export class OwnerEditing {
  constructor(identity, database, budgets, env, qualification) {
    this.identity = identity;
    this.database = database;
    this.budgets = budgets;
    this.env = env;
    this.qualification = qualification;
  }

  async current(session) {
    const warp = await this.identity.warp(session.server_id, session.uuid);
    return {
      serverId: session.server_id,
      warp: new ServerWarpList(this.database, this.budgets.now).publicWarp(warp),
    };
  }

  async update(session, value) {
    if (
      !value ||
      Object.keys(value).some(
        (key) => !['displayName', 'description', 'expectedRevision', 'requestId'].includes(key),
      )
    ) {
      throw new ApiError(400, 'Only your custom name and description can be edited');
    }
    const actor = 'player:' + session.uuid;
    await requireManagementRequest(this.database, actor, value.requestId);
    await this.budgets.consume('owner-updates', 500);
    await this.budgets.consume('owner-update:' + session.uuid, 30, 1, 3600000);
    await new StorageCapacity(this.database, this.env).requireUpdates();
    const current = await this.identity.warp(session.server_id, session.uuid);
    if (
      !Number.isSafeInteger(value.expectedRevision) ||
      value.expectedRevision !== current.revision
    ) {
      throw new ApiError(409, 'Your warp changed; review the latest name and description');
    }
    const metadata = metadataValue({
      displayName: value.displayName,
      description: value.description,
      tags: JSON.parse(current.tags),
    });
    if (!(await this.qualification.check(session.server_id, current, true)).active) {
      throw new ApiError(422, 'Your pass is not active on this server');
    }
    const guardId = crypto.randomUUID();
    const now = this.budgets.now;
    try {
      await this.database.batch([
        this.database
          .prepare(
            `INSERT INTO review_guards(id,valid) SELECT ?,CASE WHEN EXISTS (
          SELECT 1 FROM owner_sessions s JOIN server_warps w ON w.server_id=s.server_id AND w.uuid=s.uuid
          WHERE s.token_hash=? AND s.server_id=? AND s.uuid=? AND s.expires_at>?
          AND w.published=1 AND w.revision=? AND w.pass_state='active' AND w.checked_at>?) THEN 1 ELSE 0 END`,
          )
          .bind(
            guardId,
            session.token_hash,
            session.server_id,
            session.uuid,
            now,
            current.revision,
            now - 3600000,
          ),
        this.database
          .prepare(
            'UPDATE server_warps SET display_name=?,description=? WHERE server_id=? AND uuid=? AND revision=?',
          )
          .bind(
            metadata.displayName,
            metadata.description,
            session.server_id,
            session.uuid,
            current.revision,
          ),
        this.database
          .prepare(
            `INSERT INTO server_audit(server_id,uuid,actor,action,before_revision,after_revision,created_at,request_id,source_role)
          SELECT server_id,uuid,?,'owner_metadata',?,revision,?,?,'owner' FROM server_warps WHERE server_id=? AND uuid=?`,
          )
          .bind(actor, current.revision, now, value.requestId, session.server_id, session.uuid),
        this.database.prepare('DELETE FROM review_guards WHERE id=?').bind(guardId),
      ]);
    } catch (error) {
      if (/CHECK constraint failed|UNIQUE constraint failed/.test(String(error.message))) {
        throw new ApiError(409, 'Your warp or editing permissions changed; verify again');
      }
      throw error;
    }
    return this.current(session);
  }
}

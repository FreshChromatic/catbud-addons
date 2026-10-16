import { randomBytes } from 'node:crypto';
import { ApiError, sha256 } from './protocol.mjs';
import { ServerScope } from './server-scope.mjs';
import { StorageCapacity } from './storage-capacity.mjs';
import { ManagementQueries } from './management-queries.mjs';
import { metadataValue } from './metadata-value.mjs';

export class ManagementActions {
  constructor(database, env, now = Date.now()) {
    this.database = database;
    this.env = env;
    this.now = now;
  }

  async warp(serverId, uuid, command, value, actor, requestId = null) {
    const current = await new ManagementQueries(this.database, this.now).warp(serverId, uuid);
    if (
      !Number.isSafeInteger(value.expectedRevision) ||
      value.expectedRevision !== current.revision
    ) {
      throw new ApiError(409, 'Warp changed; review the current version');
    }
    await new StorageCapacity(this.database, this.env).requireUpdates();
    const updates = {
      unpublish: { sql: 'published=0', bindings: [] },
      disable: { sql: "availability='unavailable',last_failure=?", bindings: [this.now] },
      restore: { sql: "availability='available'", bindings: [] },
      rollback: {
        sql: 'snapshot_hash=previous_hash,previous_hash=snapshot_hash,previous_until=?',
        bindings: [this.now + 604800000],
      },
    };
    if (command === 'metadata') {
      const metadata = metadataValue(value.metadata);
      updates.metadata = {
        sql: 'display_name=?,description=?,tags=?',
        bindings: [metadata.displayName, metadata.description, JSON.stringify(metadata.tags)],
      };
    }
    const update = updates[command];
    if (!update) {
      throw new ApiError(400, 'Unsupported management action');
    }
    const operation = crypto.randomUUID();
    const retained = command === 'rollback' ? 1 : 0;
    try {
      await this.database.batch([
        this.database
          .prepare(
            `INSERT INTO review_guards(id,valid) SELECT ?,CASE WHEN EXISTS (
          SELECT 1 FROM server_warps WHERE server_id=? AND uuid=? AND revision=? AND (first_valid_at>0 OR (?=1 AND published=1))
          AND (?=0 OR (published=1 AND previous_until>? AND EXISTS
            (SELECT 1 FROM previews WHERE hash=previous_hash)))) THEN 1 ELSE 0 END`,
          )
          .bind(
            operation,
            serverId,
            current.uuid,
            current.revision,
            command === 'metadata' ? 1 : 0,
            retained,
            this.now,
          ),
        this.database
          .prepare(
            'UPDATE server_warps SET ' +
              update.sql +
              ' WHERE server_id=? AND uuid=? AND revision=?',
          )
          .bind(...update.bindings, serverId, current.uuid, current.revision),
        this.database
          .prepare(
            `INSERT INTO server_audit(server_id,uuid,actor,action,before_revision,after_revision,created_at,request_id,source_role)
          SELECT server_id,uuid,?,?,?,revision,?,?,'administrator' FROM server_warps WHERE server_id=? AND uuid=?`,
          )
          .bind(actor, command, current.revision, this.now, requestId, serverId, current.uuid),
        this.database.prepare('DELETE FROM review_guards WHERE id=?').bind(operation),
      ]);
    } catch (error) {
      if (/CHECK constraint failed|UNIQUE constraint failed/.test(String(error.message))) {
        throw new ApiError(409, 'Warp changed or retained preview is unavailable');
      }
      throw error;
    }
    return {
      completed: command,
      serverId,
      warp: await new ManagementQueries(this.database, this.now).warp(serverId, current.uuid),
    };
  }

  async contributor(id, value, actor, rotate = false, requestId = null) {
    if (
      !/^[A-Za-z0-9_-]{1,64}$/.test(id ?? '') ||
      !['contributor', 'trusted'].includes(value.role) ||
      typeof value.enabled !== 'boolean' ||
      !Array.isArray(value.servers) ||
      value.servers.length < 1 ||
      value.servers.length > 2 ||
      new Set(value.servers).size !== value.servers.length
    ) {
      throw new ApiError(400, 'Contributor role, enabled state and server scopes are required');
    }
    for (const serverId of value.servers) {
      ServerScope.require(serverId);
    }
    const capacity = new StorageCapacity(this.database, this.env);
    await (rotate ? capacity.requireGrowth() : capacity.requireUpdates());
    const credential = rotate ? randomBytes(32).toString('hex') : null;
    const operation = crypto.randomUUID();
    const statements = [];
    if (rotate) {
      statements.push(
        this.database
          .prepare(
            `INSERT INTO contributors(id,token_hash,created_at,role,enabled)
        VALUES (?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET token_hash=excluded.token_hash,
        role=excluded.role,enabled=excluded.enabled`,
          )
          .bind(id, await sha256(credential), this.now, value.role, value.enabled ? 1 : 0),
      );
    } else {
      statements.push(
        this.database
          .prepare(
            `INSERT INTO review_guards(id,valid) SELECT ?,CASE WHEN EXISTS
        (SELECT 1 FROM contributors WHERE id=?) THEN 1 ELSE 0 END`,
          )
          .bind(operation, id),
      );
      statements.push(
        this.database
          .prepare('UPDATE contributors SET role=?,enabled=? WHERE id=?')
          .bind(value.role, value.enabled ? 1 : 0, id),
      );
    }
    statements.push(
      this.database.prepare('DELETE FROM contributor_servers WHERE contributor=?').bind(id),
    );
    for (const serverId of value.servers) {
      statements.push(
        this.database
          .prepare('INSERT INTO contributor_servers(contributor,server_id) VALUES (?,?)')
          .bind(id, serverId),
      );
    }
    statements.push(
      this.database
        .prepare(
          `DELETE FROM server_upload_tickets WHERE contributor=? AND consumed=0
      AND (?=0 OR server_id NOT IN (SELECT server_id FROM contributor_servers WHERE contributor=?))`,
        )
        .bind(id, value.enabled ? 1 : 0, id),
    );
    statements.push(
      this.database
        .prepare(
          `INSERT INTO server_audit(actor,action,reason,created_at,request_id,source_role) VALUES (?,?,?,?,?,'administrator')`,
        )
        .bind(
          actor,
          rotate ? 'contributor_rotate' : 'contributor_update',
          JSON.stringify({ id, role: value.role, enabled: value.enabled, servers: value.servers }),
          this.now,
          requestId,
        ),
    );
    if (!rotate) {
      statements.push(
        this.database.prepare('DELETE FROM review_guards WHERE id=?').bind(operation),
      );
    }
    try {
      await this.database.batch(statements);
    } catch (error) {
      if (String(error.message).includes('UNIQUE constraint failed')) {
        throw new ApiError(409, 'Operation already processed; inspect the audit log');
      }
      if (String(error.message).includes('CHECK constraint failed')) {
        throw new ApiError(404, 'Contributor not found');
      }
      throw error;
    }
    return {
      contributor: id,
      role: value.role,
      enabled: value.enabled,
      servers: value.servers,
      ...(credential ? { contributorCredential: credential } : {}),
    };
  }
}

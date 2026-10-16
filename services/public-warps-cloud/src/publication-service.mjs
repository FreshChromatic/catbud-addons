import { ApiError } from './protocol.mjs';
import { StorageCapacity } from './storage-capacity.mjs';
import { metadataValue } from './metadata-value.mjs';

export class PublicationService {
  constructor(database, now = Date.now(), env = {}) {
    this.database = database;
    this.now = now;
    this.env = env;
  }

  async pending(id) {
    const submission = await this.database
      .prepare(
        `SELECT s.*,w.revision AS current_revision,w.published,w.first_valid_at,w.pass_state,w.checked_at
       FROM server_submissions s JOIN server_warps w ON w.server_id=s.server_id AND w.uuid=s.uuid
       WHERE s.id=?`,
      )
      .bind(id)
      .first();
    if (!submission) {
      throw new ApiError(404, 'Submission not found');
    }
    return submission;
  }

  async approve(id, expectedRevision, actor, automatic = false, requestId = null) {
    const submission = await this.pending(id);
    if (submission.state === 'published') {
      return { accepted: true, published: true, submissionId: id, serverId: submission.server_id };
    }
    if (
      !Number.isSafeInteger(expectedRevision) ||
      expectedRevision !== submission.current_revision
    ) {
      throw new ApiError(409, 'Published content changed; review the current version');
    }
    const capacity = await new StorageCapacity(this.database, this.env).requireUpdates();
    const guardId = crypto.randomUUID();
    const now = this.now;
    const payload =
      submission.kind === 'metadata'
        ? metadataValue(JSON.parse(submission.payload))
        : JSON.parse(submission.payload);
    const validObservation =
      ['launch', 'arrival'].includes(submission.kind) ||
      (submission.kind === 'preview' && payload.validArrival === true);
    const failure = submission.kind === 'failure';
    const metadata = submission.kind === 'metadata';
    const trustedDiscovery = submission.kind === 'discovery' && automatic;
    if (
      !trustedDiscovery &&
      !['preview', 'launch', 'arrival', 'failure', 'metadata'].includes(submission.kind)
    ) {
      throw new ApiError(409, 'A name discovery alone does not prove a valid warp');
    }
    const statements = [
      this.database
        .prepare(
          `INSERT INTO review_guards(id,valid)
         SELECT ?,CASE WHEN EXISTS (
           SELECT 1 FROM server_submissions s JOIN server_warps w
           ON w.server_id=s.server_id AND w.uuid=s.uuid
           WHERE s.id=? AND s.state='pending' AND s.expires_at>?
           AND w.revision=? AND (w.first_valid_at>0 OR ?=1)
           AND (?=0 OR w.last_failure=0)
           AND (w.published=1 OR (SELECT COUNT(*) FROM server_warps WHERE published=1)<5000)
           AND (?=1 OR (w.pass_state='active' AND w.checked_at>?))
           AND (?=0 OR EXISTS (SELECT 1 FROM contributors c JOIN contributor_servers cs
             ON cs.contributor=c.id WHERE c.id=s.contributor AND c.id=? AND c.enabled=1
             AND c.role='trusted' AND cs.server_id=s.server_id))) THEN 1 ELSE 0 END`,
        )
        .bind(
          guardId,
          id,
          now,
          expectedRevision,
          validObservation || trustedDiscovery ? 1 : 0,
          trustedDiscovery ? 1 : 0,
          failure ? 1 : 0,
          now - 3600000,
          automatic ? 1 : 0,
          actor,
        ),
      this.database
        .prepare(
          `WITH retention AS MATERIALIZED (
           SELECT CASE WHEN ?=1 AND snapshot_hash IS NOT NULL AND snapshot_hash IS NOT ?
           AND (SELECT COALESCE(SUM(size),0) FROM previews WHERE hash IN
             (SELECT previous_hash FROM server_warps WHERE previous_until>?))
             +COALESCE((SELECT size FROM previews WHERE hash=snapshot_hash),0)<=33554432
           THEN 1 ELSE 0 END AS retain FROM server_warps WHERE server_id=? AND uuid=?)
           UPDATE server_warps SET
           first_valid_at=CASE WHEN first_valid_at=0 AND ?=1 THEN ? ELSE first_valid_at END,
           first_valid_source=CASE WHEN first_valid_at=0 AND ?=1 THEN ? ELSE first_valid_source END,
           published=CASE WHEN ?=1 THEN published ELSE 1 END,
           availability=CASE WHEN ?=1 THEN 'unavailable' WHEN ?=1 THEN 'available' ELSE availability END,
           last_failure=CASE WHEN ?=1 THEN ? ELSE last_failure END,
           last_success=CASE WHEN ?=1 THEN ? ELSE last_success END,
           previous_hash=CASE WHEN ? IS NOT NULL AND snapshot_hash IS NOT ?
             THEN CASE WHEN (SELECT retain FROM retention)=1 THEN snapshot_hash ELSE NULL END ELSE previous_hash END,
           previous_until=CASE WHEN ? IS NOT NULL AND snapshot_hash IS NOT ?
             THEN CASE WHEN (SELECT retain FROM retention)=1 THEN ? ELSE 0 END ELSE previous_until END,
           snapshot_hash=COALESCE(?,snapshot_hash),
           display_name=CASE WHEN ?=1 THEN ? ELSE display_name END,
           description=CASE WHEN ?=1 THEN ? ELSE description END,
           tags=CASE WHEN ?=1 THEN ? ELSE tags END
         WHERE server_id=? AND uuid=? AND revision=?`,
        )
        .bind(
          capacity.state === 'normal' ? 1 : 0,
          submission.hash,
          now,
          submission.server_id,
          submission.uuid,
          validObservation ? 1 : 0,
          now,
          validObservation ? 1 : 0,
          actor,
          failure ? 1 : 0,
          failure ? 1 : 0,
          validObservation ? 1 : 0,
          failure ? 1 : 0,
          now,
          submission.kind === 'arrival' || payload.validArrival === true ? 1 : 0,
          now,
          submission.hash,
          submission.hash,
          submission.hash,
          submission.hash,
          now + 604800000,
          submission.hash,
          metadata ? 1 : 0,
          payload.displayName ?? null,
          metadata ? 1 : 0,
          payload.description ?? null,
          metadata ? 1 : 0,
          JSON.stringify(payload.tags ?? []),
          submission.server_id,
          submission.uuid,
          expectedRevision,
        ),
      this.database.prepare("UPDATE server_submissions SET state='published' WHERE id=?").bind(id),
      this.database
        .prepare(
          `INSERT INTO server_audit(server_id,uuid,submission_id,actor,action,before_revision,after_revision,created_at,request_id,source_role)
         SELECT server_id,uuid,?,?,?, ?,revision,?,?,? FROM server_warps WHERE server_id=? AND uuid=?`,
        )
        .bind(
          id,
          actor,
          automatic ? 'automatic_publish' : 'approve',
          expectedRevision,
          now,
          requestId,
          automatic ? 'trusted' : 'administrator',
          submission.server_id,
          submission.uuid,
        ),
      this.database.prepare('DELETE FROM review_guards WHERE id=?').bind(guardId),
    ];
    try {
      await this.database.batch(statements);
    } catch (error) {
      if (/CHECK constraint failed|UNIQUE constraint failed/.test(String(error.message))) {
        throw new ApiError(
          409,
          'Publication requires current qualification, valid evidence and unchanged permissions',
        );
      }
      throw error;
    }
    return { accepted: true, published: true, submissionId: id, serverId: submission.server_id };
  }

  async reject(id, actor, reason, requestId = null) {
    const submission = await this.pending(id);
    if (submission.state !== 'pending') {
      throw new ApiError(409, 'Submission is already processed');
    }
    if (typeof reason !== 'string' || reason.length < 1 || reason.length > 500) {
      throw new ApiError(400, 'A bounded rejection reason is required');
    }
    const guardId = crypto.randomUUID();
    try {
      await this.database.batch([
        this.database
          .prepare(
            `INSERT INTO review_guards(id,valid) SELECT ?,CASE WHEN EXISTS
         (SELECT 1 FROM server_submissions WHERE id=? AND state='pending') THEN 1 ELSE 0 END`,
          )
          .bind(guardId, id),
        this.database
          .prepare("UPDATE server_submissions SET state='rejected',reason=? WHERE id=?")
          .bind(reason, id),
        this.database
          .prepare(
            `INSERT INTO server_audit(server_id,uuid,submission_id,actor,action,reason,created_at,request_id,source_role)
         VALUES (?,?,?,?,'reject',?,?,?,'administrator')`,
          )
          .bind(submission.server_id, submission.uuid, id, actor, reason, this.now, requestId),
        this.database.prepare('DELETE FROM review_guards WHERE id=?').bind(guardId),
      ]);
    } catch (error) {
      if (/CHECK constraint failed|UNIQUE constraint failed/.test(String(error.message))) {
        throw new ApiError(409, 'Submission was already processed');
      }
      throw error;
    }
    return { accepted: true, state: 'rejected', submissionId: id };
  }
}

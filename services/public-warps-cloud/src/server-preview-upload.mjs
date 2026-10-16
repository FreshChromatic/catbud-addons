import { ApiError, HASH, UUID, boundedBytes, identity, setting, sha256 } from './protocol.mjs';
import { canonicalPreview } from './preview-codec.mjs';
import { PublicationService } from './publication-service.mjs';

export class ServerPreviewUpload {
  constructor(database, budgets, env, qualification, submissions) {
    this.database = database;
    this.budgets = budgets;
    this.env = env;
    this.qualification = qualification;
    this.submissions = submissions;
  }

  async preflight(serverId, value, actor) {
    const player = identity(value);
    if (
      !HASH.test(value.hash ?? '') ||
      !Number.isSafeInteger(value.size) ||
      value.size < 1 ||
      value.size > 65536
    ) {
      throw new ApiError(400, 'Invalid upload size or digest');
    }
    await this.submissions.remember(serverId, player);
    if (!(await this.qualification.check(serverId, player)).active) {
      throw new ApiError(422, 'Player has no active pass on this server');
    }
    const now = this.budgets.now;
    const old = await this.database
      .prepare(
        'SELECT * FROM server_upload_tickets WHERE server_id=? AND uuid=? AND contributor=? AND hash=?',
      )
      .bind(serverId, player.uuid, actor.id, value.hash)
      .first();
    if (old?.consumed) {
      return {
        needed: false,
        ...(await this.result(old, actor)),
      };
    }
    if (old && old.expires_at > now) {
      return { needed: true, ticket: old.id, expiresAt: old.expires_at };
    }
    if (old) {
      await this.database
        .prepare('DELETE FROM server_upload_tickets WHERE id=? AND consumed=0 AND expires_at<=?')
        .bind(old.id, now)
        .run();
    }
    await this.budgets.consume('preview', setting(this.env, 'PREVIEW_DAILY_LIMIT', 1000));
    await this.budgets.consume('actor-preview:' + actor.id, actor.trusted ? 100 : 20);
    const id = crypto.randomUUID();
    const inserted = await this.database
      .prepare(
        `INSERT INTO server_upload_tickets(id,server_id,uuid,contributor,hash,size,created_at,expires_at)
       SELECT ?,?,?,?,?,?,?,? WHERE
       (SELECT value FROM metadata WHERE key='stored_bytes')
       +(SELECT value FROM metadata WHERE key='reserved_bytes')+?<=?
       AND (SELECT COUNT(*) FROM server_upload_tickets WHERE consumed=0)<200
       AND (SELECT COUNT(*) FROM server_submissions WHERE state='pending')<200
       AND (SELECT COUNT(*) FROM server_submissions WHERE server_id=? AND uuid=? AND state='pending' AND contributor!=?)<5
       AND NOT EXISTS (SELECT 1 FROM server_upload_tickets WHERE server_id=? AND uuid=? AND contributor=? AND consumed=0 AND expires_at>?)
       AND EXISTS (SELECT 1 FROM server_warps w WHERE server_id=? AND uuid=?
         AND (preview_at=0 OR preview_at<=?)
         AND (first_valid_at>0 OR EXISTS (SELECT 1 FROM server_submissions s
           WHERE s.server_id=w.server_id AND s.uuid=w.uuid AND s.contributor=?
           AND s.state='pending' AND s.kind='arrival' AND s.expires_at>?)))
       RETURNING id`,
      )
      .bind(
        id,
        serverId,
        player.uuid,
        actor.id,
        value.hash,
        value.size,
        now,
        now + 600000,
        value.size,
        setting(this.env, 'STORAGE_LIMIT_BYTES', 335544320),
        serverId,
        player.uuid,
        actor.id,
        serverId,
        player.uuid,
        actor.id,
        now,
        serverId,
        player.uuid,
        now - 1800000,
        actor.id,
        now,
      )
      .first();
    if (!inserted) {
      throw new ApiError(
        429,
        'Preview requires valid arrival evidence, cooldown and available capacity',
        1800,
      );
    }
    return { needed: true, ticket: id, expiresAt: now + 600000 };
  }

  async ticket(serverId, id, actor) {
    if (!UUID.test(id)) {
      throw new ApiError(400, 'Invalid upload ticket');
    }
    const ticket = await this.database
      .prepare('SELECT * FROM server_upload_tickets WHERE id=? AND server_id=? AND contributor=?')
      .bind(id, serverId, actor.id)
      .first();
    if (!ticket) {
      throw new ApiError(404, 'Upload ticket not found for this server and contributor');
    }
    if (!ticket.consumed && ticket.expires_at <= this.budgets.now) {
      throw new ApiError(410, 'Upload ticket expired');
    }
    return ticket;
  }

  async result(ticket, actor) {
    const submission = await new PublicationService(
      this.database,
      this.budgets.now,
      this.env,
    ).pending(ticket.submission_id);
    if (actor.trusted && submission.state === 'pending') {
      await this.qualification.check(ticket.server_id, {
        uuid: ticket.uuid,
        name: await this.database
          .prepare('SELECT name FROM server_warps WHERE server_id=? AND uuid=?')
          .bind(ticket.server_id, ticket.uuid)
          .first('name'),
      });
      const publication = new PublicationService(this.database, this.budgets.now, this.env);
      const current = await publication.pending(submission.id);
      return {
        ...(await publication.approve(current.id, current.current_revision, actor.id, true)),
        hash: ticket.result_hash,
        state: 'published',
      };
    }
    return {
      accepted: true,
      published: submission.state === 'published',
      state: submission.state === 'pending' ? 'pending_review' : submission.state,
      submissionId: submission.id,
      hash: ticket.result_hash,
      serverId: ticket.server_id,
      reason: submission.reason,
    };
  }

  async upload(serverId, id, request, actor) {
    const ticket = await this.ticket(serverId, id, actor);
    if (ticket.consumed) {
      await request.body?.cancel();
      return this.result(ticket, actor);
    }
    const bytes = await boundedBytes(request.body, 65536);
    if (bytes.length > ticket.size || (await sha256(bytes)) !== ticket.hash) {
      throw new ApiError(400, 'Upload size or digest does not match ticket');
    }
    const preview = await canonicalPreview(bytes);
    if (preview.bytes.length > ticket.size) {
      throw new ApiError(413, 'Canonical preview exceeds reserved capacity');
    }
    const now = this.budgets.now;
    const own = await this.database
      .prepare(
        "SELECT id,kind FROM server_submissions WHERE server_id=? AND uuid=? AND contributor=? AND state='pending' AND expires_at>?",
      )
      .bind(serverId, ticket.uuid, actor.id, now)
      .first();
    const validArrival = own?.kind === 'arrival';
    const submissionId = crypto.randomUUID();
    const guardId = crypto.randomUUID();
    try {
      await this.database.batch([
        this.database
          .prepare(
            `INSERT INTO review_guards(id,valid) SELECT ?,CASE WHEN EXISTS (
           SELECT 1 FROM server_upload_tickets t JOIN contributors c ON c.id=t.contributor
           JOIN contributor_servers s ON s.contributor=c.id AND s.server_id=t.server_id
           WHERE t.id=? AND t.consumed=0 AND t.expires_at>? AND c.enabled=1)
           AND (SELECT COUNT(*) FROM server_submissions WHERE state='pending')<200
           AND (SELECT COUNT(*) FROM server_submissions WHERE server_id=? AND uuid=? AND state='pending' AND contributor!=?)<5
           AND (?=0 OR EXISTS (SELECT 1 FROM server_submissions WHERE id=? AND state='pending' AND kind='arrival' AND expires_at>?))
           THEN 1 ELSE 0 END`,
          )
          .bind(
            guardId,
            ticket.id,
            now,
            serverId,
            ticket.uuid,
            actor.id,
            validArrival ? 1 : 0,
            own?.id ?? '',
            now,
          ),
        this.database
          .prepare(
            `INSERT INTO previews(hash,bytes,size,created_at) VALUES (?,?,?,?) ON CONFLICT(hash) DO NOTHING`,
          )
          .bind(preview.hash, Array.from(preview.bytes), preview.bytes.length, now),
        this.database
          .prepare(
            "UPDATE server_submissions SET state='rejected',reason='Superseded by preview' WHERE server_id=? AND uuid=? AND contributor=? AND state='pending'",
          )
          .bind(serverId, ticket.uuid, actor.id),
        this.database
          .prepare(
            `INSERT INTO server_submissions(id,server_id,uuid,contributor,hash,kind,payload,base_revision,created_at,expires_at)
          SELECT ?,server_id,uuid,?,?,'preview',?,revision,?,? FROM server_warps WHERE server_id=? AND uuid=?`,
          )
          .bind(
            submissionId,
            actor.id,
            preview.hash,
            JSON.stringify(validArrival ? { validArrival: true } : {}),
            now,
            now + 604800000,
            serverId,
            ticket.uuid,
          ),
        this.database
          .prepare('UPDATE server_warps SET preview_at=? WHERE server_id=? AND uuid=?')
          .bind(now, serverId, ticket.uuid),
        this.database
          .prepare(
            'UPDATE server_upload_tickets SET consumed=1,result_hash=?,submission_id=? WHERE id=?',
          )
          .bind(preview.hash, submissionId, id),
        this.database.prepare('DELETE FROM review_guards WHERE id=?').bind(guardId),
      ]);
    } catch (error) {
      if (String(error.message).includes('CHECK constraint failed')) {
        const latest = await this.ticket(serverId, id, actor);
        if (latest.consumed) {
          return this.result(latest, actor);
        }
        throw new ApiError(409, 'Upload expired, access changed or pending capacity reached');
      }
      throw error;
    }
    return this.result(await this.ticket(serverId, id, actor), actor);
  }
}

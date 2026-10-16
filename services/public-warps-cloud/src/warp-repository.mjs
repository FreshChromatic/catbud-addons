import { ApiError, HASH, identity, setting } from './protocol.mjs';

export class WarpRepository {
  constructor(database, budgets, env) {
    this.database = database;
    this.budgets = budgets;
    this.env = env;
  }

  async remember(player) {
    const { uuid, name } = identity(player);
    await this.database
      .prepare(
        `
      INSERT INTO warps(uuid,name,next_check)
      SELECT ?,?,? WHERE (SELECT COUNT(*) FROM warps)<5200
      ON CONFLICT(uuid) DO NOTHING
    `,
      )
      .bind(uuid, name, this.budgets.now)
      .run();
    const row = await this.database.prepare('SELECT * FROM warps WHERE uuid=?').bind(uuid).first();
    if (!row) {
      throw new ApiError(429, 'Warp capacity reached', 3600);
    }
    return row;
  }

  async proposeName(player, actor) {
    const now = this.budgets.now;
    await this.database
      .prepare(
        `
      INSERT INTO submissions(uuid,contributor,kind,created_at,expires_at)
      SELECT ?,?,'discovery',?,? WHERE NOT EXISTS (SELECT 1 FROM submissions WHERE uuid=?)
      AND (SELECT COUNT(*) FROM submissions)<200
      AND EXISTS (SELECT 1 FROM warps WHERE uuid=? AND published=0)
    `,
      )
      .bind(player.uuid, actor, now, now + 604800000, player.uuid, player.uuid)
      .run();
  }

  async reservePreview(player, actor, value) {
    if (
      !HASH.test(value.hash) ||
      !Number.isSafeInteger(value.size) ||
      value.size < 1 ||
      value.size > 65536
    ) {
      throw new ApiError(400, 'Invalid upload size or digest');
    }
    const now = this.budgets.now;
    const old = await this.database
      .prepare(
        `
      SELECT * FROM upload_tickets WHERE uuid=? AND contributor=? AND hash=?
    `,
      )
      .bind(player.uuid, actor, value.hash)
      .first();
    if (old?.consumed) {
      return { needed: false, accepted: true, hash: old.result_hash };
    }
    if (old && old.expires_at > now) {
      return { needed: true, ticket: old.id, expiresAt: old.expires_at };
    }
    if (old) {
      await this.database
        .prepare('DELETE FROM upload_tickets WHERE id=? AND consumed=0 AND expires_at<=?')
        .bind(old.id, now)
        .run();
    }
    await this.budgets.consume('preview', setting(this.env, 'PREVIEW_DAILY_LIMIT', 1000));
    await this.budgets.consume('actor-preview:' + actor, 20);
    const ticket = crypto.randomUUID();
    const row = await this.database
      .prepare(
        `
      INSERT INTO upload_tickets(id,uuid,contributor,hash,size,created_at,expires_at)
      SELECT ?,?,?,?,?,?,? WHERE
      (SELECT value FROM metadata WHERE key='stored_bytes')
      +(SELECT value FROM metadata WHERE key='reserved_bytes')+?<=?
      AND (SELECT COUNT(*) FROM upload_tickets WHERE consumed=0)
        +(SELECT COUNT(*) FROM submissions WHERE kind='preview')<200
      AND NOT EXISTS (SELECT 1 FROM upload_tickets WHERE uuid=? AND consumed=0 AND expires_at>?)
      AND NOT EXISTS (SELECT 1 FROM submissions WHERE uuid=? AND kind='preview')
      AND (SELECT COUNT(*) FROM submissions WHERE kind='preview')<200
      AND EXISTS (SELECT 1 FROM warps WHERE uuid=? AND (preview_at=0 OR preview_at<=?))
      ON CONFLICT(uuid,hash,contributor) DO NOTHING
      RETURNING id
    `,
      )
      .bind(
        ticket,
        player.uuid,
        actor,
        value.hash,
        value.size,
        now,
        now + 600000,
        value.size,
        setting(this.env, 'STORAGE_LIMIT_BYTES', 335544320),
        player.uuid,
        now,
        player.uuid,
        player.uuid,
        now - 1800000,
      )
      .first();
    if (!row) {
      throw new ApiError(429, 'Preview cooldown, pending review or storage capacity reached', 1800);
    }
    return { needed: true, ticket, expiresAt: now + 600000 };
  }

  async ticket(id, actor) {
    const ticket = await this.database
      .prepare('SELECT * FROM upload_tickets WHERE id=? AND contributor=?')
      .bind(id, actor)
      .first();
    if (!ticket) {
      throw new ApiError(404, 'Upload ticket not found');
    }
    if (!ticket.consumed && ticket.expires_at <= this.budgets.now) {
      throw new ApiError(410, 'Upload ticket expired');
    }
    return ticket;
  }

  async acceptPreview(ticket, preview) {
    const now = this.budgets.now;
    if (preview.bytes.length > ticket.size) {
      throw new ApiError(413, 'Canonical preview exceeds reserved capacity');
    }
    const statements = [
      this.database
        .prepare(
          `INSERT INTO previews(hash,bytes,size,created_at)
        SELECT ?,?,?,? WHERE EXISTS (SELECT 1 FROM upload_tickets WHERE id=? AND consumed=0 AND expires_at>?)
        ON CONFLICT(hash) DO NOTHING`,
        )
        .bind(preview.hash, Array.from(preview.bytes), preview.bytes.length, now, ticket.id, now),
      this.database
        .prepare(
          `INSERT INTO submissions(uuid,contributor,hash,kind,created_at,expires_at)
        SELECT uuid,contributor,?,'preview',?,? FROM upload_tickets WHERE id=? AND consumed=0 AND expires_at>?
        ON CONFLICT(uuid) DO UPDATE SET hash=excluded.hash,kind='preview',contributor=excluded.contributor,
        created_at=excluded.created_at,expires_at=excluded.expires_at
        WHERE submissions.kind='discovery'`,
        )
        .bind(preview.hash, now, now + 604800000, ticket.id, now),
      this.database
        .prepare(
          `UPDATE warps SET preview_at=? WHERE uuid=? AND EXISTS
        (SELECT 1 FROM upload_tickets WHERE id=? AND consumed=0 AND expires_at>?)`,
        )
        .bind(now, ticket.uuid, ticket.id, now),
      this.database
        .prepare(
          'UPDATE upload_tickets SET consumed=1,result_hash=? WHERE id=? AND consumed=0 AND expires_at>?',
        )
        .bind(preview.hash, ticket.id, now),
    ];
    const results = await this.database.batch(statements);
    if (!results[3].meta.changes) {
      const latest = await this.ticket(ticket.id, ticket.contributor);
      if (!latest.consumed) {
        throw new ApiError(410, 'Upload ticket expired');
      }
    }
    return { accepted: true, published: false, hash: preview.hash };
  }
}

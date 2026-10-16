import { DatabaseSync } from 'node:sqlite';
import { initializePreviewHistory, cleanSnapshots } from './snapshot-management.mjs';

export class WarpRepository {
  constructor(database, now = Date.now) {
    this.db = new DatabaseSync(database);
    this.now = now;
    this.db.exec(`PRAGMA journal_mode=WAL;
      CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY, value INTEGER NOT NULL);
      INSERT OR IGNORE INTO metadata VALUES ('version', 0);
      CREATE TABLE IF NOT EXISTS warps (
        uuid TEXT PRIMARY KEY, name TEXT NOT NULL, verified_at INTEGER NOT NULL,
        reported_at INTEGER NOT NULL, snapshot_hash TEXT,
        display_name TEXT, description TEXT, tags TEXT NOT NULL DEFAULT '[]',
        revision INTEGER NOT NULL DEFAULT 0);
      CREATE TABLE IF NOT EXISTS snapshots (
        hash TEXT PRIMARY KEY, bytes BLOB NOT NULL, created_at INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS candidates (
        uuid TEXT PRIMARY KEY, hash TEXT NOT NULL, created_at INTEGER NOT NULL);
      PRAGMA user_version=1;`);
    initializePreviewHistory(this.db);
    this.clean();
  }

  version() {
    return this.db.prepare("SELECT value FROM metadata WHERE key='version'").get().value;
  }

  incrementVersion() {
    this.db.prepare("UPDATE metadata SET value=value+1 WHERE key='version'").run();
  }

  clean() {
    cleanSnapshots(this.db, this.now());
  }

  etag() {
    // Expiry changes list status even when no report mutates the database.
    return `"${this.version()}-${Math.floor(this.now() / 300000)}"`;
  }

  list() {
    const rows = this.db.prepare('SELECT * FROM warps ORDER BY name COLLATE NOCASE').all();
    return {
      version: this.version(),
      generatedAt: this.now(),
      warps: rows.map((warp) => this.publicWarp(warp)),
    };
  }

  publicWarp(warp) {
    const expired = this.now() - warp.verified_at > 86400000;
    return {
      uuid: warp.uuid,
      name: warp.name,
      verifiedAt: warp.verified_at,
      reportedAt: warp.reported_at,
      snapshotHash: warp.snapshot_hash,
      displayName: warp.display_name,
      description: warp.description,
      tags: JSON.parse(warp.tags),
      revision: warp.revision,
      status: expired ? 'stale' : 'verified_report',
    };
  }

  snapshot(hash) {
    const row = this.db
      .prepare(
        'SELECT bytes FROM snapshots WHERE hash=? AND hash IN (SELECT snapshot_hash FROM warps)',
      )
      .get(hash);
    return row ? Buffer.from(row.bytes) : null;
  }

  removeInactivePlayer(uuid) {
    const key = uuid.toLowerCase();
    const removed = this.db.prepare('DELETE FROM warps WHERE uuid=?').run(key);
    this.db.prepare('DELETE FROM candidates WHERE uuid=?').run(key);
    if (removed.changes) {
      this.incrementVersion();
    }
    this.clean();
  }

  recordReport(player, preview, reportedAt) {
    const old = this.db.prepare('SELECT * FROM warps WHERE uuid=?').get(player.uuid);
    let hash = old?.snapshot_hash ?? null;
    let candidate = false;
    this.db.exec('BEGIN IMMEDIATE');
    try {
      if (preview) {
        this.db
          .prepare('INSERT OR IGNORE INTO snapshots VALUES (?,?,?)')
          .run(preview.hash, preview.bytes, reportedAt);
        if (!hash || hash === preview.hash) {
          hash = preview.hash;
        } else {
          // A client cannot prove world contents; replacement requires administrator review.
          this.db
            .prepare(
              `INSERT INTO candidates VALUES (?,?,?)
            ON CONFLICT(uuid) DO UPDATE SET hash=excluded.hash,created_at=excluded.created_at`,
            )
            .run(player.uuid, preview.hash, reportedAt);
          candidate = true;
        }
      }
      this.db
        .prepare(
          `INSERT INTO warps (uuid,name,verified_at,reported_at,snapshot_hash)
        VALUES (?,?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET name=excluded.name,
        verified_at=excluded.verified_at,reported_at=excluded.reported_at,
        snapshot_hash=excluded.snapshot_hash`,
        )
        .run(player.uuid, player.name, player.at, reportedAt, hash);
      this.incrementVersion();
      this.clean();
      this.db.exec('COMMIT');
    } catch (error) {
      this.db.exec('ROLLBACK');
      throw error;
    }
    return {
      accepted: true,
      previewCandidate: candidate,
      version: this.version(),
      snapshotHash: hash,
    };
  }
}

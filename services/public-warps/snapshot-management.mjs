// Direct database administration only; never exposed through client HTTP routes.
export function initializePreviewHistory(db) {
  db.exec(
    'CREATE TABLE IF NOT EXISTS previous_snapshots (uuid TEXT PRIMARY KEY, hash TEXT NOT NULL)',
  );
}
export function cleanSnapshots(db, now = Date.now()) {
  db.prepare(
    'DELETE FROM candidates WHERE created_at<? OR uuid NOT IN (SELECT uuid FROM warps)',
  ).run(now - 7 * 86400000);
  db.exec(`DELETE FROM previous_snapshots WHERE uuid NOT IN (SELECT uuid FROM warps);
    DELETE FROM snapshots WHERE hash NOT IN
    (SELECT snapshot_hash FROM warps WHERE snapshot_hash IS NOT NULL
     UNION SELECT hash FROM candidates UNION SELECT hash FROM previous_snapshots)`);
}
export function managePreview(db, action, uuid, now = Date.now()) {
  initializePreviewHistory(db);
  db.exec('BEGIN IMMEDIATE');
  try {
    const warp = db.prepare('SELECT snapshot_hash FROM warps WHERE uuid=?').get(uuid);
    if (!warp) {
      throw new Error('Warp not found');
    }
    let replacement;
    if (action === 'approve') {
      const candidate = db.prepare('SELECT hash,created_at FROM candidates WHERE uuid=?').get(uuid);
      if (!candidate || candidate.created_at < now - 7 * 86400000) {
        throw new Error('No current candidate for UUID');
      }
      replacement = candidate.hash;
    } else if (action === 'rollback') {
      replacement = db.prepare('SELECT hash FROM previous_snapshots WHERE uuid=?').get(uuid)?.hash;
      if (!replacement) {
        throw new Error('No previous preview for UUID');
      }
    } else if (action === 'remove') {
      replacement = null;
    } else if (action !== 'reject') {
      throw new Error('Unknown preview action');
    }
    if (action !== 'reject') {
      if (warp.snapshot_hash) {
        db.prepare(
          `INSERT INTO previous_snapshots VALUES (?,?)
        ON CONFLICT(uuid) DO UPDATE SET hash=excluded.hash`,
        ).run(uuid, warp.snapshot_hash);
      } else {
        db.prepare('DELETE FROM previous_snapshots WHERE uuid=?').run(uuid);
      }
      db.prepare('UPDATE warps SET snapshot_hash=?,revision=revision+1 WHERE uuid=?').run(
        replacement,
        uuid,
      );
      db.prepare("UPDATE metadata SET value=value+1 WHERE key='version'").run();
    }
    db.prepare('DELETE FROM candidates WHERE uuid=?').run(uuid);
    cleanSnapshots(db, now);
    db.exec('COMMIT');
  } catch (error) {
    db.exec('ROLLBACK');
    throw error;
  }
}

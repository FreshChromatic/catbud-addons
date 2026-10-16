// Local administrator tool. No administrative secret or endpoint is exposed to mod clients.
import { DatabaseSync } from 'node:sqlite';
import { initializePreviewHistory, cleanSnapshots, managePreview } from './snapshot-management.mjs';
const [command, uuid, ...rest] = process.argv.slice(2);
// fileURLToPath is needed for Windows drive letters.
import { fileURLToPath } from 'node:url';
const db = new DatabaseSync(
  process.env.CATBUD_WARPS_DB ?? fileURLToPath(new URL('public-warps.sqlite', import.meta.url)),
);
try {
  initializePreviewHistory(db);
  cleanSnapshots(db);
  if (command === 'candidates') {
    console.table(
      db
        .prepare(
          'SELECT c.uuid,w.name,c.hash,c.created_at FROM candidates c JOIN warps w ON c.uuid=w.uuid',
        )
        .all(),
    );
  } else if (['approve', 'reject', 'rollback', 'remove'].includes(command) && uuid) {
    managePreview(db, command, uuid.toLowerCase());
    console.log('Preview action completed: ' + command);
  } else if (command === 'name' && uuid) {
    const name = rest.join(' ').trim();
    if (!name || [...name].length > 40) {
      throw new Error('Name must contain 1–40 characters');
    }
    db.exec('BEGIN IMMEDIATE');
    const result = db
      .prepare('UPDATE warps SET display_name=?,revision=revision+1 WHERE uuid=?')
      .run(name, uuid);
    if (!result.changes) {
      db.exec('ROLLBACK');
      throw new Error('Warp not found');
    }
    db.prepare("UPDATE metadata SET value=value+1 WHERE key='version'").run();
    db.exec('COMMIT');
    console.log('Name updated');
  } else {
    console.log(
      'Usage: node admin.mjs candidates | approve/reject/rollback/remove <UUID> | name <UUID> <display name>',
    );
    process.exitCode = 1;
  }
} finally {
  db.close();
}

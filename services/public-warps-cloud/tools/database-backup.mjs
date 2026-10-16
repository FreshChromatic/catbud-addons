import { DatabaseSync, backup } from 'node:sqlite';
import { mkdir, readdir, stat, link, unlink, copyFile } from 'node:fs/promises';
import { join, basename } from 'node:path';
import { randomUUID } from 'node:crypto';

export class DatabaseBackup {
  static async databaseFile(stateDirectory) {
    const directory = join(stateDirectory, 'd1', 'miniflare-D1DatabaseObject');
    const files = (await readdir(directory)).filter((name) => /^[a-f0-9]{64}\.sqlite$/.test(name));
    if (files.length !== 1) {
      throw new Error('Expected exactly one local public-warps database.');
    }
    return join(directory, files[0]);
  }

  static validate(file) {
    const database = new DatabaseSync(file, { readOnly: true });
    try {
      const result = database.prepare('PRAGMA quick_check').get();
      if (Object.values(result)[0] !== 'ok') {
        throw new Error('Database integrity check failed.');
      }
      const migration = database
        .prepare('SELECT name FROM local_migrations WHERE name=?')
        .get('0002_review_guards.sql');
      if (!migration) {
        throw new Error('Backup does not contain the current public-warps schema.');
      }
      if (database.prepare('PRAGMA foreign_key_check').get()) {
        throw new Error('Backup contains broken database references.');
      }
      const counters = database
        .prepare(
          `SELECT
        (SELECT value FROM metadata WHERE key='stored_bytes') AS stored,
        (SELECT COALESCE(SUM(size),0) FROM previews) AS actual,
        (SELECT value FROM metadata WHERE key='reserved_bytes') AS reserved,
        (SELECT COALESCE(SUM(size),0) FROM upload_tickets WHERE consumed=0) AS tickets`,
        )
        .get();
      if (counters.stored !== counters.actual || counters.reserved !== counters.tickets) {
        throw new Error('Backup capacity counters do not match stored data.');
      }
    } finally {
      database.close();
    }
  }

  static async create(stateDirectory, destination) {
    const source = await this.databaseFile(stateDirectory);
    await mkdir(destination, { recursive: false });
    const target = join(destination, basename(source));
    const temporary = join(destination, randomUUID() + '.tmp');
    const database = new DatabaseSync(source, { readOnly: true });
    try {
      await backup(database, temporary);
      this.validate(temporary);
      await link(temporary, target);
    } finally {
      database.close();
      await unlink(temporary).catch(() => {});
    }
    return target;
  }

  static async recover(source, destination) {
    const name = basename(source);
    if (!/^[a-f0-9]{64}\.sqlite$/.test(name) || (await stat(source)).size > 400 * 1024 * 1024) {
      throw new Error('Unsupported backup filename or size.');
    }
    this.validate(source);
    await mkdir(destination, { recursive: false });
    const directory = join(destination, 'd1', 'miniflare-D1DatabaseObject');
    await mkdir(directory, { recursive: true });
    await copyFile(source, join(directory, name));
  }
}

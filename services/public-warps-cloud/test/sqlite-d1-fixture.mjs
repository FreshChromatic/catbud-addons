import { DatabaseSync } from 'node:sqlite';

export function sqliteDatabase(context) {
  const sqlite = new DatabaseSync(':memory:');
  context.after(() => sqlite.close());
  const database = {
    prepare(sql) {
      const statement = sqlite.prepare(sql);
      const wrap = (values) => ({
        bind: (...bound) => wrap(bound),
        async all() {
          return {
            results: statement.all(...values).map((row) => ({ ...row })),
            meta: { size_after: sqlite.prepare('PRAGMA page_count').get().page_count * 4096 },
          };
        },
        async first(column) {
          const row = (await this.all()).results[0];
          return row ? (column === undefined ? row : row[column]) : null;
        },
        async run() {
          statement.run(...values);
          return { results: [], meta: {} };
        },
      });
      return wrap([]);
    },
    async batch(statements) {
      sqlite.exec('BEGIN');
      try {
        const results = [];
        for (const statement of statements) {
          results.push(await statement.run());
        }
        sqlite.exec('COMMIT');
        return results;
      } catch (error) {
        sqlite.exec('ROLLBACK');
        throw error;
      }
    },
    async exec(sql) {
      sqlite.exec(sql);
    },
  };
  return database;
}

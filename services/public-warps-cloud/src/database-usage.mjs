export class DatabaseUsage {
  constructor(operation, now = Date.now()) {
    this.operation = operation;
    this.day = new Date(now).toISOString().slice(0, 10);
    this.id = crypto.randomUUID();
    this.queries = 0;
    this.rowsRead = 0;
    this.rowsWritten = 0;
    this.unknown = 0;
    this.databaseBytes = 0;
  }

  async measure(execute, count = 1) {
    let result;
    try {
      result = await execute();
    } catch (error) {
      this.queries += count;
      this.unknown += count;
      throw error;
    }
    const entries = Array.isArray(result) ? result : [result];
    const missing = Math.max(0, count - entries.length);
    this.queries += missing;
    this.unknown += missing;
    for (const entry of entries) {
      this.queries++;
      const meta = entry?.meta;
      const valid =
        Number.isSafeInteger(meta?.rows_read) &&
        meta.rows_read >= 0 &&
        Number.isSafeInteger(meta?.rows_written) &&
        meta.rows_written >= 0;
      if (valid) {
        this.rowsRead += meta.rows_read;
        this.rowsWritten += meta.rows_written;
      } else {
        this.unknown++;
      }
      if (Number.isSafeInteger(meta?.size_after) && meta.size_after >= 0) {
        this.databaseBytes = Math.max(this.databaseBytes, meta.size_after);
      }
    }
    return result;
  }

  report() {
    return {
      type: 'd1_usage',
      id: this.id,
      day: this.day,
      operation: this.operation,
      queries: this.queries,
      rowsRead: this.rowsRead,
      rowsWritten: this.rowsWritten,
      unknown: this.unknown,
      databaseBytes: this.databaseBytes,
    };
  }
}

export class MeasuredDatabase {
  constructor(database, usage) {
    this.database = database;
    this.usage = usage;
  }

  prepare(sql) {
    return new MeasuredStatement(this.database.prepare(sql), this.usage);
  }

  batch(statements) {
    return this.usage.measure(
      () => this.database.batch(statements.map((statement) => statement.statement ?? statement)),
      statements.length,
    );
  }
}

class MeasuredStatement {
  constructor(statement, usage) {
    this.statement = statement;
    this.usage = usage;
  }

  bind(...values) {
    return new MeasuredStatement(this.statement.bind(...values), this.usage);
  }

  all() {
    return this.usage.measure(() => this.statement.all());
  }

  run() {
    return this.usage.measure(() => this.statement.run());
  }

  async first(column) {
    const result = await this.all();
    const first = result.results[0];
    if (!first) {
      return null;
    }
    if (column === undefined) {
      return first;
    }
    if (!Object.hasOwn(first, column)) {
      throw new Error('Requested result column is missing');
    }
    return first[column];
  }
}

export class UsageSummary {
  constructor() {
    this.days = new Map();
    this.ids = new Set();
    this.duplicates = 0;
  }

  add(report) {
    if (report?.type !== 'd1_usage') {
      return false;
    }
    const numbers = ['queries', 'rowsRead', 'rowsWritten', 'unknown', 'databaseBytes'];
    const operations = [
      'list',
      'changes',
      'discovery',
      'availability',
      'preflight',
      'upload',
      'preview',
      'scheduled',
      'admin',
      'other',
    ];
    if (
      !/^[0-9a-f-]{36}$/.test(report.id ?? '') ||
      !/^\d{4}-\d{2}-\d{2}$/.test(report.day ?? '') ||
      !operations.includes(report.operation) ||
      numbers.some((field) => !Number.isSafeInteger(report[field]) || report[field] < 0) ||
      report.unknown > report.queries
    ) {
      throw new Error('Invalid D1 usage record');
    }
    if (this.ids.has(report.id)) {
      this.duplicates++;
      return true;
    }
    if (this.ids.size >= 200000 || (this.days.size >= 32 && !this.days.has(report.day))) {
      throw new Error('Usage report exceeds bounded history');
    }
    this.ids.add(report.id);
    let day = this.days.get(report.day);
    if (!day) {
      day = {
        day: report.day,
        reports: 0,
        queries: 0,
        rowsRead: 0,
        rowsWritten: 0,
        unknown: 0,
        databaseBytes: 0,
        operations: {},
      };
      this.days.set(report.day, day);
    }
    let operation = day.operations[report.operation];
    if (!operation) {
      operation = { reports: 0, queries: 0, rowsRead: 0, rowsWritten: 0, unknown: 0 };
      day.operations[report.operation] = operation;
    }
    for (const target of [day, operation]) {
      target.reports++;
      for (const field of ['queries', 'rowsRead', 'rowsWritten', 'unknown']) {
        target[field] += report[field];
        if (!Number.isSafeInteger(target[field])) {
          throw new Error('Usage counter overflow');
        }
      }
    }
    day.databaseBytes = Math.max(day.databaseBytes, report.databaseBytes);
    return true;
  }

  report() {
    return {
      scope: 'observed_logs_only',
      duplicates: this.duplicates,
      days: [...this.days.values()]
        .sort((a, b) => a.day.localeCompare(b.day))
        .map((day) => ({
          ...day,
          incomplete: day.unknown > 0,
          warning: day.rowsRead >= 2500000 || day.rowsWritten >= 50000,
        })),
    };
  }
}

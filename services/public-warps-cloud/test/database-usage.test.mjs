import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseUsage, MeasuredDatabase } from '../src/database-usage.mjs';
import { UsageSummary } from '../src/usage-summary.mjs';

test('query failures and missing metadata remain unknown rather than free', async () => {
  const usage = new DatabaseUsage('upload');
  await usage.measure(async () => ({ meta: { rows_read: 10, rows_written: 4, size_after: 100 } }));
  await usage.measure(async () => ({ results: [] }));
  await assert.rejects(
    usage.measure(async () => {
      throw new Error('private SQL');
    }, 3),
  );
  const report = usage.report();
  assert.equal(report.rowsRead, 10);
  assert.equal(report.rowsWritten, 4);
  assert.equal(report.queries, 5);
  assert.equal(report.unknown, 4);
  assert.ok(!JSON.stringify(report).includes('private SQL'));
});

test('interleaved operations have independent counters and bound statements preserve first-column semantics', async () => {
  const original = {
    prepare: () => ({
      bind: () => original.prepare(),
      all: async () => ({ results: [{ value: 42 }], meta: { rows_read: 2, rows_written: 0 } }),
    }),
  };
  const first = new DatabaseUsage('list');
  const second = new DatabaseUsage('list');
  const values = await Promise.all([
    new MeasuredDatabase(original, first).prepare('SELECT ?').bind(42).first('value'),
    new MeasuredDatabase(original, second).prepare('SELECT ?').bind(42).first(),
  ]);
  assert.deepEqual(values, [42, { value: 42 }]);
  assert.equal(first.rowsRead, 2);
  assert.equal(second.rowsRead, 2);
});

test('daily reports deduplicate deliveries, flag warnings and retain partial measurements', () => {
  const usage = new DatabaseUsage('scheduled', Date.UTC(2026, 9, 11));
  usage.rowsWritten = 50000;
  usage.queries = 2;
  usage.unknown = 1;
  const summary = new UsageSummary();
  summary.add(usage.report());
  summary.add(usage.report());
  const report = summary.report();
  assert.equal(report.duplicates, 1);
  assert.equal(report.days[0].rowsWritten, 50000);
  assert.equal(report.days[0].warning, true);
  assert.equal(report.days[0].incomplete, true);
  assert.throws(() => summary.add({ ...usage.report(), rowsRead: -1 }));
});

test('missing batch results are counted as unknown queries', async () => {
  const usage = new DatabaseUsage('admin');
  await usage.measure(async () => [{ meta: { rows_read: 2, rows_written: 1 } }], 3);
  assert.equal(usage.queries, 3);
  assert.equal(usage.unknown, 2);
});

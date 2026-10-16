import test from 'node:test';
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';
import { DatabaseUsage } from '../src/database-usage.mjs';

const execute = promisify(execFile);
const tool = fileURLToPath(new URL('../tools/usage-report.mjs', import.meta.url));

async function withLog(content, verify) {
  const directory = await mkdtemp(join(tmpdir(), 'catbud-usage-'));
  const file = join(directory, 'usage.jsonl');
  try {
    await writeFile(file, content);
    await verify(file, directory);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
}

test('report CLI combines plain and tail logs without double-counting delivered reports', async () => {
  const usage = new DatabaseUsage('upload', Date.UTC(2026, 9, 11));
  await usage.measure(async () => ({ meta: { rows_read: 17, rows_written: 9, size_after: 4096 } }));
  const report = usage.report();
  const lines = [
    'ordinary server output',
    'null',
    JSON.stringify(report),
    JSON.stringify({ logs: [null, { message: ['ordinary output', JSON.stringify(report)] }] }),
  ];
  await withLog(lines.join('\n'), async (file, directory) => {
    const { stdout } = await execute(process.execPath, [tool, file], { cwd: directory });
    const summary = JSON.parse(stdout);
    assert.equal(summary.scope, 'observed_logs_only');
    assert.equal(summary.duplicates, 1);
    assert.equal(summary.days[0].rowsRead, 17);
    assert.equal(summary.days[0].rowsWritten, 9);
  });
});

test('report CLI refuses to infer zero usage or accept invalid metering records', async () => {
  await withLog('null\n{"logs":[]}\n', async (file) => {
    await assert.rejects(execute(process.execPath, [tool, file]), /zero usage cannot be inferred/);
  });
  const report = new DatabaseUsage('list').report();
  await withLog(JSON.stringify({ ...report, rowsWritten: -1 }), async (file) => {
    await assert.rejects(execute(process.execPath, [tool, file]), /Invalid D1 usage record/);
  });
});

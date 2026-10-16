import { createReadStream } from 'node:fs';
import { stat } from 'node:fs/promises';
import { createInterface } from 'node:readline';
import { UsageSummary } from '../src/usage-summary.mjs';

const file = process.argv[2];
if (!file || (await stat(file)).size > 128 * 1024 * 1024) {
  throw new Error('usage-report requires a JSONL log file up to 128 MiB');
}
const summary = new UsageSummary();
const lines = createInterface({
  input: createReadStream(file, { encoding: 'utf8' }),
  crlfDelay: Infinity,
});
for await (const line of lines) {
  if (line.length > 65536) {
    throw new Error('Usage log line exceeds size limit');
  }
  if (!line.trim()) {
    continue;
  }
  let document;
  try {
    document = JSON.parse(line);
  } catch {
    continue;
  }
  summary.add(document);
  if (Array.isArray(document?.logs)) {
    for (const log of document.logs) {
      for (const message of Array.isArray(log?.message) ? log.message : []) {
        if (typeof message === 'string' && message.startsWith('{')) {
          let report;
          try {
            report = JSON.parse(message);
          } catch {
            continue;
          }
          summary.add(report);
        }
      }
    }
  }
}
const report = summary.report();
if (!report.days.length) {
  throw new Error('No D1 usage records found; zero usage cannot be inferred');
}
console.log(JSON.stringify(report, null, 2));

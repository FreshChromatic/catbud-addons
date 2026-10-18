import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';
import { AddonManagementApplication } from '../src/addons/addon-management-application.mjs';
import { AddonPublicApplication } from '../src/addons/addon-public-application.mjs';
import { signedRelease } from '../test/addon-release-fixture.mjs';

const runtime = new Miniflare(
  convertV4MiniflareOptions({
    modules: true,
    script: 'export default { fetch() { return new Response("ok"); } }',
    compatibilityDate: '2026-10-11',
    d1Databases: ['DB'],
  }),
);
const database = await runtime.getD1Database('DB');
await database.exec(
  (
    await readFile(new URL('../migrations/0008_addon_updates.sql', import.meta.url), 'utf8')
  ).replaceAll('\n', ' '),
);
const release = signedRelease();
const env = { DB: database, ADDON_RELEASE_KEYS: JSON.stringify(release.keys) };
const csrf = crypto.randomUUID();
const origin = 'http://127.0.0.1:8791';
const server = createServer(async (incoming, outgoing) => {
  try {
    const path = new URL(incoming.url, origin).pathname;
    let response;
    if (path === '/api/session') {
      response = Response.json({ email: '本機隔離驗證', csrf });
    } else if (path.startsWith('/api/addons/') || path.startsWith('/addons/v1/')) {
      let body;
      if (incoming.method === 'POST') {
        if (incoming.headers.origin !== origin || incoming.headers['x-csrf-token'] !== csrf) {
          throw new Error('Local same-origin verification required');
        }
        const chunks = [];
        let total = 0;
        for await (const chunk of incoming) {
          total += chunk.length;
          if (total > 16384) {
            throw new Error('Request too large');
          }
          chunks.push(chunk);
        }
        body = Buffer.concat(chunks);
      }
      const request = new Request(origin + incoming.url, {
        method: incoming.method,
        headers: incoming.headers,
        body,
      });
      response = path.startsWith('/api/')
        ? await new AddonManagementApplication(env, Date.now(), release.fetcher).handle(request, {
            actor: 'local-verification',
          })
        : await new AddonPublicApplication(database).handle(request);
    } else if (path === '/') {
      response = new Response(null, { status: 302, headers: { Location: '/addons.html' } });
    } else if (/^\/[a-z-]+\.(html|mjs|css)$/.test(path)) {
      const bytes = await readFile(new URL('../admin' + path, import.meta.url));
      response = new Response(bytes, {
        headers: {
          'Content-Type': path.endsWith('.html')
            ? 'text/html; charset=utf-8'
            : path.endsWith('.css')
              ? 'text/css'
              : 'text/javascript',
        },
      });
    } else {
      response = new Response('Not found', { status: 404 });
    }
    outgoing.writeHead(response.status, Object.fromEntries(response.headers));
    outgoing.end(Buffer.from(await response.arrayBuffer()));
  } catch (error) {
    outgoing.writeHead(400, { 'Content-Type': 'application/json' });
    outgoing.end(JSON.stringify({ error: error.message }));
  }
});
server.listen(8791, '127.0.0.1', () =>
  console.log(
    'Isolated addon management verification: ' + origin + '/addons.html (fixture release v1.0.4)',
  ),
);
process.on('SIGINT', async () => {
  server.close();
  await runtime.dispose();
  process.exit(0);
});

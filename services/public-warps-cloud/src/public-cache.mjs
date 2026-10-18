export class PublicCache {
  constructor(cache, application) {
    this.cache = cache;
    this.application = application;
  }

  async handle(request, context) {
    const url = new URL(request.url);
    const eligible =
      request.method === 'GET' &&
      (['/v1/warps', '/v1/changes', '/v2/servers'].includes(url.pathname) ||
        /^\/addons\/v1\/(catalog|notices|status)$/.test(url.pathname) ||
        /^\/v2\/servers\/(catbud|catrealm)\/(warps|changes)$/.test(url.pathname));
    const bypass = /(?:no-cache|no-store|max-age=0)/i.test(
      request.headers.get('Cache-Control') ?? '',
    );
    if (!eligible || bypass) {
      return this.application.handle(request);
    }
    const keyUrl = new URL(url);
    if (keyUrl.pathname.startsWith('/addons/v1/')) {
      const allowed = keyUrl.pathname.endsWith('/catalog')
        ? ['minecraft', 'channel']
        : keyUrl.pathname.endsWith('/notices')
          ? ['minecraft', 'server', 'cursor']
          : [];
      for (const name of [...keyUrl.searchParams.keys()]) {
        if (!allowed.includes(name)) {
          keyUrl.searchParams.delete(name);
        }
      }
      keyUrl.searchParams.sort();
    }
    const key = new Request(keyUrl.toString());
    let response = await this.cache.match(key);
    if (keyUrl.pathname.startsWith('/addons/v1/')) {
      console.log(
        JSON.stringify({
          type: 'addon_cache',
          route: keyUrl.pathname.split('/').at(-1),
          hit: Boolean(response),
        }),
      );
    }
    if (!response) {
      const unconditional = new Request(request);
      unconditional.headers.delete('If-None-Match');
      response = await this.application.handle(unconditional);
      if (response.status === 200) {
        context.waitUntil(this.cache.put(key, response.clone()));
      }
    }
    const etag = response.headers.get('ETag');
    if (etag && request.headers.get('If-None-Match') === etag) {
      return new Response(null, { status: 304, headers: { ETag: etag } });
    }
    return response;
  }
}

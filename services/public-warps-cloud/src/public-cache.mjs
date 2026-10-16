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
        /^\/v2\/servers\/(catbud|catrealm)\/(warps|changes)$/.test(url.pathname));
    const bypass = /(?:no-cache|no-store|max-age=0)/i.test(
      request.headers.get('Cache-Control') ?? '',
    );
    if (!eligible || bypass) {
      return this.application.handle(request);
    }
    const key = new Request(url.toString());
    let response = await this.cache.match(key);
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

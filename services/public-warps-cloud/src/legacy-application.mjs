import { ApiError, requestJson } from './protocol.mjs';
import { ServerApplication } from './server-application.mjs';

export class LegacyApplication {
  constructor(application) {
    this.application = new ServerApplication(application);
  }

  async handle(request) {
    const url = new URL(request.url);
    let route = url.pathname.slice(4);
    if (route.startsWith('snapshots/')) {
      route = 'previews/' + route.slice(10);
    } else if (/^submissions\/[0-9a-f-]{36}$/.test(route)) {
      route = 'uploads/' + route.slice(12);
    }
    let body;
    if (route === 'availability-reports' && request.method === 'POST') {
      const value = await requestJson(request);
      if (!['successful_arrival', 'target_no_pass_or_set'].includes(value.reason)) {
        throw new ApiError(400, 'Unsupported availability reason');
      }
      route = 'observations';
      body = JSON.stringify({ ...value, submissionId: value.submissionId ?? crypto.randomUUID() });
    }
    url.pathname = '/v2/servers/catbud/' + route;
    const forwarded = new Request(url, {
      method: request.method,
      headers: request.headers,
      body: body ?? request.body,
      ...(request.body && !body ? { duplex: 'half' } : {}),
    });
    return this.application.handle(forwarded);
  }
}

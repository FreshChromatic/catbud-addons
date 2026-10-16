import { ApiError } from './protocol.mjs';

const SERVERS = Object.freeze({
  catbud: Object.freeze({ id: 'catbud', name: '貓芽', apiHost: 'api.catbud.net' }),
  catrealm: Object.freeze({ id: 'catrealm', name: '貓域', apiHost: 'api.catrealm.net' }),
});

export class ServerScope {
  static require(id) {
    if (typeof id !== 'string' || !Object.hasOwn(SERVERS, id)) {
      throw new ApiError(404, 'Unsupported server');
    }
    return SERVERS[id];
  }

  static list() {
    return Object.values(SERVERS).map(({ id, name }) => ({ id, name, protocolVersion: 2 }));
  }

  static playerUrl(serverId, uuid) {
    return `https://${ServerScope.require(serverId).apiHost}/discord/player/${uuid}?detailed=true`;
  }
}

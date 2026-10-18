import { ApiError, json, requestJson } from './protocol.mjs';
import { OfficialCertificateVerifier } from './official-certificate-verifier.mjs';
import { OwnerIdentity } from './owner-identity.mjs';
import { OwnerEditing } from './owner-editing.mjs';

export class OwnerApplication {
  constructor(application, qualification) {
    this.application = application;
    this.identity = new OwnerIdentity(
      application.database,
      application.budgets,
      application.env,
      application.ownerVerifier ??
        new OfficialCertificateVerifier(undefined, application.budgets.now),
    );
    this.editing = new OwnerEditing(
      this.identity,
      application.database,
      application.budgets,
      application.env,
      qualification,
    );
  }

  async handle(serverId, route, request) {
    if (request.method === 'GET' && route === 'owner/warp') {
      return json(await this.editing.current(await this.identity.require(serverId, request)));
    }
    if (
      request.method !== 'POST' ||
      !['owner/challenge', 'owner/verify', 'owner/metadata'].includes(route)
    ) {
      throw new ApiError(404, 'Owner route not found');
    }
    if (this.application.env.POSTING_ENABLED !== 'true') {
      throw new ApiError(503, 'Editing is temporarily disabled', 60);
    }
    if (!request.headers.get('Content-Type')?.startsWith('application/json')) {
      throw new ApiError(415, 'JSON is required');
    }
    const value = await requestJson(request, 4096);
    if (!value || Array.isArray(value) || typeof value !== 'object') {
      throw new ApiError(400, 'An object is required');
    }
    if (route === 'owner/challenge') {
      if (Object.keys(value).some((key) => !['uuid', 'name', 'clientNonce'].includes(key))) {
        throw new ApiError(400, 'Invalid challenge fields');
      }
      return json(await this.identity.challenge(serverId, value, request));
    }
    if (route === 'owner/verify') {
      if (
        Object.keys(value).some(
          (key) => !['challengeId', 'proofKey', 'certificate', 'signature'].includes(key),
        )
      ) {
        throw new ApiError(400, 'Invalid verification fields');
      }
      return json(await this.identity.verify(serverId, value, new URL(request.url).origin));
    }
    return json(await this.editing.update(await this.identity.require(serverId, request), value));
  }
}

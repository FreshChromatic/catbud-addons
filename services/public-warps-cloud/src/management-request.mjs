import { ApiError, UUID } from './protocol.mjs';

export async function requireManagementRequest(database, actor, requestId) {
  if (!UUID.test(requestId ?? '')) {
    throw new ApiError(400, 'A valid management request identifier is required');
  }
  const processed = await database
    .prepare('SELECT id FROM server_audit WHERE actor=? AND request_id=?')
    .bind(actor, requestId)
    .first();
  if (processed) {
    throw new ApiError(409, 'Operation already processed; inspect the audit log before retrying');
  }
}

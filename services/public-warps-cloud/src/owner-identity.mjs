import { randomBytes } from 'node:crypto';
import { ApiError, identity, sha256, UUID } from './protocol.mjs';
import { StorageCapacity } from './storage-capacity.mjs';

export class OwnerIdentity {
  constructor(database, budgets, env, verifier) {
    this.database = database;
    this.budgets = budgets;
    this.env = env;
    this.verifier = verifier;
  }

  async warp(serverId, uuid) {
    const warp = await this.database
      .prepare('SELECT * FROM server_warps WHERE server_id=? AND uuid=? AND published=1')
      .bind(serverId, uuid)
      .first();
    if (!warp) {
      throw new ApiError(404, 'Your published warp was not found on this server');
    }
    return warp;
  }

  async challenge(serverId, value, request) {
    const player = identity(value);
    if (!/^[0-9a-f]{64}$/.test(value.clientNonce ?? '')) {
      throw new ApiError(400, 'A fresh client nonce is required');
    }
    await this.budgets.consume('owner-challenges', 400);
    const address = request.headers.get('CF-Connecting-IP') ?? 'local';
    const source = await sha256(address.slice(0, 45));
    await this.budgets.consume('owner-challenge-source:' + source, 12, 1, 60000);
    await new StorageCapacity(this.database, this.env).requireGrowth();
    const warp = await this.warp(serverId, player.uuid);
    if (warp.name.toLowerCase() !== player.name.toLowerCase()) {
      throw new ApiError(403, 'Player identity does not match this warp');
    }
    const now = this.budgets.now;
    const id = crypto.randomUUID();
    const proofKey = randomBytes(32).toString('hex');
    const serverNonce = randomBytes(32).toString('hex');
    const serverHash = (
      await sha256(
        [
          'catbud-owner-edit-v1',
          new URL(request.url).origin,
          serverId,
          player.uuid,
          value.clientNonce,
          serverNonce,
        ].join('\n'),
      )
    ).slice(0, 40);
    const inserted = await this.database
      .prepare(
        `INSERT INTO owner_challenges(id,server_id,uuid,name,server_hash,proof_hash,expires_at)
      SELECT ?,?,?,?,?,?,? WHERE (SELECT COUNT(*) FROM owner_challenges WHERE expires_at>?)<200 RETURNING id`,
      )
      .bind(
        id,
        serverId,
        player.uuid,
        warp.name,
        serverHash,
        await sha256(proofKey),
        now + 120000,
        now,
      )
      .first();
    if (!inserted) {
      throw new ApiError(429, 'Account verification capacity reached', 60);
    }
    return {
      serverId,
      uuid: player.uuid,
      challengeId: id,
      proofKey,
      serverNonce,
      serverHash,
      expiresAt: now + 120000,
    };
  }

  async verify(serverId, value, origin) {
    if (!UUID.test(value.challengeId ?? '') || !/^[0-9a-f]{64}$/.test(value.proofKey ?? '')) {
      throw new ApiError(400, 'Invalid ownership challenge');
    }
    await this.budgets.consume('owner-verifications', 400);
    const challenge = await this.database
      .prepare(
        `UPDATE owner_challenges SET consumed=1
      WHERE id=? AND server_id=? AND proof_hash=? AND consumed=0 AND expires_at>? RETURNING *`,
      )
      .bind(value.challengeId, serverId, await sha256(value.proofKey), this.budgets.now)
      .first();
    if (!challenge) {
      throw new ApiError(401, 'Ownership challenge expired or already used');
    }
    const certificateExpiry = await this.verifier.verify(challenge, value, origin);
    await this.warp(serverId, challenge.uuid);
    await new StorageCapacity(this.database, this.env).requireGrowth();
    const token = randomBytes(32).toString('hex');
    const expiresAt = Math.min(this.budgets.now + 300000, certificateExpiry);
    const inserted = await this.database
      .prepare(
        `INSERT INTO owner_sessions(token_hash,server_id,uuid,expires_at)
      SELECT ?,?,?,? WHERE (SELECT COUNT(*) FROM owner_sessions WHERE expires_at>?)<1024 RETURNING uuid`,
      )
      .bind(await sha256(token), serverId, challenge.uuid, expiresAt, this.budgets.now)
      .first();
    if (!inserted) {
      throw new ApiError(429, 'Owner session capacity reached', 60);
    }
    return { serverId, uuid: challenge.uuid, ownerToken: token, expiresAt };
  }

  async require(serverId, request) {
    const token = request.headers.get('Authorization')?.replace(/^Bearer /, '') ?? '';
    if (!/^[0-9a-f]{64}$/.test(token)) {
      throw new ApiError(401, 'Official account verification is required');
    }
    const session = await this.database
      .prepare('SELECT * FROM owner_sessions WHERE token_hash=? AND server_id=? AND expires_at>?')
      .bind(await sha256(token), serverId, this.budgets.now)
      .first();
    if (!session) {
      throw new ApiError(401, 'Owner editing session expired');
    }
    return session;
  }
}

import { generateKeyPairSync, sign } from 'node:crypto';

export class OwnerCertificateFixture {
  constructor(now) {
    this.now = now;
    this.authority = generateKeyPairSync('rsa', { modulusLength: 2048 });
    this.account = generateKeyPairSync('rsa', { modulusLength: 2048 });
    this.trust = this.authority.publicKey
      .export({ type: 'spki', format: 'der' })
      .toString('base64');
  }

  proof(challenge, uuid, origin = 'https://warps.test', expiresAt = this.now + 3600000) {
    const publicKey = this.account.publicKey.export({ type: 'spki', format: 'der' });
    const payload = Buffer.alloc(24 + publicKey.length);
    Buffer.from(uuid.replaceAll('-', ''), 'hex').copy(payload);
    payload.writeBigInt64BE(BigInt(expiresAt), 16);
    publicKey.copy(payload, 24);
    const signed = [
      'catbud-owner-edit-proof-v1',
      origin,
      challenge.serverId,
      challenge.uuid,
      challenge.challengeId,
      challenge.serverHash,
    ].join('\n');
    return {
      certificate: {
        publicKey: publicKey.toString('base64'),
        expiresAt,
        signature: sign('RSA-SHA1', payload, this.authority.privateKey).toString('base64'),
      },
      signature: sign('RSA-SHA256', Buffer.from(signed), this.account.privateKey).toString(
        'base64',
      ),
    };
  }
}

import trust from './official-certificate-keys.json' with { type: 'json' };
import { ApiError } from './protocol.mjs';

const RSA = 'RSASSA-PKCS1-v1_5';

function decoded(value, maximum) {
  if (
    typeof value !== 'string' ||
    value.length > maximum ||
    !/^[A-Za-z0-9+/]+={0,2}$/.test(value)
  ) {
    throw new ApiError(401, 'Invalid account certificate');
  }
  return Uint8Array.from(atob(value), (character) => character.charCodeAt(0));
}

export class OfficialCertificateVerifier {
  constructor(keys = trust.keys, now = Date.now()) {
    this.keys = keys;
    this.now = now;
  }

  async verify(challenge, value, origin) {
    const certificate = value.certificate;
    if (
      !certificate ||
      typeof certificate !== 'object' ||
      Array.isArray(certificate) ||
      Object.keys(certificate).some((key) => !['publicKey', 'expiresAt', 'signature'].includes(key))
    ) {
      throw new ApiError(401, 'An official account certificate is required');
    }
    const expiry = certificate.expiresAt;
    if (!Number.isSafeInteger(expiry) || expiry <= this.now) {
      throw new ApiError(401, 'Account certificate expired or invalid');
    }
    try {
      const publicKey = decoded(certificate.publicKey, 1024);
      const certificateSignature = decoded(certificate.signature, 1024);
      const signature = decoded(value.signature, 1024);
      const signedCertificate = new Uint8Array(24 + publicKey.length);
      signedCertificate.set(
        Uint8Array.from(challenge.uuid.replaceAll('-', '').match(/../g), (part) =>
          parseInt(part, 16),
        ),
      );
      new DataView(signedCertificate.buffer).setBigInt64(16, BigInt(expiry));
      signedCertificate.set(publicKey, 24);
      let authentic = false;
      for (const encoded of this.keys) {
        const authority = await crypto.subtle.importKey(
          'spki',
          decoded(encoded, 2048),
          { name: RSA, hash: 'SHA-1' },
          false,
          ['verify'],
        );
        if (await crypto.subtle.verify(RSA, authority, certificateSignature, signedCertificate)) {
          authentic = true;
          break;
        }
      }
      if (!authentic) {
        throw new ApiError(403, 'Account certificate is not signed by Minecraft');
      }
      const account = await crypto.subtle.importKey(
        'spki',
        publicKey,
        { name: RSA, hash: 'SHA-256' },
        false,
        ['verify'],
      );
      if (
        account.algorithm.modulusLength !== 2048 ||
        Array.from(account.algorithm.publicExponent).join(',') !== '1,0,1'
      ) {
        throw new ApiError(403, 'Unsupported account signing key');
      }
      const payload = new TextEncoder().encode(
        [
          'catbud-owner-edit-proof-v1',
          origin,
          challenge.server_id,
          challenge.uuid,
          challenge.id,
          challenge.server_hash,
        ].join('\n'),
      );
      if (!(await crypto.subtle.verify(RSA, account, signature, payload))) {
        throw new ApiError(403, 'Account does not own this challenge');
      }
      return expiry;
    } catch (error) {
      if (error instanceof ApiError) {
        throw error;
      }
      throw new ApiError(401, 'Invalid account certificate or signature');
    }
  }
}

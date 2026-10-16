import { createRemoteJWKSet, jwtVerify } from 'jose';
import { timingSafeEqual } from 'node:crypto';
import { ApiError } from './protocol.mjs';

const keySets = new Map();

export class AccessVerifier {
  constructor(env, now = Date.now(), keys) {
    this.env = env;
    this.now = now;
    this.keys = keys;
  }

  configuration() {
    const issuer = this.env.ACCESS_TEAM_DOMAIN;
    if (
      !/^https:\/\/[a-z0-9-]+\.cloudflareaccess\.com$/.test(issuer ?? '') ||
      !/^[0-9a-f]{64}$/.test(this.env.ACCESS_AUD ?? '') ||
      !/^[0-9a-f]{64}$/.test(this.env.CSRF_SECRET ?? '')
    ) {
      throw new ApiError(503, 'Administrator login is not configured');
    }
    const emails = (this.env.ACCESS_ALLOWED_EMAILS ?? '')
      .split(',')
      .map((email) => email.trim().toLowerCase())
      .filter(Boolean);
    if (!emails.length) {
      throw new ApiError(503, 'Administrator allow list is not configured');
    }
    return { issuer, emails };
  }

  async require(request) {
    const { issuer, emails } = this.configuration();
    const token = request.headers.get('Cf-Access-Jwt-Assertion');
    if (!token || token.length > 16384) {
      throw new ApiError(401, 'Administrator login required');
    }
    if (!this.keys && !keySets.has(issuer)) {
      keySets.set(issuer, createRemoteJWKSet(new URL(issuer + '/cdn-cgi/access/certs')));
    }
    let payload;
    try {
      ({ payload } = await jwtVerify(token, this.keys ?? keySets.get(issuer), {
        issuer,
        audience: this.env.ACCESS_AUD,
        algorithms: ['RS256'],
        requiredClaims: ['exp', 'iat', 'sub', 'email'],
        currentDate: new Date(this.now),
      }));
    } catch {
      throw new ApiError(401, 'Administrator session expired or invalid');
    }
    if (
      typeof payload.email !== 'string' ||
      typeof payload.sub !== 'string' ||
      !emails.includes(payload.email.toLowerCase())
    ) {
      throw new ApiError(403, 'Administrator identity is not allowed');
    }
    const key = await crypto.subtle.importKey(
      'raw',
      new TextEncoder().encode(this.env.CSRF_SECRET),
      { name: 'HMAC', hash: 'SHA-256' },
      false,
      ['sign'],
    );
    const signature = new Uint8Array(
      await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(token)),
    );
    const csrf = Array.from(signature, (byte) => byte.toString(16).padStart(2, '0')).join('');
    return { actor: payload.email.toLowerCase(), expiresAt: payload.exp * 1000, csrf };
  }

  requireWrite(request, identity) {
    const origin = new URL(request.url).origin;
    const supplied = request.headers.get('X-CSRF-Token') ?? '';
    if (
      origin !== this.env.ADMIN_ORIGIN ||
      request.headers.get('Origin') !== origin ||
      !/^[0-9a-f]{64}$/.test(supplied) ||
      !timingSafeEqual(Buffer.from(supplied), Buffer.from(identity.csrf))
    ) {
      throw new ApiError(403, 'Same-origin administrator action required');
    }
    if (!request.headers.get('Content-Type')?.startsWith('application/json')) {
      throw new ApiError(415, 'Administrator actions require JSON');
    }
  }
}

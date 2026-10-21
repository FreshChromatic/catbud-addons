export class ApiError extends Error {
  constructor(status, message, retryAfter) {
    super(message);
    this.status = status;
    this.retryAfter = retryAfter;
  }
}

export const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export const NAME = /^\.?[A-Za-z0-9_]{1,16}$/;
export const HASH = /^[0-9a-f]{64}$/;

export function identity(value) {
  if (
    !value ||
    typeof value.uuid !== 'string' ||
    typeof value.name !== 'string' ||
    !UUID.test(value.uuid) ||
    !NAME.test(value.name)
  ) {
    throw new ApiError(400, 'Invalid player identity');
  }
  return { uuid: value.uuid.toLowerCase(), name: value.name };
}

export function json(value, status = 200, headers = {}) {
  return Response.json(value, {
    status,
    headers: { 'Cache-Control': 'no-store', ...headers },
  });
}

export async function boundedBytes(body, limit) {
  if (!body) {
    throw new ApiError(400, 'Missing body');
  }
  const reader = body.getReader();
  const chunks = [];
  let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) {
        break;
      }
      size += value.byteLength;
      if (size > limit) {
        await reader.cancel();
        throw new ApiError(413, 'Body exceeds limit');
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.length;
  }
  return bytes;
}

export async function requestJson(request, limit = 4096) {
  const bytes = await boundedBytes(request.body, limit);
  try {
    return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
  } catch {
    throw new ApiError(400, 'Invalid JSON');
  }
}

export async function sha256(value) {
  const bytes = typeof value === 'string' ? new TextEncoder().encode(value) : value;
  const hash = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
  return Array.from(hash, (byte) => byte.toString(16).padStart(2, '0')).join('');
}

export function setting(env, name, fallback) {
  const value = Number(env[name] ?? fallback);
  if (!Number.isSafeInteger(value) || value < 1) {
    throw new ApiError(503, 'Invalid service capacity configuration');
  }
  return value;
}

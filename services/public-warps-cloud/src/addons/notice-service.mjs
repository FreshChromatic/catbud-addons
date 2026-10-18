import { ApiError, UUID } from '../protocol.mjs';
import { MINECRAFT, MODULES } from './release-manifest.mjs';

export function noticeContent(value, now) {
  const list = (items, allowed) =>
    Array.isArray(items) &&
    items.length <= allowed.length &&
    new Set(items).size === items.length &&
    items.every((item) => allowed.includes(item));
  if (
    !value ||
    typeof value.title !== 'string' ||
    !value.title.trim() ||
    value.title.length > 80 ||
    typeof value.body !== 'string' ||
    !value.body.trim() ||
    value.body.length > 4000 ||
    !['info', 'important'].includes(value.severity) ||
    !list(value.modules, Object.keys(MODULES)) ||
    !list(value.minecraftVersions, MINECRAFT) ||
    !list(value.servers, ['catbud', 'catrealm']) ||
    !Number.isSafeInteger(value.startsAt) ||
    !Number.isSafeInteger(value.expiresAt) ||
    value.startsAt < 0 ||
    value.expiresAt <= value.startsAt ||
    value.expiresAt <= now ||
    typeof value.notifyOnRevision !== 'boolean'
  ) {
    throw new ApiError(400, 'Invalid announcement content, audience or schedule');
  }
  if (value.link) {
    let url;
    try {
      url = new URL(value.link);
    } catch {
      throw new ApiError(400, 'Invalid announcement link');
    }
    if (
      url.protocol !== 'https:' ||
      url.username ||
      url.password ||
      url.port ||
      value.link.length > 1024 ||
      !['github.com', 'catbud.net', 'catrealm.net', 'api.cbwp.workers.dev'].includes(url.hostname)
    ) {
      throw new ApiError(400, 'Announcement link is not an approved HTTPS destination');
    }
  }
  return {
    title: value.title,
    body: value.body,
    severity: value.severity,
    modules: value.modules,
    minecraftVersions: value.minecraftVersions,
    servers: value.servers,
    startsAt: value.startsAt,
    expiresAt: value.expiresAt,
    link: value.link || '',
    notifyOnRevision: value.notifyOnRevision,
  };
}

export class NoticeService {
  constructor(repository) {
    this.repository = repository;
    this.database = repository.database;
  }

  async write(id, command, value, actor) {
    if (!UUID.test(id)) {
      throw new ApiError(400, 'Invalid announcement identifier');
    }
    const action = `notices/${id}/${command}`;
    const previous = await this.repository.replay(actor, value, action);
    if (previous) {
      return previous;
    }
    const existing = await this.database
      .prepare('SELECT * FROM addon_notices WHERE id=?')
      .bind(id)
      .first();
    const now = this.repository.now;
    let content;
    let state;
    if (command === 'save') {
      content = noticeContent(value.content, now);
      state = 'draft';
      if (existing?.state === 'published') {
        throw new ApiError(409, 'Withdraw a published announcement before editing');
      }
    } else {
      if (!existing || !['publish', 'withdraw'].includes(command)) {
        throw new ApiError(404, 'Announcement not found');
      }
      content = noticeContent(JSON.parse(existing.content), command === 'withdraw' ? 0 : now);
      state = command === 'publish' ? 'published' : 'withdrawn';
      if (existing.state === state) {
        throw new ApiError(409, 'Announcement is already in the requested state');
      }
    }
    const revision = (existing?.revision ?? 0) + 1;
    return this.repository.mutate(
      actor,
      value,
      action,
      [
        this.database
          .prepare(
            `INSERT INTO addon_notices
      (id,revision,state,content,created_at,updated_at,starts_at,expires_at) VALUES(?,?,?,?,?,?,?,?)
      ON CONFLICT(id) DO UPDATE SET revision=excluded.revision,state=excluded.state,content=excluded.content,
      updated_at=excluded.updated_at,starts_at=excluded.starts_at,expires_at=excluded.expires_at`,
          )
          .bind(
            id,
            revision,
            state,
            JSON.stringify(content),
            existing?.created_at ?? now,
            now,
            content.startsAt,
            content.expiresAt,
          ),
      ],
      { id, state, noticeRevision: revision },
    );
  }
}

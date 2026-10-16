import { StorageCapacity } from './storage-capacity.mjs';
import { setting } from './protocol.mjs';

export class ManagementStatus {
  constructor(database, env, now = Date.now()) {
    this.database = database;
    this.env = env;
    this.now = now;
  }

  async inspect() {
    const capacity = await new StorageCapacity(this.database, this.env).inspect();
    const { results: servers } = await this.database
      .prepare(
        `SELECT n.id,n.name,n.revision,
      (SELECT COUNT(*) FROM server_warps w WHERE w.server_id=n.id AND w.published=1) AS published,
      (SELECT COUNT(*) FROM server_submissions s WHERE s.server_id=n.id AND s.state='pending' AND s.expires_at>?) AS pending,
      (SELECT COUNT(*) FROM server_warps w WHERE w.server_id=n.id AND w.published=1 AND w.checked_at<=?) AS stale,
      (SELECT MIN(next_check) FROM server_warps w WHERE w.server_id=n.id AND w.published=1) AS nextCheck
      FROM server_namespaces n ORDER BY id`,
      )
      .bind(this.now, this.now - 3600000)
      .all();
    const bucket = Math.floor(this.now / 86400000);
    const { results: budgets } = await this.database
      .prepare('SELECT key,used FROM budgets WHERE key IN (?,?,?)')
      .bind('verification:' + bucket, 'preview:' + bucket, 'preflight:' + bucket)
      .all();
    const used = Object.fromEntries(budgets.map((row) => [row.key.split(':')[0], row.used]));
    return {
      ...capacity,
      postingEnabled: this.env.POSTING_ENABLED === 'true',
      servers,
      daily: {
        qualification: {
          used: used.verification ?? 0,
          limit: setting(this.env, 'VERIFY_DAILY_LIMIT', 2600),
        },
        previews: {
          used: used.preview ?? 0,
          limit: setting(this.env, 'PREVIEW_DAILY_LIMIT', 1000),
        },
        submissions: { used: used.preflight ?? 0, limit: 5000 },
      },
      usageNote: '帳號層級 Workers 請求及 D1 每日讀寫用量請查看 Cloudflare；此處未取得完整統計。',
    };
  }
}

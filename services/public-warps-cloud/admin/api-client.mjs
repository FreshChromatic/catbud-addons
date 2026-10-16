export class ManagementClient {
  constructor() {
    this.session = null;
  }

  async request(path, value, signal) {
    const options = { credentials: 'same-origin', cache: 'no-store', signal };
    if (value !== undefined) {
      options.method = 'POST';
      options.headers = {
        'Content-Type': 'application/json',
        'X-CSRF-Token': this.session?.csrf ?? '',
      };
      options.body = JSON.stringify({
        ...value,
        requestId: value.requestId ?? crypto.randomUUID(),
      });
    }
    const response = await fetch('/api/' + path, options);
    if (response.status === 401 || response.status === 403 || response.redirected) {
      throw new Error('登入已逾時或沒有操作權限，請重新整理頁面登入。');
    }
    const result = await response.json();
    if (!response.ok) {
      if (response.status === 409) {
        throw new Error('資料已變更或不符合發布條件。請重新整理並比較最新資料，再決定操作。');
      }
      throw new Error(result.error ?? '操作失敗，請稍後重試。');
    }
    return result;
  }

  async login() {
    this.session = await this.request('session');
    return this.session;
  }

  async preview(serverId, hash, signal) {
    const response = await fetch(`/api/servers/${serverId}/previews/${hash}`, {
      credentials: 'same-origin',
      cache: 'no-store',
      signal,
    });
    if (
      !response.ok ||
      response.redirected ||
      !response.headers.get('Content-Type')?.startsWith('application/octet-stream')
    ) {
      throw new Error('預覽載入失敗；請重新確認登入與資料狀態。');
    }
    const bytes = await response.arrayBuffer();
    if (bytes.byteLength > 65536) {
      throw new Error('預覽超過大小限制。');
    }
    return bytes;
  }
}

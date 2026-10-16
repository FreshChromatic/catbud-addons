import { ManagementClient } from './api-client.mjs';
import { PreviewView } from './preview-view.mjs';

const client = new ManagementClient();
const state = {
  server: 'catbud',
  page: 'submissions',
  offset: 0,
  nextOffset: null,
  serial: 0,
  views: [],
  busy: false,
};
const labels = {
  submissions: '待審核',
  warps: '已發布',
  contributors: '投稿者',
  audit: '操作紀錄',
  status: '服務狀態',
};
const kinds = {
  discovery: '名稱發現',
  launch: '傳送啟動',
  arrival: '成功到達',
  preview: '預覽更新',
  failure: '傳點無法使用',
  metadata: '用途修改',
  owner_metadata: '玩家編輯名稱與說明',
};
const $ = (id) => document.getElementById(id);
const element = (tag, text, className) => {
  const node = document.createElement(tag);
  if (text != null) node.textContent = text;
  if (className) node.className = className;
  return node;
};
const date = (value) => (value ? new Date(value).toLocaleString('zh-TW') : '尚無紀錄');
const route = (path) => `servers/${state.server}/${path}`;

function message(text = '', error = false) {
  $('message').textContent = text;
  $('message').className = error ? 'error' : '';
}

function clearDetail() {
  state.serial++;
  state.views.forEach((view) => view.dispose());
  state.views = [];
  $('detail').replaceChildren(element('p', '選取一筆資料查看詳情。', 'empty'));
}

function fields(values) {
  const list = element('dl');
  for (const [label, value] of Object.entries(values))
    list.append(element('dt', label), element('dd', value ?? '—'));
  return list;
}

function button(text, action, className) {
  const node = element('button', text, className);
  node.onclick = () =>
    Promise.resolve()
      .then(action)
      .catch((error) => message(error.message, true));
  return node;
}

async function confirmAction(title, description) {
  $('confirm-title').textContent = title;
  $('confirm-description').textContent = description;
  const dialog = $('confirm');
  dialog.showModal();
  return new Promise((resolve) =>
    dialog.addEventListener('close', () => resolve(dialog.returnValue === 'continue'), {
      once: true,
    }),
  );
}

async function write(path, value) {
  if (state.busy) throw new Error('操作處理中，請稍候。');
  state.busy = true;
  message('正在處理…');
  try {
    return await client.request(path, { ...value, requestId: crypto.randomUUID() });
  } finally {
    state.busy = false;
  }
}

async function load(offset = 0) {
  clearDetail();
  const serial = state.serial;
  message('正在載入…');
  $('list-title').textContent = labels[state.page];
  $('more').hidden = true;
  $('previous').hidden = true;
  $('search').hidden = state.page === 'status';
  $('list-count').hidden = state.page === 'status';
  $('grant').hidden = state.page !== 'contributors';
  $('items').replaceChildren();
  try {
    if (state.page === 'status') {
      const status = await client.request('status');
      if (serial !== state.serial) return;
      $('detail').replaceChildren(
        element('h2', '服務狀態'),
        fields({
          資料庫狀態: { normal: '正常', restricted: '限制投稿', stopped: '暫停更新' }[status.state],
          資料庫用量: `${(status.bytes / 1048576).toFixed(1)} MiB`,
          預覽資料: `${(status.storedBytes / 1048576).toFixed(1)} MiB`,
          預留上傳空間: `${(status.reservedBytes / 1048576).toFixed(1)} MiB`,
          投稿設定: status.postingEnabled ? '開放' : '暫停',
          今日資格檢查: `${status.daily?.qualification?.used ?? '未知'} / ${status.daily?.qualification?.limit ?? '未知'}`,
          今日預覽預檢: `${status.daily?.previews?.used ?? '未知'} / ${status.daily?.previews?.limit ?? '未知'}`,
          今日投稿請求: `${status.daily?.submissions?.used ?? '未知'} / ${status.daily?.submissions?.limit ?? '未知'}`,
        }),
        ...(status.servers ?? []).map((server) =>
          fields({
            伺服器: server.name,
            已發布: server.published,
            待審核: server.pending,
            資格待更新: server.stale,
            清單版本: server.revision,
          }),
        ),
        element('p', status.usageNote ?? '完整用量統計尚未取得。'),
      );
      message();
      return;
    }
    const result = await client.request(
      route(state.page) + '?offset=' + offset + '&q=' + encodeURIComponent($('search').value),
    );
    if (serial !== state.serial) return;
    for (const item of result.items) {
      const node = element('button', null, 'item');
      node.append(
        element('strong', item.name ?? item.id ?? item.actor),
        element('small', kinds[item.kind] ?? kinds[item.action] ?? item.action ?? item.role),
        element('small', date(item.created_at ?? item.checked_at)),
      );
      if (item.display_name && item.display_name !== item.name)
        node.append(element('small', item.display_name));
      node.onclick = () => {
        $('items')
          .querySelectorAll('.selected')
          .forEach((entry) => entry.classList.remove('selected'));
        node.classList.add('selected');
        show(item).catch((error) => message(error.message, true));
      };
      $('items').append(node);
    }
    if (!result.items.length)
      $('items').append(element('p', `目前沒有${labels[state.page]}資料。`, 'empty'));
    state.offset = offset;
    state.nextOffset = result.nextOffset;
    $('more').hidden = state.nextOffset == null;
    $('previous').hidden = offset === 0;
    $('list-count').textContent = `共 ${result.total} 筆 · 第 ${Math.floor(offset / 25) + 1} 頁`;
    message();
  } catch (error) {
    if (serial === state.serial) message(error.message, true);
  }
}

function metadataEditor(warp, target) {
  const form = element('form');
  const inputs = {};
  for (const [key, label, value, limit] of [
    ['displayName', '自訂名稱', warp.display_name ?? '', 64],
    ['description', '用途說明', warp.description ?? '', 512],
    ['tags', '標籤（以逗號分隔，最多八個）', JSON.parse(warp.tags ?? '[]').join(', '), 200],
  ]) {
    const field = element('label', label, 'field');
    const input = element(key === 'description' ? 'textarea' : 'input');
    input.value = value;
    input.maxLength = limit;
    inputs[key] = input;
    field.append(input);
    form.append(field);
  }
  const save = button('儲存用途資訊', async () => {
    await write(target + '/metadata', {
      expectedRevision: warp.revision,
      metadata: {
        displayName: inputs.displayName.value,
        description: inputs.description.value,
        tags: inputs.tags.value
          .split(',')
          .map((tag) => tag.trim())
          .filter(Boolean),
      },
    });
    await load();
    message('用途資訊已更新。');
  });
  save.type = 'button';
  form.append(save);
  return form;
}

async function show(item) {
  clearDetail();
  const serial = state.serial;
  const server = state.server;
  const detail = $('detail');
  if (state.page === 'contributors') return contributorEditor(item);
  if (state.page === 'audit') {
    detail.replaceChildren(
      element('h2', '操作紀錄'),
      fields({
        操作者: item.actor,
        動作: kinds[item.action] ?? item.action,
        傳點: item.uuid,
        時間: date(item.created_at),
        版本變更: `${item.before_revision ?? '—'} → ${item.after_revision ?? '—'}`,
        說明: item.reason,
      }),
    );
    return;
  }
  const reviewing = state.page === 'submissions';
  const result = await client.request(
    route(reviewing ? 'submissions/' + item.id : 'warps/' + item.uuid),
  );
  if (serial !== state.serial) return;
  const warp = reviewing ? result.current : result;
  const submission = reviewing ? result.submission : null;
  detail.replaceChildren(
    element('h2', warp.name),
    fields({
      伺服器: server === 'catbud' ? '貓芽' : '貓域',
      '玩家 ID': warp.name,
      自訂名稱: warp.display_name,
      UUID: warp.uuid,
      月卡: { active: '有月卡', inactive: '沒有月卡', unknown: '尚待確認' }[warp.pass_state],
      資格檢查: date(warp.checked_at),
      傳點狀態: { available: '可使用', unavailable: '目前無法使用', unknown: '尚待確認' }[
        warp.availability
      ],
      有效紀錄: warp.first_valid_at ? date(warp.first_valid_at) : '尚未確認有效傳送',
      ...(submission
        ? {
            投稿者: submission.contributor,
            類型: kinds[submission.kind],
            投稿時間: date(submission.created_at),
            到期時間: date(submission.expires_at),
            '提案 / 目前版本': `${submission.base_revision} / ${warp.revision}`,
          }
        : {}),
    }),
  );
  detail.scrollTop = 0;
  if (matchMedia('(max-width: 960px)').matches) detail.scrollIntoView({ block: 'start' });
  if (result.changed)
    detail.append(
      element('p', '正式內容已有更新。請比較目前內容，再決定是否批准這筆提案。', 'warning'),
    );
  if (result.expired) detail.append(element('p', '這筆提案已到期，無法批准。', 'warning'));
  if (submission?.kind === 'metadata') {
    const proposed = JSON.parse(submission.payload);
    detail.append(
      element('h3', '用途資訊提案'),
      fields({
        目前名稱: warp.display_name,
        提案名稱: proposed.displayName,
        目前用途: warp.description,
        提案用途: proposed.description,
        目前標籤: JSON.parse(warp.tags ?? '[]').join(', '),
        提案標籤: (proposed.tags ?? []).join(', '),
      }),
    );
  }
  const previews = element('div', null, 'previews');
  detail.append(previews);
  const hashes = [
    ['目前預覽', warp.snapshot_hash],
    ...(submission?.hash ? [['提案預覽', submission.hash]] : []),
  ];
  let remaining = hashes.filter(([, hash]) => hash).length;
  let failed = false;
  let approve;
  const eligibility =
    !result.expired &&
    submission?.kind !== 'discovery' &&
    (warp.first_valid_at > 0 ||
      ['launch', 'arrival'].includes(submission?.kind) ||
      JSON.parse(submission?.payload ?? '{}').validArrival === true);
  const ready = () => {
    remaining--;
    if (approve) approve.disabled = !eligibility || failed || remaining > 0;
  };
  for (const [title, hash] of hashes) {
    const container = element('div', null, 'preview');
    container.append(element('h3', title));
    previews.append(container);
    if (!hash) container.append(element('p', '尚無預覽', 'empty'));
    else
      state.views.push(
        new PreviewView(
          container,
          client,
          server,
          hash,
          () => {
            failed = true;
            if (approve) approve.disabled = true;
          },
          ready,
        ),
      );
    if (hash)
      container.append(
        button('下載這份快照', async () => {
          const bytes = await client.preview(server, hash);
          const link = element('a');
          const url = URL.createObjectURL(new Blob([bytes], { type: 'application/octet-stream' }));
          link.href = url;
          link.download = `${server}-${warp.name}-${hash.slice(0, 8)}.json.gz`;
          link.click();
          setTimeout(() => URL.revokeObjectURL(url), 10000);
        }),
      );
  }
  const actions = element('div', null, 'actions');
  detail.append(actions);
  const target = `servers/${server}/${reviewing ? 'submissions/' + submission.id : 'warps/' + warp.uuid}`;
  if (reviewing) {
    detail.append(
      element('p', '資格由官方接口核對；傳送與預覽內容由投稿來源提供，仍需判斷是否可信。', 'muted'),
    );
    const reason = element('input');
    reason.placeholder = '拒絕原因（必填）';
    reason.maxLength = 500;
    reason.setAttribute('aria-label', '拒絕原因');
    approve = button(
      '批准這筆提案',
      async () => {
        await write(target + '/approve', { expectedRevision: warp.revision });
        await load();
        message('提案已發布。');
      },
      'primary',
    );
    approve.disabled = !eligibility || remaining > 0;
    actions.append(
      approve,
      reason,
      button('拒絕', async () => {
        if (!reason.value.trim()) throw new Error('請填寫拒絕原因。');
        await write(target + '/reject', {
          reason: reason.value.trim(),
          expectedRevision: warp.revision,
        });
        await load();
        message('提案已拒絕。');
      }),
    );
  } else {
    detail.append(metadataEditor(warp, target));
    for (const [command, title, description] of [
      ['rollback', '回退預覽', '將目前預覽換回保留的上一版。'],
      ['unpublish', '下架', '這筆傳點將停止出現在共用下載名單。'],
      ['disable', '標記無法使用', '保留傳點，顯示暫時無法使用；玩家仍可嘗試傳送。'],
      ['restore', '恢復可用狀態', '這只修改可用狀態，不代表新增成功到達次數。'],
    ])
      actions.append(
        button(
          title,
          async () => {
            if (!(await confirmAction(title, `${warp.name}（${server}）：${description}`))) return;
            await write(target + '/' + command, { expectedRevision: warp.revision });
            await load();
            message('操作已完成。');
          },
          command === 'unpublish' ? 'danger' : '',
        ),
      );
  }
}

function contributorEditor(item = null) {
  const detail = $('detail');
  detail.replaceChildren(element('h2', item ? '投稿者權限' : '新增投稿者'));
  const id = element('input');
  id.value = item?.id ?? '';
  id.disabled = Boolean(item);
  id.placeholder = '投稿者識別名稱';
  id.setAttribute('aria-label', '投稿者識別名稱');
  const role = element('select');
  role.append(
    new Option('一般投稿者（需審核）', 'contributor'),
    new Option('可信投稿者（有效內容自動發布）', 'trusted'),
  );
  role.value = item?.role ?? 'contributor';
  role.setAttribute('aria-label', '投稿者角色');
  const enabled = element('input');
  enabled.type = 'checkbox';
  enabled.checked = item?.enabled !== 0;
  const enabledLabel = element('label', ' 啟用投稿');
  enabledLabel.prepend(enabled);
  const scopes = {};
  const currentScopes = item ? JSON.parse(item.servers) : [state.server];
  detail.append(id, role, element('h3', '授權伺服器'));
  for (const server of ['catbud', 'catrealm']) {
    const input = element('input');
    input.type = 'checkbox';
    input.checked = currentScopes.includes(server);
    scopes[server] = input;
    const label = element('label', server === 'catbud' ? ' 貓芽 ' : ' 貓域 ');
    label.prepend(input);
    detail.append(label);
  }
  detail.append(
    enabledLabel,
    element('p', '可信來源可自動發布有效回報與預覽；密鑰僅在建立或更換時顯示一次。', 'warning'),
  );
  const submit = (rotate) => async () => {
    const title = rotate ? '建立／更換投稿密鑰' : '變更投稿權限';
    if (
      !(await confirmAction(
        title,
        `${id.value}：角色 ${role.value}，${enabled.checked ? '啟用' : '停用'}；更換密鑰會讓舊密鑰立即失效。`,
      ))
    )
      return;
    const result = await write(
      `contributors/${encodeURIComponent(id.value)}/${rotate ? 'grant' : 'update'}`,
      {
        role: role.value,
        enabled: enabled.checked,
        servers: Object.keys(scopes).filter((server) => scopes[server].checked),
      },
    );
    if (result.contributorCredential) {
      const credential = element('textarea');
      credential.readOnly = true;
      credential.value = result.contributorCredential;
      credential.setAttribute('aria-label', '一次性顯示的投稿密鑰');
      detail.append(
        element('p', '請保存到該投稿者的私人模組設定；切換頁面後不再顯示。'),
        credential,
      );
    }
    message('投稿權限已更新。');
  };
  const actions = element('div', null, 'actions');
  if (item) actions.append(button('儲存權限', submit(false)));
  actions.append(button(item ? '更換密鑰' : '建立投稿者', submit(true), 'primary'));
  detail.append(actions);
}

$('server').onchange = () => {
  state.server = $('server').value;
  load();
};
$('refresh').onclick = () => load();
$('more').onclick = () => load(state.nextOffset);
$('previous').onclick = () => load(Math.max(0, state.offset - 25));
let searchTimer;
$('search').oninput = () => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(() => load(), 250);
};
$('grant').onclick = () => {
  clearDetail();
  contributorEditor();
};
document.querySelectorAll('[data-page]').forEach((node) => {
  node.onclick = () => {
    state.page = node.dataset.page;
    document
      .querySelectorAll('[data-page]')
      .forEach((button) => button.removeAttribute('aria-current'));
    node.setAttribute('aria-current', 'page');
    load();
  };
});
client
  .login()
  .then((session) => {
    $('identity').textContent = session.email;
    load();
  })
  .catch((error) => {
    $('identity').textContent = '尚未登入';
    message(error.message, true);
  });

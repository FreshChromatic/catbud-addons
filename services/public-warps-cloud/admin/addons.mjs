import { ManagementClient } from './api-client.mjs';

const client = new ManagementClient();
const $ = (id) => document.getElementById(id);
const state = { page: 'releases', revision: 0, busy: false, serial: 0 };
const stateNames = { draft: '草稿', published: '已發布', withdrawn: '已撤回' };
const serverNames = { catbud: '貓芽', catrealm: '貓域' };
const moduleNames = {
  core: 'Catbud Core',
  magic_tower: '魔塔輔助',
  public_warps: '公共傳點',
  monster_wave: '怪物波次',
};
const node = (tag, text) => {
  const result = document.createElement(tag);
  if (text !== undefined) {
    result.textContent = text;
  }
  return result;
};
const message = (value) => {
  $('message').textContent = value;
};
const button = (label, action) => {
  const result = node('button', label);
  result.type = 'button';
  result.onclick = () =>
    Promise.resolve()
      .then(action)
      .catch((error) => message(error.message));
  return result;
};
const field = (label, type, value = '') => {
  const wrapper = node('label', label);
  wrapper.className = 'field';
  const input = node(type === 'textarea' ? 'textarea' : 'input');
  if (type !== 'textarea') {
    input.type = type;
  }
  input.value = value;
  wrapper.append(input);
  return { wrapper, input };
};
async function confirm(title, description) {
  $('confirm-title').textContent = title;
  $('confirm-description').textContent = description;
  $('confirm').showModal();
  return new Promise((resolve) =>
    $('confirm').addEventListener('close', () => resolve($('confirm').returnValue === 'continue'), {
      once: true,
    }),
  );
}
async function write(path, value, successMessage = '已儲存。') {
  if (state.busy) {
    return;
  }
  state.busy = true;
  try {
    await client.request('addons/' + path, { ...value, expectedRevision: state.revision });
    await load();
    message(successMessage);
  } finally {
    state.busy = false;
  }
}
async function load() {
  const serial = ++state.serial;
  const page = state.page;
  message('正在載入…');
  $('list-title').textContent = { releases: '版本發布', notices: '公告', audit: '操作紀錄' }[page];
  $('create-notice').hidden = page !== 'notices';
  const data = await client.request('addons/' + page);
  if (serial !== state.serial) {
    return;
  }
  state.revision = data.revision;
  $('items').replaceChildren();
  $('detail').replaceChildren();
  if (page === 'releases') {
    const tag = field('GitHub Release 標籤', 'text', 'v');
    tag.input.maxLength = 128;
    $('detail').append(
      node('h2', '匯入已發布版本'),
      node('p', '匯入後先核對產物與相容版本，再決定核准。'),
      tag.wrapper,
      button('匯入並驗證', () => write('releases/import', { tag: tag.input.value.trim() })),
    );
    for (const release of data.releases) {
      $('items').append(button(release.id, () => showRelease(release, data.channels)));
    }
  } else if (page === 'notices') {
    for (const notice of data.notices) {
      $('items').append(
        button(`${notice.title} · ${stateNames[notice.state]}`, () => editNotice(notice)),
      );
    }
    $('detail').append(node('p', '選取公告查看內容，或新增公告。'));
  } else {
    for (const operation of data.operations) {
      $('items').append(
        node(
          'p',
          `${new Date(operation.created_at).toLocaleString()} · ${operation.action} · ${operation.actor}`,
        ),
      );
    }
  }
  message('');
}
function showRelease(release, channels) {
  const detail = $('detail');
  const recommended = channels.filter((channel) => channel.release_id === release.id);
  detail.replaceChildren(node('h2', release.id));
  detail.append(
    node(
      'p',
      '目前推薦：' + (recommended.map((channel) => channel.minecraft).join('、') || '尚未推薦'),
    ),
  );
  if (release.error) {
    detail.append(node('p', '驗證異常：' + release.error));
  }
  const remove = button('刪除版本', async () => {
    if (
      await confirm(
        '刪除 ' + release.id,
        '將此版本從管理清單移除，保留操作紀錄。此操作不會刪除 GitHub Release 或玩家已安裝的檔案；GitHub 來源仍完整時可重新匯入。確定刪除？',
      )
    ) {
      await write(`releases/${release.id}/delete`, {}, '已刪除版本，操作紀錄已保留。');
    }
  });
  remove.className = 'danger';
  remove.disabled = recommended.length > 0;
  detail.append(remove);
  if (recommended.length > 0) {
    detail.append(node('p', '此版本仍在推薦中，請先撤回推薦再刪除。'));
  }
  for (const [module, note] of Object.entries(release.manifest.notes)) {
    detail.append(node('p', `${moduleNames[module]}：${note}`));
  }
  const table = node('table');
  const header = node('tr');
  for (const title of ['模組', '遊戲版本', '檔案大小', '依賴']) {
    header.append(node('th', title));
  }
  table.append(header);
  for (const artifact of release.manifest.artifacts) {
    const row = node('tr');
    const dependencies =
      `Java ${artifact.javaMinimum} 起；Fabric Loader ${artifact.loaderRange.slice(2)} 起` +
      (artifact.requires.catbud_core ? `；Core ${artifact.requires.catbud_core.slice(2)} 起` : '');
    for (const text of [
      moduleNames[artifact.module],
      artifact.minecraft,
      (artifact.size / 1048576).toFixed(2) + ' MiB',
      dependencies,
    ]) {
      row.append(node('td', text));
    }
    table.append(row);
  }
  detail.append(
    table,
    button('核准發布', async () => {
      if (
        await confirm(
          '核准 ' + release.id,
          '此版本將向符合遊戲版本的玩家提供更新。請先核對本版模組、更新摘要及版本。',
        )
      ) {
        await write(`releases/${release.id}/promote`, {});
      }
    }),
    button('撤回推薦', async () => {
      if (await confirm('撤回 ' + release.id, '停止向玩家推薦此版本，保留發布歷史。')) {
        await write(`releases/${release.id}/withdraw`, {});
      }
    }),
  );
}
function editNotice(notice = {}) {
  const detail = $('detail');
  if (notice.state === 'published') {
    showPublishedNotice(notice, detail);
    return;
  }
  const id = notice.id ?? crypto.randomUUID();
  detail.replaceChildren(node('h2', notice.id ? notice.title : '新增公告'));
  const form = node('form');
  let dirty = false;
  form.addEventListener('input', () => {
    dirty = true;
  });
  const title = field('標題', 'text', notice.title ?? '');
  title.input.maxLength = 80;
  title.input.required = true;
  const body = field('內容', 'textarea', notice.body ?? '');
  body.input.maxLength = 4000;
  body.input.required = true;
  const start = field('開始時間', 'datetime-local', localDate(notice.startsAt ?? Date.now()));
  const end = field(
    '到期時間',
    'datetime-local',
    localDate(notice.expiresAt ?? Date.now() + 7 * 86400000),
  );
  const link = field('連結（可留空）', 'url', notice.link ?? '');
  for (const item of [title, body]) {
    form.append(item.wrapper);
  }
  const groups = {};
  for (const [key, label, options] of [
    [
      'modules',
      '適用模組（全不選代表全部）',
      ['core', 'magic_tower', 'public_warps', 'monster_wave'],
    ],
    ['minecraftVersions', '遊戲版本（全不選代表全部）', ['1.21.11', '26.1.2', '26.2', '26.3']],
    ['servers', '伺服器（全不選代表全域）', ['catbud', 'catrealm']],
  ]) {
    const group = node('fieldset');
    group.append(node('legend', label));
    groups[key] = options.map((option) => {
      const item = field(moduleNames[option] ?? serverNames[option] ?? option, 'checkbox');
      item.input.checked = (notice[key] ?? []).includes(option);
      group.append(item.wrapper);
      return { value: option, input: item.input };
    });
    form.append(group);
  }
  const important = field('重要公告', 'checkbox');
  important.input.checked = notice.severity === 'important';
  const notify = field('此修訂重新提醒', 'checkbox');
  notify.input.checked = notice.notifyOnRevision ?? false;
  for (const item of [start, end, link, important, notify]) {
    form.append(item.wrapper);
  }
  const preview = node('div');
  preview.className = 'notice-preview';
  form.append(
    button('預覽', () =>
      preview.replaceChildren(node('h3', title.input.value), node('p', body.input.value)),
    ),
    preview,
  );
  const save = node('button', '儲存草稿');
  save.type = 'submit';
  form.append(save);
  form.onsubmit = (event) => {
    event.preventDefault();
    const content = {
      title: title.input.value,
      body: body.input.value,
      startsAt: new Date(start.input.value).getTime(),
      expiresAt: new Date(end.input.value).getTime(),
      link: link.input.value,
      severity: important.input.checked ? 'important' : 'info',
      notifyOnRevision: notify.input.checked,
    };
    for (const [key, choices] of Object.entries(groups)) {
      content[key] = choices.filter((choice) => choice.input.checked).map((choice) => choice.value);
    }
    write(`notices/${id}/save`, { content }).catch((error) => message(error.message));
  };
  detail.append(form);
  if (notice.id) {
    const actions =
      notice.state === 'withdrawn'
        ? [['publish', '重新發布']]
        : [
            ['publish', '發布公告'],
            ['withdraw', '封存草稿'],
          ];
    for (const [command, label] of actions) {
      detail.append(
        button(label, async () => {
          if (command === 'publish' && dirty) {
            throw new Error('請先儲存草稿，再預覽並發布，避免發布到尚未儲存的內容。');
          }
          if (await confirm(label, notice.title)) {
            await write(`notices/${id}/${command}`, {});
          }
        }),
      );
    }
  }
}
function localDate(time) {
  const date = new Date(time);
  return new Date(time - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
}

function showPublishedNotice(notice, detail) {
  detail.replaceChildren(node('h2', notice.title));
  const body = node('div');
  body.className = 'notice-preview';
  body.append(node('p', notice.body));
  detail.append(body);
  const values = node('dl');
  for (const [label, value] of Object.entries({
    狀態: stateNames[notice.state],
    適用模組: notice.modules.map((id) => moduleNames[id]).join('、') || '全部',
    遊戲版本: notice.minecraftVersions.join('、') || '全部',
    伺服器: notice.servers.map((id) => serverNames[id]).join('、') || '全域',
    生效時間: new Date(notice.startsAt).toLocaleString(),
    到期時間: new Date(notice.expiresAt).toLocaleString(),
  })) {
    values.append(node('dt', label), node('dd', value));
  }
  detail.append(
    values,
    button('撤回公告', async () => {
      if (await confirm('撤回公告', notice.title)) {
        await write(`notices/${notice.id}/withdraw`, {});
      }
    }),
  );
}
for (const page of ['releases', 'notices', 'audit']) {
  $(page + '-tab').onclick = () => {
    state.page = page;
    for (const item of ['releases', 'notices', 'audit']) {
      $(item + '-tab').setAttribute('aria-current', item === page ? 'page' : 'false');
    }
    load().catch((error) => message(error.message));
  };
}
$('create-notice').onclick = () => editNotice();
$('refresh').onclick = () => load().catch((error) => message(error.message));
client
  .login()
  .then((session) => {
    $('identity').textContent = session.email;
    return load();
  })
  .catch((error) => message(error.message));

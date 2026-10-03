// ---------- 通用 ----------
async function api(path, method = 'GET', body) {
  const opt = { method, headers: { 'Content-Type': 'application/json' } };
  if (body !== undefined) opt.body = JSON.stringify(body);
  const r = await fetch(path, opt);
  return r.json();
}
function showMsg(id, text, ok) {
  const el = document.getElementById(id);
  el.className = 'msg ' + (ok ? 'ok' : 'err');
  el.textContent = text;
}
function hideMsg(id) { document.getElementById(id).className = 'msg'; }
function fmtSize(n) {
  if (!n || n <= 0) return '-';
  const u = ['B', 'KB', 'MB', 'GB', 'TB'];
  let i = 0, v = n;
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
  return v.toFixed(v >= 100 ? 0 : 1) + ' ' + u[i];
}
function fmtSpeed(n) { return n > 0 ? fmtSize(n) + '/s' : ''; }

// ---------- 页签 ----------
document.querySelectorAll('nav.tabs button').forEach(b => {
  b.addEventListener('click', () => {
    document.querySelectorAll('nav.tabs button').forEach(x => x.classList.remove('active'));
    document.querySelectorAll('.tab').forEach(x => x.classList.remove('active'));
    b.classList.add('active');
    const tab = document.getElementById('tab-' + b.dataset.tab);
    tab.classList.add('active');
    stopTaskPoll();
    if (b.dataset.tab === 'accounts') loadPlatforms();
    if (b.dataset.tab === 'tasks') { loadTasks(); startTaskPoll(); }
    if (b.dataset.tab === 'settings') loadSettings();
  });
});

// ---------- 账号 ----------
const LOGIN_HINTS = {
  quark: 'pan.quark.cn', uc: 'drive.uc.cn', baidu: 'pan.baidu.com', c139: 'yun.139.com'
};
async function loadPlatforms() {
  const r = await api('/api/platforms');
  if (!r.ok) return;
  const grid = document.getElementById('platGrid');
  grid.innerHTML = '';
  r.data.forEach(p => {
    const d = document.createElement('div');
    d.className = 'plat';
    let body = '';
    if (p.loginType === 'cookie') {
      body = `
        <label>Cookie（${LOGIN_HINTS[p.id] || ''}，F12 → 网络 → 复制请求头 Cookie）</label>
        <textarea class="cookie" id="ck-${p.id}" placeholder="粘贴完整的 Cookie 字符串"></textarea>
        <div class="row" style="margin-top:10px">
          <button class="btn small" onclick="cookieLogin('${p.id}')">保存并验证</button>
          ${(p.id === 'quark' || p.id === 'uc') ? `<button class="btn small" onclick="qrLogin('${p.id}')">扫码登录</button>` : ''}
          ${p.loggedIn ? `<button class="danger small" onclick="logout('${p.id}')">退出</button>` : ''}
        </div>`;
    } else if (p.id === 'pan123') {
      body = `
        <label>账号（手机号/邮箱）</label><input type="text" id="u-${p.id}">
        <label>密码</label><input type="password" id="p-${p.id}">
        <div class="row" style="margin-top:10px">
          <button class="btn small" onclick="pwdLogin('${p.id}')">登录</button>
          ${p.loggedIn ? `<button class="danger small" onclick="logout('${p.id}')">退出</button>` : ''}
        </div>`;
    } else if (p.id === 'xunlei') {
      body = `
        <label>账号（手机号）</label><input type="text" id="u-xunlei">
        <label>密码</label><input type="password" id="p-xunlei">
        <div class="row" style="margin-top:10px">
          <button class="btn small" onclick="xunleiLogin()">密码登录</button>
          ${p.loggedIn ? `<button class="danger small" onclick="logout('xunlei')">退出</button>` : ''}
        </div>
        <div style="margin-top:14px;border-top:1px dashed #e5e7eb;padding-top:10px">
          <label>短信验证码登录（被风控拦时用这个）</label>
          <div class="row" style="margin-top:6px">
            <button class="ghost small" onclick="xunleiSendSms()">发送验证码</button>
            <input type="text" id="smscode-xunlei" style="max-width:160px" placeholder="6 位验证码">
            <button class="btn small" onclick="xunleiSmsLogin()">验证并登录</button>
          </div>
          <div class="hint" style="margin-top:6px">短信登录时账号框填绑定的手机号，验证码发到该手机</div>
        </div>
        <div id="review-xunlei" style="display:none;margin-top:8px"></div>`;
    }
    d.innerHTML = `
      <h3>${p.name} <span class="badge ${p.loggedIn ? 'on' : ''}">${p.loggedIn ? '已登录' : '未登录'}</span></h3>
      <div class="nick">${p.loggedIn && p.nickname ? '昵称：' + escapeHtml(p.nickname) : ''}</div>
      ${body}
      <div class="msg" id="msg-${p.id}"></div>`;
    grid.appendChild(d);
  });
}
function escapeHtml(s) { return String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])); }

async function cookieLogin(pid) {
  const v = document.getElementById('ck-' + pid).value.trim();
  hideMsg('msg-' + pid);
  if (!v) { showMsg('msg-' + pid, '请先粘贴 Cookie', false); return; }
  const r = await api('/api/accounts/' + pid, 'POST', { cookie: v });
  if (r.ok) { showMsg('msg-' + pid, '登录成功：' + (r.data.nickname || ''), true); setTimeout(loadPlatforms, 800); }
  else showMsg('msg-' + pid, r.message || '登录失败', false);
}
async function pwdLogin(pid) {
  const u = document.getElementById('u-' + pid).value.trim();
  const p = document.getElementById('p-' + pid).value;
  hideMsg('msg-' + pid);
  if (!u || !p) { showMsg('msg-' + pid, '请输入账号和密码', false); return; }
  const r = await api('/api/accounts/' + pid, 'POST', { username: u, password: p });
  if (r.ok && r.data.nickname) { showMsg('msg-' + pid, '登录成功：' + r.data.nickname, true); setTimeout(loadPlatforms, 800); }
  else if (r.ok) showMsg('msg-' + pid, '登录成功', true), setTimeout(loadPlatforms, 800);
  else showMsg('msg-' + pid, r.message || '登录失败', false);
}
let xunleiSmsState = null;
function showXunleiReview(url) {
  const el = document.getElementById('review-xunlei');
  if (!url) { el.style.display = 'none'; el.innerHTML = ''; return; }
  el.style.display = 'block';
  el.innerHTML = `短信收不到？可先在浏览器打开<a href="${url}" target="_blank" rel="noopener">迅雷验证页面</a>完成验证再回来登录`;
}
async function xunleiLogin() {
  const u = document.getElementById('u-xunlei').value.trim();
  const p = document.getElementById('p-xunlei').value;
  hideMsg('msg-xunlei');
  if (!u || !p) { showMsg('msg-xunlei', '请输入账号和密码', false); return; }
  showMsg('msg-xunlei', '登录中…', true);
  const r = await api('/api/accounts/xunlei', 'POST', { username: u, password: p });
  if (!r.ok) { showMsg('msg-xunlei', r.message || '登录失败', false); return; }
  const d = r.data;
  if (d.reviewUrl) showXunleiReview(d.reviewUrl);
  if (d.needSms) {
    if (/^1\d{10}$/.test(u)) {
      const s = await api('/api/accounts/xunlei/sms', 'POST', { mobile: u });
      if (s.ok) {
        xunleiSmsState = { mobile: u, creditKey: s.data.creditKey, smsToken: s.data.smsToken };
        showMsg('msg-xunlei', '已发送短信验证码，请输入后点「验证并登录」', true);
      } else {
        showMsg('msg-xunlei', (s.message || '短信发送失败') + '，可点「发送验证码」重试', false);
      }
    } else {
      showMsg('msg-xunlei', (d.message || '需要短信验证') + '：账号框改填绑定手机号后点「发送验证码」', false);
    }
  } else if (d.nickname) {
    showMsg('msg-xunlei', '登录成功：' + d.nickname, true);
    setTimeout(loadPlatforms, 800);
  } else {
    showMsg('msg-xunlei', d.message || '登录失败', false);
  }
}
async function xunleiSendSms() {
  const mobile = document.getElementById('u-xunlei').value.trim();
  hideMsg('msg-xunlei');
  if (!/^1\d{10}$/.test(mobile)) { showMsg('msg-xunlei', '短信登录请在账号框填绑定的手机号', false); return; }
  const s = await api('/api/accounts/xunlei/sms', 'POST', { mobile });
  if (!s.ok) { showMsg('msg-xunlei', s.message || '短信发送失败', false); return; }
  xunleiSmsState = { mobile, creditKey: s.data.creditKey, smsToken: s.data.smsToken };
  showMsg('msg-xunlei', '验证码已发送，请输入后点「验证并登录」', true);
}
async function xunleiSmsLogin() {
  const code = document.getElementById('smscode-xunlei').value.trim();
  if (!code || !xunleiSmsState) { showMsg('msg-xunlei', '请先获取短信验证码', false); return; }
  const r = await api('/api/accounts/xunlei/sms-login', 'POST',
    { mobile: xunleiSmsState.mobile, code, creditKey: xunleiSmsState.creditKey, smsToken: xunleiSmsState.smsToken });
  if (r.ok) { showMsg('msg-xunlei', '登录成功：' + (r.data.nickname || ''), true); setTimeout(loadPlatforms, 800); }
  else showMsg('msg-xunlei', r.message || '验证失败', false);
}
async function logout(pid) {
  await api('/api/accounts/' + pid, 'DELETE');
  loadPlatforms();
}

// ---------- 扫码登录（夸克 / UC）----------
let qrTimer = null, qrSessionId = null;
async function qrLogin(pid) {
  hideMsg('msg-' + pid);
  const r = await api('/api/qrlogin/' + pid, 'POST');
  if (!r.ok) { showMsg('msg-' + pid, r.message || '获取二维码失败', false); return; }
  qrSessionId = r.data.sessionId;
  document.getElementById('qrTitle').textContent = (pid === 'uc' ? 'UC网盘' : '夸克网盘') + '扫码登录';
  try {
    const qr = qrcode(0, 'M');
    qr.addData(r.data.qrUrl);
    qr.make();
    document.getElementById('qrImg').innerHTML = qr.createSvgTag({ scalable: true });
  } catch (e) {
    document.getElementById('qrImg').innerHTML = '<p class="hint">二维码生成失败</p>';
  }
  const st = document.getElementById('qrStatus');
  st.className = 'msg'; st.textContent = '请用手机 App 扫码确认';
  document.getElementById('qrModal').style.display = 'flex';
  clearInterval(qrTimer);
  qrTimer = setInterval(() => qrPoll(pid), 2000);
}
async function qrPoll(pid) {
  const r = await api('/api/qrlogin/' + pid + '/status?sessionId=' + encodeURIComponent(qrSessionId));
  if (!r.ok) { qrStop('获取状态失败，请重试'); return; }
  const s = r.data.status;
  if (s === 'success') {
    qrStop();
    document.getElementById('qrModal').style.display = 'none';
    loadPlatforms();
  }
  else if (s === 'expired') qrStop('二维码已过期，请关闭后重新获取');
  else if (s === 'failed') qrStop(r.data.message || '登录失败，请重试');
}
function qrStop(msg) {
  if (qrTimer) { clearInterval(qrTimer); qrTimer = null; }
  if (msg) document.getElementById('qrStatus').textContent = msg;
}
async function qrCancel() {
  if (qrSessionId) { try { await api('/api/qrlogin/session?sessionId=' + encodeURIComponent(qrSessionId), 'DELETE'); } catch (e) {} }
  qrStop();
  document.getElementById('qrModal').style.display = 'none';
}

// ---------- 解析 ----------
let curSession = null, curDirFid = '0', dirStack = [], curFiles = [];
async function parseShare() {
  const link = document.getElementById('shareLink').value.trim();
  const pwd = document.getElementById('sharePwd').value.trim();
  hideMsg('parseMsg');
  if (!link) { showMsg('parseMsg', '请粘贴分享链接', false); return; }
  const btn = document.getElementById('parseBtn');
  btn.disabled = true; btn.textContent = '解析中…';
  try {
    const r = await api('/api/parse', 'POST', { link, pwd });
    if (!r.ok) { showMsg('parseMsg', r.message || '解析失败', false); return; }
    curSession = r.data.sessionId;
    curDirFid = '0'; dirStack = [];
    document.getElementById('fileCard').style.display = 'block';
    document.getElementById('parseTitle').textContent = r.data.title || '分享文件';
    document.getElementById('parsePlat').textContent = r.data.platformName;
    await loadDir('0', '根目录');
    showMsg('parseMsg', '解析成功', true);
  } finally { btn.disabled = false; btn.textContent = '解析'; }
}
async function loadDir(fid, name) {
  const r = await api(`/api/sessions/${curSession}/files?dirFid=${encodeURIComponent(fid)}`);
  if (!r.ok) { showMsg('parseMsg', r.message || '获取文件列表失败', false); return; }
  curFiles = r.data; curDirFid = fid;
  renderFiles(); renderCrumb();
}
function renderCrumb() {
  const c = document.getElementById('crumb');
  c.innerHTML = '';
  const mk = (label, idx) => {
    const b = document.createElement('button'); b.textContent = label;
    b.onclick = () => {
      dirStack = dirStack.slice(0, idx);
      const t = idx === 0 ? { fid: '0', name: '根目录' } : dirStack[idx - 1];
      loadDir(t.fid, t.name);
    };
    return b;
  };
  c.appendChild(mk('根目录', 0));
  dirStack.forEach((d, i) => {
    const s = document.createElement('span'); s.className = 'sep'; s.textContent = '/'; c.appendChild(s);
    c.appendChild(mk(d.name, i + 1));
  });
}
function renderFiles() {
  const el = document.getElementById('fileList');
  if (!curFiles.length) { el.innerHTML = '<div class="empty">空文件夹</div>'; return; }
  const dirs = curFiles.filter(f => f.isdir), files = curFiles.filter(f => !f.isdir);
  const sorted = [...dirs, ...files];
  let html = '<table class="files"><tr><th style="width:34px"></th><th>文件名</th><th style="width:90px">大小</th><th style="width:130px">修改时间</th></tr>';
  sorted.forEach((f, i) => {
    const cb = `<input type="checkbox" data-i="${curFiles.indexOf(f)}" onchange="updateSel()">`;
    const name = f.isdir
      ? `<a href="javascript:void(0)" onclick="enterDir(${curFiles.indexOf(f)})" style="color:#1a73e8">📁 ${escapeHtml(f.fname)}</a>`
      : `📄 ${escapeHtml(f.fname)}`;
    html += `<tr><td>${cb}</td><td>${name}</td><td>${f.isdir ? '-' : fmtSize(f.fsize)}</td><td style="color:#999;font-size:12px">${escapeHtml(f.modifyTime || '')}</td></tr>`;
  });
  el.innerHTML = html + '</table>';
  updateSel();
}
function enterDir(i) {
  const f = curFiles[i];
  dirStack.push({ fid: f.fid, name: f.fname });
  loadDir(f.fid, f.fname);
}
function toggleAll(on) {
  document.querySelectorAll('#fileList input[type=checkbox]').forEach(c => c.checked = on);
  updateSel();
}
function updateSel() {
  const cbs = [...document.querySelectorAll('#fileList input[type=checkbox]:checked')];
  const n = cbs.length;
  const hasDir = cbs.some(c => curFiles[+c.dataset.i] && curFiles[+c.dataset.i].isdir);
  document.getElementById('selInfo').textContent = n ? `已选 ${n} 项${hasDir ? '（含文件夹，将下载其全部内容）' : ''}` : '';
}
async function downloadSelected() {
  const idx = [...document.querySelectorAll('#fileList input[type=checkbox]:checked')].map(c => +c.dataset.i);
  hideMsg('dlMsg');
  if (!idx.length) { showMsg('dlMsg', '请先勾选要下载的文件', false); return; }
  const items = idx.map(i => curFiles[i]);
  const btn = document.getElementById('dlBtn');
  btn.disabled = true;
  try {
    if (items.some(f => f.isdir)) {
      // 含文件夹：先展开预览（文件数/总大小），确认后再提交
      btn.textContent = '正在扫描文件夹…';
      const ex = await api('/api/downloads/expand', 'POST', { sessionId: curSession, files: items });
      if (!ex.ok) { showMsg('dlMsg', ex.message || '展开文件夹失败', false); return; }
      const tip = ex.data.truncated ? '\n（文件数超过上限 5000，已截断）' : '';
      const okGo = confirm(`将下载 ${ex.data.fileCount} 个文件（共 ${fmtSize(ex.data.totalSize)}），保存到下载目录的「${ex.data.batchName}」文件夹。继续吗？${tip}`);
      if (!okGo) return;
      btn.textContent = '取链中…（每个文件需转存+取直链，请耐心等）';
      const r = await api('/api/downloads', 'POST', { sessionId: curSession, expandId: ex.data.expandId });
      if (!r.ok) { showMsg('dlMsg', r.message || '提交失败', false); return; }
      showMsg('dlMsg', `已加入 ${r.data.taskIds.length} 个下载任务，可到「下载任务」页查看进度`, true);
    } else {
      btn.textContent = '取链中…（每个文件需转存+取直链）';
      const r = await api('/api/downloads', 'POST', { sessionId: curSession, files: items });
      if (!r.ok) { showMsg('dlMsg', r.message || '提交失败', false); return; }
      showMsg('dlMsg', `已加入 ${r.data.taskIds.length} 个下载任务，可到「下载任务」页查看进度`, true);
    }
  } finally { btn.disabled = false; btn.textContent = '下载选中'; }
}
async function directDownload() {
  const url = document.getElementById('directUrl').value.trim();
  const name = document.getElementById('directName').value.trim();
  hideMsg('directMsg');
  if (!url) { showMsg('directMsg', '请输入下载链接', false); return; }
  const r = await api('/api/downloads/direct', 'POST', { url, fileName: name });
  if (r.ok) showMsg('directMsg', '已加入下载任务，可到「下载任务」页查看进度', true);
  else showMsg('directMsg', r.message || '提交失败', false);
}

// ---------- 下载任务 ----------
let pollTimer = null;
function startTaskPoll() { stopTaskPoll(); pollTimer = setInterval(loadTasks, 2000); }
function stopTaskPoll() { if (pollTimer) { clearInterval(pollTimer); pollTimer = null; } }
const STATUS_TXT = { downloading: '下载中', paused: '已暂停', completed: '已完成', failed: '失败' };
let taskMap = {};               // id -> task
const selectedTasks = new Set();
const expandedBatches = new Set();
function renderTask(t) {
  const d = document.createElement('div');
  d.className = 'task';
  const pct = t.total > 0 ? Math.min(100, Math.round(t.downloaded * 100 / t.total)) : 0;
  const bar = t.status === 'completed' ? 'done' : (t.status === 'failed' ? 'fail' : '');
  let actions = '';
  if (t.status === 'downloading') actions += `<button class="ghost small" onclick="taskOp(${t.id},'pause')">暂停</button>`;
  if (t.status === 'paused' || t.status === 'failed') actions += `<button class="ghost small" onclick="taskOp(${t.id},'resume')">继续</button>`;
  actions += `<button class="danger small" onclick="taskDel(${t.id})">删除</button>`;
  d.innerHTML = `
      <div class="trow">
        <input type="checkbox" class="taskCk" data-id="${t.id}" ${selectedTasks.has(t.id) ? 'checked' : ''} onchange="taskCkChanged(this)">
        <div class="tbody">
          <div class="name">${escapeHtml(t.fileName)}</div>
          <div class="pbar ${bar}"><div style="width:${t.status === 'completed' ? 100 : pct}%"></div></div>
          <div class="meta">
            <span class="status ${t.status}">${STATUS_TXT[t.status] || t.status}</span>
            <span>${fmtSize(t.downloaded)} / ${fmtSize(t.total)}${t.total > 0 && t.status !== 'completed' ? ' · ' + pct + '%' : ''}</span>
            ${t.status === 'downloading' && t.speed > 0 ? `<span>${fmtSpeed(t.speed)}</span>` : ''}
            ${t.relPath ? `<span style="color:#999">${escapeHtml(t.relPath)}</span>` : ''}
          </div>
          ${t.error ? `<div class="err-text">${escapeHtml(t.error)}</div>` : ''}
          <div class="actions">${actions}</div>
        </div>
      </div>`;
  return d;
}
function renderBatch(batchId, batchName, kids) {
  const wrap = document.createElement('div');
  wrap.className = 'batch';
  const done = kids.filter(t => t.status === 'completed').length;
  const failed = kids.filter(t => t.status === 'failed').length;
  const downloading = kids.filter(t => t.status === 'downloading').length;
  const totalBytes = kids.reduce((a, t) => a + (t.total || 0), 0);
  const downBytes = kids.reduce((a, t) => a + Math.min(t.downloaded || 0, t.total || (t.downloaded || 0)), 0);
  const speed = kids.reduce((a, t) => a + (t.speed || 0), 0);
  const pct = totalBytes > 0 ? Math.min(100, Math.round(downBytes * 100 / totalBytes)) : (kids.length && done === kids.length ? 100 : 0);
  let stat;
  if (downloading > 0) stat = `下载中 ${done}/${kids.length}`;
  else if (done === kids.length) stat = '已完成';
  else if (failed > 0 && done + failed === kids.length) stat = `完成（${failed} 个失败）`;
  else stat = `已暂停 ${done}/${kids.length}${failed ? ` · ${failed} 失败` : ''}`;
  const open = expandedBatches.has(batchId);
  let acts = `<button class="ghost small" onclick="toggleBatch('${batchId}')">${open ? '收起' : '展开'}</button>`;
  if (downloading > 0) acts += `<button class="ghost small" onclick="batchOp('${batchId}','pause')">暂停全部</button>`;
  if (kids.some(t => t.status === 'paused' || t.status === 'failed')) acts += `<button class="ghost small" onclick="batchOp('${batchId}','resume')">继续全部</button>`;
  acts += `<button class="danger small" onclick="batchDelete('${batchId}', false, this)">删批次（仅任务）</button>`;
  acts += `<button class="danger small" onclick="batchDelete('${batchId}', true, this)">删批次（任务+文件）</button>`;
  const head = document.createElement('div');
  head.innerHTML = `
      <div class="trow"><div class="tbody">
        <div class="name">📁 ${escapeHtml(batchName || '文件夹下载')}</div>
        <div class="pbar ${done === kids.length ? 'done' : (failed && !downloading ? 'fail' : '')}"><div style="width:${pct}%"></div></div>
        <div class="meta"><span>${stat}</span><span>${fmtSize(downBytes)} / ${fmtSize(totalBytes)}${totalBytes > 0 ? ' · ' + pct + '%' : ''}</span>${speed > 0 ? `<span>${fmtSpeed(speed)}</span>` : ''}</div>
        <div class="actions">${acts}</div>
      </div></div>`;
  wrap.appendChild(head);
  if (open) {
    const box = document.createElement('div');
    box.className = 'kids';
    kids.forEach(t => box.appendChild(renderTask(t)));
    wrap.appendChild(box);
  }
  return wrap;
}
function toggleBatch(batchId) {
  if (expandedBatches.has(batchId)) expandedBatches.delete(batchId); else expandedBatches.add(batchId);
  loadTasks();
}
async function batchOp(batchId, op) {
  const kids = Object.values(taskMap).filter(t => t.batchId === batchId);
  const ids = kids.filter(t => op === 'pause' ? t.status === 'downloading' : (t.status === 'paused' || t.status === 'failed')).map(t => t.id);
  if (ids.length) await api(`/api/tasks/batch-${op}`, 'POST', { ids });
  loadTasks();
}
async function batchDelete(batchId, withFile, btn) {
  const kids = Object.values(taskMap).filter(t => t.batchId === batchId);
  const ids = kids.map(t => t.id);
  if (!ids.length) return;
  const name = (kids[0] && kids[0].batchName) || '该批次';
  if (withFile && !confirm(`删除「${name}」的 ${ids.length} 个任务记录，并同时删除已下载的文件？`)) return;
  if (!withFile && !confirm(`仅删除「${name}」的 ${ids.length} 个任务记录（文件保留）？`)) return;
  await api('/api/tasks/batch-delete', 'POST', { ids, deleteFile: !!withFile });
  expandedBatches.delete(batchId);
  loadTasks();
}
async function loadTasks() {
  const r = await api('/api/tasks');
  if (!r.ok) return;
  const list = r.data;
  document.getElementById('taskCount').textContent = list.length ? `(${list.length})` : '';
  taskMap = {};
  const cur = {};
  list.forEach(t => {
    taskMap[t.id] = t;
    cur[t.id] = t.status;
  });
  [...selectedTasks].forEach(id => { if (!(id in cur)) selectedTasks.delete(id); });
  const el = document.getElementById('taskList');
  if (!list.length) { el.innerHTML = '<div class="empty">暂无下载任务</div>'; updateSelCount(); return; }
  el.innerHTML = '';
  const seenBatch = new Set();
  list.forEach(t => {
    if (t.batchId) {
      if (seenBatch.has(t.batchId)) return;
      seenBatch.add(t.batchId);
      el.appendChild(renderBatch(t.batchId, t.batchName, list.filter(x => x.batchId === t.batchId)));
    } else {
      el.appendChild(renderTask(t));
    }
  });
  updateSelCount();
  const all = document.querySelectorAll('.taskCk');
  document.getElementById('ckAll').checked = all.length > 0 && [...all].every(c => c.checked);
}
function toggleTaskCk(on) {
  document.querySelectorAll('.taskCk').forEach(c => {
    c.checked = on;
    const id = +c.dataset.id;
    if (on) selectedTasks.add(id); else selectedTasks.delete(id);
  });
  updateSelCount();
}
function taskCkChanged(cb) {
  const id = +cb.dataset.id;
  if (cb.checked) selectedTasks.add(id); else selectedTasks.delete(id);
  updateSelCount();
  const all = document.querySelectorAll('.taskCk');
  document.getElementById('ckAll').checked = all.length > 0 && [...all].every(c => c.checked);
}
function updateSelCount() {
  document.getElementById('selCount').textContent = selectedTasks.size ? `已选 ${selectedTasks.size} 项` : '';
}
async function delSelected(withFile) {
  if (!selectedTasks.size) return;
  const ids = [...selectedTasks];
  if (withFile && !confirm(`删除所选 ${ids.length} 个任务记录，并同时删除服务器上的文件？`)) return;
  await api('/api/tasks/batch-delete', 'POST', { ids, deleteFile: !!withFile });
  selectedTasks.clear();
  loadTasks();
}
async function taskOp(id, op) { await api(`/api/tasks/${id}/${op}`, 'POST'); loadTasks(); }
async function taskDel(id) {
  // 单个删除：仅删除任务记录（文件保留；如需连文件删除，用复选框 +「删除所选（任务+文件）」）
  await api(`/api/tasks/${id}?deleteFile=false`, 'DELETE');
  loadTasks();
}

// ---------- 设置 ----------
async function loadSettings() {
  const r = await api('/api/settings');
  if (!r.ok) return;
  const s = r.data;
  document.getElementById('setConn').value = s.maxConnections;
  document.getElementById('setConc').value = s.maxConcurrentTasks;
  document.getElementById('setLimit').value = (s.speedLimitBps / 1048576).toFixed(1);
  document.getElementById('setRetry').value = s.maxRetries;
  document.getElementById('setDir').value = s.downloadDir || '';
}
async function saveSettings() {
  hideMsg('setMsg');
  const s = {
    maxConnections: Math.max(1, parseInt(document.getElementById('setConn').value) || 16),
    maxConcurrentTasks: Math.max(1, parseInt(document.getElementById('setConc').value) || 3),
    speedLimitBps: Math.round((parseFloat(document.getElementById('setLimit').value) || 0) * 1048576),
    maxRetries: Math.max(0, parseInt(document.getElementById('setRetry').value) || 0),
    downloadDir: document.getElementById('setDir').value.trim()
  };
  const r = await api('/api/settings', 'PUT', s);
  showMsg('setMsg', r.ok ? '设置已保存并即时生效' : (r.message || '保存失败'), r.ok);
}


// ---------- 下载目录选择（服务器目录浏览） ----------
let pickerPath = '', pickerParent = '';
async function openDirPicker() {
  document.getElementById('dirModal').style.display = 'flex';
  // 默认打开上次保存的目录；取不到再回主目录
  const last = document.getElementById('setDir').value.trim();
  await loadPickerDir(last || null, true);
}
async function loadPickerDir(path, fallbackHome) {
  const q = path ? '?path=' + encodeURIComponent(path) : '';
  const r = await api('/api/fs/dirs' + q);
  if (!r.ok) {
    if (fallbackHome && path) { await loadPickerDir(null, false); return; }
    document.getElementById('dirList').innerHTML = '<div class="dirempty">读取目录失败</div>';
    return;
  }
  pickerPath = r.data.path; pickerParent = r.data.parent || '';
  document.getElementById('dirCur').textContent = pickerPath;
  const list = document.getElementById('dirList');
  if (!r.data.dirs.length) { list.innerHTML = '<div class="dirempty">（无子目录）</div>'; return; }
  list.innerHTML = '';
  r.data.dirs.forEach(name => {
    const d = document.createElement('div');
    d.className = 'diritem';
    d.textContent = '📁 ' + name;
    d.onclick = () => loadPickerDir(joinDir(pickerPath, name), false);
    list.appendChild(d);
  });
}
function joinDir(base, name) {
  if (/^[a-zA-Z]:[\\/]$/.test(base) || base.endsWith('\\')) return base + name;
  if (base.endsWith('/')) return base + name;
  const sep = base.includes('\\') ? '\\' : '/';
  return base + sep + name;
}
function dirUp() { if (pickerParent) loadPickerDir(pickerParent, true); }
function dirHome() { loadPickerDir(null, false); }
function dirCancel() { document.getElementById('dirModal').style.display = 'none'; }
async function dirConfirm() {
  document.getElementById('setDir').value = pickerPath;
  dirCancel();
  await saveSettings();
}
async function dirUseDefault() {
  document.getElementById('setDir').value = '';
  dirCancel();
  await saveSettings();
  // 保存后重新加载，输入框会显示默认目录的实际路径
  loadSettings();
}

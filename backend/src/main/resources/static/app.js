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
    if (b.dataset.tab === 'drive') loadDrive();
    if (b.dataset.tab === 'history') loadHistory();
    if (b.dataset.tab === 'tasks') { loadTasks(); startTaskPoll(); }
    if (b.dataset.tab === 'settings') loadSettings();
  });
});

// ---------- 账号 ----------
const LOGIN_HINTS = {
  quark: 'pan.quark.cn', uc: 'drive.uc.cn', baidu: 'pan.baidu.com', c139: 'yun.139.com', pan115: '115.com'
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
          ${p.loggedIn && (p.id === 'quark' || p.id === 'uc') ? `<button class="ghost small" onclick="openDrive('${p.id}')">📂 打开个人盘</button>` : ''}
          ${p.loggedIn ? `<button class="danger small" onclick="logout('${p.id}')">退出</button>` : ''}
        </div>`;
    } else if (p.id === 'lanzou') {
      body = `
        <label>账号（蓝奏云官网用户名）</label><input type="text" id="u-${p.id}">
        <label>密码</label><input type="password" id="p-${p.id}">
        <div class="row" style="margin-top:10px">
          <button class="btn small" onclick="pwdLogin('${p.id}')">登录</button>
          ${p.loggedIn ? `<button class="ghost small" onclick="openDrive('lanzou')">📂 打开个人盘</button>` : ''}
          ${p.loggedIn ? `<button class="danger small" onclick="logout('${p.id}')">退出</button>` : ''}
        </div>
        <div class="hint" style="margin-top:6px">账号密码登录，登录态保存在服务器供个人盘使用；只下分享链接的话无需登录</div>`;
    } else if (p.id === 'ilanzou') {
      body = `
        <label>账号（蓝奏云优享版账号）</label><input type="text" id="u-${p.id}">
        <label>密码</label><input type="password" id="p-${p.id}">
        <div class="row" style="margin-top:10px">
          <button class="btn small" onclick="pwdLogin('${p.id}')">登录</button>
          ${p.loggedIn ? `<button class="ghost small" onclick="openDrive('ilanzou')">📂 打开个人盘</button>` : ''}
          ${p.loggedIn ? `<button class="danger small" onclick="logout('${p.id}')">退出</button>` : ''}
        </div>
        <div class="hint" style="margin-top:6px">账号密码登录，密码会一并保存：登录令牌失效时自动重新登录续期（appToken 自动续期）；只下分享链接的话无需登录</div>`;
    } else if (p.id === 'guangya') {
      body = `
        <label>账号（手机号/邮箱/用户名）</label><input type="text" id="u-${p.id}">
        <label>密码</label><input type="password" id="p-${p.id}">
        <div class="row" style="margin-top:10px">
          <button class="btn small" onclick="pwdLogin('${p.id}')">登录</button>
          ${p.loggedIn ? `<button class="ghost small" onclick="openDrive('guangya')">📂 打开个人盘</button>` : ''}
          ${p.loggedIn ? `<button class="danger small" onclick="logout('${p.id}')">退出</button>` : ''}
        </div>
        <div class="hint" style="margin-top:6px">账号密码登录，登录令牌到期会自动续期；只下分享链接的话无需登录</div>`;
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
      ${p.loggedIn && p.id !== 'lanzou' ? `<div class="quota" id="quota-${p.id}"></div>` : ''}
      ${body}
      <div class="msg" id="msg-${p.id}"></div>`;
    grid.appendChild(d);
    if (p.loggedIn && p.id !== 'lanzou') loadQuota(p.id);
  });
  loadBilibiliAccount();
  loadYoutubeAccount();
}

// ---------- B站账号（视频高清专用，与网盘账号相互独立） ----------
async function loadBilibiliAccount() {
  const badge = document.getElementById('biliBadge');
  if (!badge) return;
  const r = await api('/api/video/accounts/bilibili');
  const nick = document.getElementById('biliNick');
  const out = document.getElementById('biliLogoutBtn');
  if (!r.ok) { badge.textContent = '状态未知'; badge.className = 'badge'; return; }
  if (r.data.loggedIn) {
    badge.textContent = '已登录'; badge.className = 'badge on';
    nick.textContent = r.data.nickname ? '昵称：' + r.data.nickname : '';
    out.style.display = '';
  } else {
    badge.textContent = '未登录'; badge.className = 'badge';
    nick.textContent = '';
    out.style.display = 'none';
  }
}
async function bilibiliLogin() {
  const v = document.getElementById('ck-bilibili').value.trim();
  hideMsg('msg-bilibili');
  if (!v) { showMsg('msg-bilibili', '请先粘贴 Cookie', false); return; }
  const r = await api('/api/video/accounts/bilibili', 'POST', { cookie: v });
  if (r.ok) {
    showMsg('msg-bilibili', '登录成功：' + (r.data.nickname || ''), true);
    document.getElementById('ck-bilibili').value = '';
    loadBilibiliAccount();
  } else showMsg('msg-bilibili', r.message || '登录失败', false);
}
async function bilibiliLogout() {
  const r = await api('/api/video/accounts/bilibili', 'DELETE');
  showMsg('msg-bilibili', r.ok ? '已退出 B站登录' : (r.message || '退出失败'), r.ok);
  loadBilibiliAccount();
}

// ---------- YouTube 账号（Cookie 仅存不校验，解析失败时请更新） ----------
async function loadYoutubeAccount() {
  const badge = document.getElementById('ytBadge');
  if (!badge) return;
  const r = await api('/api/youtube/account');
  const clear = document.getElementById('ytClearBtn');
  if (!r.ok) { badge.textContent = '状态未知'; badge.className = 'badge'; return; }
  if (r.data.saved) {
    badge.textContent = '已保存'; badge.className = 'badge on';
    clear.style.display = '';
  } else {
    badge.textContent = '未保存'; badge.className = 'badge';
    clear.style.display = 'none';
  }
}
async function youtubeSaveCookie() {
  const v = document.getElementById('ck-youtube').value.trim();
  hideMsg('msg-youtube');
  if (!v) { showMsg('msg-youtube', '请先粘贴 Cookie', false); return; }
  const r = await api('/api/youtube/account', 'POST', { cookie: v });
  if (r.ok) {
    showMsg('msg-youtube', 'Cookie 已保存（未校验，解析失败时请更新）', true);
    document.getElementById('ck-youtube').value = '';
    loadYoutubeAccount();
  } else showMsg('msg-youtube', r.message || '保存失败', false);
}
async function youtubeClearCookie() {
  const r = await api('/api/youtube/account', 'DELETE');
  showMsg('msg-youtube', r.ok ? '已清除 YouTube Cookie' : (r.message || '清除失败'), r.ok);
  loadYoutubeAccount();
}

async function loadQuota(id) {
  const el = document.getElementById('quota-' + id);
  if (!el) return;
  el.innerHTML = '<span class="hint">容量加载中…</span>';
  const r = await api('/api/accounts/' + id + '/quota');
  if (!r.ok || !r.data || !r.data.total) { el.innerHTML = '<span class="hint">容量获取失败</span>'; return; }
  const used = r.data.used, total = r.data.total;
  const pct = Math.min(100, total > 0 ? used / total * 100 : 0);
  const cls = pct >= 95 ? 'hot' : pct >= 80 ? 'warn' : '';
  el.innerHTML = `<div class="qmeta"><span>已用 ${fmtSize(used)} / ${fmtSize(total)}</span><span>${pct.toFixed(1)}%</span></div>` +
    `<div class="pbar ${cls}"><div style="width:${pct}%"></div></div>`;
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
let curHistoryId = '', curHistoryFav = false;
async function parseShare() {
  const link = document.getElementById('shareLink').value.trim();
  const pwd = document.getElementById('sharePwd').value.trim();
  hideMsg('parseMsg');
  if (!link) { showMsg('parseMsg', '请粘贴分享链接', false); return; }
  // 磁力链接不走网盘解析：交给 Gopeed 引擎，创建成功后自动切到下载任务页
  if (/^magnet:\?/i.test(link) || link.toLowerCase().includes('magnet:?xt=urn:btih:')) {
    const mbtn = document.getElementById('parseBtn');
    mbtn.disabled = true; mbtn.textContent = '创建中…';
    try {
      const r = await api('/api/gopeed/tasks', 'POST', { url: link });
      if (!r.ok) { showMsg('parseMsg', r.message || '磁力任务创建失败', false); return; }
      showMsg('parseMsg', '磁力任务已创建', true);
      document.querySelector('nav.tabs button[data-tab="tasks"]').click();
    } finally { mbtn.disabled = false; mbtn.textContent = '解析'; }
    return;
  }
  // 视频 / GitHub / YouTube 链接分流（后端会再校验，识别错了会给出明确报错）
  const urlHost = (() => { const m = link.match(/https?:\/\/([^/\s]+)/i); return m ? m[1].toLowerCase() : ''; })();
  const isYoutubeLink = /(^|\.)(youtube\.com|youtube-nocookie\.com)$/.test(urlHost) || urlHost === 'youtu.be';
  const isVideoLink = /(^|\.)(douyin\.com|iesdouyin\.com|kuaishou\.com|bilibili\.com|b23\.tv|bili2233\.cn|acfun\.cn|weibo\.com|weibo\.cn|video\.weibo\.com|xiaohongshu\.com|xhslink\.com|xhslink\.cn|pixiv\.net|chenzhongtech\.com)$/.test(urlHost);
  const isGithubLink = urlHost === 'github.com' || urlHost === 'raw.githubusercontent.com';
  if (isYoutubeLink || isVideoLink || isGithubLink) {
    const vbtn = document.getElementById('parseBtn');
    vbtn.disabled = true; vbtn.textContent = '解析中…';
    try {
      if (isYoutubeLink) await parseYoutubeLink(link);
      else if (isVideoLink) await parseVideoLink(link);
      else await parseGithubLink(link);
    } finally { vbtn.disabled = false; vbtn.textContent = '解析'; }
    return;
  }
  const btn = document.getElementById('parseBtn');
  btn.disabled = true; btn.textContent = '解析中…';
  try {
    const r = await api('/api/parse', 'POST', { link, pwd });
    if (!r.ok) { showMsg('parseMsg', r.message || '解析失败', false); return; }
    document.getElementById('videoCard').style.display = 'none';
    document.getElementById('githubCard').style.display = 'none';
    document.getElementById('youtubeCard').style.display = 'none';
    curSession = r.data.sessionId;
    curHistoryId = r.data.historyId || '';
    curHistoryFav = !!r.data.favorite;
    updateFavBtn();
    loadFavorites();
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
const STATUS_TXT = { downloading: '下载中', queued: '排队中', merging: '合并中', retry_wait: '等待重试', paused: '已暂停', completed: '已完成', failed: '失败' };
let taskMap = {};               // id -> task
const selectedTasks = new Set();
const expandedBatches = new Set();
function renderTask(t) {
  const d = document.createElement('div');
  d.className = 'task';
  const pct = t.total > 0 ? Math.min(100, Math.round(t.downloaded * 100 / t.total)) : 0;
  const bar = t.status === 'completed' ? 'done' : (t.status === 'failed' ? 'fail' : '');
  let actions = '';
  if (t.status === 'downloading' || t.status === 'queued' || t.status === 'retry_wait') actions += `<button class="ghost small" onclick="taskOp(${t.id},'pause')">暂停</button>`;
  if (t.status === 'paused' || t.status === 'failed' || t.status === 'retry_wait') actions += `<button class="ghost small" onclick="taskOp(${t.id},'resume')">继续</button>`;
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
  const downloading = kids.filter(t => t.status === 'downloading' || t.status === 'queued' || t.status === 'merging' || t.status === 'retry_wait').length;
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
  const ids = kids.filter(t => op === 'pause' ? (t.status === 'downloading' || t.status === 'queued') : (t.status === 'paused' || t.status === 'failed')).map(t => t.id);
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
  loadGopeedCard();
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

// ---------- Gopeed 磁力任务 ----------
const GOPEED_STATUS_TXT = { ready: '准备中', running: '下载中', wait: '等待中', pause: '已暂停', error: '失败', done: '已完成' };
async function gopeedStatus() {
  const r = await api('/api/gopeed/status');
  return r.ok ? r.data : null;
}
async function loadGopeedCard() {
  const body = document.getElementById('gopeedBody');
  if (!body) return;
  const st = await gopeedStatus();
  if (!st) { body.innerHTML = '<p class="hint">Gopeed 状态获取失败</p>'; return; }
  if (!st.installed) {
    body.innerHTML = `<div class="hint">Gopeed 内核未安装，磁力下载不可用。<button class="ghost small" style="margin-left:8px" onclick="gotoSettings()">去设置</button></div>`;
    return;
  }
  if (!st.running) {
    body.innerHTML = `<div class="hint">Gopeed 引擎未启动${st.version ? '（内核 ' + escapeHtml(st.version) + '）' : ''}，磁力任务暂不可用。<button class="ghost small" style="margin-left:8px" onclick="gotoSettings()">去设置</button></div>`;
    return;
  }
  const r = await api('/api/gopeed/tasks');
  if (!r.ok) { body.innerHTML = `<div class="hint">${escapeHtml(r.message || '磁力任务加载失败')}</div>`; return; }
  const list = r.data || [];
  if (!list.length) { body.innerHTML = '<div class="empty">暂无磁力任务</div>'; return; }
  body.innerHTML = '';
  list.forEach(t => body.appendChild(renderGopeedTask(t)));
}
function renderGopeedTask(t) {
  const d = document.createElement('div');
  d.className = 'task';
  const pct = t.total > 0 ? Math.min(100, Math.round(t.downloaded * 100 / t.total)) : 0;
  const bar = t.status === 'done' ? 'done' : (t.status === 'error' ? 'fail' : '');
  let actions = '';
  if (t.status === 'running' || t.status === 'ready' || t.status === 'wait') actions += `<button class="ghost small" onclick="gopeedTaskOp('${t.id}','pause')">暂停</button>`;
  if (t.status === 'pause' || t.status === 'error') actions += `<button class="ghost small" onclick="gopeedTaskOp('${t.id}','continue')">继续</button>`;
  actions += `<button class="danger small" onclick="gopeedTaskDel('${t.id}')">删除</button>`;
  d.innerHTML = `
      <div class="trow">
        <div class="tbody">
          <div class="name">🧲 ${escapeHtml(t.name || '磁力任务')}</div>
          <div class="pbar ${bar}"><div style="width:${t.status === 'done' ? 100 : pct}%"></div></div>
          <div class="meta">
            <span class="status">${GOPEED_STATUS_TXT[t.status] || t.status}</span>
            <span>${fmtSize(t.downloaded)} / ${fmtSize(t.total)}${t.total > 0 && t.status !== 'done' ? ' · ' + pct + '%' : ''}</span>
            ${t.status === 'running' && t.speed > 0 ? `<span>${fmtSpeed(t.speed)}</span>` : ''}
          </div>
          <div class="actions">${actions}</div>
        </div>
      </div>`;
  return d;
}
async function gopeedTaskOp(id, op) { await api(`/api/gopeed/tasks/${encodeURIComponent(id)}/${op}`, 'POST'); loadTasks(); }
async function gopeedTaskDel(id) { await api(`/api/gopeed/tasks/${encodeURIComponent(id)}`, 'DELETE'); loadTasks(); }
function gotoSettings() { document.querySelector('nav.tabs button[data-tab="settings"]').click(); }

// ---------- Gopeed 设置 ----------
async function loadGopeedStatus() {
  const el = document.getElementById('gopeedStatus');
  if (!el) return;
  const st = await gopeedStatus();
  if (!st) { el.textContent = '状态获取失败'; return; }
  const plat = `${st.os}-${st.arch}`;
  el.textContent = !st.installed
    ? `未安装（本机 ${plat}）`
    : st.running
      ? `运行中 · 内核 ${st.version || '未知版本'}（本机 ${plat}）`
      : `已安装 ${st.version || ''}，未运行（本机 ${plat}）`;
  document.getElementById('gpInstall').disabled = st.installed;
  document.getElementById('gpStart').disabled = !st.installed || st.running;
  document.getElementById('gpStop').disabled = !st.running;
  document.getElementById('gpUninstall').disabled = !st.installed || st.running;
}
function setGopeedBtnsDisabled(dis) {
  ['gpInstall', 'gpStart', 'gpStop', 'gpUninstall'].forEach(id => {
    const b = document.getElementById(id);
    if (b) b.disabled = dis;
  });
}
async function gopeedInstall() {
  await saveSettings(); // 先把本页设置（含工具安装代理）落盘再安装，避免填了代理没保存就点安装
  hideMsg('gopeedMsg');
  showMsg('gopeedMsg', '正在从 Gopeed 官方下载并安装内核（约 20MB），请稍候…', true);
  setGopeedBtnsDisabled(true);
  const r = await api('/api/gopeed/install', 'POST', {});
  showMsg('gopeedMsg', r.ok ? '内核安装完成，点「启动」即可使用磁力下载' : (r.message || '安装失败'), r.ok);
  loadGopeedStatus();
}
async function gopeedStart() {
  hideMsg('gopeedMsg');
  setGopeedBtnsDisabled(true);
  const r = await api('/api/gopeed/start', 'POST', {});
  showMsg('gopeedMsg', r.ok ? '引擎已启动' : (r.message || '启动失败'), r.ok);
  loadGopeedStatus();
}
async function gopeedStop() {
  hideMsg('gopeedMsg');
  setGopeedBtnsDisabled(true);
  const r = await api('/api/gopeed/stop', 'POST', {});
  showMsg('gopeedMsg', r.ok ? '引擎已停止' : (r.message || '停止失败'), r.ok);
  loadGopeedStatus();
}
async function gopeedUninstall() {
  if (!confirm('卸载 Gopeed 内核？已下载的磁力任务记录会保留（在引擎存储里），重装内核后可继续。')) return;
  hideMsg('gopeedMsg');
  setGopeedBtnsDisabled(true);
  const r = await api('/api/gopeed/uninstall', 'POST', {});
  showMsg('gopeedMsg', r.ok ? '内核已卸载' : (r.message || '卸载失败'), r.ok);
  loadGopeedStatus();
}

// ---------- 设置 ----------
// 设置页平台分组（组标题 + 组内条目渲染；存取仍走 platformConnections/platformConcurrentTasks 两张表）
const PLATFORM_GROUPS = [
  { title: '网盘', items: [
    ['quark', '夸克'], ['uc', 'UC'], ['xunlei', '迅雷'],
    ['baidu', '百度'], ['pan123', '123'], ['c139', '139'],
    ['lanzou', '蓝奏云'], ['ilanzou', '优享版'], ['guangya', '光鸭'], ['pan115', '115']
  ] },
  { title: '视频', items: [['video', '视频解析']] },
  { title: '其他', items: [['github', 'GitHub']] }
];
const ALL_PLATFORMS_CONN = PLATFORM_GROUPS.flatMap(g => g.items);
async function loadSettings() {
  const r = await api('/api/settings');
  if (!r.ok) return;
  const s = r.data;
  const pc = s.platformConnections || {};
  const THREAD_OPTS = [1, 2, 4, 8, 16, 32, 64, 128, 256];
  document.getElementById('platformConnRows').innerHTML = PLATFORM_GROUPS.map(g =>
    `<div class="pgroup-title">${g.title}</div>` + g.items.map(([id, name]) => {
      const v = pc[id] || (id === 'xunlei' ? 8 : 32);
      const note = id === 'xunlei' ? ' <span class="hint">默认 8，可调</span>' : '';
      return `<div class="connrow"><span class="connname">${name}${g.title === '网盘' ? '网盘' : ''}${note}</span><div class="setctl"><select id="pc-${id}">` +
        THREAD_OPTS.map(o => `<option value="${o}"${o === v ? ' selected' : ''}>${o}</option>`).join('') +
        `</select><span class="unit">线程</span></div></div>`;
    }).join('')
  ).join('');
  const pt = s.platformConcurrentTasks || {};
  document.getElementById('platformConcInputs').innerHTML = PLATFORM_GROUPS.map(g =>
    `<div class="pconn-group">${g.title}</div>` + g.items.map(([id, name]) =>
      `<span class="pconn"><i>${name}</i><input type="text" id="pt-${id}" inputmode="numeric" placeholder="全局" value="${pt[id] || ''}"></span>`
    ).join('')
  ).join('');
  document.getElementById('setConc').value = s.maxConcurrentTasks;
  document.getElementById('setLimit').value = (s.speedLimitBps / 1048576).toFixed(1);
  document.getElementById('setRetry').value = s.maxRetries;
  document.getElementById('setAutoRetry').checked = s.autoRetry !== false;
  document.getElementById('setAutoRetryMax').value = s.autoRetryMax ?? 10;
  document.getElementById('setQuarkNoSave').checked = s.quarkNoSave !== false;
  document.getElementById('setDir').value = s.downloadDir || '';
  document.getElementById('setFfmpegPath').value = s.ffmpegPath || '';
  document.getElementById('setYtdlpPath').value = s.ytdlpPath || '';
  document.getElementById('setYtdlpProxy').value = s.ytdlpProxy || '';
  document.getElementById('setToolProxy').value = s.toolProxy || '';
  document.getElementById('setNameTemplate').value = s.videoNameTemplate || '{title}';
  loadGopeedStatus();
  refreshFfmpegCard();
  refreshYtdlpCard();
}
async function saveSettings() {
  hideMsg('setMsg');
  const platformConnections = {};
  ALL_PLATFORMS_CONN.forEach(([id]) => {
    const el = document.getElementById('pc-' + id);
    if (el) platformConnections[id] = parseInt(el.value) || (id === 'xunlei' ? 8 : 32);
  });
  const platformConcurrentTasks = {};
  ALL_PLATFORMS_CONN.forEach(([id]) => {
    const v = parseInt(document.getElementById('pt-' + id).value);
    if (v > 0) platformConcurrentTasks[id] = Math.min(64, v);
  });
  const s = {
    platformConnections,
    platformConcurrentTasks,
    maxConcurrentTasks: Math.max(1, parseInt(document.getElementById('setConc').value) || 3),
    speedLimitBps: Math.round((parseFloat(document.getElementById('setLimit').value) || 0) * 1048576),
    maxRetries: Math.max(0, parseInt(document.getElementById('setRetry').value) || 0),
    autoRetry: document.getElementById('setAutoRetry').checked,
    autoRetryMax: Math.max(0, parseInt(document.getElementById('setAutoRetryMax').value) || 0),
    quarkNoSave: document.getElementById('setQuarkNoSave').checked,
    ffmpegPath: document.getElementById('setFfmpegPath').value.trim(),
    ytdlpPath: document.getElementById('setYtdlpPath').value.trim(),
    ytdlpProxy: document.getElementById('setYtdlpProxy').value.trim(),
    toolProxy: document.getElementById('setToolProxy').value.trim(),
    videoNameTemplate: document.getElementById('setNameTemplate').value.trim() || '{title}',
    downloadDir: document.getElementById('setDir').value.trim()
  };
  const r = await api('/api/settings', 'PUT', s);
  if (r.ok) { ffmpegState = undefined; ytdlpState = undefined; } // 路径可能已变，后端按新值重探，前端缓存同步失效
  showMsg('setMsg', r.ok ? '设置已保存并即时生效' : (r.message || '保存失败'), r.ok);
}


async function resetSettings() {
  if (!confirm('把所有设置恢复为默认值？（下载目录保留不动）')) return;
  hideMsg('setMsg');
  const r = await api('/api/settings/reset', 'POST', {});
  if (r.ok) { await loadSettings(); showMsg('setMsg', '已恢复默认设置', true); }
  else showMsg('setMsg', r.message || '恢复失败', false);
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

// ---- 更新日志弹窗 ----
function showChangelog() {
  document.getElementById('logModal').style.display = 'flex';
}
function hideChangelog() {
  document.getElementById('logModal').style.display = 'none';
}

// ---------- 解析历史 / 收藏 ----------
let histById = {};
function fmtTime(ts) {
  const d = new Date(ts);
  return d.toLocaleDateString() + ' ' + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}
function updateFavBtn() {
  const b = document.getElementById('favBtn');
  if (!curHistoryId) { b.style.display = 'none'; return; }
  b.style.display = '';
  b.textContent = curHistoryFav ? '⭐ 已收藏' : '☆ 收藏';
}
async function toggleFavCurrent() {
  if (!curHistoryId) return;
  const r = await api('/api/history/' + curHistoryId + '/favorite', 'POST', { favorite: !curHistoryFav });
  if (r.ok) { curHistoryFav = r.data.favorite; updateFavBtn(); loadFavorites(); }
}
async function loadFavorites() {
  const r = await api('/api/history');
  if (!r.ok) return;
  r.data.forEach(e => { histById[e.id] = e; });
  const favs = r.data.filter(e => e.favorite);
  const card = document.getElementById('favCard');
  if (!favs.length) { card.style.display = 'none'; return; }
  card.style.display = 'block';
  document.getElementById('favChips').innerHTML = favs.map(e =>
    `<button class="favchip" title="${escapeHtml(e.link)}" onclick="reparseHist('${e.id}')">⭐ ${escapeHtml(e.title || e.link)}</button>`
  ).join('');
}
async function loadHistory() {
  const r = await api('/api/history');
  const body = document.getElementById('histBody');
  if (!r.ok) { body.innerHTML = '<div class="empty">加载失败</div>'; return; }
  r.data.forEach(e => { histById[e.id] = e; });
  if (!r.data.length) { body.innerHTML = '<div class="empty">暂无解析记录，解析过的分享会自动出现在这里</div>'; return; }
  const item = e => `
    <div class="hist">
      <div class="hmain">
        <div class="htitle">${e.favorite ? '⭐ ' : ''}${escapeHtml(e.title || '(无标题)')}</div>
        <div class="hmeta">${escapeHtml(e.platformName)} · ${fmtTime(e.createdAt)} · ${escapeHtml(e.link)}</div>
      </div>
      <div class="hacts">
        <button class="btn small" onclick="reparseHist('${e.id}')">解析</button>
        <button class="ghost small" onclick="toggleFavHist('${e.id}', ${!e.favorite})">${e.favorite ? '取消收藏' : '收藏'}</button>
        <button class="danger small" onclick="delHist('${e.id}')">删除</button>
      </div>
    </div>`;
  const favs = r.data.filter(e => e.favorite);
  const rest = r.data.filter(e => !e.favorite);
  let html = '';
  if (favs.length) html += '<div class="histsec">⭐ 收藏</div>' + favs.map(item).join('');
  if (rest.length) html += '<div class="histsec">🕘 最近解析</div>' + rest.map(item).join('');
  body.innerHTML = html;
}
function reparseHist(id) {
  const e = histById[id];
  if (!e) return;
  document.getElementById('shareLink').value = e.link;
  document.getElementById('sharePwd').value = e.pwd || '';
  document.querySelector('nav.tabs button[data-tab="parse"]').click();
  parseShare();
}
async function toggleFavHist(id, fav) {
  const r = await api('/api/history/' + id + '/favorite', 'POST', { favorite: fav });
  if (r.ok) { loadHistory(); loadFavorites(); }
}
async function delHist(id) {
  const r = await api('/api/history/' + id, 'DELETE');
  if (r.ok) { delete histById[id]; loadHistory(); loadFavorites(); }
}
async function clearHistory() {
  if (!confirm('清空全部非收藏的解析历史？')) return;
  const r = await api('/api/history/clear', 'POST', {});
  if (r.ok) { loadHistory(); }
}
loadFavorites();

// ---------- 通用输入弹窗 ----------
let inputResolve = null;
function askInput(title, initial) {
  return new Promise(res => {
    inputResolve = res;
    document.getElementById('inputTitle').textContent = title;
    const v = document.getElementById('inputValue');
    v.value = initial || '';
    document.getElementById('inputModal').style.display = 'flex';
    setTimeout(() => { v.focus(); v.select(); }, 30);
  });
}
function inputOk() {
  const v = document.getElementById('inputValue').value;
  document.getElementById('inputModal').style.display = 'none';
  if (inputResolve) { inputResolve(v); inputResolve = null; }
}
function inputCancel() {
  document.getElementById('inputModal').style.display = 'none';
  if (inputResolve) { inputResolve(null); inputResolve = null; }
}
function openDrive(platform) {
  if (platform && platform !== drivePlatform) {
    drivePlatform = platform;
    driveStack = [{ fid: '', name: '根目录' }];
  }
  document.querySelector('nav.tabs button[data-tab="drive"]').click();
}

// ---------- 网盘文件（个人盘：蓝奏云 / 蓝奏云优享版 / 光鸭云盘 / 115 网盘 / 夸克 / UC） ----------
const DRIVE_PLATFORMS = [
  ['lanzou', '蓝奏云'], ['ilanzou', '蓝奏云优享版'], ['guangya', '光鸭云盘'], ['pan115', '115 网盘'],
  ['quark', '夸克网盘'], ['uc', 'UC 网盘']
];
let drivePlatform = 'lanzou';
let driveStack = [{ fid: '', name: '根目录' }];
let driveItems = [];
function drivePlatformName() {
  const f = DRIVE_PLATFORMS.find(([id]) => id === drivePlatform);
  return f ? f[1] : drivePlatform;
}
function renderDrivePlatSwitch() {
  const el = document.getElementById('drivePlatSwitch');
  if (!el) return;
  el.innerHTML = DRIVE_PLATFORMS.map(([id, name]) =>
    `<button class="ghost small${id === drivePlatform ? ' on' : ''}" onclick="setDrivePlatform('${id}')">${name}</button>`).join('');
}
function setDrivePlatform(p) {
  if (p !== drivePlatform) {
    drivePlatform = p;
    driveStack = [{ fid: '', name: '根目录' }];
  }
  renderDrivePlatSwitch();
  loadDrive();
}
function driveCurFid() { return driveStack[driveStack.length - 1].fid; }
function renderDriveCrumb() {
  document.getElementById('driveCrumb').innerHTML = driveStack.map((c, i) =>
    (i ? ' <span class="csep">›</span> ' : '') +
    `<button class="ghost small" onclick="driveCrumbTo(${i})">${escapeHtml(c.name)}</button>`).join('');
}
function driveCrumbTo(n) { driveStack = driveStack.slice(0, n + 1); loadDrive(); }
function driveRefresh() { loadDrive(); }
async function loadDrive() {
  const list = document.getElementById('driveList');
  list.innerHTML = '<p class="hint">加载中…</p>';
  renderDrivePlatSwitch();
  renderDriveCrumb();
  document.getElementById('driveShareBox').innerHTML = '';
  const r = await api('/api/drive/' + drivePlatform + '/list?dirFid=' + encodeURIComponent(driveCurFid()));
  if (!r.ok) {
    list.innerHTML = '';
    showMsg('driveMsg', (r.message || '加载失败') + '（请先在「账号」页登录' + drivePlatformName() + '）', false);
    return;
  }
  hideMsg('driveMsg');
  driveItems = r.data || [];
  if (!driveItems.length) { list.innerHTML = '<p class="hint">这个文件夹是空的</p>'; return; }
  list.innerHTML = driveItems.map((f, i) => `
    <div class="drow">
      <span class="dic">${f.isdir ? '📁' : '📄'}</span>
      ${f.isdir ? `<button class="dname link" onclick="driveEnter(${i})">${escapeHtml(f.fname)}</button>` : `<span class="dname">${escapeHtml(f.fname)}</span>`}
      <span class="dmeta">${f.isdir ? '' : fmtSize(f.fsize)}${f.modifyTime ? ' · ' + escapeHtml(f.modifyTime) : ''}</span>
      <span class="dacts">
        ${f.isdir ? '' : `<button class="ghost small" onclick="driveDownload(${i})">下载</button>`}
        <button class="ghost small" onclick="driveShare(${i})">分享</button>
        <button class="ghost small" onclick="driveRename(${i})">重命名</button>
        <button class="ghost small danger" onclick="driveDelete(${i})">删除</button>
      </span>
    </div>`).join('');
}
function driveEnter(i) { driveStack.push({ fid: driveItems[i].fid, name: driveItems[i].fname }); loadDrive(); }
async function driveDownload(i) {
  const f = driveItems[i];
  const r = await api('/api/drive/' + drivePlatform + '/download', 'POST', { fid: f.fid, fname: f.fname, fidToken: f.fidToken || '' });
  if (r.ok) showMsg('driveMsg', '已加入下载：' + f.fname, true);
  else showMsg('driveMsg', r.message || '下载失败', false);
}
async function driveShare(i) {
  const f = driveItems[i];
  const r = await api('/api/drive/' + drivePlatform + '/share', 'POST', { isDir: f.isdir, id: f.fid, fname: f.fname });
  if (!r.ok) { showMsg('driveMsg', r.message || '生成分享失败', false); return; }
  const box = document.getElementById('driveShareBox');
  const txt = r.data.shareUrl + (r.data.passcode ? '  提取码：' + r.data.passcode : '');
  const warn = r.data.warning ? `<div class="hint" style="margin-top:4px">⚠️ ${escapeHtml(r.data.warning)}</div>` : '';
  box.innerHTML = `<div class="sharebox"><span>「${escapeHtml(f.fname)}」分享链接：<b>${escapeHtml(r.data.shareUrl)}</b>${r.data.passcode ? ' · 提取码 <b>' + escapeHtml(r.data.passcode) + '</b>' : ''}${warn}</span><span class="row" style="gap:8px"><button class="ghost small" onclick="navigator.clipboard.writeText('${txt.replace(/'/g, "")}').then(()=>showMsg('driveMsg','已复制分享链接',true))">复制</button><button class="ghost small" onclick="document.getElementById('driveShareBox').innerHTML=''">关闭</button></span></div>`;
}
async function driveRename(i) {
  const f = driveItems[i];
  const name = await askInput('重命名' + (f.isdir ? '文件夹' : '文件'), f.fname);
  if (name === null || !name.trim() || name.trim() === f.fname) return;
  const r = await api('/api/drive/' + drivePlatform + '/rename', 'POST', { isDir: f.isdir, id: f.fid, name: name.trim() });
  if (r.ok) { showMsg('driveMsg', '已重命名', true); loadDrive(); }
  else showMsg('driveMsg', r.message || '重命名失败', false);
}
async function driveDelete(i) {
  const f = driveItems[i];
  if (!confirm('确定删除「' + f.fname + '」？' + (f.isdir ? '文件夹内的文件也会一并删除。' : ''))) return;
  const r = await api('/api/drive/' + drivePlatform + '/delete', 'POST', { isDir: f.isdir, id: f.fid, pdirFid: f.pdirFid || '' });
  if (r.ok) { showMsg('driveMsg', '已删除', true); loadDrive(); }
  else showMsg('driveMsg', r.message || '删除失败', false);
}
async function driveNewFolder() {
  const name = await askInput('新建文件夹', '');
  if (name === null || !name.trim()) return;
  const r = await api('/api/drive/' + drivePlatform + '/folder', 'POST', { parentId: driveCurFid(), name: name.trim() });
  if (r.ok) { showMsg('driveMsg', '已新建文件夹', true); loadDrive(); }
  else showMsg('driveMsg', r.message || '新建失败', false);
}

// ---------- ffmpeg（B站分轨合轨支持；状态缓存供视频卡徽标与设置页共用） ----------
let ffmpegState = undefined; // undefined=未拉取，否则为 {available, version, path, source}
const FFMPEG_SOURCE_TEXT = { manual: '手动指定路径', app: '本应用托管安装', path: '系统 PATH', system: '系统安装路径' };
async function fetchFfmpegState(force) {
  if (ffmpegState !== undefined && !force) return ffmpegState;
  const r = await api('/api/video/ffmpeg');
  ffmpegState = r.ok ? r.data : { available: false };
  return ffmpegState;
}
/** 设置页合轨卡按状态分叉：已检测到只显示状态；未检测到才露出一键安装与手动说明 */
async function refreshFfmpegCard() {
  const stEl = document.getElementById('ffmpegStatus');
  if (!stEl) return;
  const st = await fetchFfmpegState(true);
  const box = document.getElementById('ffmpegInstallBox');
  const manual = document.getElementById('ffmpegManualHint');
  if (st.available) {
    stEl.textContent = `已检测到 ffmpeg v${st.version || '?'}（来源：${FFMPEG_SOURCE_TEXT[st.source] || st.source || '未知'}）· ${st.path}`;
    box.style.display = 'none';
    manual.style.display = 'none';
  } else {
    stEl.textContent = '未检测到 ffmpeg';
    box.style.display = '';
    manual.style.display = '';
  }
}
async function ffmpegInstall() {
  const btn = document.getElementById('ffmpegInstallBtn');
  btn.disabled = true;
  const oldText = btn.textContent;
  btn.textContent = '正在下载安装…';
  hideMsg('ffmpegMsg');
  try {
    await saveSettings(); // 先把本页设置（含工具安装代理）落盘再安装，避免填了代理没保存就点安装
    const r = await api('/api/video/ffmpeg/install', 'POST', {});
    if (r.ok) {
      ffmpegState = r.data;
      showMsg('ffmpegMsg', 'ffmpeg 安装完成：v' + (r.data.version || '') + '（' + (r.data.path || '') + '）', true);
    } else {
      showMsg('ffmpegMsg', r.message || '安装失败', false);
    }
  } finally {
    btn.disabled = false;
    btn.textContent = oldText;
    refreshFfmpegCard();
    if (curVideoMedias.length) renderVideoList();
  }
}

// ---------- 视频解析 ----------
let curVideoSession = null, curVideoMedias = [], curVideoList = null;
async function parseVideoLink(link) {
  const r = await api('/api/video/parse', 'POST', { link });
  if (!r.ok) { showMsg('parseMsg', r.message || '视频解析失败', false); return; }
  document.getElementById('fileCard').style.display = 'none';
  document.getElementById('githubCard').style.display = 'none';
  document.getElementById('youtubeCard').style.display = 'none';
  curVideoSession = r.data.sessionId;
  curVideoMedias = r.data.medias || [];
  curVideoList = r.data.listInfo || null;
  document.getElementById('videoCard').style.display = 'block';
  document.getElementById('videoTitle').textContent = r.data.title || '视频';
  document.getElementById('videoPlat').textContent = r.data.platformName || r.data.platform || '';
  document.getElementById('videoAuthor').textContent = r.data.author ? '作者：' + r.data.author : '';
  document.getElementById('videoText').textContent = r.data.text || '';
  document.getElementById('videoNotice').textContent = r.data.notice || '';
  const cover = document.getElementById('videoCover');
  if (r.data.cover) { cover.src = r.data.cover; cover.style.display = 'block'; } else cover.style.display = 'none';
  await fetchFfmpegState();
  renderVideoList();
  renderSeasonBox();
  showMsg('parseMsg', '解析成功', true);
}
function renderVideoList() {
  const el = document.getElementById('videoList');
  if (!curVideoMedias.length) { el.innerHTML = '<div class="empty">没有可下载的媒体</div>'; return; }
  el.innerHTML = curVideoMedias.map(m => {
    const icon = m.kind === 'IMAGE' ? '🖼️' : m.kind === 'AUDIO' ? '🎵' : '🎬';
    const muxTag = !m.needsMux ? '' : (ffmpegState && ffmpegState.available
      ? '<span class="badge on" title="音视频分轨：两轨下完后由 ffmpeg 自动合成为单个 mp4">高清 · 自动合轨</span>'
      : '<span class="badge" title="音视频分轨：未检测到 ffmpeg，会生成视频轨与 .audio.m4s 音轨两个文件，需自行合轨（可在设置页一键安装 ffmpeg）">分轨（双文件）</span>');
    const tags = [
      m.quality ? `<span class="badge">${escapeHtml(m.quality)}</span>` : '',
      m.isHls ? '<span class="badge">HLS</span>' : '',
      muxTag
    ].join(' ');
    return `<div class="drow">
      <input type="checkbox" class="vck" data-i="${m.index}" checked onchange="updateVideoSel()">
      <span class="dic">${icon}</span>
      <span class="dname">${escapeHtml(m.fileName)} ${tags}</span>
      <span class="dmeta">${m.size > 0 ? fmtSize(m.size) : '大小未知'}</span>
    </div>`;
  }).join('');
  updateVideoSel();
}
function updateVideoSel() {
  const n = document.querySelectorAll('.vck:checked').length;
  document.getElementById('videoSelInfo').textContent = n ? `已选 ${n} 项` : '';
}
function toggleVideoAll(on) {
  document.querySelectorAll('.vck').forEach(c => { c.checked = on; });
  updateVideoSel();
}
async function downloadVideoSelected() {
  const indexes = [...document.querySelectorAll('.vck:checked')].map(c => parseInt(c.dataset.i));
  if (!indexes.length) { showMsg('videoMsg', '请先勾选要下载的媒体', false); return; }
  const btn = document.getElementById('videoDlBtn');
  btn.disabled = true;
  try {
    const r = await api('/api/video/download', 'POST', { sessionId: curVideoSession, indexes });
    if (!r.ok) { showMsg('videoMsg', r.message || '提交下载失败', false); return; }
    showMsg('videoMsg', `已提交 ${r.data.taskIds.length} 个下载任务`, true);
    if (curVideoMedias.some(m => m.needsMux && indexes.includes(m.index))) {
      if (ffmpegState && ffmpegState.available) {
        showMsg('videoMsg', '已提交下载；分轨媒体的两轨下完后会自动合轨为单个 mp4', true);
      } else {
        showMsg('videoMsg', '已提交下载；注意：分轨媒体会生成视频轨 + .audio.m4s 音轨两个文件，需自行合轨（设置页可一键安装 ffmpeg 开启自动合轨）', true);
      }
    }
  } finally { btn.disabled = false; }
}

// ---------- B站合集（所属列表：整单勾选批量下载，落「合集名」文件夹） ----------
function renderSeasonBox() {
  const box = document.getElementById('videoSeasonBox');
  const info = curVideoList;
  if (!info || !info.episodes || !info.episodes.length) { box.style.display = 'none'; return; }
  box.style.display = 'block';
  document.getElementById('videoSeasonTitle').textContent = `📺 所属合集：${info.title}（共 ${info.total} 集）`;
  document.getElementById('videoSeasonFolder').textContent =
    info.folder ? `批量下载将保存到下载目录的文件夹：${info.folder}` : '';
  document.getElementById('videoSeasonList').innerHTML = info.episodes.map(e =>
    `<div class="drow">
      <input type="checkbox" class="sck" data-i="${e.index}" checked onchange="updateSeasonSel()">
      <span class="dic">${e.index}</span>
      <span class="dname">${escapeHtml(e.title)}${e.index === info.currentIndex ? ' <span class="badge on">本集</span>' : ''}</span>
      <span class="dmeta">${e.duration > 0 ? fmtDuration(e.duration) : ''}</span>
    </div>`).join('');
  document.getElementById('videoSeasonResult').innerHTML = '';
  updateSeasonSel();
}
function updateSeasonSel() {
  const n = document.querySelectorAll('.sck:checked').length;
  document.getElementById('seasonSelInfo').textContent = n ? `已选 ${n} 集` : '';
  document.getElementById('seasonDlBtn').textContent = `下载所选 (${n})`;
}
function toggleSeasonAll(on) {
  document.querySelectorAll('.sck').forEach(c => { c.checked = on; });
  updateSeasonSel();
}
async function downloadSeasonSelected() {
  const indexes = [...document.querySelectorAll('.sck:checked')].map(c => parseInt(c.dataset.i));
  if (!indexes.length) { showMsg('videoMsg', '请先勾选要下载的集数', false); return; }
  const btn = document.getElementById('seasonDlBtn');
  btn.disabled = true;
  const old = btn.textContent;
  btn.textContent = '逐集解析提交中…';
  try {
    const r = await api('/api/video/list/download', 'POST', { sessionId: curVideoSession, indexes });
    if (!r.ok) { showMsg('videoMsg', r.message || '合集下载提交失败', false); return; }
    const results = r.data.results || [];
    const okN = results.filter(x => x.ok).length;
    showMsg('videoMsg',
      `合集提交完成：成功 ${okN} / 失败 ${results.length - okN}，文件保存到文件夹「${r.data.folder || ''}」（逐集结果见下）`,
      results.length - okN === 0);
    document.getElementById('videoSeasonResult').innerHTML = results.map(x =>
      x.ok
        ? `<div class="hint" style="color:#137333">✓ 第 ${x.index} 集 ${escapeHtml(x.title)}${x.message ? '：' + escapeHtml(x.message) : ''}</div>`
        : `<div class="hint" style="color:#b26a00">✗ 第 ${x.index} 集 ${escapeHtml(x.title)}：${escapeHtml(x.message || '失败')}</div>`
    ).join('');
  } finally { btn.disabled = false; btn.textContent = old; updateSeasonSel(); }
}

// ---------- GitHub 解析 ----------
let curGithubSession = null;
async function parseGithubLink(link) {
  const r = await api('/api/github/parse', 'POST', { link });
  if (!r.ok) { showMsg('parseMsg', r.message || 'GitHub 解析失败', false); return; }
  document.getElementById('fileCard').style.display = 'none';
  document.getElementById('videoCard').style.display = 'none';
  document.getElementById('youtubeCard').style.display = 'none';
  curGithubSession = r.data.sessionId;
  document.getElementById('githubCard').style.display = 'block';
  document.getElementById('githubTitle').textContent = r.data.title || 'GitHub';
  const kindIcon = k => k === 'zip' ? '🗜️' : k === 'release' ? '📦' : '📄';
  document.getElementById('githubList').innerHTML = (r.data.items || []).map(it =>
    `<div class="drow">
      <span class="dic">${kindIcon(it.kind)}</span>
      <span class="dname">${escapeHtml(it.name)}</span>
      <span class="dmeta">${it.size > 0 ? fmtSize(it.size) : '大小未知'}</span>
      <span class="dacts"><button class="ghost small" onclick="downloadGithubItem(${it.index})">下载</button></span>
    </div>`).join('') || '<div class="empty">没有可下载的内容</div>';
  showMsg('parseMsg', '解析成功', true);
}
async function downloadGithubItem(index) {
  const r = await api('/api/github/download', 'POST', { sessionId: curGithubSession, indexes: [index] });
  showMsg('githubMsg', r.ok ? '已提交下载' : (r.message || '提交失败'), r.ok);
}
async function downloadGithubAll() {
  const r = await api('/api/github/download', 'POST', { sessionId: curGithubSession, indexes: [] });
  showMsg('githubMsg', r.ok ? `已提交 ${r.data.taskIds.length} 个下载任务` : (r.message || '提交失败'), r.ok);
}

// ---------- YouTube 解析（yt-dlp 托管工具路线） ----------
let curYtSession = null;
const YT_PLAYLIST_SUB_LANGS = [['zh-Hans', '中文（简体）'], ['zh-Hant', '中文（繁体）'], ['en', 'English'], ['ja', '日本語'], ['ko', '한국어']];
function fmtDuration(sec) {
  if (sec === null || sec === undefined || sec < 0) return '';
  sec = Math.round(sec);
  const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60;
  return (h ? h + ':' : '') + String(m).padStart(2, '0') + ':' + String(s).padStart(2, '0');
}
async function parseYoutubeLink(link) {
  document.getElementById('fileCard').style.display = 'none';
  document.getElementById('videoCard').style.display = 'none';
  document.getElementById('githubCard').style.display = 'none';
  const r = await api('/api/youtube/parse', 'POST', { link });
  const card = document.getElementById('youtubeCard');
  if (!r.ok) {
    if ((r.message || '').includes('未检测到 yt-dlp')) {
      // 未装 yt-dlp：显示卡内引导（去设置页一键安装），而不是只报一句错
      card.style.display = 'block';
      document.getElementById('ytTitle').textContent = 'YouTube';
      document.getElementById('ytVideoBox').style.display = 'none';
      document.getElementById('ytListBox').style.display = 'none';
      const g = document.getElementById('ytGuide');
      g.style.display = 'block';
      g.innerHTML = '<p class="hint" style="color:#b26a00;margin:6px 0">解析 YouTube 需要 yt-dlp（解析器工具），本机尚未安装。到「设置」页「▶️ YouTube 解析（yt-dlp）」卡点「一键安装」即可——下载的是官方单文件二进制，装在应用自己的数据目录里，不改动系统。</p>' +
        '<button class="btn small" onclick="document.querySelector(\'nav.tabs button[data-tab=&quot;settings&quot;]\').click()">去设置页安装</button>';
      showMsg('parseMsg', '未检测到 yt-dlp，请先安装', false);
      return;
    }
    card.style.display = 'none';
    showMsg('parseMsg', r.message || 'YouTube 解析失败', false);
    return;
  }
  document.getElementById('ytGuide').style.display = 'none';
  curYtSession = r.data.sessionId;
  card.style.display = 'block';
  document.getElementById('ytTitle').textContent = r.data.title || 'YouTube';
  if (r.data.kind === 'playlist') renderYtPlaylist(r.data); else renderYtVideo(r.data);
  showMsg('parseMsg', '解析成功', true);
}
function ytSubsHtml(subs, prefix) {
  if (!subs || !subs.length) return '<div class="hint">字幕：该视频无字幕</div>';
  const off = subs.filter(s => !s.auto), auto = subs.filter(s => s.auto);
  const box = s => `<label class="subck"><input type="checkbox" class="${prefix}-sub" data-lang="${escapeHtml(s.lang)}" data-auto="${s.auto ? 1 : 0}"> ${escapeHtml(s.name || s.lang)}${s.auto ? '（自动生成）' : ''} <span class="hint">${escapeHtml(s.lang)}</span></label>`;
  let h = '';
  if (off.length) h += '<div class="hint" style="margin-top:4px">字幕（与视频同名下载，文件名后缀 .语言.vtt）</div><div class="subs">' + off.map(box).join('') + '</div>';
  if (auto.length) h += '<div class="hint" style="margin-top:4px">自动字幕</div><div class="subs">' + auto.map(box).join('') + '</div>';
  return h;
}
function renderYtVideo(d) {
  document.getElementById('ytListBox').style.display = 'none';
  document.getElementById('ytVideoBox').style.display = 'block';
  const cover = document.getElementById('ytCover');
  if (d.cover) { cover.src = d.cover; cover.style.display = 'block'; } else cover.style.display = 'none';
  document.getElementById('ytAuthor').textContent = d.author ? '作者：' + d.author : '';
  const meta = [fmtDuration(d.duration) ? '时长 ' + fmtDuration(d.duration) : '', d.formatsSummary || ''].filter(Boolean).join(' · ');
  document.getElementById('ytMeta').textContent = meta;
  document.getElementById('ytFormatsSummary').textContent = d.formatsSummary || '';
  document.getElementById('ytQuality').innerHTML =
    (d.qualities || []).map(q => `<option value="${q.height}">${escapeHtml(q.label)}</option>`).join('') ||
    '<option value="0">最高画质</option>';
  document.getElementById('ytSubsBox').innerHTML = ytSubsHtml(d.subtitles, 'yt');
  hideMsg('ytMsg');
}
function ytCollectSubs(prefix) {
  const boxes = [...document.querySelectorAll('.' + prefix + '-sub:checked')];
  return { subLangs: boxes.map(c => c.dataset.lang), includeAuto: boxes.some(c => c.dataset.auto === '1') };
}
async function downloadYoutube() {
  const btn = document.getElementById('ytDlBtn');
  btn.disabled = true;
  try {
    const { subLangs, includeAuto } = ytCollectSubs('yt');
    const r = await api('/api/youtube/download', 'POST', {
      sessionId: curYtSession,
      targetHeight: parseInt(document.getElementById('ytQuality').value) || 0,
      subLangs, includeAuto
    });
    if (!r.ok) { showMsg('ytMsg', r.message || '提交下载失败', false); return; }
    showMsg('ytMsg', `已提交 ${r.data.taskIds.length} 个下载任务` + (r.data.note ? '；' + r.data.note : ''), true);
  } finally { btn.disabled = false; }
}
function renderYtPlaylist(d) {
  document.getElementById('ytVideoBox').style.display = 'none';
  document.getElementById('ytListBox').style.display = 'block';
  document.getElementById('ytListFolder').textContent =
    d.folder ? `将下载到文件夹：${d.folder}` : '';
  document.getElementById('ytListQuality').innerHTML =
    (d.qualities || []).map(q => `<option value="${q.height}">${escapeHtml(q.label)}</option>`).join('');
  document.getElementById('ytListSubsBox').innerHTML =
    '<div class="hint">字幕（逐条解析后按各视频实际有的轨道下载；某条没有所选语言会自动跳过）</div><div class="subs">' +
    YT_PLAYLIST_SUB_LANGS.map(([lang, name]) =>
      `<label class="subck"><input type="checkbox" class="ytl-sub" data-lang="${lang}" data-auto="0"> ${name} <span class="hint">${lang}</span></label>`).join('') +
    '<label class="subck"><input type="checkbox" id="ytListAutoSub"> 无官方字幕时取自动字幕</label></div>';
  document.getElementById('ytEntryList').innerHTML = (d.entries || []).map(e =>
    `<div class="drow">
      <input type="checkbox" class="ytck" data-id="${escapeHtml(e.id)}" checked onchange="updateYtListSel()">
      <span class="dic">${e.index + 1}</span>
      <span class="dname">${escapeHtml(e.title)}</span>
      <span class="dmeta">${fmtDuration(e.duration)}</span>
    </div>`).join('') || '<div class="empty">列表为空</div>';
  document.getElementById('ytListResult').innerHTML = '';
  hideMsg('ytMsg');
  updateYtListSel();
}
function updateYtListSel() {
  const n = document.querySelectorAll('.ytck:checked').length;
  document.getElementById('ytListSelInfo').textContent = n ? `已选 ${n} 个` : '';
  document.getElementById('ytListDlBtn').textContent = `下载所选 (${n})`;
}
function toggleYtListAll(on) {
  document.querySelectorAll('.ytck').forEach(c => { c.checked = on; });
  updateYtListSel();
}
async function downloadYoutubePlaylist() {
  const entryIds = [...document.querySelectorAll('.ytck:checked')].map(c => c.dataset.id);
  if (!entryIds.length) { showMsg('ytMsg', '请先勾选要下载的条目', false); return; }
  const btn = document.getElementById('ytListDlBtn');
  btn.disabled = true;
  const old = btn.textContent;
  btn.textContent = '逐条解析提交中…';
  try {
    const { subLangs } = ytCollectSubs('ytl');
    const autoEl = document.getElementById('ytListAutoSub');
    const r = await api('/api/youtube/playlist/download', 'POST', {
      sessionId: curYtSession,
      entryIds,
      targetHeight: parseInt(document.getElementById('ytListQuality').value) || 0,
      subLangs,
      includeAuto: !!(autoEl && autoEl.checked)
    });
    if (!r.ok) { showMsg('ytMsg', r.message || '提交下载失败', false); return; }
    const results = r.data.results || [];
    const okN = results.filter(x => x.ok).length;
    showMsg('ytMsg', `播放列表提交完成：成功 ${okN} / 失败 ${results.length - okN}（逐条结果见下）`, results.length - okN === 0);
    document.getElementById('ytListResult').innerHTML = results.map(x =>
      x.ok
        ? `<div class="hint" style="color:#137333">✓ ${escapeHtml(x.title)}${x.message ? '：' + escapeHtml(x.message) : ''}</div>`
        : `<div class="hint" style="color:#b26a00">✗ ${escapeHtml(x.title)}：${escapeHtml(x.message || '失败')}</div>`
    ).join('');
  } finally { btn.disabled = false; btn.textContent = old; updateYtListSel(); }
}

// ---------- yt-dlp（设置页工具卡，照 ffmpeg 卡模式；命名模板预设） ----------
let ytdlpState = undefined; // undefined=未拉取，否则为 {available, version, path, source}
const YTD_SOURCE_TEXT = { manual: '手动指定路径', app: '本应用托管安装', path: '系统 PATH' };
async function fetchYtdlpState(force) {
  if (ytdlpState !== undefined && !force) return ytdlpState;
  const r = await api('/api/youtube/ytdlp');
  ytdlpState = r.ok ? r.data : { available: false };
  return ytdlpState;
}
async function refreshYtdlpCard() {
  const stEl = document.getElementById('ytdlpStatus');
  if (!stEl) return;
  const st = await fetchYtdlpState(true);
  const box = document.getElementById('ytdlpInstallBox');
  const manual = document.getElementById('ytdlpManualHint');
  if (st.available) {
    stEl.textContent = `已检测到 yt-dlp ${st.version || ''}（来源：${YTD_SOURCE_TEXT[st.source] || st.source || '未知'}）· ${st.path}`;
    box.style.display = 'none';
    manual.style.display = 'none';
  } else {
    stEl.textContent = '未检测到 yt-dlp';
    box.style.display = '';
    manual.style.display = '';
  }
}
async function ytdlpInstall() {
  const btn = document.getElementById('ytdlpInstallBtn');
  btn.disabled = true;
  const oldText = btn.textContent;
  btn.textContent = '正在下载安装…';
  hideMsg('ytdlpMsg');
  try {
    await saveSettings(); // 先把本页设置（含工具安装代理）落盘再安装，避免填了代理没保存就点安装
    const r = await api('/api/youtube/ytdlp/install', 'POST', {});
    if (r.ok) {
      ytdlpState = r.data;
      showMsg('ytdlpMsg', 'yt-dlp 安装完成：' + (r.data.version || '') + '（' + (r.data.path || '') + '）', true);
    } else {
      showMsg('ytdlpMsg', r.message || '安装失败', false);
    }
  } finally {
    btn.disabled = false;
    btn.textContent = oldText;
    refreshYtdlpCard();
  }
}
function setNameTpl(t) { document.getElementById('setNameTemplate').value = t; }

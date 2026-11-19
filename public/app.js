(function () {
  'use strict';

  const $ = (id) => document.getElementById(id);
  const loginEl = $('login');
  const appEl = $('app');
  const tabsEl = $('tabs');
  const termsEl = $('terminals');
  const statusEl = $('status');

  const THEME = {
    background: '#300a24',
    foreground: '#ffffff',
    cursor: '#ffffff',
    selectionBackground: '#b5d5ff',
    selectionForeground: '#000000',
    black: '#2e3436', red: '#cc0000', green: '#4e9a06', yellow: '#c4a000',
    blue: '#3465a4', magenta: '#75507b', cyan: '#06989a', white: '#d3d7cf',
    brightBlack: '#555753', brightRed: '#ef2929', brightGreen: '#8ae234', brightYellow: '#fce94f',
    brightBlue: '#729fcf', brightMagenta: '#ad7fa8', brightCyan: '#34e2e2', brightWhite: '#eeeeec',
  };

  const tabs = new Map(); // id -> tab
  let activeId = null;
  const mods = { ctrl: false, alt: false };

  async function api(method, url, body) {
    const res = await fetch(url, {
      method,
      credentials: 'same-origin',
      headers: body ? { 'Content-Type': 'application/json' } : undefined,
      body: body ? JSON.stringify(body) : undefined,
    });
    let data = {};
    try { data = await res.json(); } catch (e) { /* empty */ }
    if (res.status === 401 && url !== 'api/login') {
      showLogin();
      throw new Error('Not logged in');
    }
    if (!res.ok) throw new Error(data.error || 'Request failed');
    return data;
  }

  // ------------------------------------------------------------ login
  function showLogin() {
    for (const tab of tabs.values()) destroyTab(tab);
    appEl.hidden = true;
    loginEl.hidden = false;
    $('password').focus();
  }

  loginEl.addEventListener('submit', async (e) => {
    e.preventDefault();
    $('login-error').textContent = '';
    try {
      await api('POST', 'api/login', { password: $('password').value });
      $('password').value = '';
      await start();
    } catch (err) {
      $('login-error').textContent = err.message;
    }
  });

  $('logout').addEventListener('click', async () => {
    await api('POST', 'api/logout');
    showLogin();
  });

  // ------------------------------------------------------------ tabs
  function setStatus() {
    const tab = tabs.get(activeId);
    const state = tab ? tab.state : 'off';
    statusEl.className = state === 'open' ? 'on' : state === 'closed' ? 'off' : '';
    statusEl.textContent = state === 'open' ? 'Connected' : state === 'closed' ? 'Disconnected' : 'Connecting…';
  }

  function addTab(info) {
    const term = new Terminal({
      cursorBlink: true,
      fontFamily: '"Ubuntu Mono", "DejaVu Sans Mono", Menlo, Consolas, monospace',
      fontSize: 15,
      scrollback: 10000,
      allowProposedApi: true,
      theme: THEME,
    });
    const fit = new FitAddon.FitAddon();
    term.loadAddon(fit);
    term.loadAddon(new WebLinksAddon.WebLinksAddon());

    const pane = document.createElement('div');
    pane.className = 'term';
    termsEl.appendChild(pane);
    term.open(pane);

    const button = document.createElement('div');
    button.className = 'tab';
    button.setAttribute('role', 'tab');
    const label = document.createElement('span');
    label.textContent = info.name;
    const close = document.createElement('button');
    close.className = 'close';
    close.type = 'button';
    close.title = 'Close tab (ends this shell)';
    close.setAttribute('aria-label', 'Close ' + info.name);
    close.textContent = '×';
    button.append(label, close);
    tabsEl.appendChild(button);

    const tab = { id: info.id, term, fit, pane, button, ws: null, state: 'connecting', retry: 0, timer: null, dead: false };
    tabs.set(tab.id, tab);

    button.addEventListener('click', () => activate(tab.id));
    close.addEventListener('click', async (e) => {
      e.stopPropagation();
      if (!confirm('Close this tab? Programs running in it will be stopped.')) return;
      try { await api('DELETE', 'api/sessions/' + tab.id); } catch (err) { /* ignore */ }
      removeTab(tab);
    });

    term.onData((data) => send(tab, { type: 'input', data: applyMods(data) }));
    term.onResize(({ cols, rows }) => send(tab, { type: 'resize', cols, rows }));
    term.onTitleChange((title) => { if (title) label.textContent = title.slice(0, 30); });

    connect(tab);
    return tab;
  }

  function send(tab, msg) {
    if (tab.ws && tab.ws.readyState === WebSocket.OPEN) tab.ws.send(JSON.stringify(msg));
  }

  function connect(tab) {
    if (tab.dead) return;
    tab.state = 'connecting';
    setStatus();
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const base = location.pathname.replace(/[^/]*$/, '');
    const ws = new WebSocket(`${proto}//${location.host}${base}ws?sid=${tab.id}`);
    tab.ws = ws;
    let first = true;

    ws.onopen = () => {
      tab.state = 'open';
      tab.retry = 0;
      setStatus();
      send(tab, { type: 'resize', cols: tab.term.cols, rows: tab.term.rows });
    };

    ws.onmessage = (ev) => {
      const msg = JSON.parse(ev.data);
      if (msg.type === 'output') {
        if (first) tab.term.reset(); // server replays history; avoid duplicates
        first = false;
        tab.term.write(msg.data);
      } else if (msg.type === 'exit') {
        removeTab(tab);
      }
    };

    ws.onclose = async () => {
      if (tab.dead || tab.ws !== ws) return;
      tab.state = 'closed';
      setStatus();
      // Find out why: logged out, shell gone, or just a network drop.
      try {
        const { sessions } = await api('GET', 'api/sessions');
        if (!sessions.some((s) => s.id === tab.id)) return removeTab(tab);
      } catch (e) {
        if (!loginEl.hidden) return;
      }
      tab.retry = Math.min(tab.retry + 1, 6);
      tab.timer = setTimeout(() => connect(tab), 500 * Math.pow(2, tab.retry - 1));
    };
  }

  function destroyTab(tab) {
    tab.dead = true;
    clearTimeout(tab.timer);
    if (tab.ws) tab.ws.close();
    tab.term.dispose();
    tab.pane.remove();
    tab.button.remove();
    tabs.delete(tab.id);
  }

  async function removeTab(tab) {
    if (tab.dead) return;
    destroyTab(tab);
    if (activeId === tab.id) {
      activeId = null;
      const next = [...tabs.keys()].pop();
      if (next) activate(next);
      else await newTab(); // always keep one shell open
    }
  }

  function activate(id) {
    activeId = id;
    for (const tab of tabs.values()) {
      const on = tab.id === id;
      tab.pane.classList.toggle('active', on);
      tab.button.classList.toggle('active', on);
      tab.button.setAttribute('aria-selected', String(on));
    }
    try { localStorage.setItem('termant-active', id); } catch (e) { /* ignore */ }
    const tab = tabs.get(id);
    if (tab) {
      refit();
      tab.term.focus();
      tab.button.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    }
    setStatus();
  }

  async function newTab() {
    try {
      const info = await api('POST', 'api/sessions');
      addTab(info);
      activate(info.id);
    } catch (err) {
      if (loginEl.hidden) alert(err.message);
    }
  }

  $('new-tab').addEventListener('click', newTab);

  function refit() {
    const tab = tabs.get(activeId);
    if (!tab || appEl.hidden) return;
    try { tab.fit.fit(); } catch (e) { /* not visible yet */ }
  }
  new ResizeObserver(refit).observe(termsEl);
  if (document.fonts && document.fonts.ready) document.fonts.ready.then(refit);

  // ------------------------------------------------------------ extra keys
  const SEQ = { esc: '\x1b', tab: '\t', up: '\x1b[A', down: '\x1b[B', right: '\x1b[C', left: '\x1b[D' };

  function applyMods(data) {
    if (!mods.ctrl && !mods.alt) return data;
    if (data.length === 1) {
      if (mods.ctrl) {
        const c = data.toUpperCase().charCodeAt(0);
        if (c >= 63 && c <= 95) data = String.fromCharCode(c === 63 ? 127 : c - 64);
        else if (data === ' ') data = '\x00';
      }
      if (mods.alt) data = '\x1b' + data;
    }
    mods.ctrl = mods.alt = false;
    updateMods();
    return data;
  }

  function updateMods() {
    for (const b of document.querySelectorAll('#keys [data-mod]')) {
      const on = mods[b.dataset.mod];
      b.classList.toggle('on', on);
      b.setAttribute('aria-pressed', String(on));
    }
  }

  $('keys').addEventListener('pointerdown', (e) => e.preventDefault()); // keep keyboard open
  $('keys').addEventListener('click', (e) => {
    const b = e.target.closest('button');
    const tab = tabs.get(activeId);
    if (!b || !tab) return;
    if (b.dataset.mod) {
      mods[b.dataset.mod] = !mods[b.dataset.mod];
      updateMods();
    } else {
      const data = b.dataset.seq ? SEQ[b.dataset.seq] : b.dataset.text;
      send(tab, { type: 'input', data: applyMods(data) });
    }
    tab.term.focus();
  });

  // ------------------------------------------------------------ start
  async function start() {
    let sessions;
    try {
      ({ sessions } = await api('GET', 'api/sessions'));
    } catch (e) {
      return; // login form is showing
    }
    loginEl.hidden = true;
    appEl.hidden = false;
    if (!sessions.length) return newTab();
    sessions.forEach(addTab);
    let want = null;
    try { want = localStorage.getItem('termant-active'); } catch (e) { /* ignore */ }
    activate(tabs.has(want) ? want : sessions[0].id);
  }

  start();
})();

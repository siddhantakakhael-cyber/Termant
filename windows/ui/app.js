// Termant for Windows: everything the window shows.
(function () {
  'use strict';
  const api = window.termant;
  const $ = (id) => document.getElementById(id);
  let S = null;            // what the main process told us last
  let view = 'chat';
  let models = null;       // list of model names once loaded
  const terms = new Map(); // id -> { term, fit, box, name }
  let activeTerm = 0;
  let ringTimer = null, audio = null;

  // ---------------------------------------------------------------- small helpers

  function t(key, value) {
    const table = window.STRINGS[S && S.settings.lang === 'hi' ? 'hi' : 'en'];
    const text = table[key] || window.STRINGS.en[key] || key;
    return value === undefined ? text : text.replace('%s', value);
  }

  function icon(name) {
    const NS = 'http://www.w3.org/2000/svg';
    const svg = document.createElementNS(NS, 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('class', 'icon');
    svg.setAttribute('aria-hidden', 'true');
    const shape = window.ICONS[name] || window.ICONS.help;
    for (const d of shape.f) {
      const p = document.createElementNS(NS, 'path');
      p.setAttribute('d', d);
      p.setAttribute('class', 'fill');
      svg.appendChild(p);
    }
    for (const d of shape.s) {
      const p = document.createElementNS(NS, 'path');
      p.setAttribute('d', d);
      svg.appendChild(p);
    }
    return svg;
  }

  /** el('div', {class: 'x', onclick: f}, child, 'text', ...) */
  function el(tag, props, ...children) {
    const node = document.createElement(tag);
    for (const [key, value] of Object.entries(props || {})) {
      if (value === undefined || value === null || value === false) continue;
      if (key.startsWith('on')) node.addEventListener(key.slice(2), value);
      else if (key === 'class') node.className = value;
      else if (key === 'value') node.value = value;
      else node.setAttribute(key, value === true ? '' : value);
    }
    for (const child of children.flat(Infinity)) {
      if (child === null || child === undefined || child === false) continue;
      node.appendChild(typeof child === 'string' ? document.createTextNode(child) : child);
    }
    return node;
  }

  function clear(node) {
    while (node.firstChild) node.removeChild(node.firstChild);
    return node;
  }

  function toast(title, body) {
    const node = el('div', { class: 'toast', role: 'status' }, el('b', null, title || ''), body ? el('span', null, body) : null);
    $('toasts').appendChild(node);
    setTimeout(() => node.remove(), 5000);
  }

  async function call(name, argument) {
    try {
      return await api.call(name, argument);
    } catch (e) {
      toast('Termant', e.message);
      throw e;
    }
  }

  function timeText(ms) {
    const d = new Date(ms);
    const same = d.toDateString() === new Date().toDateString();
    return (same ? '' : d.toLocaleDateString(undefined, { day: 'numeric', month: 'short' }) + ' ') +
      d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', hour12: false });
  }

  // ---------------------------------------------------------------- answers: a little Markdown

  function inline(text, into) {
    const pattern = /(`[^`\n]+`)|(\*\*[^*\n]+\*\*)|(\[[^\]\n]+\]\(https?:\/\/[^\s)]+\))|(https?:\/\/[^\s<>)\]]+)/g;
    let last = 0, m;
    while ((m = pattern.exec(text))) {
      if (m.index > last) into.appendChild(document.createTextNode(text.slice(last, m.index)));
      if (m[1]) into.appendChild(el('code', null, m[1].slice(1, -1)));
      else if (m[2]) into.appendChild(el('b', null, m[2].slice(2, -2)));
      else if (m[3]) {
        const parts = /^\[([^\]]+)\]\((.+)\)$/.exec(m[3]);
        into.appendChild(link(parts[2], parts[1]));
      } else {
        const url = m[4].replace(/[.,;:!?]+$/, ''); // a full stop after a link belongs to the sentence
        into.appendChild(link(url, url));
        last = m.index + url.length;
        pattern.lastIndex = last;
        continue;
      }
      last = m.index + m[0].length;
    }
    if (last < text.length) into.appendChild(document.createTextNode(text.slice(last)));
    return into;
  }

  function link(url, text) {
    return el('a', { href: url, onclick: (e) => { e.preventDefault(); api.call('open:link', url); } }, text);
  }

  function markdown(text) {
    const out = document.createDocumentFragment();
    const lines = String(text || '').replace(/\r/g, '').split('\n');
    let i = 0;
    while (i < lines.length) {
      const line = lines[i];
      if (/^\s*```/.test(line)) {
        const code = [];
        i++;
        while (i < lines.length && !/^\s*```/.test(lines[i])) code.push(lines[i++]);
        i++;
        const body = code.join('\n');
        const copy = el('button', { class: 'copy', onclick: () => {
          navigator.clipboard.writeText(body);
          copy.textContent = t('copied');
          setTimeout(() => { copy.textContent = t('copy'); }, 1500);
        } }, t('copy'));
        out.appendChild(el('pre', null, copy, el('code', null, body)));
      } else if (/^\s*([-*•]|\d+[.)])\s+/.test(line)) {
        const ordered = /^\s*\d+[.)]\s+/.test(line);
        const list = el(ordered ? 'ol' : 'ul');
        while (i < lines.length && /^\s*([-*•]|\d+[.)])\s+/.test(lines[i])) {
          list.appendChild(inline(lines[i].replace(/^\s*([-*•]|\d+[.)])\s+/, ''), el('li')));
          i++;
        }
        out.appendChild(list);
      } else if (/^#{1,6}\s+/.test(line)) {
        out.appendChild(inline(line.replace(/^#{1,6}\s+/, ''), el('h3')));
        i++;
      } else if (!line.trim()) {
        i++;
      } else {
        const para = el('p');
        let first = true;
        while (i < lines.length && lines[i].trim() && !/^\s*```/.test(lines[i]) && !/^\s*([-*•]|\d+[.)])\s+/.test(lines[i]) && !/^#{1,6}\s+/.test(lines[i])) {
          if (!first) para.appendChild(el('br'));
          inline(lines[i], para);
          first = false;
          i++;
        }
        out.appendChild(para);
      }
    }
    return out;
  }

  // ---------------------------------------------------------------- dialogs

  function closeDialog() {
    clear($('layer'));
  }

  /** Shows a dialog; buttons: [{text, kind, run}] where run may return false to keep it open. */
  function dialog({ title, text, body, buttons, id }) {
    let box = null;
    box = el('div', { class: 'dialog', role: 'dialog', 'aria-modal': 'true', 'aria-label': title, id: id || 'dialog' },
      el('h2', null, title),
      text ? el('p', null, text) : null,
      body ? el('div', { class: 'body' }, body) : null,
      el('div', { class: 'buttons' }, (buttons || []).map((b) => el('button', {
        class: b.kind || 'text',
        onclick: async () => {
          // close this dialog, unless another one has taken its place meanwhile
          if ((!b.run || (await b.run()) !== false) && box.isConnected) closeDialog();
        },
      }, b.text))));
    clear($('layer')).appendChild(box);
    const focus = box.querySelector('input, textarea, .buttons button:last-child');
    if (focus) focus.focus();
    return box;
  }

  function ask(title, text, hint, start, secret) {
    return new Promise((resolve) => {
      const field = el('input', { class: 'field', placeholder: hint || '', value: start || '', type: secret ? 'password' : 'text', spellcheck: 'false' });
      const done = () => { closeDialog(); resolve(field.value.trim()); };
      field.addEventListener('keydown', (e) => { if (e.key === 'Enter') done(); });
      dialog({
        title, text, body: field,
        buttons: [{ text: t('cancel'), run: () => resolve(null) }, { text: t('save'), kind: 'primary', run: () => resolve(field.value.trim()) }],
      });
      field.focus();
    });
  }

  // ---------------------------------------------------------------- the side bar

  function show(name) {
    view = name;
    for (const v of ['chat', 'term', 'auto', 'settings']) $('view-' + v).hidden = v !== name;
    $('app').classList.remove('menu-open');
    renderSide();
    if (name === 'term') {
      if (!terms.size) addTerminal();
      else fitActive();
    }
    if (name === 'auto') renderAuto();
    if (name === 'settings') renderSettings();
    if (name === 'chat') $('input').focus();
  }

  function renderSide() {
    clear($('new-chat')).append(icon('compose'), t('new_chat'));
    $('chat-list-title').textContent = t('earlier');
    const list = clear($('chat-list'));
    if (!S.chats.length) list.appendChild(el('div', { class: 'hint' }, t('no_chats')));
    for (const chat of S.chats) {
      const on = view === 'chat' && S.chat && S.chat.id === chat.id;
      list.appendChild(el('div', { class: 'row' + (on ? ' on' : ''), role: 'button', tabindex: '0',
        onclick: async () => { applyChat(await call('chat:open', chat.id)); show('chat'); },
        onkeydown: (e) => { if (e.key === 'Enter') e.currentTarget.click(); } },
      el('span', { class: 'grow' }, chat.title || t('new_chat')),
      el('button', { class: 'remove', title: t('delete_chat'), 'aria-label': t('delete_chat'), onclick: async (e) => {
        e.stopPropagation();
        S.chats = await call('chat:delete', chat.id);
        renderSide();
      } }, icon('trash'))));
    }
    const nav = clear($('nav'));
    for (const [name, image, key] of [['chat', 'chat', 'nav_chat'], ['term', 'terminal', 'nav_term'], ['auto', 'bolt', 'nav_auto'], ['settings', 'settings', 'nav_settings']]) {
      nav.appendChild(el('button', { class: 'row' + (view === name ? ' on' : ''), id: 'nav-' + name, onclick: () => show(name) }, icon(image), el('span', { class: 'grow' }, t(key))));
    }
  }

  // ---------------------------------------------------------------- the chat

  function stepIcon(text) {
    if (/^pc(\.cmd)?\s/.test(text)) return 'layers';
    if (/^web search/.test(text)) return 'search';
    if (/^web page/.test(text)) return 'globe';
    if (/^(Read)\s/.test(text)) return 'file';
    if (/^(Write|Edit)\s/.test(text)) return 'edit';
    if (/^(Helper|More for helper)\s/.test(text)) return 'chat';
    if (text === 'plan') return 'list';
    return 'terminal';
  }

  function applyChat(update) {
    S.chat = update.chat;
    S.busy = update.busy;
    S.working = update.working;
    renderChat();
  }

  function modelName() {
    return S.settings.model ? S.settings.model.replace(/^opencode\//, '') : t('model_auto');
  }

  function renderChat() {
    const chat = S.chat;
    $('chat-title').textContent = (chat && chat.title) || t('new_chat');
    clear($('model-chip')).append(icon('sparkle'), modelName());
    const folder = String(S.settings.workspace || '');
    clear($('folder-chip')).append(icon('folder'), folder.split(/[\\/]/).filter(Boolean).pop() || folder);
    $('folder-chip').title = t('s_folder') + ': ' + folder;
    const box = $('messages');
    const atEnd = box.scrollHeight - box.scrollTop - box.clientHeight < 80;
    clear(box);
    if (!chat || !chat.messages.length) {
      box.appendChild(el('div', { class: 'empty' },
        el('img', { src: '../assets/icon.png', alt: '' }),
        el('h2', null, t('hello')),
        el('p', null, t('hello_sub')),
        el('div', { class: 'ideas' }, [['idea_1', 'edit'], ['idea_2', 'timer'], ['idea_3', 'folder'], ['idea_4', 'bell']].map(([key, image]) =>
          el('button', { class: 'idea', onclick: () => { $('input').value = t(key); grow(); $('input').focus(); } }, icon(image), t(key))))));
    } else {
      const column = el('div', { class: 'column' });
      chat.messages.forEach((m, index) => {
        if (m.user) {
          column.appendChild(el('div', { class: 'msg user' }, el('div', { class: 'bubble' }, shown(m.text))));
          return;
        }
        const last = index === chat.messages.length - 1;
        const waiting = last && S.busy && !m.text;
        const node = el('div', { class: 'msg agent' + (m.failed ? ' failed' : '') });
        if (m.own) node.appendChild(el('div', { class: 'own' }, icon('heart'), t('own')));
        if (m.steps && m.steps.length) {
          node.appendChild(el('div', { class: 'steps' }, m.steps.map((s) => el('div', { class: 'step' }, icon(stepIcon(s)), el('code', null, s)))));
        }
        if (!waiting) {
          const bubble = el('div', { class: 'bubble' });
          bubble.appendChild(markdown(m.text || (m.note === 'stopped' ? '' : t('no_answer'))));
          node.appendChild(bubble);
        }
        if (m.note === 'stopped') node.appendChild(el('div', { class: 'note' }, t('stopped')));
        else if (m.note && m.note.startsWith('model:')) node.appendChild(el('div', { class: 'note' }, t('switched', m.note.slice(6))));
        else if (m.note && m.note.startsWith('saw:')) node.appendChild(el('div', { class: 'note' }, t('seen_by', m.note.slice(4))));
        if (m.learned && m.learned.length) {
          node.appendChild(el('div', { class: 'learned' }, el('b', null, t('learned_now')), m.learned.map((line) => el('div', null, line))));
        }
        column.appendChild(node);
      });
      box.appendChild(column);
    }
    if (atEnd || S.busy) box.scrollTop = box.scrollHeight;
    renderStatus();
  }

  function renderStatus() {
    const status = $('status');
    const busy = S.busy;
    status.hidden = !busy;
    if (busy) {
      const what = t('act_' + busy.activity);
      // under what is being done: the newest line the running command printed (how far a download is)
      clear(status).append(el('div', { class: 'dot' }), el('div', { class: 'what' },
        el('span', null, what + (busy.detail ? ' · ' + busy.detail : '')),
        busy.output ? el('code', { class: 'printed' }, busy.output) : null));
    }
    const send = $('send');
    const stop = !!S.working;
    send.className = 'icon-button ' + (stop ? 'stop' : 'accent');
    send.title = t(stop ? 'stop' : 'send');
    send.setAttribute('aria-label', send.title);
    clear(send).appendChild(icon(stop ? 'stop' : 'arrow_up'));
    $('input').placeholder = t('placeholder');
    $('mic').title = t('voice');
    $('mic').setAttribute('aria-label', t('voice'));
    clear($('mic')).appendChild(icon('mic'));
    $('attach').title = t('attach');
    $('attach').setAttribute('aria-label', t('attach'));
    clear($('attach')).appendChild(icon('plus'));
    showAttached();
  }

  // The pictures and files that go with the next message (full paths).
  const attached = [];
  const baseName = (file) => String(file).split(/[\\/]/).pop();

  function showAttached() {
    const row = $('attached');
    row.hidden = !attached.length;
    clear(row).append(...attached.map((file) => el('button', {
      class: 'chip', type: 'button', title: t('attach_remove', baseName(file)),
      onclick: () => { attached.splice(attached.indexOf(file), 1); showAttached(); },
    }, icon('file'), el('span', null, baseName(file)), icon('close'))));
  }

  async function pickFiles() {
    const files = await call('pick:files');
    for (const file of files || []) if (!attached.includes(file) && attached.length < 10) attached.push(file);
    showAttached();
    $('input').focus();
  }

  /** "[file: C:\\Users\\me\\a.png]" in a message is shown as the file's name. */
  function shown(text) {
    return String(text || '').replace(/\[file: (?:[^\]\n]*[\\/])?([^\]\n\\/]+)\]/g, '\uD83D\uDCCE $1');
  }

  function grow() {
    const input = $('input');
    input.style.height = 'auto';
    input.style.height = Math.min(input.scrollHeight, 180) + 'px';
  }

  function submit() {
    if (S.working) return call('chat:stop');
    const text = $('input').value.trim();
    if (!text && !attached.length) return;
    $('input').value = '';
    grow();
    if (attached.length) call('chat:send', { text: text || t('attach_default'), files: attached.splice(0) });
    else call('chat:send', text);
    showAttached();
  }

  async function pickModel() {
    const list = el('div', { class: 'body' }, el('p', null, t('loading_models')));
    const search = el('input', { class: 'field', placeholder: t('search_models'), spellcheck: 'false' });
    const fill = () => {
      clear(list);
      const want = search.value.trim().toLowerCase();
      const choose = async (model) => {
        S.settings = await call('settings:set', { model });
        closeDialog();
        renderChat();
        if (view === 'settings') renderSettings();
      };
      if (!want) {
        list.appendChild(el('button', { class: 'choice' + (!S.settings.model ? ' on' : ''), onclick: () => choose('') },
          icon('sparkle'), el('span', null, t('model_auto'), el('small', null, t('model_auto_note')))));
      }
      for (const model of models.filter((m) => m.toLowerCase().includes(want)).slice(0, 200)) {
        const free = /^opencode\/.*(free|big-pickle)/.test(model);
        list.appendChild(el('button', { class: 'choice' + (S.settings.model === model ? ' on' : ''), onclick: () => choose(model) },
          el('span', null, model.replace(/^opencode\//, ''), el('small', null, model.split('/')[0] + (free ? ' · ' + t('free') : '')))));
      }
    };
    search.addEventListener('input', fill);
    dialog({ title: t('pick_model'), body: [search, list], buttons: [{ text: t('cancel') }], id: 'models' });
    if (!models) {
      const off = (p) => { if (!models && list.firstChild) list.firstChild.textContent = t('act_setup') + (p.total ? ' · ' + p.done + ' / ' + p.total + ' MB' : ''); };
      installListeners.add(off);
      try {
        models = await api.call('models');
      } catch (e) {
        models = null;
      }
      installListeners.delete(off);
      if (!models) {
        if ($('models')) clear(list).appendChild(el('p', null, t('models_failed')));
        return;
      }
    }
    if ($('models')) fill();
  }
  const installListeners = new Set();

  // ---------------------------------------------------------------- terminals

  function termTheme() {
    const css = getComputedStyle(document.body);
    const v = (name) => css.getPropertyValue(name).trim();
    return { background: v('--bg'), foreground: v('--text'), cursor: v('--accent'), selectionBackground: v('--outline') };
  }

  async function addTerminal(kind) {
    if (terms.size >= 8) return toast('Termant', t('max_tabs'));
    if (!kind && S.shells.length > 1) {
      return dialog({
        title: t('shell_pick'),
        body: S.shells.map((s) => el('button', { class: 'choice', onclick: () => { closeDialog(); addTerminal(s.id); } }, icon('terminal'), s.name)),
        buttons: [{ text: t('cancel') }],
      });
    }
    const box = el('div', { class: 'term' });
    $('terms').appendChild(box);
    const term = new window.Terminal({
      fontFamily: '"Cascadia Mono", Consolas, "Courier New", monospace', fontSize: 14, cursorBlink: true, theme: termTheme(), scrollback: 5000,
    });
    const fit = new window.FitAddon.FitAddon();
    term.loadAddon(fit);
    term.open(box);
    fit.fit();
    let made;
    try {
      made = await call('term:create', { kind: kind || S.shells[0].id, cols: term.cols, rows: term.rows });
    } catch (e) {
      box.remove();
      term.dispose();
      return;
    }
    terms.set(made.id, { term, fit, box, name: made.name });
    term.onData((data) => api.call('term:write', { id: made.id, data }));
    term.onResize(({ cols, rows }) => api.call('term:resize', { id: made.id, cols, rows }));
    term.attachCustomKeyEventHandler((e) => {
      if (e.type === 'keydown' && e.ctrlKey && e.shiftKey && e.code === 'KeyC') {
        navigator.clipboard.writeText(term.getSelection());
        return false;
      }
      if (e.type === 'keydown' && e.ctrlKey && e.shiftKey && e.code === 'KeyV') {
        navigator.clipboard.readText().then((text) => term.paste(text));
        return false;
      }
      return true;
    });
    activeTerm = made.id;
    renderTabs();
    fitActive();
  }

  function renderTabs() {
    const tabs = clear($('tabs'));
    let n = 0;
    for (const [id, entry] of terms) {
      n++;
      entry.box.hidden = id !== activeTerm;
      tabs.appendChild(el('div', { class: 'tab' + (id === activeTerm ? ' on' : ''), role: 'tab', tabindex: '0', onclick: () => { activeTerm = id; renderTabs(); fitActive(); } },
        n + ' · ' + entry.name,
        el('button', { class: 'x', title: t('close_tab'), 'aria-label': t('close_tab'), onclick: (e) => { e.stopPropagation(); api.call('term:kill', id); dropTerminal(id); } }, icon('close'))));
    }
    $('tab-add').title = t('new_tab');
    $('tab-add').setAttribute('aria-label', t('new_tab'));
    clear($('tab-add')).appendChild(icon('plus'));
  }

  function dropTerminal(id) {
    const entry = terms.get(id);
    if (!entry) return;
    entry.term.dispose();
    entry.box.remove();
    terms.delete(id);
    if (activeTerm === id) activeTerm = terms.size ? Array.from(terms.keys()).pop() : 0;
    renderTabs();
    fitActive();
  }

  function fitActive() {
    const entry = terms.get(activeTerm);
    if (!entry || view !== 'term') return;
    requestAnimationFrame(() => {
      try { entry.fit.fit(); } catch (e) { /* not on screen yet */ }
      entry.term.focus();
    });
  }

  // ---------------------------------------------------------------- rules, alarms, notes

  function renderAuto() {
    $('auto-title').textContent = t('nav_auto');
    const page = clear($('auto'));
    const d = S.data;
    const empty = (key) => el('div', { class: 'card' }, el('div', { class: 'item' }, el('span', { class: 'sub grow' }, t(key))));
    const bin = (label, run) => el('button', { class: 'icon-button', title: t('remove'), 'aria-label': t('remove') + ': ' + label, onclick: run }, icon('trash'));

    page.appendChild(el('h2', null, t('rules')));
    page.appendChild(!d.rules.length ? empty('rules_none') : el('div', { class: 'card' }, d.rules.map((r) => {
      const toggle = el('button', { class: 'switch' + (r.enabled ? ' on' : ''), role: 'switch', 'aria-checked': String(!!r.enabled), 'aria-label': r.when,
        onclick: async () => { S.data = await call('rule:toggle', { id: r.id, enabled: !r.enabled }); renderAuto(); } });
      const last = r.lastRun ? t('rule_last', timeText(r.lastRun)) + (r.lastExit ? ' · ' + t('rule_failed') : '') : t('rule_never');
      return el('div', { class: 'item' }, icon('bolt'),
        el('span', { class: 'grow' }, el('span', { class: 'title' }, r.when), el('span', { class: 'sub' }, el('code', null, r.command)), el('span', { class: 'sub' }, last)),
        toggle, bin(r.when, async () => { S.data = await call('rule:remove', r.id); renderAuto(); }));
    })));

    page.appendChild(el('h2', null, t('alarms')));
    page.appendChild(!d.alarms.length ? empty('alarms_none') : el('div', { class: 'card' }, d.alarms.map((a) =>
      el('div', { class: 'item' }, icon(a.kind === 'timer' ? 'timer' : 'bell'),
        el('span', { class: 'grow' }, el('span', { class: 'title' }, timeText(a.at)), el('span', { class: 'sub' }, a.label || t(a.kind === 'timer' ? 'timer' : 'alarm'))),
        bin(timeText(a.at), async () => { S.data = await call('alarm:remove', a.id); renderAuto(); })))));

    page.appendChild(el('h2', null, t('notes')));
    page.appendChild(!d.notes.length ? empty('notes_none') : el('div', { class: 'card' }, d.notes.map((n, index) =>
      el('div', { class: 'item' }, icon('bulb'), el('span', { class: 'grow title' }, n),
        bin(n, async () => { S.data = await call('note:remove', index); renderAuto(); })))));
    page.appendChild(el('div', { class: 'hint' }, t('auto_note')));
  }

  // ---------------------------------------------------------------- settings

  function renderSettings() {
    $('settings-title').textContent = t('nav_settings');
    const page = $('settings');
    const top = page.scrollTop;
    clear(page);
    const s = S.settings;
    const set = async (patch) => {
      S.settings = await call('settings:set', patch);
      applyLook();
      renderAll();
    };
    const row = (image, title, sub, right, run) => el(run ? 'button' : 'div', { class: 'item', onclick: run },
      icon(image), el('span', { class: 'grow' }, el('span', { class: 'title' }, title), sub ? el('span', { class: 'sub' }, sub) : null),
      typeof right === 'string' ? el('span', { class: 'value' }, right) : right);
    const toggle = (image, title, sub, key) => {
      const sw = el('span', { class: 'switch' + (s[key] ? ' on' : ''), role: 'switch', 'aria-checked': String(!!s[key]) });
      const node = row(image, title, sub, sw, () => set({ [key]: !s[key] }));
      node.id = 'set-' + key;
      return node;
    };
    const section = (title, ...rows) => {
      page.appendChild(el('h2', null, title));
      page.appendChild(el('div', { class: 'card' }, rows));
    };

    section(t('s_look'),
      row('language', t('s_language'), null, s.lang === 'hi' ? 'हिन्दी' : 'English', () => set({ lang: s.lang === 'hi' ? 'en' : 'hi' })),
      row('theme', t('s_theme'), null, t(s.theme === 'light' ? 'light' : 'dark'), () => set({ theme: s.theme === 'light' ? 'dark' : 'light' })));

    section(t('s_agent'),
      row('sparkle', t('s_model'), null, modelName(), pickModel),
      toggle('refresh', t('s_backup'), t('s_backup_note'), 'backup'),
      toggle('bolt', t('s_auto'), t('s_auto_note'), 'auto'),
      toggle('globe', t('s_web'), t('s_web_note'), 'webSearch'),
      row('folder', t('s_folder'), s.workspace, null, async () => {
        const folder = await call('pick:folder');
        if (folder) set({ workspace: folder });
      }),
      row('external', t('s_folder_open'), null, null, () => api.call('open:folder')));

    page.appendChild(el('h2', null, t('s_keys')));
    page.appendChild(el('div', { class: 'card' }, S.providers.map(([name, variable]) =>
      row('key', name, null, t(s.keys[variable] ? 'key_set' : 'key_none'), async () => {
        const value = await ask(name, variable, t('key_hint'), '', true);
        if (value === null) return;
        const keys = Object.assign({}, s.keys);
        if (value) keys[variable] = value; else delete keys[variable];
        models = null;
        set({ keys });
      }))));
    page.appendChild(el('div', { class: 'hint' }, t('s_keys_note')));

    const voiceCount = (s.voiceKeys || []).length;
    section(t('s_voice'),
      row('key', t('voice_keys'), t('voice_keys_note'), voiceCount ? t('voice_count', voiceCount) : t('key_none'), () => {
        const field = el('textarea', { class: 'field', id: 'voice-keys', placeholder: 'sk_...\nsk_...', spellcheck: 'false' });
        field.value = (s.voiceKeys || []).join('\n');
        dialog({ title: t('voice_keys'), text: t('voice_keys_help'), body: field, buttons: [{ text: t('cancel') }, { text: t('save'), kind: 'primary', run: () => {
          const seen = new Set();
          const keys = field.value.split(/[\s,;]+/).map((k) => k.trim()).filter((k) => k.length >= 8 && !seen.has(k) && seen.add(k)).slice(0, 30);
          set({ voiceKeys: keys });
        } }] });
        field.focus();
      }),
      toggle('bell', t('voice_speak'), t('voice_speak_note'), 'speak'));

    const known = (S.data && S.data.learned) || { today: [], skills: [] };
    page.appendChild(el('h2', null, t('s_learned')));
    page.appendChild(el('div', { class: 'card', id: 'learned-card' },
      el('div', { class: 'item' }, icon('sparkle'), el('span', { class: 'grow' }, el('span', { class: 'title' }, t('learned_today', known.today.length)),
        known.today.length ? known.today.map((line) => el('span', { class: 'sub' }, line)) : el('span', { class: 'sub' }, t('learned_none')))),
      known.skills.map((k) => el('div', { class: 'item' }, icon('bolt'),
        el('span', { class: 'grow' }, el('span', { class: 'title' }, k.name), el('span', { class: 'sub' }, k.about + (k.day ? ' · ' + k.day : '')))))));
    page.appendChild(el('div', { class: 'hint' }, t('s_learned_note')));

    // the helper agents: those of Termant, and those made here
    const helpers = (S.data && S.data.team) || [];
    page.appendChild(el('h2', null, t('s_team')));
    page.appendChild(el('div', { class: 'card', id: 'team-card' },
      helpers.map((h) => el('div', { class: 'item' }, icon('chat'),
        el('span', { class: 'grow' }, el('span', { class: 'title' }, h.name),
          el('span', { class: 'sub' }, h.about + (h.model ? ' · ' + h.model : ''))),
        h.own ? el('button', { class: 'icon-button', title: t('remove'), 'aria-label': t('remove') + ': ' + h.name,
          onclick: () => call('team:remove', h.name).catch(() => {}) }, icon('trash'))
          : el('span', { class: 'sub' }, t('team_builtin')))),
      row('plus', t('team_add'), t('team_add_note'), null, () => {
        const name = el('input', { class: 'field', id: 'team-name', placeholder: t('team_name'), spellcheck: 'false' });
        const about = el('input', { class: 'field', id: 'team-about', placeholder: t('team_about') });
        const text = el('textarea', { class: 'field', id: 'team-text', placeholder: t('team_text') });
        const model = el('input', { class: 'field', id: 'team-model', placeholder: t('team_model'), spellcheck: 'false' });
        dialog({ title: t('team_add'), body: [name, about, text, model], buttons: [{ text: t('cancel') }, { text: t('save'), kind: 'primary', run: async () => {
          try {
            await call('team:add', { name: name.value, about: about.value, text: text.value, model: model.value });
          } catch (e) {
            return false; // what is wrong was told; the form stays open
          }
        } }] });
      })));
    page.appendChild(el('div', { class: 'hint' }, t('s_team_note')));

    page.appendChild(el('h2', null, t('s_mcp')));
    page.appendChild(el('div', { class: 'card' },
      Object.entries(s.mcp || {}).map(([name, command]) => el('div', { class: 'item' }, icon('plug'),
        el('span', { class: 'grow' }, el('span', { class: 'title' }, name), el('span', { class: 'sub' }, el('code', null, command))),
        el('button', { class: 'icon-button', title: t('remove'), 'aria-label': t('remove') + ': ' + name, onclick: () => {
          const mcp = Object.assign({}, s.mcp);
          delete mcp[name];
          set({ mcp });
        } }, icon('trash')))),
      row('plus', t('mcp_add'), t('s_mcp_note'), null, () => {
        const name = el('input', { class: 'field', placeholder: t('mcp_name'), spellcheck: 'false' });
        const command = el('input', { class: 'field', placeholder: t('mcp_command'), spellcheck: 'false' });
        dialog({ title: t('mcp_add'), body: [name, command], buttons: [{ text: t('cancel') }, { text: t('save'), kind: 'primary', run: () => {
          const key = name.value.trim().replace(/[^A-Za-z0-9_-]/g, '');
          if (!key || !command.value.trim()) {
            toast('Termant', t('mcp_bad'));
            return false;
          }
          set({ mcp: Object.assign({}, s.mcp, { [key]: command.value.trim() }) });
        } }] });
      })));

    section(t('s_github'),
      row('branch', 'GitHub', s.github.user ? t('github_as', s.github.user) : t('github_none'), null, async () => {
        const token = await ask('GitHub', t('github_hint'), 'ghp_…', '', true);
        if (token === null) return;
        if (token) toast('GitHub', t('github_checking'));
        try {
          S.settings = await call('github:set', token);
        } catch (e) {
          return;
        }
        renderSettings();
      }));

    const care = s.care || { on: false, times: '' };
    const careSwitch = el('span', { class: 'switch' + (care.on ? ' on' : ''), role: 'switch', 'aria-checked': String(!!care.on) });
    section(t('s_care'),
      row('heart', t('care_title'), t('care_note'), careSwitch, () => set({ care: Object.assign({}, care, { on: !care.on }) })),
      care.on ? row('clock', t('care_times'), null, care.times || '—', async () => {
        const value = await ask(t('care_times'), t('care_times_help'), '09:00, 21:00', care.times);
        if (value === null) return;
        const times = value.split(/[\s,]+/).filter(Boolean);
        if (times.length > 4 || times.some((x) => !/^([01]?\d|2[0-3]):[0-5]\d$/.test(x))) return toast('Termant', t('care_times_wrong'));
        set({ care: Object.assign({}, care, { times: times.join(', ') }) });
      }) : null,
      row('message', t('care_now'), null, null, async () => {
        const started = await call('care:now');
        toast('Termant', t(started ? 'care_now_started' : 'care_now_busy'));
        if (started) show('chat');
      }));

    section(t('s_app'),
      toggle('layers', t('s_tray'), t('s_tray_note'), 'tray'),
      toggle('bolt', t('s_autostart'), null, 'autostart'),
      row('external', t('s_arena'), null, null, () => api.call('open:link', 'https://arena.ai')),
      row('info', t('s_about'), t('about_text', S.version), null, null));
    page.scrollTop = top;
  }

  // ---------------------------------------------------------------- alarms that ring, questions from the agent

  function beep() {
    try {
      audio = audio || new AudioContext();
      for (const [at, pitch] of [[0, 880], [0.22, 1175], [0.44, 880]]) {
        const tone = audio.createOscillator(), gain = audio.createGain();
        tone.frequency.value = pitch;
        gain.gain.setValueAtTime(0.0001, audio.currentTime + at);
        gain.gain.exponentialRampToValueAtTime(0.35, audio.currentTime + at + 0.02);
        gain.gain.exponentialRampToValueAtTime(0.0001, audio.currentTime + at + 0.2);
        tone.connect(gain).connect(audio.destination);
        tone.start(audio.currentTime + at);
        tone.stop(audio.currentTime + at + 0.21);
      }
    } catch (e) {
      // no sound device: the dialog and the notification remain
    }
  }

  let waitingAsk = null; // a question from the agent that has no answer yet

  function showRing(ringing) {
    clearInterval(ringTimer);
    ringTimer = null;
    if (!ringing) {
      if ($('ring')) closeDialog();
      if (waitingAsk) showAsk(waitingAsk);
      return;
    }
    dialog({
      id: 'ring', title: ringing.label,
      body: el('div', { class: 'ring-icon' }, icon('bell')),
      buttons: [{ text: t('ring_stop'), kind: 'primary', run: () => { api.call('ring:stop'); } }],
    });
    beep();
    ringTimer = setInterval(beep, 1500);
  }

  function showAsk(question) {
    waitingAsk = question;
    if (!question) {
      if ($('ask')) closeDialog();
      return;
    }
    if ($('ring')) return; // shown when the ringing is stopped
    const reply = (value) => { waitingAsk = null; api.call('ask:answer', { id: question.id, value }); };
    const field = el('input', { class: 'field', placeholder: t('answer_hint') });
    field.addEventListener('keydown', (e) => { if (e.key === 'Enter' && field.value.trim()) { closeDialog(); reply(field.value.trim()); } });
    dialog({
      id: 'ask', title: t('ask_title'), text: question.question,
      body: [question.choices.map((c) => el('button', { class: 'choice', onclick: () => { closeDialog(); reply(c); } }, c)), field],
      buttons: [{ text: t('skip'), run: () => reply(null) }, { text: t('send'), kind: 'primary', run: () => {
        if (!field.value.trim()) return false;
        reply(field.value.trim());
      } }],
    });
    if (question.choices.length) field.blur();
  }

  // ---------------------------------------------------------------- start

  function applyLook() {
    document.body.className = S.settings.theme === 'light' ? 'light' : 'dark';
    document.documentElement.lang = S.settings.lang;
    for (const entry of terms.values()) entry.term.options.theme = termTheme();
  }

  function renderAll() {
    renderSide();
    renderChat();
    renderTabs();
    for (const b of document.querySelectorAll('#menu, .menu-button')) {
      b.title = t('menu');
      b.setAttribute('aria-label', t('menu'));
      clear(b).appendChild(icon('menu'));
    }
    if (view === 'auto') renderAuto();
    if (view === 'settings') renderSettings();
  }

  async function start() {
    S = await api.call('state');
    applyLook();
    renderAll();
    show('chat');

    $('new-chat').addEventListener('click', async () => { applyChat(await call('chat:new')); show('chat'); });
    $('model-chip').addEventListener('click', pickModel);
    $('composer').addEventListener('submit', (e) => { e.preventDefault(); submit(); });
    $('input').addEventListener('input', grow);
    $('input').addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
        e.preventDefault();
        if (!S.working) submit();
      }
    });
    $('mic').addEventListener('click', () => { $('input').focus(); api.call('voice'); });
    $('attach').addEventListener('click', () => pickFiles());
    $('tab-add').addEventListener('click', () => addTerminal());
    for (const b of document.querySelectorAll('#menu, .menu-button')) b.addEventListener('click', () => $('app').classList.toggle('menu-open'));
    $('layer').addEventListener('mousedown', (e) => {
      if (e.target === $('layer') && !$('ring') && !$('ask') && !$('notice')) closeDialog();
    });
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && $('layer').firstChild && !$('ring') && !$('ask') && !$('notice')) closeDialog();
    });
    window.addEventListener('resize', fitActive);

    api.on('chat', (update) => { applyChat(update); });
    api.on('chats', (list) => { S.chats = list; renderSide(); });
    api.on('data', (d) => { S.data = d; if (view === 'auto') renderAuto(); if (view === 'settings') renderSettings(); });
    api.on('settings', (s) => { S.settings = s; renderAll(); });
    let speaking = null;
    api.on('voice', (sound) => {
      if (speaking) speaking.pause();
      speaking = new Audio('data:audio/mpeg;base64,' + sound);
      speaking.play().catch(() => {});
    });
    $('folder-chip').addEventListener('click', async () => {
      const folder = await call('pick:folder');
      if (!folder) return;
      S.settings = await call('settings:set', { workspace: folder });
      renderAll();
      toast(t('s_folder'), folder);
    });
    api.on('install', (p) => { for (const f of installListeners) f(p); });
    api.on('installed', () => { S.agentInstalled = true; });
    api.on('shells', (list) => { S.shells = list; });
    api.on('toast', (m) => toast(m.title, m.body));
    api.on('ring', showRing);
    api.on('ask', showAsk);
    api.on('term:data', ({ id, data }) => { const entry = terms.get(id); if (entry) entry.term.write(data); });
    api.on('term:exit', ({ id }) => dropTerminal(id));

    if (S.ringing) showRing(S.ringing);
    if (!S.settings.seenNotice) {
      dialog({ id: 'notice', title: t('notice_title'), text: t('notice_text'), buttons: [{ text: t('notice_ok'), kind: 'primary', run: async () => {
        S.settings = await call('settings:set', { seenNotice: true });
        $('input').focus();
      } }] });
    }
  }

  // for the app's own test
  window.termantTest = {
    show, terminalText: () => {
      const entry = terms.get(activeTerm);
      if (!entry) return '';
      const buffer = entry.term.buffer.active;
      const lines = [];
      for (let i = 0; i < buffer.length; i++) lines.push(buffer.getLine(i).translateToString(true));
      return lines.join('\n');
    },
    terminals: () => terms.size,
    typeInTerminal: (text) => api.call('term:write', { id: activeTerm, data: text }),
  };

  start();
})();

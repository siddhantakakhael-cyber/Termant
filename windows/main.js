// Termant for Windows: the main process. It owns the window, the chats, the
// agent, the terminals, the alarms and rules, and the small local server
// through which the `pc` command reaches the app.
const { app, BrowserWindow, ipcMain, Tray, Menu, Notification, dialog, shell, nativeImage } = require('electron');
const fs = require('fs');
const os = require('os');
const path = require('path');
const http = require('http');
const crypto = require('crypto');
const { execFile } = require('child_process');
const { Store } = require('./src/store');
const { Agent, PROVIDERS } = require('./src/agent');
const { Schedule, span, clock } = require('./src/schedule');
const learning = require('./src/learn');
const { Voice, keysFrom } = require('./src/voice');
const { Team } = require('./src/team');
const attach = require('./src/attach');

const WINDOWS = process.platform === 'win32';
const selftestArg = process.argv.find((a) => a.startsWith('--selftest='));
const SELFTEST = selftestArg ? path.resolve(selftestArg.slice('--selftest='.length)) : '';
const START_HIDDEN = process.argv.includes('--hidden');

if (process.env.TERMANT_DATA) app.setPath('userData', path.resolve(process.env.TERMANT_DATA));
app.setAppUserModelId('app.termant.windows');
app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');

if (!SELFTEST && !app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => showWindow());
  app.whenReady().then(start);
}

let store, settings, agent, schedule, win, tray, quitting = false;
let chats = [], current = null;
let busy = null; // { chat, activity, detail, startedAt } while the agent works
let notes = [];
let learned = null;
let team = null;
let voice = null;
let knownModels = [];
let lastCare = '';
let ringing = null;
let bridge = null;
const asks = new Map();
const terminals = new Map();
let nextTerminal = 1, nextAsk = 1;
const pcDir = app.isPackaged ? path.join(process.resourcesPath, 'pc') : path.join(__dirname, 'resources');
const logLines = [];

function log(text) {
  const line = new Date().toISOString().slice(11, 19) + ' ' + text;
  logLines.push(line);
  if (logLines.length > 2000) logLines.shift();
  if (SELFTEST) {
    try {
      fs.appendFileSync(path.join(SELFTEST, 'app.log'), line + '\n');
    } catch (e) {
      // the folder is made by the test
    }
  }
}

function defaults() {
  const hindi = /^hi\b/i.test(app.getLocale() || '');
  return {
    lang: hindi ? 'hi' : 'en',
    theme: 'dark',
    model: '',
    workspace: path.join(app.getPath('documents'), 'Termant'),
    auto: true,
    backup: true,
    care: { on: false, times: '09:00, 21:00' },
    webSearch: true,
    keys: {},
    mcp: {},
    github: { token: '', user: '' },
    tray: true,
    autostart: false,
    seenNotice: false,
    voiceKeys: [],
    voiceId: '',
    speak: false,
  };
}

function send(channel, value) {
  if (win && !win.isDestroyed()) win.webContents.send(channel, value);
}

function start() {
  store = new Store(app.getPath('userData'));
  settings = Object.assign(defaults(), store.load('settings.json', {}));
  chats = store.load('chats.json', []);
  notes = store.load('notes.json', []);
  knownModels = store.load('models.json', []);
  learned = new learning.Learned(store.dir);
  voice = new Voice(store.dir);
  team = new Team(store.dir);
  agent = new Agent(path.join(store.dir, 'agent'), pcDir, () => settings, () => team.config());
  writeNotes();
  // OpenCode is started now, so that the first message does not wait for it
  setTimeout(() => {
    try {
      if (!agent.installed()) return;
      agent.writeConfig(path.join(pcDir, 'AGENTS.md'));
      agent.server();
    } catch (e) {
      log('server: ' + e.message);
    }
  }, 1200);
  schedule = new Schedule(store, () => shellEnv(), {
    ring: (label, late) => ring(label, late),
    changed: () => send('data', data()),
    log,
  });
  writeNotes();
  startBridge();
  createWindow();
  createTray();
  schedule.start();
  setInterval(careTick, 20000);
  if (SELFTEST) {
    fs.mkdirSync(SELFTEST, { recursive: true });
    win.webContents.once('did-finish-load', () => {
      require('./test/selftest')({
        app, win, dir: SELFTEST, log, pcDir, agent, schedule, store, shellEnv,
        settings: () => settings, chats: () => chats, logs: () => logLines, busy: () => busy, current: () => current,
      });
    });
  }
}

// ---------------------------------------------------------------- window and tray

function background() {
  return settings.theme === 'light' ? '#f6f8fa' : '#0d1117';
}

function createWindow() {
  win = new BrowserWindow({
    width: 1120, height: 760, minWidth: 420, minHeight: 480,
    show: !START_HIDDEN,
    backgroundColor: background(),
    icon: path.join(__dirname, 'build', 'icon.png'),
    title: 'Termant',
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false },
  });
  win.removeMenu();
  win.loadFile(path.join(__dirname, 'ui', 'index.html'));
  win.on('close', (event) => {
    // Alarms and rules need the app: closing the window keeps it in the tray.
    if (!quitting && settings.tray && !SELFTEST) {
      event.preventDefault();
      win.hide();
    }
  });
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:/.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });
  win.webContents.on('will-navigate', (event, url) => {
    event.preventDefault();
    if (/^https?:/.test(url)) shell.openExternal(url);
  });
}

function showWindow() {
  if (!win || win.isDestroyed()) return createWindow();
  if (win.isMinimized()) win.restore();
  win.show();
  win.focus();
}

function createTray() {
  try {
    const icon = nativeImage.createFromPath(path.join(__dirname, 'build', 'icon.png')).resize({ width: 16, height: 16 });
    tray = new Tray(icon);
    tray.setToolTip('Termant');
    tray.setContextMenu(Menu.buildFromTemplate([
      { label: 'Open Termant', click: showWindow },
      { type: 'separator' },
      { label: 'Quit', click: () => { quitting = true; app.quit(); } },
    ]));
    tray.on('click', showWindow);
  } catch (e) {
    log('no tray: ' + e.message);
  }
}

app.on('before-quit', () => {
  quitting = true;
  if (schedule) schedule.stop();
  for (const t of terminals.values()) {
    try { t.kill(); } catch (e) { /* already gone */ }
  }
  if (agent) agent.stop();
  if (agent) agent.stopServer();
  try { fs.rmSync(store.file('bridge.json'), { force: true }); } catch (e) { /* nothing to remove */ }
});
app.on('window-all-closed', () => {
  if (!settings || !settings.tray || SELFTEST) app.quit();
});

// ---------------------------------------------------------------- what the screens show

function summaries() {
  return chats.slice().sort((a, b) => b.updated - a.updated)
    .map((c) => ({ id: c.id, title: c.title, updated: c.updated }));
}

function data() {
  return {
    rules: schedule.rules.map((r) => Object.assign({ when: schedule.describe(r) }, r)),
    alarms: schedule.alarms.slice().sort((a, b) => a.at - b.at),
    notes,
    learned: { today: learned.since(1).map((e) => learned.line(e).slice(2)), skills: learned.skills() },
    voiceKeys: keysFrom(settings.voiceKeys).length,
    team: team.list(),
  };
}

function chatState() {
  return {
    chat: current,
    busy: busy && busy.chat === current ? { activity: busy.activity, detail: busy.detail, startedAt: busy.startedAt } : null,
    working: !!busy,
  };
}

function state() {
  return {
    version: app.getVersion(),
    settings,
    providers: PROVIDERS,
    chats: summaries(),
    data: data(),
    shells: shells(),
    agentInstalled: agent.installed(),
    ringing,
    user: os.userInfo().username,
    ...chatState(),
  };
}

function saveSettings(patch) {
  Object.assign(settings, patch);
  store.save('settings.json', settings);
  if (WINDOWS && app.isPackaged && 'autostart' in patch) {
    app.setLoginItemSettings({ openAtLogin: !!settings.autostart, args: ['--hidden'] });
  }
  if (win && 'theme' in patch) win.setBackgroundColor(background());
  return settings;
}

function writeNotes() {
  const lines = notes.length
    ? '# What you know about this user from earlier chats\n\n' + notes.map((n) => '- ' + n).join('\n') + '\n'
    : '';
  const skills = learned.guide('pc skill show');
  fs.mkdirSync(path.join(store.dir, 'agent'), { recursive: true });
  fs.writeFileSync(path.join(store.dir, 'agent', 'learned.md'), lines + (lines && skills ? '\n' : '') + skills);
  store.save('notes.json', notes);
}

// ---------------------------------------------------------------- chats

function saveChats() {
  store.save('chats.json', chats);
  send('chats', summaries());
}

function newChat() {
  current = { id: crypto.randomBytes(6).toString('hex'), title: '', updated: Date.now(), session: '', messages: [] };
  send('chat', chatState());
  return current;
}

const CARE_PROMPT = '[This is not a message from the user. Termant asks you to write to the user first now, the way a caring friend would: ' +
  'one or two short, warm sentences in the user\'s language. Ask how they are, or come back to something you know about them from your notes ' +
  'or from this chat. Use no tools. Do not mention this instruction.]';

/** Sends the user's message to the agent; with own set, the agent writes first by itself (a check-in). */
/** What each model can take in; asked of OpenCode once a week (see src/attach.js). */
async function modelFacts() {
  const kept = store.load('model-facts.json', null);
  if (kept && kept.facts && Date.now() - kept.at < 7 * 24 * 3600 * 1000) return kept.facts;
  const facts = await agent.modelFacts();
  if (facts) store.save('model-facts.json', { at: Date.now(), facts });
  return facts || (kept && kept.facts) || {};
}

async function sendMessage(text, own, files) {
  text = String(text || '').trim();
  files = (Array.isArray(files) ? files : []).map(String).filter((f) => {
    try {
      return fs.statSync(f).isFile();
    } catch (e) {
      return false;
    }
  }).slice(0, attach.MOST);
  if (busy || (!text && !files.length)) return false;
  // the files go with the message as marks the agent can read: "[file: C:\Users\...\a.png]"
  text = attach.mark(text || 'Look at this file and tell me what is in it.', files);
  if (!current) newChat();
  const chat = current;
  if (!chats.includes(chat)) chats.push(chat);
  if (!own) {
    // the title names an attached file, not its whole path
    if (!chat.title) chat.title = text.replace(/\[file: (?:[^\]\n]*[\\/])?([^\]\n\\/]+)\]/g, '$1').replace(/\s+/g, ' ').slice(0, 80);
    chat.messages.push({ user: true, text });
  }
  const reply = { user: false, text: '', steps: [], failed: false, note: '', own: !!own };
  const knownBefore = learned.diary().length;
  let seenBy = '';
  chat.messages.push(reply);
  chat.updated = Date.now();
  busy = { chat, activity: agent.installed() ? 'start' : 'setup', detail: '', startedAt: Date.now() };
  saveChats();
  send('chat', chatState());
  let handed = [];
  const attempt = (model) => agent.run({
    text, session: chat.session, model, cwd: settings.workspace, files: handed,
    log: SELFTEST ? (raw) => fs.appendFileSync(path.join(SELFTEST, 'events.jsonl'), raw + '\n') : null,
    onEvent: (event) => {
      if (event.kind === 'session') chat.session = event.session;
      if (event.kind === 'text') reply.text = event.text;
      if (event.kind === 'step') reply.steps.push(event.step);
      if (event.activity) {
        busy.activity = event.activity;
        busy.detail = event.detail || '';
      }
      send('chat', chatState());
    },
  });
  try {
    if (!agent.installed()) {
      await agent.install((fraction, done, total) => {
        busy.detail = total ? Math.round(done) + ' / ' + Math.round(total) + ' MB' : Math.round(done) + ' MB';
        send('chat', chatState());
      });
      send('installed', true);
    }
    busy.activity = 'start';
    busy.detail = '';
    send('chat', chatState());
    agent.writeConfig(path.join(pcDir, 'AGENTS.md'));
    let first = settings.model;
    if (files.length) {
      // a picture goes to the model itself; when the chosen model cannot see, a free one that can is asked
      const plan = attach.plan(settings.model, files, await modelFacts());
      handed = plan.files;
      first = plan.model || settings.model;
      if (plan.switched) seenBy = first.replace(/^opencode\//, '');
      log('attached: ' + files.length + ' file(s), ' + handed.length + ' handed to ' + (first || 'the default model'));
    }
    let result = await attempt(first);
    // The chosen model gave nothing (down, or its free limit is used up): try other free ones.
    if (result.failed && !result.stopped && !result.steps.length && settings.backup) {
      if (!knownModels.length) {
        knownModels = (await agent.models()) || [];
        store.save('models.json', knownModels);
      }
      const others = knownModels.filter((m) => /^opencode\/.*(free|big-pickle)/.test(m) && m !== settings.model).slice(0, 3);
      for (const model of others) {
        busy.activity = 'switch';
        busy.detail = model.replace(/^opencode\//, '');
        reply.text = '';
        reply.steps = [];
        send('chat', chatState());
        log('backup model: ' + model);
        const next = await attempt(model);
        if (!next.failed || next.stopped) {
          result = next;
          if (!next.stopped) reply.note = 'model:' + model.replace(/^opencode\//, '');
          break;
        }
      }
    }
    reply.text = result.text;
    reply.failed = result.failed;
    reply.steps = result.steps;
    if (result.helpers && result.helpers.length) reply.helpers = result.helpers;
    if (result.session) chat.session = result.session;
    if (result.stopped) reply.note = 'stopped';
    else if (seenBy && !reply.note) reply.note = 'saw:' + seenBy;
    reply.seconds = Math.round((Date.now() - busy.startedAt) / 100) / 10;
  } catch (e) {
    reply.failed = true;
    reply.text = 'The AI agent could not be set up: ' + (e.message || e) + '\nCheck the internet connection and try again.';
  }
  // what the agent taught itself while it answered is shown under the answer
  const fresh = learned.diary().slice(knownBefore);
  if (fresh.length) reply.learned = fresh.map((e) => learned.line(e).slice(2));
  log('reply: ' + JSON.stringify({ text: reply.text.slice(0, 300), steps: reply.steps, failed: reply.failed, note: reply.note,
    fast: agent.fast, seconds: reply.seconds, learned: reply.learned, helpers: reply.helpers }));
  if (settings.speak && !own && !reply.failed && reply.text && keysFrom(settings.voiceKeys).length) {
    say(reply.text.replace(/```[\s\S]*?```/g, ' ').replace(/[*_#`>|]/g, ' ')).catch((e) => log('speak: ' + e.message));
  }
  if (own) {
    // a check-in that did not work is not shown; one that did is announced
    if (reply.failed || !reply.text) chat.messages.splice(chat.messages.indexOf(reply), 1);
    else if (!win || !win.isFocused()) notify('Termant', reply.text.slice(0, 200));
    if (!chat.messages.length) chats = chats.filter((c) => c !== chat);
  }
  chat.updated = Date.now();
  busy = null;
  saveChats();
  send('chat', chatState());
  return true;
}

/** The agent writes first, at the times of day chosen in Settings. */
function careTick() {
  const care = settings.care || {};
  if (!care.on || busy) return;
  const now = new Date();
  for (const time of String(care.times || '').split(/[\s,]+/)) {
    const t = clock(time);
    if (!t || t[0] !== now.getHours() || t[1] !== now.getMinutes()) continue;
    const key = now.toDateString() + ' ' + time;
    if (lastCare === key) return;
    lastCare = key;
    sendMessage(CARE_PROMPT, true);
    return;
  }
}

// ---------------------------------------------------------------- terminals

let wsl = null;
function shells() {
  if (!WINDOWS) return [{ id: 'bash', name: 'bash' }];
  const list = [{ id: 'powershell', name: 'PowerShell' }, { id: 'cmd', name: 'Command Prompt' }];
  if (wsl) list.push({ id: 'wsl', name: 'Ubuntu (WSL)' });
  return list;
}

function findWsl() {
  if (!WINDOWS) return;
  execFile('wsl.exe', ['-l', '-q'], { windowsHide: true, timeout: 8000, encoding: 'buffer' }, (error, stdout) => {
    const text = stdout ? stdout.toString('utf16le').replace(/\0/g, '').trim() : '';
    wsl = !error && text.length > 0 && !/no installed distributions/i.test(text);
    if (wsl) send('shells', shells());
  });
}

function shellEnv() {
  const env = agent.env();
  // a terminal is the user's own: it keeps their usual settings folders
  for (const name of ['XDG_CONFIG_HOME', 'XDG_DATA_HOME', 'XDG_STATE_HOME', 'XDG_CACHE_HOME', 'OPENCODE_CONFIG_DIR', 'NO_COLOR']) delete env[name];
  return env;
}

function createTerminal(kind, cols, rows) {
  const pty = require('@lydell/node-pty');
  const table = {
    powershell: ['powershell.exe', ['-NoLogo']],
    cmd: ['cmd.exe', []],
    wsl: ['wsl.exe', []],
    bash: ['/bin/bash', []],
  };
  const [file, args] = table[kind] || table[WINDOWS ? 'powershell' : 'bash'];
  let cwd = settings.workspace;
  try {
    fs.mkdirSync(cwd, { recursive: true });
  } catch (e) {
    cwd = os.homedir();
  }
  const id = nextTerminal++;
  const term = pty.spawn(file, args, { name: 'xterm-256color', cols: cols || 100, rows: rows || 30, cwd, env: shellEnv() });
  terminals.set(id, term);
  term.onData((chunk) => send('term:data', { id, data: chunk }));
  term.onExit(() => {
    terminals.delete(id);
    send('term:exit', { id });
  });
  return { id, name: (shells().find((s) => s.id === kind) || { name: kind }).name };
}

// ---------------------------------------------------------------- alarms, questions, notifications

function notify(title, body) {
  try {
    if (Notification.isSupported()) {
      const n = new Notification({ title: title || 'Termant', body: body || '', icon: path.join(__dirname, 'build', 'icon.png') });
      n.on('click', showWindow);
      n.show();
    }
  } catch (e) {
    log('notification failed: ' + e.message);
  }
  log('notify: ' + title + ' | ' + body);
  send('toast', { title, body });
}

function ring(label, late) {
  if (late) return notify(label, 'Missed: the PC was off or Termant was closed.');
  ringing = { label };
  log('ring: ' + label);
  notify(label, 'Termant');
  send('ring', ringing);
  if (win && !win.isDestroyed()) {
    showWindow();
    win.flashFrame(true);
  }
}

function stopRing() {
  ringing = null;
  send('ring', null);
  if (win && !win.isDestroyed()) win.flashFrame(false);
}

function ask(question, choices) {
  return new Promise((resolve) => {
    const id = nextAsk++;
    const timer = setTimeout(() => answer(id, null), 300000);
    asks.set(id, { resolve, timer, question, choices });
    log('ask: ' + question + ' | ' + choices.join(' | '));
    send('ask', { id, question, choices });
    showWindow();
    if (win) win.flashFrame(true);
  });
}

function answer(id, value) {
  const waiting = asks.get(id);
  if (!waiting) return;
  asks.delete(id);
  clearTimeout(waiting.timer);
  if (win && !win.isDestroyed()) win.flashFrame(false);
  send('ask', null);
  waiting.resolve(value);
}

// ---------------------------------------------------------------- the `pc` command's door into the app

function startBridge() {
  const token = crypto.randomBytes(24).toString('hex');
  const server = http.createServer((request, response) => {
    const reply = (code, body) => {
      response.writeHead(code, { 'content-type': 'application/json; charset=utf-8' });
      response.end(JSON.stringify(body));
    };
    if (request.method !== 'POST' || request.headers['x-termant'] !== token) return reply(403, { ok: false, error: 'not allowed' });
    const parts = [];
    request.on('data', (part) => parts.push(part));
    request.on('end', async () => {
      let body = {};
      try {
        body = JSON.parse(Buffer.concat(parts).toString('utf8').replace(/^\uFEFF/, '') || '{}');
      } catch (e) {
        return reply(400, { ok: false, error: 'bad request' });
      }
      try {
        reply(200, { ok: true, text: await command(request.url, body) });
      } catch (e) {
        reply(200, { ok: false, error: String(e.message || e) });
      }
    });
  });
  server.listen(0, '127.0.0.1', () => {
    bridge = { port: server.address().port, token };
    store.save('bridge.json', bridge);
    log('bridge on port ' + bridge.port);
  });
}

function when(ms) {
  const d = new Date(ms);
  const two = (n) => (n < 10 ? '0' : '') + n;
  const day = d.toDateString() === new Date().toDateString() ? '' : d.toDateString().slice(0, 10) + ' ';
  return day + two(d.getHours()) + ':' + two(d.getMinutes());
}

/** Speaks with ElevenLabs through the window; the keys take turns when one is used up. */
async function say(text) {
  const spoken = await voice.speak(text, settings.voiceKeys, { voice: settings.voiceId });
  if (SELFTEST) fs.writeFileSync(path.join(SELFTEST, 'said.mp3'), spoken.audio);
  send('voice', spoken.audio.toString('base64'));
  return 'Said with ElevenLabs, key ' + spoken.key + ' of ' + keysFrom(settings.voiceKeys).length +
    (spoken.notes.length ? ' (' + spoken.notes.join('; ') + ')' : '') + '.';
}

/** Makes or changes a helper agent; it is also written in the diary of what was learned. */
function teamAdd(name, about, text, model) {
  const told = team.add(name, about, text, model);
  learned.record('helper', String(name).trim().toLowerCase(), String(about).replace(/\s+/g, ' ').trim());
  send('data', data());
  return told;
}

function teamRemove(name) {
  const told = team.remove(name);
  send('data', data());
  return told;
}

async function command(route, body) {
  if (route === '/youtube') {
    if (body.action === 'read') return learning.readText(await learning.youtubeRead(body.id, { lang: body.lang, from: body.from }));
    const list = await learning.youtubeSearch(body.query);
    return learning.searchText(String(body.query).trim(), list);
  }
  if (route === '/skill') {
    let text;
    if (body.action === 'save') text = learned.saveSkill(body.name, body.about, body.steps, body.from);
    else if (body.action === 'show') return learned.showSkill(body.name);
    else if (body.action === 'remove') text = learned.removeSkill(body.name);
    else return learned.listText();
    writeNotes();
    send('data', data());
    return text;
  }
  if (route === '/learned') return learned.sinceText(body.days);
  if (route === '/team') {
    if (body.action === 'add') return teamAdd(body.name, body.about, body.text, body.model);
    if (body.action === 'remove') return teamRemove(body.name);
    if (body.action === 'show') return team.show(body.name);
    return team.listText();
  }
  if (route === '/voice') {
    const keys = keysFrom(settings.voiceKeys);
    if (body.action === 'say') return say(String(body.text || ''));
    if (body.action === 'has') return keys.length ? 'yes' : 'no';
    if (body.action === 'voices') return voice.voices(keys);
    if (body.action === 'use') {
      saveSettings({ voiceId: String(body.id || '').trim() });
      send('settings', settings);
      return settings.voiceId ? 'Voice set to ' + settings.voiceId + '.' : 'Back to the usual voice.';
    }
    if (body.action === 'add') {
      const more = keysFrom(keys.concat(keysFrom(body.keys)));
      const added = more.length - keys.length;
      saveSettings({ voiceKeys: more });
      send('settings', settings);
      send('data', data());
      return added + ' key(s) added; ' + more.length + ' ElevenLabs key(s) are saved now.';
    }
    if (body.action === 'retry') {
      voice.forgetRest();
      return 'All keys will be tried again.';
    }
    return voice.report(keys, body.online !== false);
  }
  if (route === '/notify') {
    notify(String(body.title || 'Termant'), String(body.text || ''));
    return 'Notification shown.';
  }
  if (route === '/alarm') {
    const t = clock(body.at);
    if (!t) throw new Error('Give the time as HH:MM on the 24-hour clock, for example: pc alarm 07:00 Wake up');
    const at = new Date();
    at.setHours(t[0], t[1], 0, 0);
    if (at.getTime() <= Date.now()) at.setDate(at.getDate() + 1);
    const id = schedule.addAlarm(at.getTime(), String(body.label || ''), 'alarm');
    return 'Alarm ' + id + ' set for ' + when(at.getTime()) + (body.label ? ' (' + body.label + ')' : '') + '. It rings while Termant is running.';
  }
  if (route === '/timer') {
    const ms = span(body.after);
    if (!ms) throw new Error('Give the time as 30s, 10m or 2h, for example: pc timer 10m Tea');
    const id = schedule.addAlarm(Date.now() + ms, String(body.label || ''), 'timer');
    return 'Timer ' + id + ' rings at ' + when(Date.now() + ms) + (body.label ? ' (' + body.label + ')' : '') + '.';
  }
  if (route === '/alarms') {
    if (body.remove) return schedule.removeAlarm(body.remove) ? 'Removed.' : 'There is no alarm ' + body.remove + '.';
    if (!schedule.alarms.length) return 'No alarms or timers are set.';
    return schedule.alarms.map((a) => a.id + '  ' + a.kind + '  ' + when(a.at) + '  ' + a.label).join('\n');
  }
  if (route === '/ring') {
    ring(String(body.label || 'Termant'), false);
    return 'Ringing. Stop with: pc stop';
  }
  if (route === '/stop') {
    stopRing();
    return 'Stopped.';
  }
  if (route === '/ask') {
    const choices = Array.isArray(body.choices) ? body.choices.map(String).filter(Boolean).slice(0, 6) : [];
    const value = await ask(String(body.question || ''), choices);
    if (value == null) throw new Error('The user did not answer.');
    return value;
  }
  if (route === '/rule') {
    if (body.action === 'add') {
      const rule = schedule.addRule(body.trigger, body.command);
      return 'Rule ' + rule.id + ' added: ' + schedule.describe(rule) + ' -> ' + rule.command;
    }
    if (body.action === 'remove') return schedule.removeRule(body.id) ? 'Rule removed.' : 'There is no rule ' + body.id + '.';
    if (!schedule.rules.length) return 'No rules yet.';
    return schedule.rules.map((r) => r.id + '  ' + (r.enabled ? 'on ' : 'off') + '  ' + schedule.describe(r) + '  ->  ' + r.command).join('\n');
  }
  if (route === '/note') {
    if (body.action === 'add') {
      const text = String(body.text || '').replace(/\s+/g, ' ').trim();
      if (!text) throw new Error('The note is empty.');
      notes.push(text.slice(0, 500));
      learned.record('note', '', text.slice(0, 500));
      writeNotes();
      send('data', data());
      return 'Noted (' + notes.length + ').';
    }
    if (body.action === 'remove') {
      const n = parseInt(body.id, 10);
      if (!(n >= 1 && n <= notes.length)) throw new Error('There is no note ' + body.id + '.');
      notes.splice(n - 1, 1);
      writeNotes();
      send('data', data());
      return 'Note removed.';
    }
    return notes.length ? notes.map((n, i) => (i + 1) + '  ' + n).join('\n') : 'No notes yet.';
  }
  throw new Error('unknown request');
}

// ---------------------------------------------------------------- what the window may ask for

const handlers = {
  state: () => {
    if (wsl === null) findWsl();
    return state();
  },
  'settings:set': (patch) => saveSettings(patch || {}),
  'chat:new': () => {
    newChat();
    return chatState();
  },
  'chat:open': (id) => {
    current = chats.find((c) => c.id === id) || current;
    return chatState();
  },
  'chat:delete': (id) => {
    if (busy && busy.chat.id === id) return summaries();
    chats = chats.filter((c) => c.id !== id);
    if (current && current.id === id) newChat();
    saveChats();
    return summaries();
  },
  // a message, or {text, files} when pictures or files go with it
  'chat:send': (message) => {
    if (message && typeof message === 'object') sendMessage(message.text, false, message.files);
    else sendMessage(message);
    return true;
  },
  'pick:files': async () => {
    const result = await dialog.showOpenDialog(win, { properties: ['openFile', 'multiSelections'], defaultPath: settings.workspace });
    return result.canceled ? [] : result.filePaths.slice(0, attach.MOST);
  },
  'chat:stop': () => {
    agent.stop();
    return true;
  },
  models: async () => {
    if (!agent.installed()) {
      await agent.install((fraction, done, total) => send('install', { done: Math.round(done), total: Math.round(total) }));
      send('installed', true);
    }
    agent.writeConfig(path.join(pcDir, 'AGENTS.md'));
    const list = await agent.models();
    if (list) {
      knownModels = list;
      store.save('models.json', list);
    }
    return list;
  },
  'care:now': () => {
    if (busy) return false;
    sendMessage(CARE_PROMPT, true);
    return true;
  },
  // Windows has dictation built in: Win+H types what is said into the box that has the focus.
  voice: () => {
    if (!WINDOWS) return false;
    execFile('powershell.exe', ['-NoProfile', '-NoLogo', '-ExecutionPolicy', 'Bypass', '-File', path.join(pcDir, 'termant-pc.ps1'), 'key', 'win+h'],
      { windowsHide: true, timeout: 20000 }, () => {});
    return true;
  },
  'team:add': (h) => teamAdd(h.name, h.about, h.text, h.model),
  'team:remove': (name) => teamRemove(name),
  'pick:folder': async () => {
    const result = await dialog.showOpenDialog(win, { properties: ['openDirectory', 'createDirectory'], defaultPath: settings.workspace });
    return result.canceled || !result.filePaths.length ? null : result.filePaths[0];
  },
  'github:set': async (token) => {
    token = String(token || '').trim();
    if (!token) return saveSettings({ github: { token: '', user: '' } });
    const user = await new Promise((resolve, reject) => {
      const request = require('https').get('https://api.github.com/user', {
        headers: { authorization: 'Bearer ' + token, 'user-agent': 'Termant', accept: 'application/vnd.github+json' },
      }, (response) => {
        const parts = [];
        response.on('data', (p) => parts.push(p));
        response.on('end', () => {
          try {
            const body = JSON.parse(Buffer.concat(parts).toString('utf8'));
            if (response.statusCode === 200 && body.login) resolve(body.login);
            else reject(new Error('GitHub did not accept this token.'));
          } catch (e) {
            reject(new Error('GitHub did not answer properly.'));
          }
        });
      });
      request.on('error', () => reject(new Error('GitHub could not be reached. Check the internet connection.')));
    });
    return saveSettings({ github: { token, user } });
  },
  'term:create': ({ kind, cols, rows }) => createTerminal(kind, cols, rows),
  'term:write': ({ id, data: text }) => {
    const t = terminals.get(id);
    if (t) t.write(text);
  },
  'term:resize': ({ id, cols, rows }) => {
    const t = terminals.get(id);
    if (t && cols > 1 && rows > 1) {
      try { t.resize(cols, rows); } catch (e) { /* the shell has ended */ }
    }
  },
  'term:kill': (id) => {
    const t = terminals.get(id);
    if (t) {
      try { t.kill(); } catch (e) { /* already gone */ }
    }
  },
  'rule:toggle': ({ id, enabled }) => {
    schedule.setEnabled(id, enabled);
    return data();
  },
  'rule:remove': (id) => {
    schedule.removeRule(id);
    return data();
  },
  'alarm:remove': (id) => {
    schedule.removeAlarm(id);
    return data();
  },
  'note:remove': (index) => {
    if (index >= 0 && index < notes.length) notes.splice(index, 1);
    writeNotes();
    return data();
  },
  'ring:stop': () => stopRing(),
  'ask:answer': ({ id, value }) => answer(id, value),
  'open:link': (url) => {
    if (/^https?:/.test(String(url))) shell.openExternal(url);
  },
  'open:folder': () => shell.openPath(settings.workspace),
};

for (const [name, handler] of Object.entries(handlers)) {
  ipcMain.handle(name, async (event, argument) => {
    try {
      return { ok: true, value: await handler(argument) };
    } catch (e) {
      log(name + ' failed: ' + (e.stack || e));
      return { ok: false, error: String(e.message || e) };
    }
  });
}

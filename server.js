'use strict';

const http = require('http');
const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');
const { WebSocketServer } = require('ws');
const pty = require('@lydell/node-pty');

const PORT = parseInt(process.env.PORT || '3000', 10);
const HOST = process.env.HOST || '0.0.0.0';
const SHELL = process.env.TERMANT_SHELL || (fs.existsSync('/bin/bash') ? '/bin/bash' : '/bin/sh');
const HOME = process.env.TERMANT_HOME || process.env.HOME || os.homedir();
const MAX_SESSIONS = parseInt(process.env.TERMANT_MAX_SESSIONS || '8', 10);
const SCROLLBACK_BYTES = 512 * 1024; // output replayed when a browser reconnects

// ---------------------------------------------------------------- password
let PASSWORD = process.env.TERMANT_PASSWORD || '';
if (!PASSWORD) {
  PASSWORD = crypto.randomBytes(9).toString('base64url');
  console.log('\n  TERMANT_PASSWORD is not set. Generated password for this run:\n\n      ' + PASSWORD + '\n');
}
const TOKEN = crypto.createHmac('sha256', PASSWORD).update('termant-session-v1').digest('hex');

function safeEqual(a, b) {
  const ha = crypto.createHash('sha256').update(String(a)).digest();
  const hb = crypto.createHash('sha256').update(String(b)).digest();
  return crypto.timingSafeEqual(ha, hb);
}

function isAuthed(req) {
  const cookies = req.headers.cookie || '';
  const m = cookies.match(/(?:^|;\s*)termant=([a-f0-9]+)/);
  return !!m && safeEqual(m[1], TOKEN);
}

// Simple brute-force protection: per-IP failed attempt counter.
const failures = new Map();
function clientIp(req) {
  return req.socket.remoteAddress || 'unknown';
}
function isLocked(ip) {
  const f = failures.get(ip);
  return !!f && f.count >= 10 && Date.now() - f.last < 5 * 60 * 1000;
}
function recordFailure(ip) {
  const f = failures.get(ip) || { count: 0, last: 0 };
  if (Date.now() - f.last > 5 * 60 * 1000) f.count = 0;
  f.count++;
  f.last = Date.now();
  failures.set(ip, f);
}

// ---------------------------------------------------------------- sessions
// Shells live on the server, not in the browser tab. Closing or refreshing
// the page only detaches; the shell and everything running in it keeps going.
const sessions = new Map();
let counter = 0;

function createSession() {
  const id = crypto.randomBytes(8).toString('hex');
  const env = Object.assign({}, process.env, {
    TERM: 'xterm-256color',
    COLORTERM: 'truecolor',
    HOME,
    LANG: process.env.LANG || 'C.UTF-8',
  });
  delete env.TERMANT_PASSWORD;
  delete env.PORT;

  const proc = pty.spawn(SHELL, ['-l'], {
    name: 'xterm-256color',
    cols: 80,
    rows: 24,
    cwd: fs.existsSync(HOME) ? HOME : '/',
    env,
  });

  const session = {
    id,
    name: 'Shell ' + ++counter,
    proc,
    chunks: [],
    size: 0,
    clients: new Set(),
    created: Date.now(),
  };

  proc.onData((data) => {
    session.chunks.push(data);
    session.size += data.length;
    while (session.size > SCROLLBACK_BYTES && session.chunks.length > 1) {
      session.size -= session.chunks.shift().length;
    }
    const msg = JSON.stringify({ type: 'output', data });
    for (const ws of session.clients) if (ws.readyState === 1) ws.send(msg);
  });

  proc.onExit(({ exitCode }) => {
    sessions.delete(id);
    const msg = JSON.stringify({ type: 'exit', code: exitCode });
    for (const ws of session.clients) {
      if (ws.readyState === 1) ws.send(msg);
      ws.close();
    }
  });

  sessions.set(id, session);
  return session;
}

function listSessions() {
  return [...sessions.values()]
    .sort((a, b) => a.created - b.created)
    .map((s) => ({ id: s.id, name: s.name }));
}

// ---------------------------------------------------------------- http
const STATIC = {
  '/': ['public/index.html', 'text/html; charset=utf-8'],
  '/app.js': ['public/app.js', 'text/javascript; charset=utf-8'],
  '/style.css': ['public/style.css', 'text/css; charset=utf-8'],
  '/vendor/xterm.js': ['node_modules/@xterm/xterm/lib/xterm.js', 'text/javascript; charset=utf-8'],
  '/vendor/xterm.css': ['node_modules/@xterm/xterm/css/xterm.css', 'text/css; charset=utf-8'],
  '/vendor/addon-fit.js': ['node_modules/@xterm/addon-fit/lib/addon-fit.js', 'text/javascript; charset=utf-8'],
  '/vendor/addon-web-links.js': [
    'node_modules/@xterm/addon-web-links/lib/addon-web-links.js',
    'text/javascript; charset=utf-8',
  ],
};

function json(res, status, body, headers) {
  res.writeHead(status, Object.assign({ 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }, headers));
  res.end(JSON.stringify(body));
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let data = '';
    req.on('data', (c) => {
      data += c;
      if (data.length > 10000) {
        reject(new Error('too large'));
        req.destroy();
      }
    });
    req.on('end', () => resolve(data));
    req.on('error', reject);
  });
}

function isHttps(req) {
  return req.headers['x-forwarded-proto'] === 'https' || !!req.socket.encrypted;
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const p = url.pathname;

  if (req.method === 'GET' && STATIC[p]) {
    const [file, type] = STATIC[p];
    fs.readFile(path.join(__dirname, file), (err, buf) => {
      if (err) return json(res, 500, { error: 'missing file' });
      res.writeHead(200, { 'Content-Type': type, 'Cache-Control': 'no-cache', 'X-Content-Type-Options': 'nosniff' });
      res.end(buf);
    });
    return;
  }

  if (req.method === 'GET' && p === '/healthz') return json(res, 200, { ok: true });

  if (req.method === 'POST' && p === '/api/login') {
    const ip = clientIp(req);
    if (isLocked(ip)) return json(res, 429, { error: 'Too many attempts. Try again in a few minutes.' });
    let body = {};
    try {
      body = JSON.parse(await readBody(req));
    } catch (e) {
      return json(res, 400, { error: 'Bad request' });
    }
    if (!safeEqual(body.password || '', PASSWORD)) {
      recordFailure(ip);
      return json(res, 401, { error: 'Wrong password' });
    }
    failures.delete(ip);
    // The live preview is shown inside an iframe on another site, where the
    // browser only sends cookies marked SameSite=None; Secure.
    const attrs = isHttps(req) ? 'SameSite=None; Secure' : 'SameSite=Lax';
    return json(res, 200, { ok: true }, {
      'Set-Cookie': `termant=${TOKEN}; Path=/; HttpOnly; Max-Age=2592000; ${attrs}`,
    });
  }

  if (req.method === 'POST' && p === '/api/logout') {
    const attrs = isHttps(req) ? 'SameSite=None; Secure' : 'SameSite=Lax';
    return json(res, 200, { ok: true }, { 'Set-Cookie': `termant=; Path=/; HttpOnly; Max-Age=0; ${attrs}` });
  }

  if (p.startsWith('/api/')) {
    if (!isAuthed(req)) return json(res, 401, { error: 'Not logged in' });

    if (req.method === 'GET' && p === '/api/sessions') return json(res, 200, { sessions: listSessions() });

    if (req.method === 'POST' && p === '/api/sessions') {
      if (sessions.size >= MAX_SESSIONS) return json(res, 409, { error: `Limit of ${MAX_SESSIONS} tabs reached` });
      const s = createSession();
      return json(res, 200, { id: s.id, name: s.name });
    }

    const m = p.match(/^\/api\/sessions\/([a-f0-9]+)$/);
    if (req.method === 'DELETE' && m) {
      const s = sessions.get(m[1]);
      if (s) s.proc.kill('SIGHUP');
      return json(res, 200, { ok: true });
    }
  }

  json(res, 404, { error: 'Not found' });
});

// ---------------------------------------------------------------- websocket
const wss = new WebSocketServer({ noServer: true, maxPayload: 1024 * 1024 });

server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url, 'http://localhost');
  const session = sessions.get(url.searchParams.get('sid') || '');

  // Block other websites from opening a terminal with the visitor's cookie.
  const origin = req.headers.origin;
  let originOk = true;
  if (origin) {
    try {
      originOk = new URL(origin).host === req.headers.host;
    } catch (e) {
      originOk = false;
    }
  }

  if (url.pathname !== '/ws' || !isAuthed(req) || !originOk || !session) {
    socket.write('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n');
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, (ws) => attach(ws, session));
});

function attach(ws, session) {
  ws.isAlive = true;
  ws.on('pong', () => (ws.isAlive = true));

  // Replay what happened while the browser was away.
  if (session.chunks.length) ws.send(JSON.stringify({ type: 'output', data: session.chunks.join('') }));
  session.clients.add(ws);

  ws.on('message', (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw.toString());
    } catch (e) {
      return;
    }
    if (msg.type === 'input' && typeof msg.data === 'string') {
      session.proc.write(msg.data);
    } else if (msg.type === 'resize') {
      const cols = Math.max(2, Math.min(500, msg.cols | 0));
      const rows = Math.max(1, Math.min(300, msg.rows | 0));
      try {
        session.proc.resize(cols, rows);
      } catch (e) {
        /* shell already gone */
      }
    }
  });

  ws.on('close', () => session.clients.delete(ws));
  ws.on('error', () => session.clients.delete(ws));
}

// Keep connections alive through proxies and drop dead ones.
setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) {
      ws.terminate();
      continue;
    }
    ws.isAlive = false;
    ws.ping();
  }
}, 25000).unref();

server.listen(PORT, HOST, () => {
  console.log(`Termant listening on http://${HOST}:${PORT}  (shell: ${SHELL}, home: ${HOME})`);
});

function shutdown() {
  for (const s of sessions.values()) {
    try {
      s.proc.kill('SIGHUP');
    } catch (e) {
      /* ignore */
    }
  }
  process.exit(0);
}
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);

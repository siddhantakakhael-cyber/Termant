'use strict';
// The chat for phones and other PCs on the local network. The page talks to the
// same chat the window shows: a message sent from a phone appears in the app on
// the screen, and the answer appears in both. It is served on https with a
// self-signed certificate so that a browser allows the microphone for voice.

const fs = require('fs');
const os = require('os');
const path = require('path');
const http = require('http');
const https = require('https');
const crypto = require('crypto');

const FIRST_PORT = 3000;
const MOST_TRIES = 11;
const MOST_TEXT = 4000;
const MOST_MESSAGES = 60;
const MOST_FAILURES = 5;
const REST_MS = 60 * 1000;

/** The IPv4 addresses a phone on this network can use to reach this PC. */
function lanIps() {
  const ips = [];
  const ifaces = os.networkInterfaces();
  for (const name of Object.keys(ifaces)) {
    for (const i of ifaces[name] || []) {
      if (i.family === 'IPv4' && !i.internal) ips.push(i.address);
    }
  }
  return ips;
}

/** A self-signed certificate for the local addresses; made once, kept in dir. */
function certificate(dir) {
  const keyFile = path.join(dir, 'web-key.pem');
  const certFile = path.join(dir, 'web-cert.pem');
  const meta = path.join(dir, 'web-cert.json');
  const ips = lanIps();
  try {
    const old = JSON.parse(fs.readFileSync(meta, 'utf8'));
    const same = old.ips && old.ips.length === ips.length && old.ips.every((ip) => ips.includes(ip));
    if (same && fs.existsSync(keyFile) && fs.existsSync(certFile)) return Promise.resolve({ key: fs.readFileSync(keyFile), cert: fs.readFileSync(certFile) });
  } catch (e) {
    // make a new one
  }
  const selfsigned = require('selfsigned');
  const options = { keySize: 2048, notAfterDate: new Date(Date.now() + 3650 * 86400000) };
  if (ips.length) options.extensions = [{ name: 'subjectAltName', altNames: ips.map((ip) => ({ ip })) }];
  return Promise.resolve(selfsigned.generate([{ name: 'commonName', value: 'Termant' }], options))
    .then((pair) => {
      fs.writeFileSync(keyFile, pair.private);
      fs.writeFileSync(certFile, pair.cert);
      fs.writeFileSync(meta, JSON.stringify({ ips }));
      return { key: pair.private, cert: pair.cert };
    });
}

/** A short password a phone can type; no i, l, o, 0 or 1, which look alike. */
function newPassword() {
  const alphabet = 'abcdefghjkmnpqrstuvwxyz23456789';
  const bytes = crypto.randomBytes(8);
  let text = '';
  for (const b of bytes) text += alphabet[b % alphabet.length];
  return text;
}

function safeEqual(a, b) {
  const ha = crypto.createHash('sha256').update(String(a)).digest();
  const hb = crypto.createHash('sha256').update(String(b)).digest();
  return crypto.timingSafeEqual(ha, hb);
}

/**
 * Tries to make `makeServer(port)` listen on port, then on the next ports up.
 * The callback gets (error, server, port); port is 0 when no port was free.
 */
function onFreePort(makeServer, first, done) {
  let tried = 0;
  const next = () => {
    if (tried >= MOST_TRIES) return done(null, null, 0);
    const port = first + tried++;
    let server;
    try {
      server = makeServer(port);
    } catch (e) {
      return done(e, null, 0);
    }
    server.once('error', (e) => {
      if (e.code === 'EADDRINUSE' || e.code === 'EACCES') {
        try { server.close(); } catch (err) { /* not open */ }
        return next();
      }
      done(e, null, 0);
    });
    server.listen(port, '0.0.0.0', () => done(null, server, port));
  };
  next();
}

class WebChat {
  /**
   * deps:
   *   dir            the app's data folder (certificate and port live here)
   *   log            (text)
   *   settings       () => the settings object (webOn, webPassword, userName, lang)
   *   webState       () => {chat, busy} like the window's current chat
   *   sendMessage    (text, from) => promise; runs the message through the agent
   *   stop           ()           stops a running answer
   *   saveSettings   (patch)      writes the settings (a made password is kept)
   *   webChanged     ()           the window's Settings page redraws
   */
  constructor(deps) {
    this.deps = deps;
    this.httpsServer = null;
    this.httpServer = null;
    this.httpsPort = 0;
    this.httpPort = 0;
    this.secret = crypto.randomBytes(24).toString('hex');
    this.failures = new Map(); // ip -> {count, until}
  }

  /** What the window's Settings shows: the addresses and the password. */
  status() {
    const s = this.deps.settings();
    const ip = lanIps()[0] || '127.0.0.1';
    return {
      on: s.webOn !== false && this.httpsPort > 0,
      https: this.httpsPort ? 'https://' + ip + ':' + this.httpsPort + '/' : '',
      http: this.httpPort ? 'http://' + ip + ':' + this.httpPort + '/' : '',
      password: s.webPassword || '',
    };
  }

  start() {
    const self = this;
    const s = this.deps.settings();
    if (s.webPassword === undefined) s.webPassword = '';
    if (!s.webPassword) {
      s.webPassword = newPassword();
      this.deps.saveSettings({ webPassword: s.webPassword });
    }
    const listenPlain = (first) => onFreePort(() => http.createServer((req, res) => self.handle(req, res)), first, (error2, server2, port2) => {
      if (error2) self.deps.log('web chat: plain address failed: ' + (error2.message || error2));
      self.httpServer = server2;
      self.httpPort = server2 ? port2 : 0;
      self.deps.log('web chat: ' + (self.status().https || self.status().http || 'not running') +
        (self.httpsPort && self.httpPort ? ' (without the microphone: ' + self.status().http + ')' : ''));
      if (self.deps.webChanged) self.deps.webChanged();
    });
    certificate(this.deps.dir).then((cert) => {
      onFreePort(() => https.createServer({ key: cert.key, cert: cert.cert }, (req, res) => self.handle(req, res)), FIRST_PORT,
        (error, server, port) => {
          if (error) self.deps.log('web chat: https failed: ' + (error.message || error));
          self.httpsServer = server;
          self.httpsPort = server ? port : 0;
          listenPlain(self.httpsPort ? self.httpsPort + 1 : FIRST_PORT);
        });
    }).catch((e) => {
      self.deps.log('web chat: no certificate: ' + e.message);
      listenPlain(FIRST_PORT);
    });
  }

  stop() {
    for (const [name, key] of [['httpsServer', 'httpsPort'], ['httpServer', 'httpPort']]) {
      const server = this[name];
      if (server) {
        try { server.close(); } catch (e) { /* already gone */ }
        this[name] = null;
        this[key] = 0;
      }
    }
  }

  token() {
    return crypto.createHmac('sha256', this.secret).update('termant-web-v1').digest('hex');
  }

  isAuthed(request) {
    const m = String(request.headers.cookie || '').match(/(?:^|;)\s*termant=([a-f0-9]+)/);
    return !!m && safeEqual(m[1], this.token());
  }

  refused(ip) {
    const f = this.failures.get(ip);
    return !!f && f.until > Date.now();
  }

  countFailure(ip) {
    const f = this.failures.get(ip) || { count: 0, until: 0 };
    f.count++;
    if (f.count > MOST_FAILURES) f.until = Date.now() + REST_MS;
    this.failures.set(ip, f);
  }

  handle(request, response) {
    const parts = [];
    request.on('data', (part) => {
      parts.push(part);
      if (Buffer.concat(parts).length > 100000) { parts.length = 0; parts.push(Buffer.alloc(0)); request.destroy(); }
    });
    request.on('end', () => {
      try {
        this.route(request, response, Buffer.concat(parts).toString('utf8'));
      } catch (e) {
        this.deps.log('web chat: ' + (e && e.message || e));
        json(response, 500, { ok: false, error: 'the PC had a problem: ' + ((e && e.message) || e) });
      }
    });
  }

  route(request, response, body) {
    const url = (request.url || '/').split('?')[0];
    if (url === '/' || url === '/index.html') {
      let page;
      try {
        page = fs.readFileSync(path.join(__dirname, '..', 'web', 'index.html'), 'utf8')
          .replace('<!--STYLE-->', fs.readFileSync(path.join(__dirname, '..', 'web', 'style.css'), 'utf8').replace(/<\//g, '<\\/'))
          .replace('<!--APP-JS-->', fs.readFileSync(path.join(__dirname, '..', 'web', 'app.js'), 'utf8').replace(/<\//g, '<\\/'));
      } catch (e) {
        return json(response, 500, { ok: false, error: 'the page is missing from the app' });
      }
      response.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' });
      return response.end(page);
    }
    if (request.method !== 'POST' && request.method !== 'GET') return json(response, 405, { ok: false, error: 'not allowed' });
    const ip = String(request.headers['x-forwarded-for'] || request.socket.remoteAddress || '?').split(',')[0].trim();
    if (url === '/api/login') {
      if (this.refused(ip)) return json(response, 429, { ok: false, error: 'too many wrong passwords; wait a minute' });
      let sent = '';
      try { sent = String(JSON.parse(body || '{}').password || ''); } catch (e) { sent = ''; }
      const want = String(this.deps.settings().webPassword || '');
      if (!sent || !safeEqual(sent, want)) {
        this.countFailure(ip);
        return json(response, 401, { ok: false, error: 'wrong password' });
      }
      this.failures.delete(ip);
      response.writeHead(200, { 'content-type': 'application/json; charset=utf-8',
        'set-cookie': 'termant=' + this.token() + '; Path=/; HttpOnly; SameSite=Strict' });
      return response.end(JSON.stringify({ ok: true }));
    }
    if (!this.isAuthed(request)) return json(response, 401, { ok: false, error: 'the password is wanted' });
    if (url === '/api/state') {
      const w = this.deps.webState();
      const chat = w.chat;
      const messages = (chat && chat.messages ? chat.messages.slice(-MOST_MESSAGES) : []).map((m) => ({
        user: !!m.user,
        text: String(m.text || '').slice(0, 6000),
        from: m.from || '',
        steps: (m.steps || []).slice(-12).map((st) => (typeof st === 'string' ? st : st.text || '')).slice(0, 12),
        failed: !!m.failed,
      }));
      return json(response, 200, {
        ok: true,
        assistant: 'Termant',
        name: String(this.deps.settings().userName || ''),
        lang: String(this.deps.settings().lang || 'en'),
        title: chat && chat.title ? chat.title : '',
        busy: w.busy ? { activity: w.busy.activity, detail: w.busy.detail || '', output: w.busy.output || '' } : null,
        messages,
      });
    }
    if (request.method !== 'POST') return json(response, 405, { ok: false, error: 'not allowed' });
    if (url === '/api/message') {
      let text = '';
      try { text = String(JSON.parse(body || '{}').text || ''); } catch (e) { text = ''; }
      text = text.trim().slice(0, MOST_TEXT);
      if (!text) return json(response, 400, { ok: false, error: 'empty' });
      if (this.deps.webState().busy) return json(response, 409, { ok: false, error: 'busy' });
      const web = this;
      Promise.resolve(this.deps.sendMessage(text, 'web')).then((sent) => {
        if (!sent) web.deps.log('web chat: the message was not taken (busy or empty)');
      }).catch((e) => web.deps.log('web chat: ' + (e && e.message || e)));
      return json(response, 200, { ok: true });
    }
    if (url === '/api/stop') {
      try { this.deps.stop(); } catch (e) { /* already stopped */ }
      return json(response, 200, { ok: true });
    }
    if (url === '/api/logout') {
      response.writeHead(200, { 'content-type': 'application/json; charset=utf-8', 'set-cookie': 'termant=; Path=/; Max-Age=0' });
      return response.end(JSON.stringify({ ok: true }));
    }
    json(response, 404, { ok: false, error: 'not found' });
  }
}

function json(response, code, body) {
  response.writeHead(code, { 'content-type': 'application/json; charset=utf-8' });
  response.end(JSON.stringify(body));
}

module.exports = { WebChat, lanIps, newPassword, onFreePort, certificate };

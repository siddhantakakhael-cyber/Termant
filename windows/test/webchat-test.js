'use strict';
// The web chat, the page a phone opens on the local network, tested without
// Electron: a phone is a plain browser, so a plain request is the test.
const fs = require('fs');
const os = require('os');
const path = require('path');
const http = require('http');
const https = require('https');
const { WebChat, newPassword } = require('../src/webchat');

let failed = 0, count = 0;
const check = (name, ok, detail) => {
  count++;
  if (!ok) failed++;
  console.log((ok ? 'PASS ' : 'FAIL ') + name + (ok || detail === undefined ? '' : ' :: ' + String(detail).slice(0, 400)));
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function request(base, port, url, { method = 'GET', body, cookie, plain } = {}) {
  const mod = plain ? http : https;
  const options = { host: '127.0.0.1', port, path: url, method, headers: {} };
  if (!plain) options.rejectUnauthorized = false;
  if (cookie) options.headers.cookie = cookie;
  if (body !== undefined) options.headers['content-type'] = 'application/json';
  return new Promise((resolve) => {
    const r = mod.request(options, (res) => {
      let text = '';
      res.on('data', (d) => { text += d; });
      res.on('end', () => resolve({ code: res.statusCode, text, cookie: res.headers['set-cookie'] || [] }));
    });
    r.on('error', (e) => resolve({ code: 0, text: e.message, cookie: [] }));
    if (body !== undefined) r.end(JSON.stringify(body));
    else r.end();
  });
}

(async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'termant-web-'));
  const sent = [];
  let busyNow = false;
  let stopped = 0;
  const settings = { webOn: true, webPassword: 'testpass', userName: 'Siddhanta', lang: 'en' };
  const web = new WebChat({
    dir,
    log: (line) => console.log('  log: ' + line),
    settings: () => settings,
    webState: () => ({
      chat: { id: 'c1', title: 'Test chat', messages: [
        { user: true, text: 'hello from the phone' },
        { user: false, text: 'hello to the phone', steps: ['pc open notepad'] },
      ] },
      busy: busyNow ? { activity: 'command', detail: 'node slow.js', output: 'got 2 of 6 MB' } : null,
    }),
    sendMessage: async (text, from) => { sent.push({ text, from }); return true; },
    stop: () => { stopped++; },
    saveSettings: (patch) => Object.assign(settings, patch),
  });
  web.start();
  let status = null;
  for (let i = 0; i < 100 && !(status = web.status()).https; i++) await sleep(100);
  check('the page is served on https', !!status.https, JSON.stringify(status));
  check('a plain address is served too, for browsers that skipped the certificate', !!status.http, JSON.stringify(status));
  const port = parseInt(status.http.split(':').pop().replace(/[^0-9]/g, ''), 10);

  let page = await request(null, port, '/', { plain: true });
  check('the page has the login and the chat', page.code === 200 && page.text.includes('id="login"') && page.text.includes('id="messages"') && page.text.includes('hey Termant'), page.code + ' ' + page.text.slice(0, 120));
  check('the page has the wake word for a microphone that mishears it', /t\[ea\]a\?r\?m\[ae\]\?nt/.test(page.text), '');

  let r = await request(null, port, '/api/state', { plain: true });
  check('without the password nothing is shown', r.code === 401, JSON.stringify(r.text));

  r = await request(null, port, '/api/login', { plain: true, method: 'POST', body: { password: 'wrong' } });
  check('a wrong password is refused', r.code === 401 && /wrong password/.test(r.text), JSON.stringify(r.text));

  r = await request(null, port, '/api/login', { plain: true, method: 'POST', body: { password: settings.webPassword } });
  const cookie = (r.cookie || [])[0].split(';')[0];
  check('the right password opens it', r.code === 200 && /termant=/.test(cookie || ''), JSON.stringify(r.cookie));

  r = await request(null, port, '/api/state', { plain: true, cookie });
  let state = {};
  try { state = JSON.parse(r.text); } catch (e) { /* not json */ }
  check('the phone sees the same chat the window has', state.ok && state.messages.length === 2 && state.messages[0].text === 'hello from the phone' && state.messages[1].steps[0] === 'pc open notepad', JSON.stringify(state).slice(0, 300));
  check('the phone is told who it is talking to', state.assistant === 'Termant' && state.name === 'Siddhanta', JSON.stringify({ a: state.assistant, n: state.name }));

  r = await request(null, port, '/api/message', { plain: true, cookie, method: 'POST', body: { text: '   ' } });
  check('an empty message is not sent', r.code === 400, JSON.stringify(r.text));

  r = await request(null, port, '/api/message', { plain: true, cookie, method: 'POST', body: { text: 'open notepad' } });
  check('a message from the phone reaches the agent', r.code === 200 && sent.length === 1 && sent[0].text === 'open notepad' && sent[0].from === 'web', JSON.stringify(sent));

  busyNow = true;
  r = await request(null, port, '/api/message', { plain: true, cookie, method: 'POST', body: { text: 'again' } });
  check('while the agent works, the phone is told to wait', r.code === 409 && /busy/.test(r.text), JSON.stringify(r.text));
  busyNow = false;

  r = await request(null, port, '/api/stop', { plain: true, cookie, method: 'POST', body: {} });
  check('the stop button reaches the agent', r.code === 200 && stopped === 1, JSON.stringify(r.text) + ' stopped=' + stopped);

  for (let i = 0; i < 6; i++) await request(null, port, '/api/login', { plain: true, method: 'POST', body: { password: 'no' + i } });
  r = await request(null, port, '/api/login', { plain: true, method: 'POST', body: { password: settings.webPassword } });
  check('many wrong passwords rest for a minute, right one included', r.code === 429, JSON.stringify(r.text));

  r = await request(null, port, '/api/logout', { plain: true, cookie, method: 'POST', body: {} });
  check('the logout tells the browser to forget the door', r.code === 200 && /Max-Age=0/.test((r.cookie || []).join(' ')), JSON.stringify(r.cookie));

  // the https side: the certificate the browser must trust once
  r = await request(null, parseInt(status.https.split(':').pop().replace(/[^0-9]/g, ''), 10), '/api/state', {});
  check('the https side answers (the browser will accept the certificate once)', r.code === 401 || r.code === 200, 'code ' + r.code);
  check('the certificate is kept in the app folder', fs.existsSync(path.join(dir, 'web-cert.pem')) && fs.existsSync(path.join(dir, 'web-key.pem')), '');

  const p = newPassword();
  check('a made password is short and has no look-alike letters', p.length === 8 && !/[il01o]/.test(p), p);

  web.stop();
  await sleep(200);
  r = await request(null, port, '/', { plain: true });
  check('the door closes when it is turned off', r.code === 0, 'code ' + r.code);

  fs.rmSync(dir, { recursive: true, force: true });
  console.log('RESULT: ' + (failed ? 'FAIL' : 'PASS') + ' (' + count + ' checks, ' + failed + ' failed)');
  process.exit(failed ? 1 : 0);
})().catch((e) => {
  console.error('ERROR', e);
  process.exit(1);
});

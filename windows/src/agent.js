// The AI agent is OpenCode (open source). This file downloads it on first use,
// writes its settings, and runs one message at a time: `opencode run --format json`
// prints what the agent does as lines of JSON, which become the reply and its steps.
const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');
const http = require('http');
const https = require('https');
const zlib = require('zlib');
const { spawn, execFile } = require('child_process');
const { newestLine, tailOf } = require('./progress');

const WINDOWS = process.platform === 'win32';
const EXE = WINDOWS ? 'opencode.exe' : 'opencode';
const LATEST_URL = 'https://opencode.ai/update/api/latest/cli/npm';
const MODEL = /^[A-Za-z0-9._-]+\/[A-Za-z0-9._:\/@#-]+$/;

/** Environment names of the services a key can be added for (the same list as the Android app). */
const PROVIDERS = [
  ['Anthropic (Claude)', 'ANTHROPIC_API_KEY'],
  ['OpenAI (GPT)', 'OPENAI_API_KEY'],
  ['Google Gemini', 'GOOGLE_GENERATIVE_AI_API_KEY'],
  ['OpenRouter', 'OPENROUTER_API_KEY'],
  ['Groq', 'GROQ_API_KEY'],
  ['DeepSeek', 'DEEPSEEK_API_KEY'],
  ['Cerebras', 'CEREBRAS_API_KEY'],
  ['Mistral', 'MISTRAL_API_KEY'],
];

function get(url, redirects) {
  return new Promise((resolve, reject) => {
    const request = https.get(url, { headers: { 'user-agent': 'Termant' } }, (response) => {
      const code = response.statusCode;
      if (code >= 300 && code < 400 && response.headers.location && (redirects || 0) < 5) {
        response.resume();
        resolve(get(new URL(response.headers.location, url).toString(), (redirects || 0) + 1));
      } else if (code !== 200) {
        response.resume();
        reject(new Error('HTTP ' + code + ' for ' + url));
      } else {
        resolve(response);
      }
    });
    request.on('error', reject);
    request.setTimeout(60000, () => request.destroy(new Error('No answer from ' + new URL(url).host)));
  });
}

async function getText(url) {
  const response = await get(url);
  const parts = [];
  for await (const part of response) parts.push(part);
  return Buffer.concat(parts).toString('utf8');
}

/**
 * Writes the one file whose name ends with `wanted` out of a .tgz that arrives as a stream.
 * A tar file is a row of 512-byte blocks: a header block, then the file's bytes padded to a full block.
 */
function untarOne(stream, wanted, target, onBytes) {
  return new Promise((resolve, reject) => {
    const gunzip = zlib.createGunzip();
    let pending = Buffer.alloc(0);
    let left = 0, padding = 0, out = null, found = false, failed = false;
    const fail = (e) => {
      if (failed) return;
      failed = true;
      if (out) out.destroy();
      reject(e);
    };
    gunzip.on('data', (chunk) => {
      pending = pending.length ? Buffer.concat([pending, chunk]) : chunk;
      for (;;) {
        if (left > 0) {
          const take = Math.min(left, pending.length);
          if (take === 0) return;
          if (out) out.write(pending.subarray(0, take));
          pending = pending.subarray(take);
          left -= take;
          if (left === 0 && out) {
            out.end();
            out = null;
          }
        } else if (padding > 0) {
          const skip = Math.min(padding, pending.length);
          if (skip === 0) return;
          pending = pending.subarray(skip);
          padding -= skip;
        } else {
          if (pending.length < 512) return;
          const header = pending.subarray(0, 512);
          pending = pending.subarray(512);
          const name = header.subarray(0, 100).toString('utf8').replace(/\0.*$/, '');
          if (!name) continue; // the empty blocks at the end
          const size = parseInt(header.subarray(124, 136).toString('ascii').replace(/[\0 ]/g, '') || '0', 8);
          const kind = String.fromCharCode(header[156] || 48);
          left = size;
          padding = (512 - (size % 512)) % 512;
          if ((kind === '0' || kind === '\0') && name.replace(/\\/g, '/').endsWith(wanted)) {
            found = true;
            out = fs.createWriteStream(target);
            out.on('error', fail);
          }
        }
      }
    });
    gunzip.on('error', fail);
    gunzip.on('end', () => {
      if (failed) return;
      if (!found) return fail(new Error('The download does not contain ' + wanted));
      resolve();
    });
    stream.on('data', (chunk) => onBytes && onBytes(chunk.length));
    stream.on('error', fail);
    stream.pipe(gunzip);
  });
}

class Agent {
  /**
   * dir: where OpenCode and its files live. pcDir: the folder with the `pc` command.
   * settings(): the app's settings as they are now. team(): the helper agents, as OpenCode's
   * settings name them (see team.js); may be left out.
   */
  constructor(dir, pcDir, settings, team) {
    this.dir = dir;
    this.pcDir = pcDir;
    this.settings = settings;
    this.team = team || null;
    this.bin = path.join(dir, 'bin', EXE);
    this.configDir = path.join(dir, 'config', 'opencode');
    this.learnedFile = path.join(dir, 'learned.md');
    this.guideFile = path.join(this.configDir, 'AGENTS.md');
    this.running = null;
    this.installing = null;
    this.kept = null;
    /** Whether the last message went through the kept server. */
    this.fast = false;
  }

  installed() {
    return fs.existsSync(this.bin);
  }

  /** Downloads OpenCode once. onProgress(fraction 0..1 or -1, megabytes done, megabytes in all). */
  install(onProgress) {
    if (this.installed()) return Promise.resolve();
    if (!this.installing) {
      this.installing = this.download(onProgress).finally(() => {
        this.installing = null;
      });
    }
    return this.installing;
  }

  async download(onProgress) {
    let version = '';
    try {
      version = JSON.parse(await getText(LATEST_URL)).version || '';
    } catch (e) {
      // the npm registry knows the newest version too
    }
    const arch = process.arch === 'arm64' ? 'arm64' : 'x64';
    const system = WINDOWS ? 'windows' : process.platform === 'darwin' ? 'darwin' : 'linux';
    const targets = [system + '-' + arch];
    if (arch === 'x64') targets.push(system + '-x64-baseline'); // for processors without AVX2
    let last = null;
    for (const target of targets) {
      try {
        const name = '@opencode/cli-' + target;
        const info = JSON.parse(await getText('https://registry.npmjs.org/' + name.replace('/', '%2f') + '/' + (version || 'latest')));
        const total = (info.dist && info.dist.unpackedSize) || 0;
        const response = await get(info.dist.tarball);
        const packed = parseInt(response.headers['content-length'] || '0', 10);
        fs.mkdirSync(path.dirname(this.bin), { recursive: true });
        const temp = this.bin + '.download';
        let done = 0;
        await untarOne(response, 'bin/' + EXE, temp, (bytes) => {
          done += bytes;
          if (onProgress) onProgress(packed ? done / packed : -1, done / 1048576, packed / 1048576);
        });
        if (!WINDOWS) fs.chmodSync(temp, 0o755);
        await this.check(temp, total);
        fs.renameSync(temp, this.bin);
        return;
      } catch (e) {
        last = e;
      }
    }
    throw last || new Error('OpenCode could not be downloaded');
  }

  /** The downloaded program must start on this processor. */
  check(file) {
    return new Promise((resolve, reject) => {
      // Windows only runs a file whose name ends in .exe
      const probe = WINDOWS ? file + '.exe' : file;
      if (WINDOWS) fs.copyFileSync(file, probe);
      execFile(probe, ['--version'], { timeout: 120000, windowsHide: true, env: this.env() }, (error, stdout) => {
        if (WINDOWS) fs.rm(probe, { force: true }, () => {});
        if (error || !/\d+\.\d+/.test(String(stdout))) reject(error || new Error('OpenCode did not start'));
        else resolve();
      });
    });
  }

  /** What the agent's process sees: its own settings folders, the `pc` command, the user's keys. */
  env() {
    const s = this.settings();
    const env = Object.assign({}, process.env);
    const pathName = Object.keys(env).find((k) => k.toLowerCase() === 'path') || 'PATH';
    // pc.cmd for PowerShell and cmd; bash/pc for Git Bash, which does not run .cmd files by their bare name
    env[pathName] = this.pcDir + path.delimiter + path.join(this.pcDir, 'bash') + path.delimiter + (env[pathName] || '');
    env.XDG_CONFIG_HOME = path.join(this.dir, 'config');
    env.XDG_DATA_HOME = path.join(this.dir, 'data');
    env.XDG_STATE_HOME = path.join(this.dir, 'state');
    env.XDG_CACHE_HOME = path.join(this.dir, 'cache');
    env.OPENCODE_CONFIG_DIR = this.configDir;
    env.OPENCODE_DISABLE_AUTOUPDATE = '1';
    env.NO_COLOR = '1';
    env.TERMANT_DATA = path.dirname(this.dir);
    // git must fail rather than wait for a password nobody can type
    env.GIT_TERMINAL_PROMPT = '0';
    env.GH_PROMPT_DISABLED = '1';
    for (const [name, value] of Object.entries(s.keys || {})) if (value) env[name] = value;
    if (s.github && s.github.token) {
      env.GH_TOKEN = s.github.token;
      env.GITHUB_TOKEN = s.github.token;
      env.GITHUB_USER = s.github.user || '';
      env.GIT_CONFIG_COUNT = '1';
      env.GIT_CONFIG_KEY_0 = 'credential.https://github.com.helper';
      env.GIT_CONFIG_VALUE_0 = '!f() { echo username=x-access-token; echo "password=$GH_TOKEN"; }; f';
    }
    return env;
  }

  /** Writes OpenCode's settings from the app's settings; done before every message. */
  writeConfig(guideSource) {
    const s = this.settings();
    fs.mkdirSync(this.configDir, { recursive: true });
    if (guideSource) {
      let guide = fs.readFileSync(guideSource, 'utf8');
      guide = guide.replace('{{WORKSPACE}}', () => s.workspace || '').replace('{{USER}}', () => os.userInfo().username);
      fs.writeFileSync(this.guideFile, guide);
    }
    if (!fs.existsSync(this.learnedFile)) fs.writeFileSync(this.learnedFile, '');
    const permissions = [
      { action: 'shell', resource: 'pc *', effect: 'allow' },
      { action: 'shell', resource: 'date *', effect: 'allow' },
    ];
    const config = {
      $schema: 'https://opencode.ai/config.json',
      instructions: [this.guideFile, this.learnedFile],
    };
    if (s.webSearch !== false) {
      permissions.push({ action: 'websearch', resource: '*', effect: 'allow' });
      permissions.push({ action: 'webfetch', resource: '*', effect: 'allow' });
      config.websearch = { provider: 'exa' };
    } else {
      config.websearch = false;
    }
    config.permissions = permissions;
    const servers = {};
    for (const [name, command] of Object.entries(s.mcp || {})) {
      // the command line as typed, run by the shell so that quotes work
      servers[name] = {
        type: 'local',
        command: WINDOWS ? ['cmd.exe', '/d', '/s', '/c', command] : ['/bin/sh', '-lc', command],
        timeout: { startup: 120000 },
      };
    }
    if (Object.keys(servers).length) config.mcp = { servers };
    // the helper agents the main one can hand parts of a job to
    const team = this.team ? this.team() : null;
    if (team && Object.keys(team).length) config.agent = team;
    fs.writeFileSync(path.join(this.configDir, 'opencode.jsonc'), JSON.stringify(config, null, 2) + '\n');
  }

  /** The models that can be chosen, "provider/model" each; the free ones of OpenCode first. */
  models() {
    return new Promise((resolve) => {
      if (!this.installed()) return resolve(null);
      execFile(this.bin, ['models', '--standalone'], {
        timeout: 120000, windowsHide: true, env: this.env(), maxBuffer: 8 * 1024 * 1024, cwd: this.dir,
      }, (error, stdout) => {
        const found = String(stdout || '').split(/\r?\n/).map((l) => l.trim()).filter((l) => MODEL.test(l));
        if (!found.length) return resolve(null);
        const own = found.filter((m) => m.startsWith('opencode/'));
        resolve(own.concat(found.filter((m) => !m.startsWith('opencode/'))));
      });
    });
  }

  /** What each model can take in (see attach.js), or null when OpenCode did not say. */
  modelFacts() {
    return new Promise((resolve) => {
      if (!this.installed()) return resolve(null);
      execFile(this.bin, ['api', '--standalone', 'GET', '/api/model'], {
        timeout: 90000, windowsHide: true, env: this.env(), maxBuffer: 32 * 1024 * 1024, cwd: this.dir,
      }, (error, stdout) => {
        try {
          const text = String(stdout || '');
          const facts = require('./attach').factsFrom(JSON.parse(text.slice(text.indexOf('{'))).data);
          resolve(Object.keys(facts).length ? facts : null);
        } catch (e) {
          resolve(null);
        }
      });
    });
  }

  /**
   * Sends one message. onEvent gets {kind:'step'|'text'|'status'|'session', ...} while the agent works.
   * A status carries the kind of work, what it is about, and (for a command that runs) `output`: the newest line it printed.
   * Resolves to {text, steps, failed, session, stopped}.
   */
  async run(options) {
    const kept = await this.server();
    this.fast = !!kept;
    let result = await this.runOnce(options, kept);
    if (kept && result.failed && !result.stopped && !result.heard) {
      // the kept server did not take the message: ask the slow, sure way
      this.stopServer();
      this.fast = false;
      result = await this.runOnce(options, null);
    }
    return result;
  }

  /** What must stay the same for the kept server to be used again. */
  stamp() {
    const s = this.settings();
    const read = (file) => {
      try {
        return fs.readFileSync(file, 'utf8');
      } catch (e) {
        return '';
      }
    };
    return crypto.createHash('sha256').update(JSON.stringify([s.keys, s.github,
      read(path.join(this.configDir, 'opencode.jsonc')), read(this.guideFile), read(this.learnedFile)])).digest('hex');
  }

  /**
   * Keeps one OpenCode server running between messages, so that a message does not wait for
   * OpenCode to start. Resolves to {url, password}, or null when that does not work here.
   */
  server() {
    if (process.env.TERMANT_NO_SERVER || !this.installed()) return Promise.resolve(null);
    const stamp = this.stamp();
    if (this.kept && !this.kept.dead && this.kept.stamp === stamp) return this.kept.ready;
    this.stopServer();
    const password = crypto.randomBytes(18).toString('hex');
    const kept = { stamp, dead: false, child: null, ready: null };
    kept.ready = new Promise((resolve) => {
      let out = '', done = false;
      const end = (value) => {
        if (done) return;
        done = true;
        clearTimeout(timer);
        if (!value) {
          if (this.kept === kept) this.stopServer();
          kept.dead = true;
        }
        resolve(value);
      };
      const timer = setTimeout(() => end(null), 30000);
      try {
        kept.child = spawn(this.bin, ['serve', '--hostname', '127.0.0.1', '--port', '0'], {
          cwd: this.dir, env: Object.assign(this.env(), { OPENCODE_PASSWORD: password }), windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'],
        });
      } catch (e) {
        return end(null);
      }
      const read = (chunk) => {
        out = (out + chunk.toString('utf8')).slice(-4000);
        const m = out.match(/https?:\/\/127\.0\.0\.1:\d+/);
        if (m) end({ url: m[0], password });
      };
      kept.child.stdout.on('data', read);
      kept.child.stderr.on('data', read);
      kept.child.on('error', () => end(null));
      kept.child.on('close', () => {
        kept.dead = true;
        end(null);
      });
    });
    this.kept = kept;
    return kept.ready;
  }

  stopServer() {
    const kept = this.kept;
    this.kept = null;
    if (!kept || !kept.child || kept.dead) return;
    kept.dead = true;
    if (WINDOWS) execFile('taskkill', ['/pid', String(kept.child.pid), '/T', '/F'], { windowsHide: true }, () => {});
    else kept.child.kill('SIGTERM');
  }

  /**
   * Follows the kept server's event feed while a message runs. `opencode run` names a command
   * only once it has ended; the feed names it when it starts, and tells the file the command
   * prints into. So onEvent gets {kind:'status', activity, detail, output}: what runs now and
   * the newest line it has printed (how far a download is, ...). Returns the function that
   * ends the following.
   */
  follow(kept, result, onEvent) {
    const tools = new Map();
    let session = result.session || '', doing = null, watch = null, last = '', buffer = '', request = null;
    const own = (id) => !!id && id === (session || result.session);
    const look = () => {
      if (!watch || !doing) return;
      const line = newestLine(tailOf(watch.file));
      if (!line || line === last) return;
      last = line;
      result.followed = (result.followed || 0) + 1;
      onEvent({ kind: 'status', activity: doing.activity, detail: doing.detail, output: line });
    };
    const take = (raw) => {
      let event;
      try {
        event = JSON.parse(raw);
      } catch (e) {
        return;
      }
      const type = String(event.type || '');
      const d = event.data || {};
      if (type === 'session.execution.started' || type === 'session.step.started') {
        // the first conversation that starts work is the one asked here; later ones are its helpers
        if (!session) session = result.session || String(d.sessionID || '');
      } else if (type === 'session.tool.input.started') {
        if (own(d.sessionID) && tools.size < 500) tools.set(d.id, d.name);
      } else if (type === 'session.tool.called') {
        if (!own(d.sessionID)) return;
        const input = d.input || {};
        const step = describeTool({ tool: tools.get(d.id) || (input.command ? 'shell' : ''), state: { input } });
        if (!step) return;
        doing = { id: d.id, activity: step.activity, detail: step.detail };
        watch = null;
        last = '';
        onEvent({ kind: 'status', activity: step.activity, detail: step.detail });
      } else if (type === 'session.tool.success' || type === 'session.tool.failed') {
        if (doing && doing.id === d.id) doing = watch = null;
      } else if (type === 'shell.created') {
        const info = d.info || {};
        if (info.file && doing && own((info.metadata || {}).sessionID)) watch = { id: info.id, file: String(info.file) };
      } else if (type === 'shell.exited') {
        if (watch && watch.id === d.id) {
          look();
          watch = null;
        }
      }
    };
    const timer = setInterval(look, 400);
    try {
      request = http.get(kept.url + '/api/event', {
        headers: { authorization: 'Basic ' + Buffer.from('opencode:' + kept.password).toString('base64'), accept: 'text/event-stream' },
      }, (response) => {
        response.setEncoding('utf8');
        response.on('data', (chunk) => {
          buffer += chunk;
          let at;
          while ((at = buffer.indexOf('\n')) >= 0) {
            const line = buffer.slice(0, at).trim();
            buffer = buffer.slice(at + 1);
            if (line.startsWith('data:')) take(line.slice(5));
          }
        });
        response.on('error', () => {});
      });
      request.on('error', () => {});
    } catch (e) {
      // without the feed the chat shows what it showed before: each step when it has ended
    }
    return () => {
      clearInterval(timer);
      if (request) request.destroy();
    };
  }

  runOnce({ text, session, model, cwd, onEvent, log, files }, kept) {
    return new Promise((resolve) => {
      const s = this.settings();
      const args = ['run', '--format', 'json'].concat(kept ? ['--server', kept.url] : ['--standalone']);
      if (s.auto !== false) args.push('--auto');
      if (session) args.push('--session', session);
      if (model) args.push('--model', model);
      // pictures and files the user sent with the message, handed to the model itself
      for (const file of files || []) args.push('--file', file);
      args.push(text);
      try {
        fs.mkdirSync(cwd, { recursive: true });
      } catch (e) {
        cwd = os.homedir();
      }
      const result = { text: '', steps: [], failed: false, session: session || '', stopped: false, helpers: [] };
      const unfollow = kept ? this.follow(kept, result, onEvent) : () => {};
      let child;
      try {
        const env = this.env();
        if (kept) env.OPENCODE_PASSWORD = kept.password;
        // OpenCode takes its folder from this name when it is there (it is, outside Windows)
        env.PWD = cwd;
        child = spawn(this.bin, args, { cwd, env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
      } catch (e) {
        unfollow();
        result.failed = true;
        result.text = String(e.message || e);
        return resolve(result);
      }
      this.running = { child, result };
      let buffer = '', errors = '';
      const seen = new Set();
      const line = (raw) => {
        if (log) log(raw);
        let event;
        try {
          event = JSON.parse(raw);
        } catch (e) {
          return;
        }
        result.heard = true;
        if (event.sessionID && !result.session) {
          result.session = event.sessionID;
          onEvent({ kind: 'session', session: result.session });
        }
        const part = event.part || {};
        if (event.type === 'error') {
          const message = (event.error && (event.error.message || event.error.type)) || 'The model did not answer';
          result.failed = true;
          result.error = message;
        } else if (event.type === 'background_wait') {
          // the main agent has said its part and waits for the helpers it started
          const jobs = (event.jobs || []).map((j) => String((j && j.title) || '').trim()).filter(Boolean);
          onEvent({ kind: 'status', activity: 'team', detail: jobs.join(' · ') });
        } else if (event.type === 'retry') {
          onEvent({ kind: 'status', activity: 'wait', detail: (event.error && event.error.message) || '' });
        } else if (part.type === 'text' && typeof part.text === 'string') {
          if (part.id && seen.has(part.id)) return;
          if (part.id) seen.add(part.id);
          result.text += (result.text ? '\n\n' : '') + part.text.trim();
          onEvent({ kind: 'text', text: result.text });
        } else if (part.type === 'tool' || event.type === 'tool_use' || event.type === 'tool') {
          const step = describeTool(part);
          const key = part.callID || part.id;
          if (step && !(key && seen.has(key))) {
            if (key) seen.add(key);
            result.steps.push(step.text);
            if (step.helper && !result.helpers.includes(step.helper)) result.helpers.push(step.helper);
            onEvent({ kind: 'step', step: step.text, activity: step.activity, detail: step.detail });
          }
        } else if (part.type === 'step-start') {
          onEvent({ kind: 'status', activity: 'think', detail: '' });
        }
      };
      child.stdout.on('data', (chunk) => {
        buffer += chunk.toString('utf8');
        let at;
        while ((at = buffer.indexOf('\n')) >= 0) {
          const raw = buffer.slice(0, at).trim();
          buffer = buffer.slice(at + 1);
          if (raw) line(raw);
        }
      });
      child.stderr.on('data', (chunk) => {
        errors = (errors + chunk.toString('utf8')).slice(-4000);
      });
      const finish = (code) => {
        unfollow();
        if (this.running && this.running.child === child) this.running = null;
        if (buffer.trim()) line(buffer.trim());
        if (result.stopped) return resolve(result);
        if (!result.text && (result.failed || code !== 0)) {
          result.failed = true;
          result.text = result.error || errors.trim().split(/\r?\n/).slice(-3).join('\n') || 'The agent stopped (code ' + code + ').';
        }
        resolve(result);
      };
      child.on('error', (e) => {
        errors += String(e.message || e);
        finish(-1);
      });
      child.on('close', finish);
    });
  }

  /** Stops the message that is running, with everything it started. */
  stop() {
    const running = this.running;
    // the server would go on with the answer by itself, so it goes too; the next message starts a new one
    this.stopServer();
    if (!running) return;
    running.result.stopped = true;
    if (WINDOWS) {
      execFile('taskkill', ['/pid', String(running.child.pid), '/T', '/F'], { windowsHide: true }, () => {});
    } else {
      running.child.kill('SIGTERM');
    }
  }
}

/** One line for the list of what the agent did, and what kind of work it is. */
function describeTool(part) {
  const tool = String(part.tool || part.name || '').toLowerCase();
  if (!tool) return null;
  const state = part.state || {};
  const input = state.input || part.input || {};
  const one = (v) => String(v == null ? '' : v).replace(/\s+/g, ' ').trim();
  const file = one(input.filePath || input.file_path || input.path || input.file);
  if (tool === 'shell' || tool === 'bash' || tool === 'powershell' || tool === 'cmd') {
    const command = one(input.command || input.cmd || input.script);
    const pc = /^pc(\.cmd)?\s/.test(command);
    return { text: command || tool, activity: pc ? 'pc' : 'command', detail: command };
  }
  if (tool === 'read') return { text: 'Read ' + file, activity: 'read', detail: file };
  if (tool === 'write') return { text: 'Write ' + file, activity: 'edit', detail: file };
  if (tool === 'edit' || tool === 'patch' || tool === 'multiedit') return { text: 'Edit ' + file, activity: 'edit', detail: file };
  if (tool === 'websearch') return { text: 'web search: ' + one(input.query), activity: 'search', detail: one(input.query) };
  if (tool === 'webfetch') return { text: 'web page: ' + one(input.url), activity: 'web', detail: one(input.url) };
  if (tool === 'glob' || tool === 'grep' || tool === 'list' || tool === 'ls') {
    const what = one(input.pattern || input.query || file);
    return { text: tool + ' ' + what, activity: 'find', detail: what };
  }
  if (tool === 'subagent' || tool === 'task') {
    // a part of the job handed to a helper agent, which works on it by itself
    const who = one(input.agent || input.subagent_type) || 'general';
    const what = one(input.description || input.prompt).slice(0, 200);
    if (input.sessionID || input.task_id) return { text: 'More for helper ' + who + (what ? ': ' + what : ''), activity: 'team', detail: who + (what ? ': ' + what : ''), helper: who };
    return { text: 'Helper ' + who + (what ? ': ' + what : ''), activity: 'team', detail: who + (what ? ': ' + what : ''), helper: who };
  }
  if (tool.startsWith('todo')) return { text: 'plan', activity: 'think', detail: '' };
  const first = one(Object.values(input).find((v) => typeof v === 'string') || '');
  return { text: tool + (first ? ' ' + first : ''), activity: 'tool', detail: first };
}

module.exports = { Agent, PROVIDERS, untarOne, describeTool, getText };

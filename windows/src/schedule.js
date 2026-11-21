// Alarms, timers and rules: things that happen at a time, while Termant runs.
const { spawn } = require('child_process');
const path = require('path');

const WINDOWS = process.platform === 'win32';

/** "30s", "10m", "2h" as milliseconds; 0 if it is not a time span. */
function span(text) {
  const m = /^(\d+(?:\.\d+)?)\s*(s|sec|m|min|h|hr)?[a-z]*$/i.exec(String(text).trim());
  if (!m) return 0;
  const unit = (m[2] || 'm').toLowerCase()[0];
  return Math.round(parseFloat(m[1]) * (unit === 's' ? 1000 : unit === 'h' ? 3600000 : 60000));
}

/** "7:05" or "07:05" as [hours, minutes], or null. */
function clock(text) {
  const m = /^(\d{1,2})[:.](\d{2})$/.exec(String(text).trim());
  if (!m || +m[1] > 23 || +m[2] > 59) return null;
  return [+m[1], +m[2]];
}

/** A rule's trigger as typed ("at 23:00", "every 30m", "startup", "in 10m") in a fixed form, or null. */
function parseTrigger(text) {
  const words = String(text).trim().toLowerCase().split(/\s+/);
  if (words[0] === 'startup' && words.length === 1) return { kind: 'startup' };
  if (words.length !== 2) return null;
  if (words[0] === 'at') {
    const t = clock(words[1]);
    return t ? { kind: 'at', hour: t[0], minute: t[1] } : null;
  }
  if (words[0] === 'every' || words[0] === 'in') {
    const ms = span(words[1]);
    if (!ms || (words[0] === 'every' && ms < 60000)) return null;
    return { kind: words[0], ms };
  }
  return null;
}

function two(n) {
  return (n < 10 ? '0' : '') + n;
}

function describeTrigger(t) {
  if (t.kind === 'startup') return 'startup';
  if (t.kind === 'at') return 'at ' + two(t.hour) + ':' + two(t.minute);
  const ms = t.ms;
  const size = ms % 3600000 === 0 ? ms / 3600000 + 'h' : ms % 60000 === 0 ? ms / 60000 + 'm' : Math.round(ms / 1000) + 's';
  return t.kind + ' ' + size;
}

class Schedule {
  /**
   * store: where rules.json and alarms.json live. env(): the environment for a rule's command.
   * hooks: ring(label), changed(), log(text).
   */
  constructor(store, env, hooks) {
    this.store = store;
    this.env = env;
    this.hooks = hooks;
    this.rules = store.load('rules.json', []);
    this.alarms = store.load('alarms.json', []);
    this.timer = null;
  }

  start() {
    for (const rule of this.rules) if (rule.enabled && rule.trigger.kind === 'startup') this.run(rule);
    this.timer = setInterval(() => this.tick(), 2000);
  }

  stop() {
    clearInterval(this.timer);
  }

  save() {
    this.store.save('rules.json', this.rules);
    this.store.save('alarms.json', this.alarms);
    this.hooks.changed();
  }

  // ---- alarms and timers
  addAlarm(at, label, kind) {
    const id = (this.alarms.reduce((max, a) => Math.max(max, a.id), 0) || 0) + 1;
    this.alarms.push({ id, at, label: label || '', kind });
    this.save();
    return id;
  }

  removeAlarm(id) {
    const before = this.alarms.length;
    this.alarms = this.alarms.filter((a) => String(a.id) !== String(id));
    this.save();
    return before !== this.alarms.length;
  }

  // ---- rules
  addRule(triggerText, command) {
    const trigger = parseTrigger(triggerText);
    if (!trigger) throw new Error('I do not understand the trigger "' + triggerText + '". Use: at HH:MM, every 30m, startup, in 10m');
    if (!String(command || '').trim()) throw new Error('The rule has no command.');
    const id = (this.rules.reduce((max, r) => Math.max(max, r.id), 0) || 0) + 1;
    const rule = { id, trigger, command: String(command).trim(), enabled: true, created: Date.now(), lastRun: 0, lastExit: 0, lastKey: '' };
    this.rules.push(rule);
    this.save();
    return rule;
  }

  removeRule(id) {
    const before = this.rules.length;
    this.rules = this.rules.filter((r) => String(r.id) !== String(id));
    this.save();
    return before !== this.rules.length;
  }

  setEnabled(id, enabled) {
    const rule = this.rules.find((r) => String(r.id) === String(id));
    if (rule) {
      rule.enabled = !!enabled;
      if (enabled) rule.created = Date.now();
      this.save();
    }
  }

  describe(rule) {
    return describeTrigger(rule.trigger);
  }

  tick() {
    const now = Date.now();
    const due = this.alarms.filter((a) => a.at <= now);
    if (due.length) {
      this.alarms = this.alarms.filter((a) => a.at > now);
      this.save();
      // an alarm from long ago (the PC was off) is told, but does not ring
      for (const a of due) this.hooks.ring(a.label || (a.kind === 'timer' ? 'Timer' : 'Alarm'), now - a.at > 10 * 60000);
    }
    const date = new Date(now);
    for (const rule of this.rules.slice()) {
      if (!rule.enabled) continue;
      const t = rule.trigger;
      if (t.kind === 'at') {
        const key = date.toDateString() + ' ' + t.hour + ':' + t.minute;
        if (date.getHours() === t.hour && date.getMinutes() === t.minute && rule.lastKey !== key) {
          rule.lastKey = key;
          this.run(rule);
        }
      } else if (t.kind === 'every') {
        if (now - Math.max(rule.lastRun, rule.created) >= t.ms) this.run(rule);
      } else if (t.kind === 'in') {
        if (now - rule.created >= t.ms) {
          this.rules = this.rules.filter((r) => r !== rule);
          this.run(rule);
        }
      }
    }
  }

  /** Runs a rule's command in PowerShell and notes how it went. */
  run(rule) {
    rule.lastRun = Date.now();
    this.hooks.log('rule ' + rule.id + ' runs: ' + rule.command);
    let child;
    try {
      child = WINDOWS
        ? spawn('powershell.exe', ['-NoProfile', '-NoLogo', '-ExecutionPolicy', 'Bypass', '-Command', rule.command],
          { env: this.env(), windowsHide: true, stdio: 'ignore' })
        : spawn('/bin/sh', ['-c', rule.command], { env: this.env(), stdio: 'ignore' });
    } catch (e) {
      rule.lastExit = -1;
      this.save();
      return;
    }
    child.on('error', () => {
      rule.lastExit = -1;
      this.save();
    });
    child.on('close', (code) => {
      rule.lastExit = code == null ? -1 : code;
      this.hooks.log('rule ' + rule.id + ' ended with ' + rule.lastExit);
      this.save();
    });
    this.save();
  }
}

module.exports = { Schedule, span, clock, parseTrigger, describeTrigger };

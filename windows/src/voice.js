'use strict';
// Speaking with the saved voice keys: the Gemini voice (Google's free key, which speaks
// Hindi and Odia) or ElevenLabs. The ElevenLabs keys take turns: when one has used up
// its limit (or is refused), the next one is used, and the used-up one rests.

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const BASE = () => (process.env.TERMANT_ELEVEN_URL || 'https://api.elevenlabs.io').replace(/\/+$/, '');
const VOICE = 'JBFqnCBsd6RMkjVDRZzb';
const MODEL = 'eleven_multilingual_v2';
const GEMINI_BASE = () => (process.env.TERMANT_GEMINI_URL || 'https://generativelanguage.googleapis.com').replace(/\/+$/, '');
const GEMINI_MODEL = () => process.env.TERMANT_GEMINI_TTS_MODEL || 'gemini-3.8-flash-lite-tts';
const GEMINI_VOICE = 'Kore';
const MOST_LETTERS = 2500;
const HOUR = 3600000;

function keysFrom(text) {
  const seen = new Set();
  return (Array.isArray(text) ? text : String(text || '').split(/[\s,;]+/))
    .map((k) => String(k).trim()).filter((k) => k.length >= 8 && !seen.has(k) && seen.add(k)).slice(0, 30);
}

const tail = (key) => '...' + key.slice(-4);
const mark = (key) => crypto.createHash('sha256').update(key).digest('hex').slice(0, 12);

async function call(url, key, body) {
  const control = new AbortController();
  const timer = setTimeout(() => control.abort(), 60000);
  try {
    const response = await fetch(url, {
      method: body ? 'POST' : 'GET',
      headers: Object.assign({ 'xi-api-key': key }, body ? { 'content-type': 'application/json', accept: 'audio/mpeg' } : {}),
      body: body ? JSON.stringify(body) : undefined,
      signal: control.signal,
    });
    return { status: response.status, bytes: Buffer.from(await response.arrayBuffer()) };
  } catch (e) {
    const cause = e && e.cause && (e.cause.code || e.cause.message);
    throw new Error('ElevenLabs could not be reached (' + (cause || (e && e.name === 'AbortError' ? 'no answer in 60 s' : e.message)) + ').');
  } finally {
    clearTimeout(timer);
  }
}

async function geminiCall(url, key, body) {
  const control = new AbortController();
  const timer = setTimeout(() => control.abort(), 90000);
  try {
    const response = await fetch(url, {
      method: 'POST',
      headers: { 'x-goog-api-key': key, 'content-type': 'application/json' },
      body: JSON.stringify(body),
      signal: control.signal,
    });
    return { status: response.status, bytes: Buffer.from(await response.arrayBuffer()) };
  } catch (e) {
    const cause = e && e.cause && (e.cause.code || e.cause.message);
    throw new Error('Google could not be reached (' + (cause || (e && e.name === 'AbortError' ? 'no answer in 90 s' : e.message)) + ').');
  } finally {
    clearTimeout(timer);
  }
}

function wavWrap(pcm, rate) {
  // Google sends raw 16-bit little-endian PCM; this makes it a file a speaker can play.
  rate = rate || 24000;
  const head = Buffer.alloc(44);
  head.write('RIFF', 0); head.writeUInt32LE(36 + pcm.length, 4); head.write('WAVE', 8);
  head.write('fmt ', 12); head.writeUInt32LE(16, 16); head.writeUInt16LE(1, 20); head.writeUInt16LE(1, 22);
  head.writeUInt32LE(rate, 24); head.writeUInt32LE(rate * 2, 28); head.writeUInt16LE(2, 32); head.writeUInt16LE(16, 34);
  head.write('data', 36); head.writeUInt32LE(pcm.length, 40);
  return Buffer.concat([head, pcm]);
}

/** Why ElevenLabs said no: {status, message} out of its answer. */
function refusal(bytes) {
  try {
    const d = JSON.parse(bytes.toString('utf8')).detail;
    if (typeof d === 'string') return { status: '', message: d };
    return { status: String((d && d.status) || ''), message: String((d && d.message) || '') };
  } catch (e) {
    return { status: '', message: bytes.toString('utf8').slice(0, 200) };
  }
}

class Voice {
  /** dir: the app's data folder (which keys rest is kept in voice.json there). */
  constructor(dir) {
    this.file = path.join(dir, 'voice.json');
    this.geminiKeyFile = path.join(dir, 'gemini.key');
    try {
      this.state = JSON.parse(fs.readFileSync(this.file, 'utf8'));
    } catch (e) {
      this.state = {};
    }
    if (!this.state.rest) this.state.rest = {};
  }

  save() {
    try {
      fs.mkdirSync(path.dirname(this.file), { recursive: true });
      fs.writeFileSync(this.file, JSON.stringify(this.state, null, 1));
    } catch (e) {
      // not being able to remember only costs one more try next time
    }
  }

  resting(key) {
    const r = this.state.rest[mark(key)];
    if (r && r.until > Date.now()) return r;
    if (r) delete this.state.rest[mark(key)];
    return null;
  }

  /** The order to try the keys in: the one that worked last first, resting ones at the end. */
  order(keys) {
    const start = Math.max(0, keys.findIndex((k) => mark(k) === this.state.last));
    const turn = keys.map((k, i) => keys[(start + i) % keys.length]);
    return turn.filter((k) => !this.resting(k)).concat(turn.filter((k) => this.resting(k)));
  }

  geminiKey() {
    try { return fs.readFileSync(this.geminiKeyFile, 'utf8').trim(); } catch (e) { return ''; }
  }

  geminiSaveKey(key) {
    fs.mkdirSync(path.dirname(this.geminiKeyFile), { recursive: true });
    fs.writeFileSync(this.geminiKeyFile, key.trim() + '\n', { mode: 0o600 });
  }

  geminiAdd(key) {
    key = String(key || '').trim();
    if (key.length < 20 || !key.startsWith('AIza')) {
      throw new Error('That does not look like a Gemini key (it starts with AIza). Get a free one at aistudio.google.com.');
    }
    this.geminiSaveKey(key);
    this.state.engine = 'gemini';
    this.save();
    return 'Gemini key ' + tail(key) + ' saved; the Gemini voice speaks now.';
  }

  geminiRemove() {
    try { fs.unlinkSync(this.geminiKeyFile); } catch (e) { /* already gone */ }
    delete this.state.engine;
    this.save();
    return 'Gemini key removed.';
  }

  setEngine(want, keys) {
    if (want !== 'gemini' && want !== 'elevenlabs') throw new Error('Use: voice engine gemini | voice engine elevenlabs');
    if (want === 'gemini' && !this.geminiKey()) throw new Error('There is no Gemini key saved yet.');
    if (want === 'elevenlabs' && !keysFrom(keys).length) throw new Error('There is no ElevenLabs key saved yet.');
    this.state.engine = want;
    this.save();
    return 'The ' + want + ' voice speaks now.';
  }

  /** Which voice speaks now: the chosen one, or the one with keys saved. */
  pickEngine(keys) {
    keys = keysFrom(keys);
    const engine = this.state.engine || '';
    if (engine === 'gemini' && this.geminiKey()) return 'gemini';
    if (engine === 'elevenlabs' && keys.length) return 'elevenlabs';
    if (this.geminiKey() && !keys.length) return 'gemini';
    if (keys.length) return 'elevenlabs';
    return '';
  }

  /**
   * Turns text into speech with the Gemini voice (Google's free key).
   * Resolves to {audio (wav bytes), notes[]}.
   */
  async geminiSpeak(text) {
    if (!this.geminiKey()) {
      throw new Error('No Gemini key is saved. Add a free one (aistudio.google.com); it speaks Hindi and Odia.');
    }
    text = String(text || '').replace(/\s+/g, ' ').trim();
    if (!text) throw new Error('There is nothing to say.');
    const cut = text.length > MOST_LETTERS;
    if (cut) text = text.slice(0, MOST_LETTERS);
    const body = {
      contents: [{ parts: [{ text }] }],
      generationConfig: {
        responseModalities: ['AUDIO'],
        speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName: this.state.gemini_voice || GEMINI_VOICE } } },
      },
    };
    const r = await geminiCall(GEMINI_BASE() + '/v1beta/models/' + GEMINI_MODEL() + ':generateContent', this.geminiKey(), body);
    if (r.status === 200) {
      let data;
      try { data = JSON.parse(r.bytes.toString('utf8')); } catch (e) { throw new Error('Google answered without sound.'); }
      const inline = (((data.candidates || [])[0] || {}).content || {}).parts || [];
      const part = inline[0] || {};
      if (!part.inlineData || !part.inlineData.data) {
        throw new Error('Google answered without sound: ' + r.bytes.toString('utf8').slice(0, 200));
      }
      let audio = Buffer.from(part.inlineData.data, 'base64');
      if (audio.length < 100) throw new Error('Google sent no sound.');
      const mime = String(part.inlineData.mimeType || '');
      if (mime.indexOf('L16') !== -1 || mime.toLowerCase().indexOf('pcm') !== -1) audio = wavWrap(audio);
      const notes = [];
      if (cut) notes.push('only the first ' + MOST_LETTERS + ' letters were spoken');
      return { audio, notes };
    }
    let message = '';
    try { message = String(((JSON.parse(r.bytes.toString('utf8')).error) || {}).message || ''); } catch (e) { /* not json */ }
    if (r.status === 400 || r.status === 401 || r.status === 403) {
      throw new Error('Google refused the key (' + (message || 'answer ' + r.status) + '). Check it.');
    }
    if (r.status === 429) throw new Error('The free Gemini limit is reached for now. Try again later.');
    throw new Error('Google could not speak (answer ' + r.status + ': ' + (message || r.bytes.toString('utf8').slice(0, 160)) + ').');
  }

  /**
   * Turns text into speech. Resolves to {audio (mp3 bytes), key (which one, from 1), notes[]}.
   * Throws with a message for the user when no key can speak.
   */
  async speak(text, keys, options) {
    options = options || {};
    keys = keysFrom(keys);
    text = String(text || '').replace(/\s+/g, ' ').trim();
    if (!text) throw new Error('There is nothing to say.');
    if (!keys.length) throw new Error('No ElevenLabs key is saved.');
    const cut = text.length > MOST_LETTERS;
    if (cut) text = text.slice(0, MOST_LETTERS);
    const url = BASE() + '/v1/text-to-speech/' + encodeURIComponent(options.voice || VOICE) + '?output_format=mp3_44100_128';
    const notes = [];
    for (const key of this.order(keys)) {
      const n = keys.indexOf(key) + 1;
      const r = await call(url, key, { text, model_id: options.model || MODEL });
      if (r.status === 200 && r.bytes.length > 100) {
        this.state.last = mark(key);
        delete this.state.rest[mark(key)];
        this.save();
        if (cut) notes.push('only the first ' + MOST_LETTERS + ' letters were spoken');
        return { audio: r.bytes, key: n, notes };
      }
      const why = refusal(r.bytes);
      let rest = 0, reason = why.status || 'answer ' + r.status;
      if (r.status === 401 && /invalid_api_key|missing_api_key/.test(why.status)) rest = 7 * 24 * HOUR;
      else if (/quota_exceeded/.test(why.status) || r.status === 402) rest = 6 * HOUR;
      else if (r.status === 401 || r.status === 403) rest = 24 * HOUR;
      else if (r.status === 429) rest = 60000;
      else if (r.status >= 500) rest = 60000;
      else {
        // the request itself is wrong (the voice, the text): another key would say the same
        throw new Error('ElevenLabs refused: ' + (why.message || reason) + (why.status === 'voice_not_found' ? ' Pick a voice with: voice list' : ''));
      }
      this.state.rest[mark(key)] = { until: Date.now() + rest, why: reason, message: why.message.slice(0, 200) };
      notes.push('key ' + n + ' (' + tail(key) + '): ' + reason);
    }
    this.save();
    throw new Error('None of the ' + keys.length + ' ElevenLabs keys can speak now: ' + notes.join('; ') + '.');
  }

  /** One line per key: how much of its limit is used, and whether it rests. */
  async report(keys, online) {
    keys = keysFrom(keys);
    if (!keys.length && !this.geminiKey()) {
      return 'No voice key is saved. A free Google key (Hindi, Odia and more) or ElevenLabs keys: Settings.';
    }
    const lines = [];
    if (this.geminiKey()) {
      lines.push(this.pickEngine(keys) === 'gemini'
        ? 'Gemini key ' + tail(this.geminiKey()) + ' saved; the Gemini voice speaks now.'
        : 'Gemini key ' + tail(this.geminiKey()) + ' saved; the ' + this.pickEngine(keys) + ' voice speaks now.');
    }
    for (let i = 0; i < keys.length; i++) {
      const key = keys[i];
      let line = (i + 1) + '. ' + tail(key);
      if (online !== false) {
        try {
          const r = await call(BASE() + '/v1/user/subscription', key);
          const d = r.status === 200 ? JSON.parse(r.bytes.toString('utf8')) : null;
          if (d && d.character_limit) {
            line += '  ' + d.character_count + ' of ' + d.character_limit + ' letters used' + (d.tier ? ' (' + d.tier + ')' : '');
            if (d.character_count >= d.character_limit && !this.resting(key)) {
              this.state.rest[mark(key)] = { until: Date.now() + 6 * HOUR, why: 'quota_exceeded', message: '' };
            }
          } else line += '  ' + (refusal(r.bytes).status || 'answer ' + r.status);
        } catch (e) {
          line += '  (could not ask ElevenLabs)';
        }
      }
      const rest = this.resting(key);
      if (rest) line += '  resting until ' + new Date(rest.until).toLocaleString() + ': ' + rest.why;
      else if (mark(key) === this.state.last) line += '  in use';
      lines.push(line);
    }
    this.save();
    return lines.join('\n');
  }

  async voices(keys) {
    keys = keysFrom(keys);
    if (!keys.length) throw new Error('No ElevenLabs key is saved.');
    for (const key of this.order(keys)) {
      const r = await call(BASE() + '/v1/voices', key);
      if (r.status !== 200) continue;
      const list = (JSON.parse(r.bytes.toString('utf8')).voices || []).slice(0, 60);
      return list.map((v) => v.voice_id + '  ' + v.name + (v.labels ? '  ' + Object.values(v.labels).join(', ') : '')).join('\n') || 'No voices.';
    }
    throw new Error('No key could list the voices.');
  }

  forgetRest() {
    this.state.rest = {};
    this.save();
  }
}

module.exports = { Voice, keysFrom, tail, VOICE, MODEL, GEMINI_VOICE };

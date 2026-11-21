'use strict';
// What the agent teaches itself: YouTube (search, and the words spoken in a video),
// skills (step-by-step how-tos kept as files) and a diary of what was learned and when.
// Nothing here needs Electron, so `node test/learn-test.js` can run it anywhere.

const fs = require('fs');
const path = require('path');

const YOUTUBE = () => (process.env.TERMANT_YOUTUBE_URL || 'https://www.youtube.com').replace(/\/+$/, '');
const BROWSER = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';
const PHONE_APP = '20.10.38';
const MOST_SKILLS = 60;
const MOST_SKILL_LETTERS = 6000;
const MOST_ABOUT = 160;
const MOST_DIARY = 500;
const SKILL_NAME = /^[a-z][a-z0-9-]{1,39}$/;

async function ask(url, options) {
  const control = new AbortController();
  const timer = setTimeout(() => control.abort(), 25000);
  try {
    const headers = Object.assign({ 'user-agent': BROWSER, 'accept-language': 'en-US,en;q=0.8', cookie: 'SOCS=CAI; CONSENT=YES+1' },
      (options && options.headers) || {});
    const response = await fetch(url, Object.assign({}, options, { headers, signal: control.signal }));
    return { status: response.status, text: await response.text() };
  } catch (e) {
    const cause = e && e.cause && (e.cause.code || e.cause.message);
    throw new Error('YouTube could not be reached (' + (cause || (e && e.name === 'AbortError' ? 'no answer in 25 s' : e.message)) + '). Check the internet connection.');
  } finally {
    clearTimeout(timer);
  }
}

function json(text) {
  try {
    return JSON.parse(text);
  } catch (e) {
    return null;
  }
}

/** The object that follows `name = ` in a page, cut out by counting braces. */
function embedded(page, name) {
  let at = page.indexOf(name);
  while (at >= 0) {
    const open = page.indexOf('{', at);
    if (open < 0 || open - at > name.length + 8) {
      at = page.indexOf(name, at + name.length);
      continue;
    }
    let depth = 0, quote = false;
    for (let i = open; i < page.length; i++) {
      const c = page[i];
      if (quote) {
        if (c === '\\') i++;
        else if (c === '"') quote = false;
      } else if (c === '"') quote = true;
      else if (c === '{') depth++;
      else if (c === '}' && --depth === 0) return json(page.slice(open, i + 1));
    }
    return null;
  }
  return null;
}

function words(node) {
  if (!node) return '';
  if (typeof node === 'string') return node;
  if (node.simpleText) return String(node.simpleText);
  if (Array.isArray(node.runs)) return node.runs.map((r) => r.text || '').join('');
  return '';
}

/** Every video in an answer of YouTube's search, wherever it sits. */
function videos(tree) {
  const found = [], seen = new Set();
  const walk = (node) => {
    if (!node || typeof node !== 'object') return;
    if (Array.isArray(node)) return node.forEach(walk);
    const v = node.videoRenderer;
    if (v && v.videoId && !seen.has(v.videoId)) {
      seen.add(v.videoId);
      found.push({
        id: v.videoId,
        title: words(v.title),
        channel: words(v.ownerText) || words(v.longBylineText),
        length: words(v.lengthText),
        views: words(v.viewCountText),
        age: words(v.publishedTimeText),
      });
    }
    for (const key of Object.keys(node)) if (key !== 'videoRenderer') walk(node[key]);
  };
  walk(tree);
  return found;
}

async function youtubeSearch(query, most) {
  query = String(query || '').replace(/\s+/g, ' ').trim();
  if (!query) throw new Error('Say what to look for, for example: youtube search "mail merge in Word"');
  let list = [], why = '';
  try {
    const r = await ask(YOUTUBE() + '/youtubei/v1/search?prettyPrint=false', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ context: { client: { clientName: 'WEB', clientVersion: '2.20250101.00.00', hl: 'en' } }, query }),
    });
    if (r.status === 200) list = videos(json(r.text));
    else why = 'answer ' + r.status;
  } catch (e) {
    why = e.message;
  }
  if (!list.length) {
    const r = await ask(YOUTUBE() + '/results?hl=en&search_query=' + encodeURIComponent(query));
    if (r.status === 200) list = videos(embedded(r.text, 'ytInitialData'));
    else why = 'answer ' + r.status;
  }
  if (!list.length) throw new Error('YouTube gave no videos for "' + query + '"' + (why ? ' (' + why + ')' : '') + '. Try other words, or search the web instead.');
  return list.slice(0, most || 8);
}

function searchText(query, list) {
  return 'YouTube, "' + query + '": ' + list.length + ' videos\n' + list.map((v, i) =>
    (i + 1) + '. ' + v.title + '\n   ' + [v.channel, v.length, v.views, v.age].filter(Boolean).join(' | ') + '\n   id ' + v.id).join('\n') +
    '\nRead what is said in one of them:  youtube read ID';
}

function videoId(text) {
  text = String(text || '').trim();
  if (/^[A-Za-z0-9_-]{11}$/.test(text)) return text;
  const m = text.match(/(?:[?&]v=|youtu\.be\/|\/shorts\/|\/embed\/|\/live\/)([A-Za-z0-9_-]{11})/);
  if (!m) throw new Error('That is not a YouTube video: give the id (11 letters) or the link.');
  return m[1];
}

function unescape(text) {
  return text.replace(/<[^>]+>/g, '')
    .replace(/&#(\d+);/g, (all, n) => String.fromCodePoint(+n))
    .replace(/&#x([0-9a-f]+);/gi, (all, n) => String.fromCodePoint(parseInt(n, 16)))
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&amp;/g, '&');
}

/** Captions as [{at: seconds, text}], from YouTube's JSON or either of its XML forms. */
function captions(body) {
  const lines = [];
  const data = json(body);
  if (data && Array.isArray(data.events)) {
    for (const event of data.events) {
      const text = (event.segs || []).map((s) => s.utf8 || '').join('').replace(/\s+/g, ' ').trim();
      if (text) lines.push({ at: (event.tStartMs || 0) / 1000, text });
    }
    return lines;
  }
  const one = /<(text|p)\b([^>]*)>([\s\S]*?)<\/\1>/g;
  let m;
  while ((m = one.exec(body))) {
    const start = m[2].match(/\bstart="([\d.]+)"/), t = m[2].match(/\bt="(\d+)"/);
    const text = unescape(unescape(m[3])).replace(/\s+/g, ' ').trim();
    if (text) lines.push({ at: start ? parseFloat(start[1]) : t ? parseInt(t[1], 10) / 1000 : 0, text });
  }
  return lines;
}

function clock(seconds) {
  seconds = Math.max(0, Math.round(seconds));
  const h = Math.floor(seconds / 3600), m = Math.floor(seconds % 3600 / 60), s = seconds % 60;
  return (h ? h + ':' + String(m).padStart(2, '0') : String(m)) + ':' + String(s).padStart(2, '0');
}

async function player(id) {
  let last = '';
  try {
    const r = await ask(YOUTUBE() + '/youtubei/v1/player?prettyPrint=false', {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'user-agent': 'com.google.android.youtube/' + PHONE_APP + ' (Linux; U; Android 14) gzip' },
      body: JSON.stringify({ context: { client: { clientName: 'ANDROID', clientVersion: PHONE_APP, androidSdkVersion: 34, hl: 'en' } }, videoId: id }),
    });
    const data = r.status === 200 ? json(r.text) : null;
    if (data && data.videoDetails) return data;
    last = data && data.playabilityStatus ? words(data.playabilityStatus.reason) || data.playabilityStatus.status : 'answer ' + r.status;
  } catch (e) {
    last = e.message;
  }
  const page = await ask(YOUTUBE() + '/watch?v=' + id + '&hl=en');
  const data = page.status === 200 ? embedded(page.text, 'ytInitialPlayerResponse') : null;
  if (data && data.videoDetails) return data;
  const reason = data && data.playabilityStatus ? words(data.playabilityStatus.reason) || data.playabilityStatus.status : last || 'answer ' + page.status;
  throw new Error('YouTube did not give this video (' + reason + '). Try another video from the search.');
}

/**
 * What a video says. options: lang (like "hi"), from (minute to start at), most (letters).
 * Resolves to {id, title, channel, seconds, lang, auto, lines, text, more, description}.
 */
async function youtubeRead(which, options) {
  options = options || {};
  const id = videoId(which);
  const data = await player(id);
  const details = data.videoDetails || {};
  const tracks = (((data.captions || {}).playerCaptionsTracklistRenderer || {}).captionTracks || []).filter((t) => t.baseUrl);
  const result = {
    id, title: details.title || '', channel: details.author || '', seconds: parseInt(details.lengthSeconds, 10) || 0,
    description: String(details.shortDescription || '').trim(), lang: '', auto: false, lines: [], text: '', more: 0,
    languages: tracks.map((t) => t.languageCode + (t.kind === 'asr' ? ' (automatic)' : '')),
  };
  const wanted = [options.lang, 'en', 'hi'].filter(Boolean);
  const rank = (t) => {
    const at = wanted.findIndex((w) => t.languageCode === w || String(t.languageCode).startsWith(w + '-'));
    return (at < 0 ? 50 : at * 10) + (t.kind === 'asr' ? 1 : 0);
  };
  tracks.sort((a, b) => rank(a) - rank(b));
  for (const track of tracks.slice(0, 3)) {
    let url = track.baseUrl.replace(/&fmt=[a-z0-9]+/g, '') + '&fmt=json3';
    if (process.env.TERMANT_YOUTUBE_URL) url = url.replace(/^https?:\/\/[^/]+/, YOUTUBE());
    let r;
    try {
      r = await ask(url);
    } catch (e) {
      continue;
    }
    const lines = r.status === 200 ? captions(r.text) : [];
    if (!lines.length) continue;
    result.lang = track.languageCode;
    result.auto = track.kind === 'asr';
    result.lines = lines;
    break;
  }
  const from = Math.max(0, parseFloat(options.from) || 0) * 60;
  const most = options.most || 12000;
  let text = '', mark = -1e9;
  for (const line of result.lines) {
    if (line.at < from) continue;
    if (text.length + line.text.length > most) {
      result.more = Math.floor(line.at / 60);
      break;
    }
    if (line.at - mark >= 60) {
      text += (text ? '\n' : '') + '[' + clock(line.at) + '] ';
      mark = line.at;
    } else text += ' ';
    text += line.text;
  }
  result.text = text;
  return result;
}

function readText(v) {
  const out = ['YouTube video ' + v.id + ': ' + v.title, 'By ' + v.channel + (v.seconds ? ', ' + clock(v.seconds) + ' long' : '') + '. Link: https://youtu.be/' + v.id];
  if (v.text) {
    out.push('What is said (' + v.lang + (v.auto ? ', written down by a machine, so words can be wrong' : '') + '):', v.text);
    if (v.more) out.push('(cut here; the rest:  youtube read ' + v.id + ' --from ' + v.more + ')');
  } else {
    out.push(v.languages.length
      ? 'The words of this video could not be fetched (YouTube refused). Only its description is below; try another video.'
      : 'This video has no captions, so what is said in it cannot be read. Only its description is below; try another video.');
  }
  if (v.description && (!v.text || v.description.length < 1500)) out.push('Description:', v.description.slice(0, 3000));
  return out.join('\n');
}

// ---------------------------------------------------------------- skills and the diary

function day(date) {
  const d = date || new Date();
  return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
}

class Learned {
  /** dir: the app's data folder. */
  constructor(dir) {
    this.dir = dir;
    this.skillsDir = path.join(dir, 'skills');
    this.diaryFile = path.join(dir, 'journal.json');
  }

  diary() {
    try {
      const list = JSON.parse(fs.readFileSync(this.diaryFile, 'utf8'));
      return Array.isArray(list) ? list : [];
    } catch (e) {
      return [];
    }
  }

  /** Writes down that something was learned now. kind: skill, note or tool. */
  record(kind, name, text, from) {
    const list = this.diary();
    list.push({ day: day(), at: Date.now(), kind, name: name || '', text: text || '', from: from || '' });
    fs.mkdirSync(this.dir, { recursive: true });
    fs.writeFileSync(this.diaryFile, JSON.stringify(list.slice(-MOST_DIARY), null, 1));
  }

  /** What was learned in the last `days` days (1 = today), as [{day, kind, name, text, from}]. */
  since(days) {
    const first = new Date();
    first.setDate(first.getDate() - Math.max(1, days || 1) + 1);
    const from = day(first);
    return this.diary().filter((e) => e.day >= from);
  }

  line(e) {
    return '- ' + e.kind + (e.name ? ' ' + e.name : '') + ': ' + e.text + (e.from ? ' (from ' + e.from + ')' : '');
  }

  sinceText(days) {
    days = Math.max(1, parseInt(days, 10) || 1);
    const list = this.since(days);
    const head = days === 1 ? 'Learned today (' + day() + ')' : 'Learned in the last ' + days + ' days';
    if (!list.length) return head + ': nothing yet.';
    const out = [head + ': ' + list.length];
    let last = '';
    for (const e of list) {
      if (days > 1 && e.day !== last) out.push(e.day);
      last = e.day;
      out.push(this.line(e));
    }
    return out.join('\n');
  }

  skills() {
    let names = [];
    try {
      names = fs.readdirSync(this.skillsDir).filter((f) => f.endsWith('.md')).sort();
    } catch (e) {
      // no skill yet
    }
    return names.map((file) => {
      const text = fs.readFileSync(path.join(this.skillsDir, file), 'utf8');
      const head = text.split(/\r?\n---\r?\n/)[0];
      const field = (key) => ((head.match(new RegExp('^' + key + ': (.*)$', 'm')) || [])[1] || '').trim();
      return { name: file.slice(0, -3), about: field('what'), from: field('from'), day: field('learned') };
    });
  }

  saveSkill(name, about, steps, from) {
    name = String(name || '').trim();
    about = String(about || '').replace(/\s+/g, ' ').trim();
    steps = String(steps || '').replace(/\r\n/g, '\n').trim();
    from = String(from || '').replace(/\s+/g, ' ').trim().slice(0, 300);
    if (!SKILL_NAME.test(name)) throw new Error('The name must be 2 to 40 small letters, digits or dashes and start with a letter, like word-table.');
    if (!about) throw new Error('Say in a few words what the skill is for.');
    if (about.length > MOST_ABOUT) throw new Error('Describe it in at most ' + MOST_ABOUT + ' letters; this has ' + about.length + '.');
    if (steps.length < 20) throw new Error('The steps are missing. Write them to a file, numbered and short, and give the file.');
    if (steps.length > MOST_SKILL_LETTERS) throw new Error('The steps have ' + steps.length + ' letters; keep a skill under ' + MOST_SKILL_LETTERS + '. Keep only what is needed to do it.');
    const known = this.skills();
    const again = known.some((s) => s.name === name);
    if (!again && known.length >= MOST_SKILLS) throw new Error('There are already ' + MOST_SKILLS + ' skills. Remove one that is old first: skill list, then skill remove NAME.');
    fs.mkdirSync(this.skillsDir, { recursive: true });
    fs.writeFileSync(path.join(this.skillsDir, name + '.md'),
      '# ' + name + '\nwhat: ' + about + '\nfrom: ' + from + '\nlearned: ' + day() + '\n---\n' + steps + '\n');
    this.record('skill', name, about, from);
    return (again ? 'Skill updated: ' : 'Skill learned: ') + name + ' (' + about + '). You will find it in every chat from now on.';
  }

  showSkill(name) {
    name = String(name || '').trim();
    const file = path.join(this.skillsDir, name + '.md');
    if (!SKILL_NAME.test(name) || !fs.existsSync(file)) throw new Error('There is no skill "' + name + '" (see: skill list).');
    return fs.readFileSync(file, 'utf8').trim();
  }

  removeSkill(name) {
    this.showSkill(name);
    fs.unlinkSync(path.join(this.skillsDir, String(name).trim() + '.md'));
    return 'Skill removed: ' + String(name).trim();
  }

  listText() {
    const list = this.skills();
    return list.length ? list.map((s) => s.name + ': ' + s.about + (s.day ? '  (' + s.day + ')' : '')).join('\n') : 'No skills yet.';
  }

  /** The part of the agent's guide that names the skills. */
  guide(showCommand) {
    const list = this.skills();
    if (!list.length) return '';
    return '# Skills you taught yourself earlier\n\nBefore a task that one of these fits, read it with `' + showCommand +
      ' NAME` and follow it.\n\n' + list.map((s) => '- `' + s.name + '`: ' + s.about).join('\n') + '\n';
  }
}

module.exports = { youtubeSearch, searchText, youtubeRead, readText, videoId, captions, videos, embedded, Learned, day };

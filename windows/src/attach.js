// Files the user sends with a message: which of them the model gets handed itself,
// and which model is asked when the chosen one cannot take them.
const fs = require('fs');
const path = require('path');

const PICTURES = ['.png', '.jpg', '.jpeg', '.gif', '.webp'];
/** The biggest picture or PDF handed to a model; bigger ones the agent opens itself. */
const DIRECT_LIMIT = 4 * 1000 * 1000;
/** The biggest text file put into the message. */
const TEXT_LIMIT = 200 * 1024;
const MOST = 10;
/** Among the free models that can take the file, the ones tried first. */
const LIKED = ['glm', 'mimo', 'deepseek', 'longcat', 'hy4', 'inkling', 'nemotron'];
const MARK = /\[file: ([^\]\n]+)\]/g;

/** 'image', 'pdf', 'text' or 'other', by the first bytes and then by the ending. */
function kindOf(file) {
  let head = Buffer.alloc(0);
  try {
    const fd = fs.openSync(file, 'r');
    const buffer = Buffer.alloc(4096);
    head = buffer.subarray(0, fs.readSync(fd, buffer, 0, 4096, 0));
    fs.closeSync(fd);
  } catch (e) {
    return 'other';
  }
  const ending = path.extname(file).toLowerCase();
  if (head.subarray(0, 4).toString('latin1') === '%PDF') return 'pdf';
  if (head.subarray(0, 4).toString('latin1') === '\x89PNG' || (head[0] === 0xff && head[1] === 0xd8) ||
    head.subarray(0, 3).toString('latin1') === 'GIF' ||
    (head.subarray(0, 4).toString('latin1') === 'RIFF' && head.subarray(8, 12).toString('latin1') === 'WEBP')) return 'image';
  if (PICTURES.includes(ending) || ending === '.pdf') return 'other'; // the name says so, the bytes do not
  if (head.length && !head.includes(0)) return 'text';
  return 'other';
}

/**
 * The model to ask for files of these kinds, and the kinds it is shown.
 * facts: {"provider/model": {input: ['text', 'image', ...], free: bool}}.
 */
function choose(model, needs, facts) {
  const takes = (name, kind) => ((facts[name] || {}).input || []).includes(kind);
  needs = [...new Set(needs)];
  if (!needs.length) return { model, shown: [] };
  // the list of models could not be read: a picture is handed over all the same
  if (!Object.keys(facts || {}).length) return { model, shown: needs.filter((k) => k === 'image') };
  if (model && needs.every((k) => takes(model, k))) return { model, shown: needs };
  const free = Object.keys(facts).filter((n) => n.startsWith('opencode/') && facts[n].free).sort();
  for (const wanted of [needs, needs.filter((k) => k === 'image')]) {
    if (!wanted.length) continue;
    if (model && wanted.every((k) => takes(model, k))) return { model, shown: wanted };
    const able = free.filter((n) => wanted.every((k) => takes(n, k)));
    if (able.length) {
      const rank = (n) => {
        const at = LIKED.findIndex((w) => n.includes(w));
        return at < 0 ? LIKED.length : at;
      };
      able.sort((a, b) => rank(a) - rank(b) || (a < b ? -1 : 1));
      return { model: able[0], shown: wanted };
    }
  }
  return { model, shown: needs.filter((k) => model && takes(model, k)) };
}

/** The files named in a message as "[file: PATH]" that are there. */
function marked(text) {
  const found = [];
  for (const m of String(text || '').matchAll(MARK)) {
    const file = m[1].trim();
    try {
      if (fs.statSync(file).isFile() && !found.includes(file)) found.push(file);
    } catch (e) {
      // not there: the agent will say so
    }
  }
  return found.slice(0, MOST);
}

/** The message with its files marked, the way the agent is told about them. */
function mark(text, files) {
  const lines = (files || []).slice(0, MOST).map((f) => '[file: ' + f + ']');
  return lines.length ? String(text || '').trim() + '\n' + lines.join('\n') : String(text || '');
}

/**
 * What to do with the files of a message:
 * {model, files: [handed to the model itself], switched: bool, left: how many the agent must open itself}.
 */
function plan(model, files, facts) {
  const kinds = new Map();
  for (const file of files) {
    let size = 0;
    try {
      size = fs.statSync(file).size;
    } catch (e) {
      continue;
    }
    const kind = kindOf(file);
    if ((kind === 'image' || kind === 'pdf') && size <= DIRECT_LIMIT) kinds.set(file, kind);
    else if (kind === 'text' && size <= TEXT_LIMIT) kinds.set(file, 'text');
  }
  const needs = [...kinds.values()].filter((k) => k !== 'text');
  const picked = choose(model, needs, facts || {});
  const direct = [...kinds.keys()].filter((f) => kinds.get(f) === 'text' || picked.shown.includes(kinds.get(f)));
  return { model: picked.model, files: direct, switched: needs.length > 0 && picked.model !== model, left: files.length - direct.length };
}

/** OpenCode's list of models (the "data" of GET /api/model) as facts. */
function factsFrom(listed) {
  const facts = {};
  for (const m of listed || []) {
    if (!m || !m.providerID || !m.id) continue;
    const cost = Array.isArray(m.cost) && m.cost.length ? m.cost : [{}];
    facts[m.providerID + '/' + m.id] = {
      input: ((m.capabilities || {}).input || ['text']).slice(),
      free: !cost.some((c) => (c && (c.input || c.output)) ? true : false),
    };
  }
  return facts;
}

module.exports = { kindOf, choose, plan, mark, marked, factsFrom, DIRECT_LIMIT, MOST };

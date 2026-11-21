'use strict';
// The team: helper agents that the main agent hands parts of a job to. Each helper is an
// OpenCode "subagent": it works in its own conversation, at the same time as the others,
// and reports back. Four come with Termant; the user or the agent can add more.

const fs = require('fs');
const path = require('path');

const NAME = /^[a-z][a-z0-9-]{1,29}$/;
const MODEL = /^[A-Za-z0-9._-]+\/[A-Za-z0-9._:/@#-]+$/;
const MOST = 20;
const MOST_ABOUT = 200;
const MOST_TEXT = 6000;
/** Names OpenCode uses itself. */
const TAKEN = ['build', 'plan', 'general', 'explore', 'compaction', 'summary', 'title'];

const HANDS_OFF = 'Do not touch the screen, the mouse or the keyboard (`pc click`, `pc type`, `pc key`, `pc drag`, ' +
  '`pc open`, `pc screen`): there is one screen and the main agent has it. Do not ask the user anything; ' +
  'if something is missing, say so in your report. Your last message is your report to the main agent: short, only what it needs.';

const BUILTIN = [
  {
    name: 'researcher',
    about: 'Finds things out: searches the web and YouTube, reads pages and videos, and reports the facts or steps with their links. Give it one question or topic. It changes nothing.',
    text: 'You are the researcher of Termant\'s team on this Windows PC. Another agent gave you one question or topic. ' +
      'Find it out: search the web and read the pages; for how-tos also search YouTube with `pc youtube search WORDS` and read what a video says with `pc youtube read ID`. ' +
      'Report what you found: the facts or the steps in short, each with the link it came from, and say plainly what you could not find or are not sure of. ' +
      'Do not change or make files. ' + HANDS_OFF,
  },
  {
    name: 'writer',
    about: 'Writes or rewrites text: a letter, a report, a summary, a translation, a document. Give it the facts, the language and tone, and the file to save to (or it returns the text).',
    text: 'You are the writer of Termant\'s team on this Windows PC. Another agent told you what to write. ' +
      'Write it in the language and tone that was asked, from the facts you were given; do not invent facts, and mark what is missing as [missing: ...]. ' +
      'If you were given a file name, save the text there (a Word file with `pc word new FILE "first paragraph"` and `pc word add FILE "next paragraph"`); otherwise return the whole text in your report. ' +
      HANDS_OFF,
  },
  {
    name: 'coder',
    about: 'Writes a program or script, runs it and fixes it until it works. Give it exactly what the program must do and the folder to work in.',
    text: 'You are the coder of Termant\'s team on this Windows PC. Another agent told you what program or script to make. ' +
      'Make it in the folder you were told (or the working folder), run it, and fix what fails until it works. Keep to your own files: other helpers may be working in the same folder. ' +
      'Report the files you made, how to run them, and what your test showed. ' + HANDS_OFF,
  },
  {
    name: 'checker',
    about: 'Checks finished work against what was asked: reads the files, runs the program, and lists what is wrong or missing. Give it the request and where the work is. It changes nothing.',
    text: 'You are the checker of Termant\'s team on this Windows PC. Another agent gave you a request and the work that was done for it. ' +
      'Check the work against the request: read the files, run the program, try the hard cases. Change nothing. ' +
      'Report one line for each thing that is wrong or missing (with the file and place), most serious first; if everything is right, say that in one line. ' +
      HANDS_OFF,
  },
];

function oneLine(text) {
  return String(text == null ? '' : text).replace(/\s+/g, ' ').trim();
}

function today() {
  const d = new Date();
  const two = (n) => (n < 10 ? '0' : '') + n;
  return d.getFullYear() + '-' + two(d.getMonth() + 1) + '-' + two(d.getDate());
}

class Team {
  /** dir: the app's data folder. */
  constructor(dir) {
    this.file = path.join(dir, 'team.json');
  }

  /** The helpers the user or the agent made: [{name, about, text, model, day}]. */
  own() {
    try {
      const list = JSON.parse(fs.readFileSync(this.file, 'utf8'));
      return Array.isArray(list) ? list.filter((h) => h && NAME.test(h.name) && h.text) : [];
    } catch (e) {
      return [];
    }
  }

  /** All helpers, those of Termant first: [{name, about, model, day, own}]. */
  list() {
    return BUILTIN.map((h) => ({ name: h.name, about: h.about, model: '', day: '', own: false }))
      .concat(this.own().map((h) => ({ name: h.name, about: h.about, model: h.model || '', day: h.day || '', own: true })));
  }

  /** Makes a helper, or changes the one of that name. Returns the line to tell. */
  add(name, about, text, model) {
    name = String(name || '').trim().toLowerCase();
    about = oneLine(about);
    text = String(text || '').trim();
    model = String(model || '').trim();
    if (!NAME.test(name)) throw new Error('A helper\'s name is 2 to 30 small letters, digits or dashes and starts with a letter, like "poet" or "hindi-teacher".');
    if (TAKEN.includes(name) || BUILTIN.some((h) => h.name === name)) throw new Error('"' + name + '" is a helper that is already there and cannot be changed. Choose another name.');
    if (about.length < 10) throw new Error('Say in one sentence what this helper is for; the main agent picks helpers by that sentence.');
    if (about.length > MOST_ABOUT) throw new Error('What it is for is ' + about.length + ' letters; keep it to one sentence of at most ' + MOST_ABOUT + '.');
    if (text.length < 20) throw new Error('The instructions are too short. Write how this helper must work, in a few sentences.');
    if (text.length > MOST_TEXT) throw new Error('The instructions are ' + text.length + ' letters; the most is ' + MOST_TEXT + '.');
    if (model && !MODEL.test(model)) throw new Error('The model must be written as provider/model, like "opencode/big-pickle". Leave it out to use the chat\'s model.');
    const list = this.own();
    const at = list.findIndex((h) => h.name === name);
    if (at < 0 && list.length >= MOST) throw new Error('There are already ' + MOST + ' helpers of your own. Remove one first: pc team remove NAME');
    const helper = { name, about, text, model, day: today() };
    if (at < 0) list.push(helper); else list[at] = helper;
    this.save(list);
    return (at < 0 ? 'Made the helper "' : 'Changed the helper "') + name + '": ' + about +
      ' It can be used from the next message on.';
  }

  remove(name) {
    name = String(name || '').trim().toLowerCase();
    if (BUILTIN.some((h) => h.name === name)) throw new Error('"' + name + '" comes with Termant and stays.');
    const list = this.own();
    const rest = list.filter((h) => h.name !== name);
    if (rest.length === list.length) throw new Error('There is no helper "' + name + '". See them with: pc team');
    this.save(rest);
    return 'Removed the helper "' + name + '".';
  }

  show(name) {
    name = String(name || '').trim().toLowerCase();
    const h = BUILTIN.find((b) => b.name === name) || this.own().find((o) => o.name === name);
    if (!h) throw new Error('There is no helper "' + name + '". See them with: pc team');
    return 'Helper ' + h.name + (h.model ? ' (model ' + h.model + ')' : '') + '\nFor: ' + h.about + '\n\n' + h.text;
  }

  listText() {
    const list = this.list();
    const line = (h) => '- ' + h.name + ': ' + h.about + (h.model ? ' [model ' + h.model + ']' : '');
    const mine = list.filter((h) => h.own);
    return ['Helpers that come with Termant: ' + (list.length - mine.length)]
      .concat(list.filter((h) => !h.own).map(line))
      .concat(mine.length ? ['Helpers made here: ' + mine.length].concat(mine.map(line)) : ['Helpers made here: none yet. Make one: pc team add NAME "what it is for" FILE'])
      .join('\n');
  }

  save(list) {
    fs.mkdirSync(path.dirname(this.file), { recursive: true });
    fs.writeFileSync(this.file, JSON.stringify(list, null, 1));
  }

  /** The "agent" part of OpenCode's settings. */
  config() {
    const out = {};
    for (const h of BUILTIN) out[h.name] = { description: h.about, mode: 'subagent', prompt: h.text };
    for (const h of this.own()) {
      out[h.name] = { description: h.about, mode: 'subagent', prompt: h.text + '\n\n' + HANDS_OFF };
      if (h.model) out[h.name].model = h.model;
    }
    return out;
  }
}

module.exports = { Team, BUILTIN, NAME };

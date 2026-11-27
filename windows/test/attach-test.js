// Checks the files that go with a message (src/attach.js): which model is asked, what is handed
// over, and (when TERMANT_OPENCODE names the program) that a picture really reaches the model
// through OpenCode. A pretend model answers (see team-test.js).
//   node windows/test/attach-test.js
const fs = require('fs');
const os = require('os');
const path = require('path');
const zlib = require('zlib');
const attach = require('../src/attach');
const { fakeModel } = require('./team-test');

/** A small real PNG: 8 by 8, red. */
function png() {
  const chunk = (type, data) => {
    const body = Buffer.concat([Buffer.from(type, 'latin1'), data]);
    const size = Buffer.alloc(4), sum = Buffer.alloc(4);
    size.writeUInt32BE(data.length);
    sum.writeUInt32BE(zlib.crc32 ? zlib.crc32(body) >>> 0 : crc(body));
    return Buffer.concat([size, body, sum]);
  };
  const head = Buffer.alloc(13);
  head.writeUInt32BE(8, 0);
  head.writeUInt32BE(8, 4);
  head.set([8, 2, 0, 0, 0], 8);
  const row = Buffer.concat([Buffer.from([0]), Buffer.alloc(24, 0)]);
  for (let i = 1; i < 25; i += 3) row[i] = 255;
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', head),
    chunk('IDAT', zlib.deflateSync(Buffer.concat(Array(8).fill(row)))), chunk('IEND', Buffer.alloc(0))]);
}

function crc(buffer) {
  let c, sum = 0xffffffff;
  for (const byte of buffer) {
    c = (sum ^ byte) & 0xff;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    sum = (sum >>> 8) ^ c;
  }
  return (sum ^ 0xffffffff) >>> 0;
}

module.exports = { png };

if (require.main === module) (async () => {
  let failed = 0, count = 0;
  const check = (name, ok, detail) => {
    count++;
    if (!ok) failed++;
    console.log((ok ? 'PASS ' : 'FAIL ') + name + (ok || detail === undefined ? '' : ' :: ' + String(detail).slice(0, 500)));
  };
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'termant-attach-'));
  const file = (name, data) => {
    const full = path.join(dir, name);
    fs.writeFileSync(full, data);
    return full;
  };
  const picture = file('red.png', png());
  const photo = file('photo', Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), Buffer.alloc(40)]));
  const paper = file('paper.pdf', '%PDF-1.4\n1 0 obj<<>>endobj\n');
  const notes = file('notes.txt', 'WORDS-IN-THE-FILE chai is tea\n');
  const packed = file('things.zip', Buffer.concat([Buffer.from('PK\x03\x04', 'latin1'), Buffer.alloc(60)]));
  const fake = file('not-a-picture.png', 'these are only words');
  const big = file('big.png', Buffer.concat([png(), Buffer.alloc(attach.DIRECT_LIMIT)]));

  check('a picture is known by its first bytes', attach.kindOf(picture) === 'image' && attach.kindOf(photo) === 'image');
  check('a PDF, a text file and an archive are told apart', attach.kindOf(paper) === 'pdf' && attach.kindOf(notes) === 'text' && attach.kindOf(packed) === 'other');
  check('a file that is only named like a picture is not handed over as one', attach.kindOf(fake) === 'other');
  check('a file that is not there is "other"', attach.kindOf(path.join(dir, 'nothing.png')) === 'other');

  const facts = {
    'opencode/big-pickle': { input: ['text'], free: true },
    'opencode/hy4-flash-free': { input: ['text', 'image'], free: true },
    'opencode/glm-5.3-flash-free': { input: ['text', 'image'], free: true },
    'opencode/paid-eyes': { input: ['text', 'image'], free: false },
    'anthropic/claude': { input: ['text', 'image', 'pdf'], free: false },
    'groq/words-only': { input: ['text'], free: false },
  };
  let plan = attach.plan('anthropic/claude', [picture, paper], facts);
  check('a model that takes pictures and PDFs keeps both and stays', plan.model === 'anthropic/claude' && plan.files.length === 2 && !plan.switched && plan.left === 0, JSON.stringify(plan));
  plan = attach.plan('opencode/big-pickle', [picture], facts);
  check('a model that cannot see: a free one that can is asked', plan.model === 'opencode/glm-5.3-flash-free' && plan.switched && plan.files[0] === picture, JSON.stringify(plan));
  plan = attach.plan('groq/words-only', [picture], facts);
  check('never a paid model by itself', plan.model === 'opencode/glm-5.3-flash-free', JSON.stringify(plan));
  plan = attach.plan('opencode/big-pickle', [picture, paper], facts);
  check('no free model reads PDFs: the picture is shown, the PDF is left to the agent', plan.model === 'opencode/glm-5.3-flash-free' && plan.files.length === 1 && plan.left === 1, JSON.stringify(plan));
  plan = attach.plan('groq/words-only', [paper], facts);
  check('a PDF alone with a model that cannot read it: nothing is forced', plan.model === 'groq/words-only' && !plan.files.length && !plan.switched && plan.left === 1, JSON.stringify(plan));
  plan = attach.plan('opencode/big-pickle', [notes, packed], facts);
  check('a text file goes into the message with any model, an archive does not', plan.model === 'opencode/big-pickle' && plan.files.length === 1 && plan.files[0] === notes && plan.left === 1, JSON.stringify(plan));
  plan = attach.plan('opencode/big-pickle', [big], facts);
  check('a picture over 4 MB is not handed over', !plan.files.length && !plan.switched && plan.left === 1, JSON.stringify(plan));
  plan = attach.plan('opencode/big-pickle', [picture], {});
  check('without the list of models a picture is handed over all the same', plan.model === 'opencode/big-pickle' && plan.files.length === 1 && !plan.switched, JSON.stringify(plan));
  plan = attach.plan('', [picture], facts);
  check('with no model chosen, a free one that can see is asked', plan.model === 'opencode/glm-5.3-flash-free' && plan.switched, JSON.stringify(plan));

  const said = attach.mark('dekho', [picture, notes]);
  check('the files are marked in the message', said === 'dekho\n[file: ' + picture + ']\n[file: ' + notes + ']', said);
  check('and found again, only those that are there', attach.marked(said + '\n[file: ' + path.join(dir, 'gone.txt') + ']').join('|') === picture + '|' + notes);
  check('a message without files stays as it is', attach.mark('hello', []) === 'hello');
  const listed = attach.factsFrom([
    { providerID: 'opencode', id: 'a-free', capabilities: { input: ['text', 'image'] }, cost: [{ input: 0, output: 0 }] },
    { providerID: 'x', id: 'paid', capabilities: { input: ['text'] }, cost: [{ input: 1, output: 2 }] },
    { id: 'broken' }, null,
  ]);
  check('OpenCode\'s list of models is read', Object.keys(listed).join(' ') === 'opencode/a-free x/paid' && listed['opencode/a-free'].free && listed['opencode/a-free'].input.includes('image') && !listed['x/paid'].free, JSON.stringify(listed));

  const program = process.env.TERMANT_OPENCODE;
  if (!program) {
    console.log('SKIP the real run of OpenCode with a picture (name the program in TERMANT_OPENCODE)');
  } else {
    const { Agent } = require('../src/agent');
    const model = await fakeModel();
    const home = path.join(dir, 'agent');
    const folder = path.join(dir, 'work');
    fs.mkdirSync(path.join(home, 'bin'), { recursive: true });
    const settings = { keys: {}, github: {}, auto: true, workspace: folder, mcp: {}, webSearch: false };
    const agent = new Agent(home, path.join(__dirname, '..', 'resources'), () => settings, () => ({}));
    fs.symlinkSync(path.resolve(program), agent.bin);
    process.env.OPENCODE_CONFIG_CONTENT = JSON.stringify(model.config);
    agent.writeConfig(path.join(__dirname, '..', 'resources', 'AGENTS.md'));
    let result = await agent.run({ text: attach.mark('LOOK at these', [picture, notes]), model: model.model, cwd: folder, files: [picture, notes], onEvent: () => {} });
    check('a picture sent with a message reaches the model', /SAW pictures=1 /.test(result.text) && !result.failed, JSON.stringify(result.text));
    check('the words of a text file reach the model', /words=yes/.test(result.text), JSON.stringify(result.text));
    result = await agent.run({ text: 'LOOK again', model: model.model, cwd: folder, onEvent: () => {} });
    check('a message without files carries none', /SAW pictures=0 words=no/.test(result.text), JSON.stringify(result.text));
    const facts2 = await agent.modelFacts();
    if (facts2) {
      console.log('real-models=yes free-models-that-take-pictures=' + Object.keys(facts2).filter((n) => n.startsWith('opencode/') && facts2[n].free && facts2[n].input.includes('image')).length);
      check('the pretend model is in OpenCode\'s list', !!facts2[model.model], Object.keys(facts2).slice(0, 5).join(' '));
    } else {
      console.log('SKIP the real list of models (no answer from OpenCode)');
    }
    agent.stopServer();
    model.close();
  }
  console.log('RESULT: ' + (failed ? 'FAIL' : 'PASS') + ' (' + count + ' checks, ' + failed + ' failed)');
  setTimeout(() => process.exit(failed ? 1 : 0), 300);
})().catch((e) => {
  console.log('FAIL the test ran to its end :: ' + (e.stack || e));
  console.log('RESULT: FAIL');
  process.exit(1);
});

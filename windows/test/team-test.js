'use strict';
// Checks the helper agents: the list of helpers, how a hand-over is shown, and (when OpenCode
// is on this computer) a real run of OpenCode in which a pretend model hands two parts of a
// job to two helpers. Run:  node test/team-test.js
// For the real run, name the OpenCode program:  TERMANT_OPENCODE=/path/to/opencode node test/team-test.js
const http = require('http');
const fs = require('fs');
const os = require('os');
const path = require('path');

/**
 * A pretend chat model that speaks the OpenAI chat protocol. Asked "TEAM ...", it starts the
 * researcher and the helper "poet" in one step; the researcher runs one command and reports,
 * the poet reports; then the main agent's answer repeats what the helpers reported.
 * Asked "RUN command", the main agent runs that command and answers with what it printed.
 * Resolves to {url, config, model, asked, close}.
 */
function fakeModel() {
  const asked = [];
  const textOf = (m) => (typeof m.content === 'string' ? m.content
    : Array.isArray(m.content) ? m.content.map((p) => (p && p.text) || '').join(' ') : '');
  const answer = (body) => {
    const messages = body.messages || [];
    const system = messages.filter((m) => m.role === 'system').map(textOf).join(' ');
    const tools = (body.tools || []).map((t) => (t.function || {}).name);
    const users = messages.filter((m) => m.role === 'user').map(textOf);
    const results = messages.filter((m) => m.role === 'tool');
    if (system.includes('researcher of Termant')) {
      const shell = ['shell', 'bash', 'powershell'].find((name) => tools.includes(name));
      if (!results.length && shell) return { who: 'researcher', wait: 1500, calls: [[shell, { command: 'echo helper-ran > helper-ran.txt' }]] };
      return { who: 'researcher', text: 'RESEARCH REPORT: chai is tea.' };
    }
    if (system.includes('POET-MARK')) return { who: 'poet', wait: 1500, text: 'POEM REPORT: garam chai, meethi baat.' };
    if (!tools.length) return { who: 'title', text: 'Helpers' };
    // A helper's report comes back in a user message: <subagent ...> in OpenCode up to 2.3,
    // <background-result kind="subagent"> ... </background-result> in OpenCode 2.4 and later
    const reports = users.filter((u) => u.includes('<subagent ') || u.includes('<background-result kind="subagent"'));
    if (reports.length) {
      const said = reports.map((r) => r.replace(/<\/?(?:subagent|background-result)[^>]*>/g, ' ').replace(/\s+/g, ' ').trim());
      return { who: 'main', text: 'TEAM RESULT (' + reports.length + '): ' + said.join(' | ') };
    }
    // "RUN command": the main agent runs that command, then answers with what it printed
    const wanted = /^RUN (.+)$/m.exec(users[users.length - 1] || '');
    if (wanted) {
      const shell = ['shell', 'bash', 'powershell'].find((name) => tools.includes(name));
      const since = messages.slice(messages.map((m) => m.role).lastIndexOf('user') + 1).filter((m) => m.role === 'tool');
      if (!since.length && shell) return { who: 'main', calls: [[shell, { command: wanted[1].trim() }]] };
      return { who: 'main', text: 'RAN: ' + since.map(textOf).join(' ').slice(0, 1500) };
    }
    if (users.some((u) => u.includes('LOOK'))) {
      // how many pictures came with the message itself, and whether a text file's words are in it
      const last = messages.filter((m) => m.role === 'user').pop().content;
      const parts = Array.isArray(last) ? last : [];
      return { who: 'main', text: 'SAW pictures=' + parts.filter((p) => p && p.type === 'image_url').length +
        ' words=' + (users.some((u) => u.includes('WORDS-IN-THE-FILE')) ? 'yes' : 'no') };
    }
    if (results.length) return { who: 'main', text: 'I started two helpers.' };
    if (tools.includes('subagent') && users.some((u) => u.includes('TEAM'))) {
      return { who: 'main', calls: [
        ['subagent', { agent: 'researcher', description: 'Find out about chai', prompt: 'What is chai? Run your one command, then report in one line.' }],
        ['subagent', { agent: 'poet', description: 'A poem about chai', prompt: 'Write one line about chai.' }],
      ] };
    }
    return { who: 'main', text: 'plain answer' };
  };
  const server = http.createServer((req, res) => {
    let raw = '';
    req.on('data', (d) => { raw += d; });
    req.on('end', () => {
      if (req.method !== 'POST') {
        res.writeHead(200, { 'content-type': 'application/json' });
        return res.end('{"data":[{"id":"fake","object":"model"}]}');
      }
      let body = {};
      try {
        body = JSON.parse(raw);
      } catch (e) {
        // answered as a plain question
      }
      const a = answer(body);
      asked.push({ who: a.who, at: Date.now(), text: a.text || '', calls: (a.calls || []).map((c) => c[0]) });
      setTimeout(() => {
        res.writeHead(200, { 'content-type': 'text/event-stream' });
        const send = (delta, finish) => res.write('data: ' + JSON.stringify({ id: 'c1', object: 'chat.completion.chunk', created: 1, model: 'fake',
          choices: [{ index: 0, delta, finish_reason: finish || null }] }) + '\n\n');
        send({ role: 'assistant', content: '' });
        if (a.calls) {
          a.calls.forEach(([name, args], index) => send({ tool_calls: [{ index, id: 'call_' + asked.length + '_' + index, type: 'function',
            function: { name, arguments: JSON.stringify(args) } }] }));
          send({}, 'tool_calls');
        } else {
          send({ content: a.text });
          send({}, 'stop');
        }
        res.write('data: ' + JSON.stringify({ id: 'c1', object: 'chat.completion.chunk', created: 1, model: 'fake', choices: [],
          usage: { prompt_tokens: 10, completion_tokens: 5, total_tokens: 15 } }) + '\n\n');
        res.end('data: [DONE]\n\n');
      }, a.wait || 0);
    });
  });
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => {
    const url = 'http://127.0.0.1:' + server.address().port;
    resolve({
      url, asked, model: 'termanttest/fake', close: () => server.close(),
      // given to OpenCode in OPENCODE_CONFIG_CONTENT: one more provider, which is this pretend model
      config: { provider: { termanttest: { npm: '@ai-sdk/openai-compatible', name: 'Termant test', options: { baseURL: url + '/v1', apiKey: 'x' },
        models: { fake: { name: 'Fake' } } } } },
    });
  }));
}

const POET = { name: 'poet', about: 'Writes one short line of verse on a given subject.', text: 'POET-MARK You write one short line of verse about the subject you are given.' };

/** What a run with the pretend model must show. check(name, ok, detail). */
function judge(check, result, fake, folder) {
  const steps = result.steps || [];
  check('the main agent hands parts to two helpers', steps.includes('Helper researcher: Find out about chai') && steps.includes('Helper poet: A poem about chai'), JSON.stringify(steps));
  check('the helpers\' reports come back into the answer', /RESEARCH REPORT: chai is tea\./.test(result.text) && /POEM REPORT: garam chai/.test(result.text) && !result.failed, JSON.stringify(result.text));
  const at = (who, n) => (fake.asked.filter((a) => a.who === who)[n] || {}).at;
  check('the helpers work at the same time', at('poet', 0) && at('researcher', 1) && at('poet', 0) < at('researcher', 1),
    'poet asked at ' + at('poet', 0) + ', researcher reported at ' + at('researcher', 1));
  check('a helper runs a command by itself', fs.existsSync(path.join(folder, 'helper-ran.txt')), fs.readdirSync(folder).join(' '));
}

module.exports = { fakeModel, judge, POET };

if (require.main === module) {
  (async () => {
    const { Team } = require('../src/team');
    const { Agent, describeTool } = require('../src/agent');
    let failed = 0, count = 0;
    const check = (name, ok, detail) => {
      count++;
      if (!ok) failed++;
      console.log((ok ? 'PASS ' : 'FAIL ') + name + (ok || detail === undefined ? '' : ' :: ' + String(detail).slice(0, 500)));
    };
    const refused = (run, pattern) => {
      try {
        run();
      } catch (e) {
        return pattern.test(e.message) ? true : e.message;
      }
      return 'was accepted';
    };
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'termant-team-'));
    const team = new Team(dir);

    check('six helpers come with Termant', team.list().map((h) => h.name).join(' ') === 'researcher writer coder checker planner coach' && team.list().every((h) => !h.own));
    let told = team.add('Poet', '  Writes one short line\n of verse on a given subject. ', POET.text);
    check('a helper is made', /^Made the helper "poet": Writes one short line of verse on a given subject\./.test(told) && team.list().length === 7 && team.list()[6].own, told);
    told = team.add('poet', POET.about, POET.text, 'opencode/big-pickle');
    check('the same name changes the helper', /^Changed the helper "poet"/.test(told) && team.list().length === 7 && team.list()[6].model === 'opencode/big-pickle', told);
    check('a helper of Termant cannot be replaced', refused(() => team.add('checker', POET.about, POET.text), /already there/));
    check('a name of OpenCode cannot be taken', refused(() => team.add('build', POET.about, POET.text), /already there/));
    check('a wrong name is refused', refused(() => team.add('My Agent', POET.about, POET.text), /small letters/));
    check('a helper without a purpose is refused', refused(() => team.add('x1', 'short', POET.text), /what this helper is for/));
    check('too short instructions are refused', refused(() => team.add('x1', POET.about, 'do it'), /too short/));
    check('too long instructions are refused', refused(() => team.add('x1', POET.about, 'a'.repeat(6001)), /6001 letters/));
    check('a wrong model is refused', refused(() => team.add('x1', POET.about, POET.text, 'gpt five'), /provider\/model/));
    const config = team.config();
    check('OpenCode gets every helper as a subagent', Object.keys(config).join(' ') === 'researcher writer coder checker planner coach poet' &&
      Object.values(config).every((a) => a.mode === 'subagent' && a.description && a.prompt) && config.poet.model === 'opencode/big-pickle' && !('model' in config.checker));
    check('every helper is told to leave the screen alone', Object.values(config).every((a) => a.prompt.includes('Do not touch the screen')));
    check('the list names them all', /Helpers that come with Termant: 6\n- researcher: /.test(team.listText()) && /Helpers made here: 1\n- poet: .*\[model opencode\/big-pickle\]/.test(team.listText()), team.listText());
    check('one helper is shown', /^Helper poet \(model opencode\/big-pickle\)\nFor: Writes one short line/.test(team.show('poet')) && /POET-MARK/.test(team.show('poet')) && /^Helper checker\n/.test(team.show('checker')));
    check('a helper of Termant stays', refused(() => team.remove('writer'), /comes with Termant/));
    check('an unknown helper cannot be removed', refused(() => team.remove('nobody'), /no helper "nobody"/));
    for (let i = 0; i < 19; i++) team.add('helper-' + i, POET.about, POET.text);
    check('at most 20 helpers of one\'s own', refused(() => team.add('one-more', POET.about, POET.text), /already 20/) && team.list().length === 26);
    for (let i = 0; i < 19; i++) team.remove('helper-' + i);
    check('helpers are removed', team.list().length === 7);
    check('a broken file is an empty list', (fs.writeFileSync(team.file, '{oops'), team.list().length === 6));
    team.add('poet', POET.about, POET.text);

    let step = describeTool({ tool: 'subagent', state: { input: { agent: 'researcher', description: 'Find out about chai', prompt: 'long' } } });
    check('a hand-over is shown as a step', step.text === 'Helper researcher: Find out about chai' && step.activity === 'team' && step.helper === 'researcher', JSON.stringify(step));
    step = describeTool({ tool: 'subagent', state: { input: { agent: 'researcher', sessionID: 'ses_1', prompt: 'Also look at   green tea' } } });
    check('more work for a helper is shown as a step', step.text === 'More for helper researcher: Also look at green tea', JSON.stringify(step));

    const program = process.env.TERMANT_OPENCODE;
    if (!program) {
      console.log('SKIP the real run of OpenCode with helpers (name the program in TERMANT_OPENCODE)');
    } else {
      const fake = await fakeModel();
      const home = path.join(dir, 'agent');
      const folder = path.join(dir, 'work');
      fs.mkdirSync(path.join(home, 'bin'), { recursive: true });
      const settings = { keys: {}, github: {}, auto: true, workspace: folder, mcp: {}, webSearch: false };
      const agent = new Agent(home, path.join(__dirname, '..', 'resources'), () => settings, () => team.config());
      fs.symlinkSync(path.resolve(program), agent.bin);
      process.env.OPENCODE_CONFIG_CONTENT = JSON.stringify(fake.config);
      agent.writeConfig(path.join(__dirname, '..', 'resources', 'AGENTS.md'));
      const written = JSON.parse(fs.readFileSync(path.join(agent.configDir, 'opencode.jsonc'), 'utf8'));
      check('the helpers are in OpenCode\'s settings', written.agent && written.agent.poet && written.agent.researcher.mode === 'subagent');
      const events = [];
      const result = await agent.run({ text: 'TEAM please', model: fake.model, cwd: folder, onEvent: (e) => events.push(e) });
      judge(check, result, fake, folder);
      check('while the helpers work, the chat says so', events.some((e) => e.kind === 'status' && e.activity === 'team' && /Find out about chai/.test(e.detail) && /A poem about chai/.test(e.detail)),
        JSON.stringify(events.filter((e) => e.kind === 'status')));
      check('the helpers used are named', result.helpers.slice().sort().join(' ') === 'poet researcher', JSON.stringify(result.helpers));
      check('this run went through the kept server', agent.fast === true);
      agent.stopServer();
      fake.close();
    }
    console.log('RESULT: ' + (failed ? 'FAIL' : 'PASS') + ' (' + count + ' checks, ' + failed + ' failed)');
    setTimeout(() => process.exit(failed ? 1 : 0), 300);
  })().catch((e) => {
    console.log('FAIL the test ran to its end :: ' + (e.stack || e));
    console.log('RESULT: FAIL');
    process.exit(1);
  });
}

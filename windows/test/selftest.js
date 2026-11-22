// The app tests itself: started as  Termant.exe --selftest=<folder> [--selftest-agent],
// it drives its own window, uses the real `pc` command, saves screenshots and
// result.json into the folder, and exits with 0 only if every hard check passed.
const fs = require('fs');
const path = require('path');
const http = require('http');
const { execFile } = require('child_process');

const WINDOWS = process.platform === 'win32';
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

module.exports = async function selftest(ctx) {
  const { app, win, dir, log, pcDir, agent, schedule, store } = ctx;
  const results = [];
  const check = (name, ok, detail, soft) => {
    results.push({ name, ok: !!ok, soft: !!soft, detail: String(detail === undefined ? '' : detail).slice(0, 600) });
    log((ok ? 'PASS ' : soft ? 'SOFT-FAIL ' : 'FAIL ') + name + (detail ? ' :: ' + String(detail).slice(0, 300) : ''));
  };
  const js = (code) => win.webContents.executeJavaScript(code, true);
  let shots = 0;
  const shot = async (name) => {
    await sleep(350);
    const image = await win.webContents.capturePage();
    fs.writeFileSync(path.join(dir, String(++shots).padStart(2, '0') + '-' + name + '.png'), image.toPNG());
  };
  const until = async (test, ms) => {
    const end = Date.now() + ms;
    while (Date.now() < end) {
      try {
        if (await test()) return true;
      } catch (e) {
        // not there yet
      }
      await sleep(250);
    }
    return false;
  };

  /** Runs the real `pc` command (on other systems: the same request, sent directly). */
  const pc = (...args) => new Promise((resolve) => {
    if (WINDOWS) {
      // cmd.exe wants the whole line in one more pair of quotes
      const line = '"' + [path.join(pcDir, 'pc.cmd'), ...args].map((a) => '"' + a + '"').join(' ') + '"';
      execFile('cmd.exe', ['/d', '/s', '/c', line], { env: ctx.shellEnv(), windowsHide: true, timeout: 420000, windowsVerbatimArguments: true },
        (error, stdout, stderr) => resolve({ code: error ? (error.code || 1) : 0, out: String(stdout).trim(), err: String(stderr).trim() }));
      return;
    }
    const tail = args.slice(1), rest = (n) => tail.slice(n).join(' ');
    const routes = {
      notify: () => ['/notify', { title: tail[0], text: rest(1) }],
      alarm: () => ['/alarm', { at: tail[0], label: rest(1) }],
      timer: () => ['/timer', { after: tail[0], label: rest(1) }],
      alarms: () => ['/alarms', { remove: tail[0] === 'remove' ? tail[1] : '' }],
      ask: () => ['/ask', { question: tail[0], choices: tail.slice(1) }],
      stop: () => ['/stop', {}],
      rule: () => (tail[0] === 'add'
        ? ['/rule', { action: 'add', trigger: tail.slice(1, tail.indexOf('do')).join(' '), command: tail.slice(tail.indexOf('do') + 1).join(' ') }]
        : ['/rule', { action: tail[0] || 'list', id: tail[1] }]),
      note: () => ['/note', { action: tail[0] || 'list', text: rest(1), id: tail[1] }],
    };
    if (!routes[args[0]]) return resolve({ code: 0, out: '', err: 'skipped' });
    const [route, body] = routes[args[0]]();
    const bridge = store.load('bridge.json', {});
    const request = http.request({ host: '127.0.0.1', port: bridge.port, path: route, method: 'POST', headers: { 'x-termant': bridge.token } }, (response) => {
      let text = '';
      response.on('data', (part) => { text += part; });
      response.on('end', () => {
        const answer = JSON.parse(text);
        resolve({ code: answer.ok ? 0 : 1, out: answer.text || '', err: answer.error || '' });
      });
    });
    request.end(JSON.stringify(body));
  });

  try {
    win.setSize(1120, 760);
    win.show();
    await sleep(1500);

    // ---- first start
    check('window loads', await js('!!window.termantTest && !!document.querySelector("#nav-chat")'));
    check('first-start notice is shown', await js('!!document.getElementById("notice")'));
    await shot('first-start');
    await js('document.querySelector("#notice .primary").click()');
    check('notice is remembered', await until(() => ctx.settings().seenNotice, 3000));
    await shot('chat-empty');

    // ---- the pc command reaches the app
    check('bridge file exists', await until(() => store.load('bridge.json', {}).port > 0, 5000));
    let r = await pc('notify', 'Hello', 'from the test');
    check('pc notify', r.code === 0 && ctx.logs().some((l) => l.includes('notify: Hello | from the test')), JSON.stringify(r));
    r = await pc('note', 'add', 'The user likes chai');
    const learned = fs.readFileSync(path.join(store.dir, 'agent', 'learned.md'), 'utf8');
    check('pc note add reaches the agent\'s notes', r.code === 0 && learned.includes('The user likes chai'), JSON.stringify(r));
    r = await pc('note', 'list');
    check('pc note list', r.out.includes('The user likes chai'), JSON.stringify(r));

    // ---- rules
    r = await pc('rule', 'add', 'every', '30m', 'do', 'pc', 'notify', 'Paani', 'Paani pee lo');
    check('pc rule add every', r.code === 0 && schedule.rules.length === 1, JSON.stringify(r));
    r = await pc('rule', 'add', 'at', '23:00', 'do', 'pc', 'volume', '0');
    check('pc rule add at', r.code === 0 && schedule.rules.length === 2, JSON.stringify(r));
    r = await pc('rule', 'add', 'sometimes', 'do', 'pc', 'volume', '0');
    check('a wrong trigger is refused', r.code !== 0 && /trigger/.test(r.err + r.out), JSON.stringify(r));
    const marker = path.join(dir, 'rule-ran.txt');
    r = WINDOWS
      ? await pc('rule', 'add', 'in', '2s', 'do', 'pc', 'clipboard', 'set', 'rule-ran-ok')
      : await pc('rule', 'add', 'in', '2s', 'do', 'touch', marker);
    check('pc rule add in', r.code === 0, JSON.stringify(r));
    if (WINDOWS) {
      const ran = await until(async () => (await pc('clipboard', 'get')).out.includes('rule-ran-ok'), 30000);
      check('a rule runs its command, with pc on its PATH', ran, (await pc('clipboard', 'get')).out);
    } else {
      check('a rule runs its command', await until(() => fs.existsSync(marker), 10000));
    }
    check('a one-time rule removes itself', await until(() => schedule.rules.length === 2, 5000), schedule.rules.length);
    r = await pc('rule', 'list');
    check('pc rule list', /every 30m/.test(r.out) && /at 23:00/.test(r.out), JSON.stringify(r));

    // ---- alarms and timers
    r = await pc('alarm', '07:00', 'Wake', 'up');
    check('pc alarm', r.code === 0 && schedule.alarms.some((a) => a.label === 'Wake up' && a.kind === 'alarm'), JSON.stringify(r));
    r = await pc('alarm', '7pm');
    check('a wrong alarm time is refused', r.code !== 0, JSON.stringify(r));
    r = await pc('timer', '3s', 'Chai');
    check('pc timer', r.code === 0, JSON.stringify(r));
    await js('window.termantTest.show("auto")');
    await shot('rules-and-alarms');
    check('rules page lists the rule', await js('document.getElementById("auto").textContent.includes("every 30m")'));
    check('the timer rings', await until(() => js('!!document.getElementById("ring")'), 12000));
    await shot('timer-rings');
    await js('document.querySelector("#ring .primary").click()');
    check('ringing stops', await until(async () => !(await js('!!document.getElementById("ring")')), 4000));

    // ---- a question from the agent
    const asked = pc('ask', 'Which colour?', 'Red', 'Blue');
    check('the question appears', await until(() => js('!!document.getElementById("ask")'), 15000));
    await shot('question');
    await js('Array.from(document.querySelectorAll("#ask .choice")).find((b) => b.textContent === "Blue").click()');
    r = await asked;
    check('pc ask prints the answer', r.code === 0 && r.out === 'Blue', JSON.stringify(r));

    // ---- terminal
    await js('window.termantTest.show("term")');
    await sleep(600);
    if (await js('!!document.querySelector("#dialog .choice")')) {
      await shot('terminal-shells');
      await js('document.querySelector("#dialog .choice").click()');
    }
    check('a terminal tab opens', await until(() => js('window.termantTest.terminals() === 1'), 15000));
    await sleep(2500);
    // the shell works out the number, so the typed line itself does not count
    const line = WINDOWS ? 'echo "termant-ok-$(6*7)"' : 'echo "termant-ok-$((6*7))"';
    await js('window.termantTest.typeInTerminal(' + JSON.stringify(line + '\r') + ')');
    check('the terminal runs a command', await until(async () => (await js('window.termantTest.terminalText()')).includes('termant-ok-42'), 20000),
      (await js('window.termantTest.terminalText()')).slice(-400));
    if (WINDOWS) {
      await js('window.termantTest.typeInTerminal("pc info\\r")');
      check('pc works inside the terminal', await until(async () => /Processor:/.test(await js('window.termantTest.terminalText()')), 40000),
        (await js('window.termantTest.terminalText()')).slice(-400));
    }
    await shot('terminal');

    // ---- settings, Hindi, light theme
    await js('window.termantTest.show("settings")');
    await shot('settings');
    await js('document.querySelector("#settings .card .item").click()');
    check('language changes to Hindi', await until(() => js('document.getElementById("settings-title").textContent === "सेटिंग"'), 4000));
    await js('document.querySelectorAll("#settings .card .item")[1].click()');
    check('theme changes to light', await until(() => js('document.body.className === "light"'), 4000));
    await shot('settings-hindi-light');
    await js('window.termantTest.show("chat")');
    await shot('chat-hindi-light');
    await js('window.termantTest.show("settings")');
    await js('document.querySelector("#settings .card .item").click()');
    await until(() => js('document.getElementById("settings-title").textContent === "Settings"'), 4000);
    await js('document.querySelectorAll("#settings .card .item")[1].click()');
    await until(() => js('document.body.className === "dark"'), 4000);
    check('settings are saved to disk', store.load('settings.json', {}).lang === 'en' && store.load('settings.json', {}).theme === 'dark');
    await js('window.termantTest.show("chat")');

    // ---- learning: skills, the diary, YouTube and the ElevenLabs keys (a pretend YouTube and ElevenLabs on this PC)
    if (WINDOWS) {
      const pretend = http.createServer((request, response) => {
        let body = '';
        request.on('data', (part) => { body += part; });
        request.on('end', () => {
          const url = new URL(request.url, 'http://x');
          const key = request.headers['xi-api-key'] || '';
          const give = (code, value, type) => {
            response.writeHead(code, { 'content-type': type || 'application/json' });
            response.end(Buffer.isBuffer(value) ? value : JSON.stringify(value));
          };
          if (url.pathname === '/youtubei/v1/search') {
            return give(200, { contents: [{ videoRenderer: { videoId: 'dQw4w9WgXcQ', title: { runs: [{ text: 'Make a table in Word' }] },
              ownerText: { runs: [{ text: 'Office Guru' }] }, lengthText: { simpleText: '4:05' } } }] });
          }
          if (url.pathname === '/youtubei/v1/player') {
            return give(200, { videoDetails: { videoId: 'dQw4w9WgXcQ', title: 'Make a table in Word', author: 'Office Guru', lengthSeconds: '245', shortDescription: '' },
              captions: { playerCaptionsTracklistRenderer: { captionTracks: [{ baseUrl: 'https://www.youtube.com/api/timedtext?v=dQw4w9WgXcQ&lang=en', languageCode: 'en' }] } } });
          }
          if (url.pathname === '/api/timedtext') return give(200, { events: [{ tStartMs: 0, segs: [{ utf8: 'click insert then table' }] }] });
          if (url.pathname.startsWith('/v1/text-to-speech/')) {
            if (key === 'key-used-up-1') return give(401, { detail: { status: 'quota_exceeded', message: 'No credits left.' } });
            return give(200, Buffer.concat([Buffer.from('ID3'), Buffer.alloc(600, 3)]), 'audio/mpeg');
          }
          if (url.pathname === '/v1/user/subscription') return give(200, { tier: 'free', character_count: key === 'key-used-up-1' ? 10000 : 40, character_limit: 10000 });
          give(404, {});
        });
      });
      await new Promise((resolve) => pretend.listen(0, '127.0.0.1', resolve));
      const pretendUrl = 'http://127.0.0.1:' + pretend.address().port;
      process.env.TERMANT_YOUTUBE_URL = pretendUrl;
      process.env.TERMANT_ELEVEN_URL = pretendUrl;
      r = await pc('youtube', 'search', 'table', 'in', 'word');
      check('pc youtube search lists videos', r.code === 0 && /1\. Make a table in Word/.test(r.out) && /id dQw4w9WgXcQ/.test(r.out), JSON.stringify(r));
      r = await pc('youtube', 'read', 'dQw4w9WgXcQ');
      check('pc youtube read prints what is said', r.code === 0 && /\[0:00\] click insert then table/.test(r.out) && /4:05 long/.test(r.out), JSON.stringify(r));
      const stepsFile = path.join(dir, 'steps.md');
      fs.writeFileSync(stepsFile, '1. Open Word.\n2. Insert > Table.\n3. Pick 3 x 3.\n');
      r = await pc('skill', 'save', 'word-table', 'Make a table in Word', stepsFile, 'https://youtu.be/dQw4w9WgXcQ');
      check('pc skill save', r.code === 0 && /Skill learned: word-table/.test(r.out), JSON.stringify(r));
      check('the skill is named in the agent\'s guide', fs.readFileSync(path.join(store.dir, 'agent', 'learned.md'), 'utf8').includes('- `word-table`: Make a table in Word'));
      r = await pc('skill', 'show', 'word-table');
      check('pc skill show prints the steps', r.code === 0 && /2\. Insert > Table\./.test(r.out), JSON.stringify(r));
      r = await pc('skill', 'save', 'Bad Name', 'x', stepsFile);
      check('a wrong skill name is refused', r.code !== 0 && /name must be/.test(r.err + r.out), JSON.stringify(r));
      // the skill that comes with the app, and the command it uses (the installed files, a real Python)
      r = await pc('skill', 'list');
      check('the skill that comes with the app is among the skills', r.code === 0 && /^remove-ai-marks: Find and remove the marks AI tools leave/m.test(r.out), JSON.stringify(r));
      check('and is named in the agent\'s guide', fs.readFileSync(path.join(store.dir, 'agent', 'learned.md'), 'utf8').includes('- `remove-ai-marks`: '));
      r = await pc('skill', 'show', 'remove-ai-marks');
      check('pc skill show prints its steps', r.code === 0 && /pc marks clean/.test(r.out) && /from: https:\/\/github\.com\/guillaumemeyer\/watermarks-remover/.test(r.out), JSON.stringify(r).slice(0, 400));
      const marked = path.join(dir, 'marked note.txt');
      fs.writeFileSync(marked, 'Namaste\u200b duniya\u2060, yeh ek\u202fnote hai.\r\n');
      r = await pc('marks', 'check', marked);
      check('pc marks check finds invisible letters in a text file', r.code === 0 && /Kind: text/.test(r.out) && /U\+200B ZERO WIDTH SPACE/.test(r.out) && /Result: marks found/.test(r.out) && /Not seen by this check/.test(r.out), JSON.stringify(r).slice(0, 900));
      r = await pc('marks', 'clean', marked);
      const cleaned = path.join(dir, 'marked note.cleaned.txt');
      check('pc marks clean makes a cleaned copy and keeps the original', r.code === 0 && /Cleaned copy: .*marked note\.cleaned\.txt/.test(r.out) && /invisible letters removed: 2, odd spaces made plain: 1/.test(r.out) && /The original was not changed/.test(r.out)
        && fs.existsSync(cleaned) && fs.readFileSync(cleaned, 'utf8') === 'Namaste duniya, yeh ek note hai.\r\n' && fs.readFileSync(marked, 'utf8').includes('\u200b'), JSON.stringify(r).slice(0, 900));
      r = await pc('marks', 'check', cleaned);
      check('the copy has no marks', r.code === 0 && /Result: no marks found/.test(r.out), JSON.stringify(r).slice(0, 600));
      r = await pc('learned');
      check('pc learned tells what was learned today', r.code === 0 && /Learned today \(\d{4}-\d\d-\d\d\): 2/.test(r.out) && /- skill word-table: Make a table in Word \(from https:\/\/youtu\.be\/dQw4w9WgXcQ\)/.test(r.out) && /- note: The user likes chai/.test(r.out), JSON.stringify(r));
      // helper agents: made with the pc command, and in Settings
      const helper = require('./team-test').POET;
      const helperFile = path.join(dir, 'poet.md');
      fs.writeFileSync(helperFile, helper.text);
      r = await pc('team', 'add', 'poet', helper.about, helperFile);
      check('pc team add makes a helper', r.code === 0 && /Made the helper "poet": Writes one short line of verse/.test(r.out), JSON.stringify(r));
      r = await pc('team');
      check('pc team lists the helpers', r.code === 0 && /Helpers that come with Termant: 6/.test(r.out) && /- researcher: /.test(r.out) && /Helpers made here: 1/.test(r.out) && /- poet: Writes one short line/.test(r.out), JSON.stringify(r));
      r = await pc('team', 'show', 'poet');
      check('pc team show prints the instructions', r.code === 0 && /POET-MARK/.test(r.out), JSON.stringify(r));
      r = await pc('team', 'add', 'checker', helper.about, helperFile);
      check('a helper of Termant cannot be replaced', r.code !== 0 && /already there/.test(r.err + r.out), JSON.stringify(r));
      r = await pc('learned');
      check('a new helper is in today\'s diary', r.code === 0 && /- helper poet: Writes one short line of verse/.test(r.out), JSON.stringify(r));
      await js('document.getElementById("nav-settings").click()');
      check('Settings lists the helpers', await until(() => js('(() => { const c = document.getElementById("team-card"); return !!c && c.textContent.includes("researcher") && c.textContent.includes("poet"); })()'), 5000));
      await js('document.querySelector("#team-card > :last-child").click()');
      await until(() => js('!!document.getElementById("team-name")'), 3000);
      await js('document.getElementById("team-name").value = "hindi-teacher"; document.getElementById("team-about").value = "Explains Hindi grammar with short examples."; ' +
        'document.getElementById("team-text").value = "You explain Hindi grammar simply, with two short examples each time."; document.querySelector("#dialog .buttons button:last-child").click()');
      check('a helper is made in Settings', await until(() => js('document.getElementById("team-card").textContent.includes("hindi-teacher")'), 5000),
        await js('document.getElementById("team-card").textContent'));
      await js('document.getElementById("team-card").scrollIntoView({ block: "center" })');
      await shot('team');
      await js('document.querySelector("#team-card button[aria-label$=\\"hindi-teacher\\"]").click()');
      check('a helper is removed in Settings', await until(() => js('!document.getElementById("team-card").textContent.includes("hindi-teacher")'), 5000));
      check('Settings shows what was learned today', await until(() => js('(document.getElementById("learned-card") || {textContent: ""}).textContent.includes("word-table")'), 5000));
      await js('document.getElementById("learned-card").scrollIntoView({ block: "center" })');
      await shot('learned');
      r = await pc('voice', 'add', 'key-used-up-1', 'key-good-333');
      check('pc voice add saves two keys', r.code === 0 && /2 ElevenLabs key\(s\) are saved/.test(r.out) && ctx.settings().voiceKeys.length === 2, JSON.stringify(r));
      r = await pc('say', 'namaste', 'from', 'termant');
      const sound = path.join(dir, 'said.mp3');
      check('pc say moves to the second key when the first is used up', r.code === 0 && /Said with ElevenLabs, key 2 of 2 \(key 1 \(\.\.\.up-1\): quota_exceeded\)/.test(r.out) && fs.existsSync(sound) && fs.statSync(sound).size === 603, JSON.stringify(r));
      r = await pc('say', 'again');
      check('the used-up key is left out the next time', r.code === 0 && /Said with ElevenLabs, key 2 of 2\.$/.test(r.out), JSON.stringify(r));
      r = await pc('voice');
      check('pc voice shows each key without printing it', r.code === 0 && /1\. \.\.\.up-1 .*10000 of 10000.*resting/.test(r.out) && /2\. \.\.\.-333 .*in use/.test(r.out) && !r.out.includes('key-good-333'), JSON.stringify(r));
      await js('document.getElementById("nav-chat").click()');
      check('the chat shows the working folder', await until(() => js('document.getElementById("folder-chip").textContent.length > 0'), 4000), await js('document.getElementById("folder-chip").title'));
      delete process.env.TERMANT_YOUTUBE_URL;
      delete process.env.TERMANT_ELEVEN_URL;
      pretend.close();
      store.save('settings.json', Object.assign(ctx.settings(), { voiceKeys: [] }));
      // the real YouTube, from this machine (it often refuses computers in data centres, so this does not decide the result)
      r = await pc('youtube', 'search', 'how', 'to', 'make', 'a', 'table', 'in', 'Word');
      fs.writeFileSync(path.join(dir, 'youtube-search.txt'), r.out + '\n' + r.err);
      check('the real YouTube: search', r.code === 0 && /\nRead what is said/.test(r.out), JSON.stringify(r).slice(0, 300), true);
      const real = (r.out.match(/\n {3}id ([A-Za-z0-9_-]{11})/) || [])[1];
      if (real) {
        r = await pc('youtube', 'read', real);
        fs.writeFileSync(path.join(dir, 'youtube-read.txt'), r.out + '\n' + r.err);
        check('the real YouTube: what a video says', r.code === 0 && /What is said \(/.test(r.out), JSON.stringify(r).slice(0, 300), true);
      }
    }

    // ---- the AI agent (needs the internet and a free model that answers)
    if (process.argv.includes('--selftest-agent')) {
      const started = Date.now();
      let installError = '';
      try {
        if (!agent.installed()) await agent.install(() => {});
      } catch (e) {
        installError = String(e.message || e);
      }
      check('the agent downloads and starts', agent.installed(), installError || Math.round((Date.now() - started) / 1000) + ' s');
      if (agent.installed()) {
        agent.writeConfig(path.join(pcDir, 'AGENTS.md'));
        const models = await agent.models();
        check('the model list loads', models && models.length > 0, models ? models.length + ' models, first: ' + models.slice(0, 4).join(', ') : 'none');
        await js('document.getElementById("model-chip").click()');
        await until(() => js('document.querySelectorAll("#models .choice").length > 1'), 60000);
        await shot('models');
        await js('document.querySelector("#models .buttons button").click()');

        const send = async (text, seconds) => {
          await js('document.getElementById("input").value = ' + JSON.stringify(text) + '; document.getElementById("send").click()');
          await until(() => !!ctx.busy(), 5000);
          await until(() => !ctx.busy(), seconds * 1000);
          const chat = ctx.current();
          return chat && chat.messages.length ? chat.messages[chat.messages.length - 1] : { failed: true, text: 'no reply', steps: [] };
        };
        let reply = await send('Reply with exactly this one word and nothing else: pong', 300);
        check('the agent answers', !reply.failed && /pong/i.test(reply.text), JSON.stringify(reply), true);
        const firstSeconds = reply.seconds;
        const firstFast = agent.fast;
        reply = await send('Reply with exactly this one word and nothing else: ping', 300);
        check('the second message goes through the kept server', firstFast && agent.fast && !reply.failed && /ping/i.test(reply.text),
          'first ' + firstSeconds + ' s (kept server: ' + firstFast + '), second ' + reply.seconds + ' s (kept server: ' + agent.fast + ') ' + JSON.stringify(reply).slice(0, 200), true);
        await shot('answer');
        if (!reply.failed && WINDOWS) {
          reply = await send('Use your shell tool to run exactly this command: pc clipboard set termant-agent-ok   Then say "done".', 300);
          const clip = (await pc('clipboard', 'get')).out;
          check('the agent controls the PC with pc', clip.includes('termant-agent-ok'), JSON.stringify(reply) + ' clipboard=' + clip, true);
          await shot('agent-uses-pc');
          reply = await send('Open Notepad with the pc command, then type the words "namaste from termant" into it with pc type. Then tell me what you did.', 420);
          await sleep(1500);
          const screen = (await pc('windows')).out;
          check('the agent opens Notepad', /notepad/i.test(screen), JSON.stringify(reply).slice(0, 400) + ' windows=' + screen, true);
          await pc('screenshot', path.join(dir, 'desktop-after-agent.png'));
          await shot('agent-notepad');
        }

        // ---- helper agents at work. The model is a pretend one on this PC, so that the result
        // does not hang on whether a real model chooses to use helpers; OpenCode itself is the real one.
        const teamTest = require('./team-test');
        const fake = await teamTest.fakeModel();
        const real = { model: ctx.settings().model, backup: ctx.settings().backup };
        process.env.OPENCODE_CONFIG_CONTENT = JSON.stringify(fake.config);
        agent.stopServer();
        Object.assign(ctx.settings(), { model: fake.model, backup: false });
        await js('document.getElementById("nav-chat").click(); document.getElementById("new-chat").click()');
        await sleep(500);
        reply = await send('TEAM please', 240);
        teamTest.judge((name, ok, detail) => check('helpers: ' + name, ok, detail), reply, fake, ctx.settings().workspace);
        check('helpers: the chat shows who got which part', await js('[...document.querySelectorAll(".step code")].some((c) => c.textContent === "Helper researcher: Find out about chai")'),
          JSON.stringify(reply).slice(0, 500));
        await shot('helpers');

        // ---- a picture sent with a message: the pretend model says what reached it
        const picture = path.join(dir, 'attached.png');
        fs.writeFileSync(picture, require('./attach-test').png());
        check('files: the button to add a picture or file is there', await js('!!document.querySelector("#attach svg")'));
        await js('document.getElementById("new-chat").click()');
        await sleep(500);
        await js('window.termant.call("chat:send", ' + JSON.stringify({ text: 'LOOK at this', files: [picture] }) + ').then(() => true)');
        await until(() => !!ctx.busy(), 5000);
        await until(() => !ctx.busy(), 240000);
        reply = (ctx.current() && ctx.current().messages.slice(-1)[0]) || { text: 'no reply' };
        check('files: a picture sent with a message reaches the model', /SAW pictures=1/.test(reply.text || ''), JSON.stringify(reply).slice(0, 500));
        check('files: the message names the file, not its path', await js('[...document.querySelectorAll(".msg.user .bubble")].some((b) => b.textContent.includes("attached.png") && !b.textContent.includes("[file:"))'));
        await shot('attached');
        delete process.env.OPENCODE_CONFIG_CONTENT;
        agent.stopServer();
        Object.assign(ctx.settings(), real);
        fake.close();
      }
    }
  } catch (e) {
    check('the test ran to its end', false, e.stack || e);
  }

  const hard = results.filter((x) => !x.ok && !x.soft).length;
  const soft = results.filter((x) => !x.ok && x.soft).length;
  fs.writeFileSync(path.join(dir, 'result.json'), JSON.stringify({ pass: hard === 0, failed: hard, softFailed: soft, results }, null, 1));
  log('RESULT: ' + (hard === 0 ? 'PASS' : 'FAIL') + ' (' + results.length + ' checks, ' + hard + ' failed, ' + soft + ' soft-failed)');
  app.exit(hard === 0 ? 0 : 1);
};

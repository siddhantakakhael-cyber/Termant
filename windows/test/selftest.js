// The app tests itself: started as  Termant.exe --selftest=<folder> [--selftest-agent],
// it drives its own window, uses the real `pc` command, saves screenshots and
// result.json into the folder, and exits with 0 only if every hard check passed.
const fs = require('fs');
const os = require('os');
const path = require('path');
const http = require('http');
const httpsLib = require('https');
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

    // ---- draw: a picture from words (the draw command, against a pretend quick service)
    {
      const pngBytes = require('./attach-test').png();
      const drawFake = http.createServer((req, res) => {
        if ((req.url || '').startsWith('/prompt/')) { res.writeHead(200, { 'content-type': 'image/png' }); return res.end(pngBytes); }
        res.writeHead(404, { 'content-type': 'text/plain' });
        res.end('no such thing');
      });
      await new Promise((resolve) => drawFake.listen(0, '127.0.0.1', resolve));
      const drawEnv = Object.assign({}, ctx.shellEnv(), {
        TERMANT_DATA: path.join(dir, 'draw-data'),
        TERMANT_DRAW_POLLINATIONS: 'http://127.0.0.1:' + drawFake.address().port,
        TERMANT_DRAW_GAP: '0',
        TERMANT_CHAT: '1',
      });
      const draw = (args, env) => new Promise((resolve) => {
        const line = '"' + [path.join(pcDir, 'draw.cmd'), ...args].map((a) => '"' + a + '"').join(' ') + '"';
        execFile('cmd.exe', ['/d', '/s', '/c', line], { env: env || drawEnv, windowsHide: true, timeout: 180000, windowsVerbatimArguments: true },
          (error, stdout, stderr) => resolve({ code: error ? (error.code || 1) : 0, out: String(stdout).trim(), err: String(stderr).trim() }));
      });
      if (WINDOWS) {
        let d = await draw(['a red apple on a table, photo']);
        const savedPath = (d.out.match(/Picture saved: (.+?) \(/) || [])[1] || '';
        check('draw: a picture is made and saved in the Pictures folder', d.code === 0 && savedPath.startsWith(os.homedir() + path.sep + 'pictures'), JSON.stringify(d).slice(0, 300));
        check('draw: the saved file is the picture the service gave', savedPath && fs.existsSync(savedPath) && fs.readFileSync(savedPath).equals(pngBytes), savedPath);
        const drawnList = path.join(dir, 'draw-data', 'agent', 'cache', 'drawn.txt');
        check('draw: the chat gets the picture to show under the answer', fs.existsSync(drawnList) && fs.readFileSync(drawnList, 'utf8').includes(savedPath),
          fs.existsSync(drawnList) ? fs.readFileSync(drawnList, 'utf8').slice(0, 200) : 'no drawn list');
        d = await draw(['last']);
        check('draw last: the newest picture is named', d.code === 0 && d.out === savedPath, JSON.stringify(d).slice(0, 200));
        d = await draw(['--seed', 'x', 'a cat']);
        check('draw: a wrong seed is refused at once', d.code !== 0 && /seed/.test(d.out + d.err), JSON.stringify(d).slice(0, 200));
        d = await draw(['a cat'], Object.assign({}, drawEnv, {
          TERMANT_DRAW_POLLINATIONS: 'http://127.0.0.1:1',
          TERMANT_DRAW_HORDE: 'http://127.0.0.1:1',
          TERMANT_DRAW_WAIT: '3',
          TERMANT_DRAW_POLL: '1',
        }));
        check('draw: when no service answers, it says so plainly', d.code !== 0 && /could not be reached/.test(d.out + d.err), JSON.stringify(d).slice(0, 300));
        if (savedPath && fs.existsSync(savedPath)) fs.unlinkSync(savedPath);
      } else {
        check('draw: the Windows draw command is not tested on this system', true, 'not Windows');
      }
      drawFake.close();
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

        // ---- a command that runs for a while: the chat says how far it is while it runs.
        // The pretend model runs "node slow.js", which prints a line every 0.7 s like a download does.
        const progressTest = require('./progress-test');
        fs.mkdirSync(ctx.settings().workspace, { recursive: true });
        fs.writeFileSync(path.join(ctx.settings().workspace, 'slow.js'), progressTest.SLOW);
        await js('document.getElementById("new-chat").click()');
        await sleep(500);
        const printed = [];
        const watcher = setInterval(() => {
          const b = ctx.busy();
          if (b && b.output && printed[printed.length - 1] !== b.output) printed.push(b.output);
        }, 100);
        await js('document.getElementById("input").value = "RUN node slow.js"; document.getElementById("send").click()');
        await until(() => !!ctx.busy(), 5000);
        await until(() => !ctx.busy() || /^got [2-4] of 6 MB$/.test(ctx.busy().output || ''), 120000);
        const during = ctx.busy() ? { activity: ctx.busy().activity, detail: ctx.busy().detail, output: ctx.busy().output } : null;
        const onScreen = await js('[(document.querySelector("#status span") || {}).textContent || "", (document.querySelector("#status .printed") || {}).textContent || ""]');
        const card = await ctx.busyWindow();
        await shot('progress');
        await until(() => !ctx.busy(), 120000);
        clearInterval(watcher);
        reply = (ctx.current() && ctx.current().messages.slice(-1)[0]) || { text: 'no reply' };
        check('progress: the command is named while it runs', !!during && during.activity === 'command' && during.detail === 'node slow.js', JSON.stringify(during));
        check('progress: what the running command prints is followed', printed.filter((l) => /^got [1-5] of 6 MB$/.test(l)).length >= 3, JSON.stringify(printed));
        check('progress: the newest line is on the screen under the command', /node slow\.js/.test(onScreen[0]) && /^got [1-6] of 6 MB$/.test(onScreen[1]), JSON.stringify(onScreen));
        check('progress: the answer has what the command printed', !reply.failed && /got 6 of 6 MB/.test(reply.text || '') && (reply.steps || []).join('|') === 'node slow.js', JSON.stringify(reply).slice(0, 500));
        check('progress: the corner card says what is done and for how long',
          !!card && card.visible && /Running a command/.test(card.text) && /of 6 MB/.test(card.text)
          && (/\d+s\b/.test(card.text) || /\d+:\d\d\b/.test(card.text))
          && !!card.data && card.data.activity === 'command' && card.data.detail === 'node slow.js',
          JSON.stringify(card).slice(0, 300));
        check('progress: the line is gone when the work is done', await js('!document.querySelector("#status .printed")'));
        // the corner card, like the phone's: gone with the work
        await sleep(500);
        const cardAfter = await ctx.busyWindow();
        check('progress: the corner card is away when the work is done', !!cardAfter && !cardAfter.visible, JSON.stringify(cardAfter));

        // ---- the web chat: a phone on the network talks to this same chat.
        // The window is the phone: a request from this process is the phone's browser.
        const webStatus = ctx.web();
        check('web chat: the page is served', webStatus && webStatus.https && webStatus.password, JSON.stringify(webStatus));
        if (webStatus && webStatus.https) {
          const webPort = parseInt(webStatus.https.split(':').pop().replace(/[^0-9]/g, ''), 10);
          const webRequest = (url, { method = 'GET', body, cookie } = {}) => new Promise((resolve) => {
            const r = httpsLib.request({ host: '127.0.0.1', port: webPort, path: url, method, rejectUnauthorized: false,
              headers: Object.assign(body ? { 'content-type': 'application/json' } : {}, cookie ? { cookie } : {}) }, (res) => {
              let text = '';
              res.on('data', (d) => { text += d; });
              res.on('end', () => resolve({ code: res.statusCode, text, cookie: (res.headers['set-cookie'] || [])[0] || '' }));
            });
            r.on('error', (e) => resolve({ code: 0, text: e.message, cookie: '' }));
            if (body) r.end(JSON.stringify(body));
            else r.end();
          });
          let w = await webRequest('/');
          check('web chat: the phone gets the page with the wake word', w.code === 200 && w.text.includes('id="login"') && w.text.includes('hey Termant'), 'code ' + w.code);
          w = await webRequest('/api/state');
          check('web chat: without the password the chat is hidden', w.code === 401, JSON.stringify(w.text));
          w = await webRequest('/api/login', { method: 'POST', body: { password: 'wrong' } });
          check('web chat: a wrong password is refused', w.code === 401, JSON.stringify(w.text));
          w = await webRequest('/api/login', { method: 'POST', body: { password: webStatus.password } });
          const webCookie = (w.cookie || '').split(';')[0];
          check('web chat: the password from Settings opens it', w.code === 200 && /termant=/.test(webCookie), JSON.stringify(w.cookie));
          w = await webRequest('/api/message', { method: 'POST', cookie: webCookie, body: { text: 'RUN echo pong' } });
          check('web chat: a message from the phone is taken', w.code === 200, JSON.stringify(w.text));
          await until(() => !!ctx.busy(), 5000);
          if (ctx.busy()) {
            w = await webRequest('/api/message', { method: 'POST', cookie: webCookie, body: { text: 'again' } });
            check('web chat: while the phone waits, a second message is refused', w.code === 409 && /busy/.test(w.text), JSON.stringify(w.text));
          } else {
            check('web chat: the answer came before the phone could ask twice', true, 'not busy');
          }
          await until(() => !ctx.busy(), 120000);
          const webChat = ctx.current();
          const webMsgs = (webChat && webChat.messages) || [];
          const lastUser = [...webMsgs].reverse().find((m) => m.user) || {};
          const lastReply = webMsgs[webMsgs.length - 1] || {};
          check('web chat: the phone\u2019s message is in the chat on the screen', lastUser.user && lastUser.from === 'web' && /pong/.test(lastUser.text), JSON.stringify(lastUser).slice(0, 200));
          check('web chat: the answer appears in both', !lastReply.user && /pong/.test(lastReply.text || ''), JSON.stringify(lastReply).slice(0, 200));
          w = await webRequest('/api/state', { cookie: webCookie });
          let webState = {};
          try { webState = JSON.parse(w.text); } catch (e) { webState = { error: String(e) }; }
          const phoneMsgs = webState.messages || [];
          check('web chat: the phone sees the same answer',
            w.code === 200 && webState.ok && phoneMsgs.length >= 2 && phoneMsgs[phoneMsgs.length - 1].text.includes('pong'),
            'code ' + w.code + ' ' + w.text.slice(0, 200));
          await shot('web-chat');
        }

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

        // ---- draw: a picture the agent draws for the answer; the chat and the phone show it.
        // The quick service is a pretend one on this PC; the agent's environment points at it.
        const pngDraw = require('./attach-test').png();
        const drawAgentFake = http.createServer((req, res) => {
          if ((req.url || '').startsWith('/prompt/')) { res.writeHead(200, { 'content-type': 'image/png' }); return res.end(pngDraw); }
          res.writeHead(404, { 'content-type': 'text/plain' });
          res.end('no such thing');
        });
        await new Promise((resolve) => drawAgentFake.listen(0, '127.0.0.1', resolve));
        process.env.TERMANT_DRAW_POLLINATIONS = 'http://127.0.0.1:' + drawAgentFake.address().port;
        process.env.TERMANT_DRAW_GAP = '0';
        agent.stopServer(); // a new agent takes the new environment
        await js('document.getElementById("new-chat").click()');
        await sleep(500);
        reply = await send('RUN draw "a picture for the test"', 300);
        const drawnPath = (reply.pictures || [])[0] || '';
        check('draw: the agent made a picture and the answer names it',
          !reply.failed && /Picture saved:/.test(reply.text || '') && drawnPath.startsWith(os.homedir() + path.sep + 'pictures') && fs.existsSync(drawnPath),
          JSON.stringify(reply).slice(0, 400));
        check('draw: the chat shows the picture under the answer',
          await until(() => js('[...document.querySelectorAll(".msg.agent .pictures img")].some((i) => (i.title || "") === ' + JSON.stringify(drawnPath) + ' && i.naturalWidth > 0)'), 15000), drawnPath);
        await shot('draw');
        if (webStatus && webStatus.https) {
          // a phone on the network gets the same answer and can load the same picture
          const picPort = parseInt(webStatus.https.split(':').pop().replace(/[^0-9]/g, ''), 10);
          const picRequest = (url, { method = 'GET', body, cookie, binary = false } = {}) => new Promise((resolve) => {
            const req = httpsLib.request({ host: '127.0.0.1', port: picPort, path: url, method, rejectUnauthorized: false,
              headers: Object.assign(body ? { 'content-type': 'application/json' } : {}, cookie ? { cookie } : {}) }, (res) => {
                const parts = [];
                res.on('data', (d) => parts.push(d));
                res.on('end', () => {
                  const buf = Buffer.concat(parts);
                  resolve({ code: res.statusCode, text: binary ? '' : buf.toString('utf8'), buf, type: res.headers['content-type'] || '', cookie: (res.headers['set-cookie'] || [])[0] || '' });
                });
              });
            req.on('error', (e) => resolve({ code: 0, text: e.message, buf: Buffer.alloc(0), type: '', cookie: '' }));
            if (body) req.end(JSON.stringify(body));
            else req.end();
          });
          let w = await picRequest('/api/login', { method: 'POST', body: { password: webStatus.password } });
          const picCookie = (w.cookie || '').split(';')[0];
          w = await picRequest('/api/state', { cookie: picCookie });
          let st = {};
          try { st = JSON.parse(w.text); } catch (e) { st = { error: String(e) }; }
          const phoneLast = (st.messages || []).slice(-1)[0] || {};
          check('draw: the phone sees the picture in the same answer',
            Array.isArray(phoneLast.pictures) && phoneLast.pictures.length === 1 && phoneLast.pictures[0].startsWith('/api/pic?path='),
            JSON.stringify(phoneLast).slice(0, 200));
          w = await picRequest(phoneLast.pictures && phoneLast.pictures[0] || '', { cookie: picCookie, binary: true });
          check('draw: the phone can load the picture', w.code === 200 && (w.type || '').includes('image/png') && w.buf.equals(pngDraw), 'code ' + w.code + ' ' + w.type);
          w = await picRequest(phoneLast.pictures && phoneLast.pictures[0] || '/api/pic');
          check('draw: without the password the picture is not given', w.code === 401, 'code ' + w.code);
        }
        delete process.env.TERMANT_DRAW_POLLINATIONS;
        delete process.env.TERMANT_DRAW_GAP;
        drawAgentFake.close();
        if (drawnPath && fs.existsSync(drawnPath)) fs.unlinkSync(drawnPath);
        delete process.env.OPENCODE_CONFIG_CONTENT;
        agent.stopServer();
        Object.assign(ctx.settings(), real);
        fake.close();
      }
    }
  } catch (e) {
    const renderer = ctx.rendererError && ctx.rendererError();
    check('the test ran to its end', false, (e.stack || e) + (renderer ? ' | the page said: ' + renderer : ''));
  }

  const hard = results.filter((x) => !x.ok && !x.soft).length;
  const soft = results.filter((x) => !x.ok && x.soft).length;
  fs.writeFileSync(path.join(dir, 'result.json'), JSON.stringify({ pass: hard === 0, failed: hard, softFailed: soft, results }, null, 1));
  log('RESULT: ' + (hard === 0 ? 'PASS' : 'FAIL') + ' (' + results.length + ' checks, ' + hard + ' failed, ' + soft + ' soft-failed)');
  app.exit(hard === 0 ? 0 : 1);
};

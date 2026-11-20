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

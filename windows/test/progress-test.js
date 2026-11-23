'use strict';
// Checks that the chat can say how far a running command is: the newest line of what the command
// has printed, made readable for downloads, and (when OpenCode is on this computer) a real run of
// OpenCode in which a pretend model runs a slow command. Run:  node test/progress-test.js
// For the real run, name the OpenCode program:  TERMANT_OPENCODE=/path/to/opencode node test/progress-test.js
const fs = require('fs');
const os = require('os');
const path = require('path');
const { newestLine, tailOf } = require('../src/progress');

/** A command that prints six lines, one every 0.7 s, the way a download tells how far it is. */
const SLOW = "let n = 0; const t = setInterval(() => { n++; console.log('got ' + n + ' of 6 MB'); if (n > 5) clearInterval(t); }, 700);\n";

/** What a run of "RUN node slow.js" must show. check(name, ok, detail); events are what onEvent got. */
function judge(check, result, events) {
  const shown = events.filter((e) => e.kind === 'status' && e.output);
  const lines = shown.map((e) => e.output);
  check('the command is named when it starts, not when it has ended',
    events.findIndex((e) => e.kind === 'status' && e.activity === 'command' && e.detail === 'node slow.js') >= 0 &&
    events.findIndex((e) => e.kind === 'status' && e.detail === 'node slow.js') < events.findIndex((e) => e.kind === 'step'),
    JSON.stringify(events.map((e) => e.kind + ':' + (e.detail || e.step || '') + (e.output ? '>' + e.output : ''))).slice(0, 600));
  check('what the command prints is followed while it runs', lines.filter((l) => /^got [1-5] of 6 MB$/.test(l)).length >= 3, JSON.stringify(lines));
  check('the lines come in their order', lines.join('|') === lines.slice().sort().join('|') && new Set(lines).size === lines.length, JSON.stringify(lines));
  check('each line comes with the command it belongs to', shown.length > 0 && shown.every((e) => e.activity === 'command' && e.detail === 'node slow.js'), JSON.stringify(shown.slice(0, 2)));
  check('the answer has what the command printed', !result.failed && /RAN: .*got 6 of 6 MB/.test(String(result.text).replace(/\s+/g, ' ')), JSON.stringify(result.text));
  check('the step is listed once', (result.steps || []).filter((s) => s === 'node slow.js').length === 1, JSON.stringify(result.steps));
}

module.exports = { SLOW, judge };

if (require.main === module) {
  (async () => {
    let failed = 0, count = 0;
    const check = (name, ok, detail) => {
      count++;
      if (!ok) failed++;
      console.log((ok ? 'PASS ' : 'FAIL ') + name + (ok || detail === undefined ? '' : ' :: ' + String(detail).slice(0, 600)));
    };
    const is = (name, printed, wanted) => check(name, newestLine(printed) === wanted, JSON.stringify(newestLine(printed)));

    is('nothing printed gives nothing', '', '');
    is('only empty lines give nothing', '\n\r\n  \n', '');
    is('the newest line is taken', 'one\ntwo\nthree\n', 'three');
    is('a line still being written counts', 'one\ntwo\nthr', 'thr');
    is('Windows line ends are understood', 'one\r\ntwo\r\n', 'two');
    is('a line that redraws itself gives its newest state', 'Fetching 0%\rFetching 20%\rFetching 40%', 'Fetching 40%');
    is('colours are left out', '\u001b[32mGet:1\u001b[0m http://archive.ubuntu.com noble InRelease [256 kB]\n', 'Get:1 http://archive.ubuntu.com noble InRelease [256 kB]');
    is('runs of spaces and tabs become one space', 'Unpacking   ffmpeg\t(7:6.1.1) ...\n', 'Unpacking ffmpeg (7:6.1.1) ...');
    check('a very long line is cut', newestLine('x'.repeat(500)).length === 160);
    is('Hindi stays as it is', 'डाउनलोड हो रहा है 40%\n', 'डाउनलोड हो रहा है 40%');

    const head = '  % Total    % Received % Xferd  Average Speed   Time    Time     Time  Current\n' +
      '                                 Dload  Upload   Total   Spent    Left  Speed\n';
    is('curl: the heading lines say nothing', 'start\n' + head, 'start');
    is('curl: a download in the middle', head + '\r  0     0    0     0    0     0      0      0 --:--:-- --:--:-- --:--:--     0' +
      '\r 45 22.3M   45 10.1M    0     0  1024k      0  0:00:22  0:00:10  0:00:12 1100k', '45% of 22.3 MB \u00b7 1100 kB/s \u00b7 12s left');
    is('curl: minutes left', ' 12  700M   12 84.0M    0     0  2048k      0  0:05:50  0:00:41  0:05:09 2100k', '12% of 700 MB \u00b7 2100 kB/s \u00b7 5:09 left');
    is('curl: hours left', '  1 4096M    1 41.0M    0     0   512k      0  2:16:32  0:01:22  2:15:10  500k', '1% of 4096 MB \u00b7 500 kB/s \u00b7 2:15:10 left');
    is('curl: before the first byte', '  0     0    0     0    0     0      0      0 --:--:--  0:00:02 --:--:--     0', '0 B downloaded');
    is('curl: the size known, nothing there yet', '  0 22.3M    0     0    0     0      0      0 --:--:-- --:--:-- --:--:--     0', '0% of 22.3 MB');
    is('curl: done', '100   313  100   313    0     0    135      0  0:00:02  0:00:02 --:--:--   135', '100% of 313 B \u00b7 135 B/s');
    is('curl: a download of unknown size', '100 10.1M    0 10.1M    0     0  1024k      0 --:--:--  0:00:10 --:--:-- 1100k', '10.1 MB downloaded \u00b7 1100 kB/s');
    is('curl: an upload', ' 30 5120k    0     0   30 1536k      0   512k  0:00:10  0:00:03  0:00:07  512k', '30% of 5120 kB \u00b7 512 kB/s \u00b7 7s left');
    is('curl -#: the bar becomes its percentage', '\r##########                                                      13.7%', '13.7%');
    is('curl -#: at the start', '\r                                                                 0.0%', '0.0%');
    is('wget: a line of dots with a percentage', '  1050K .......... .......... .......... .......... .......... 12% 1.20M 3s\n', '12% \u00b7 1.20 MB/s \u00b7 3s left');
    is('wget: without a known size', '  1050K .......... .......... .......... .......... .......... 1.20M\n', '1050 kB downloaded \u00b7 1.20 MB/s');
    is('wget: the last line', '  8800K .......... .......... ....                            100% 2.40M=3.9s\n', '100% \u00b7 2.40 MB/s');
    is('a line of numbers that is no meter stays', '1 2 3 4 5 6 7 8 9 10 11 12', '1 2 3 4 5 6 7 8 9 10 11 12');
    is('a sentence that ends in a percentage stays', 'Progress: 50%', 'Progress: 50%');
    is('apt lines stay as they are', 'Get:12 http://ports.ubuntu.com noble/main arm64 libavcodec60 [5,321 kB]\nFetched 23.4 MB in 5s (4,512 kB/s)\n', 'Fetched 23.4 MB in 5s (4,512 kB/s)');
    is('pip lines stay as they are', 'Collecting numpy\n  Downloading numpy-2.1.0-cp312-cp312-manylinux_2_17_aarch64.whl (16.3 MB)\n', 'Downloading numpy-2.1.0-cp312-cp312-manylinux_2_17_aarch64.whl (16.3 MB)');

    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'termant-progress-'));
    const file = path.join(dir, 'printed.out');
    check('a file that is not there reads as nothing', tailOf(file) === '');
    fs.writeFileSync(file, '');
    check('an empty file reads as nothing', tailOf(file) === '');
    fs.writeFileSync(file, 'a'.repeat(9000) + '\nlast line\n');
    check('only the end of a long file is read', tailOf(file).length === 4096 && newestLine(tailOf(file)) === 'last line');
    fs.writeFileSync(file, Buffer.from('first\r\ngot 3 of 6 MB\r\n', 'utf16le'));
    check('two bytes a letter are understood', newestLine(tailOf(file)) === 'got 3 of 6 MB', JSON.stringify(tailOf(file)));
    fs.writeFileSync(file, 'पहला\nदूसरा 40%\n');
    check('a file in Hindi is read', newestLine(tailOf(file)) === 'दूसरा 40%');

    const program = process.env.TERMANT_OPENCODE;
    if (!program) {
      console.log('SKIP the real run of OpenCode with a slow command (name the program in TERMANT_OPENCODE)');
    } else {
      const { Agent } = require('../src/agent');
      const { fakeModel } = require('./team-test');
      const fake = await fakeModel();
      const home = path.join(dir, 'agent');
      const folder = path.join(dir, 'work');
      fs.mkdirSync(path.join(home, 'bin'), { recursive: true });
      fs.mkdirSync(folder, { recursive: true });
      fs.writeFileSync(path.join(folder, 'slow.js'), SLOW);
      const settings = { keys: {}, github: {}, auto: true, workspace: folder, mcp: {}, webSearch: false };
      const agent = new Agent(home, path.join(__dirname, '..', 'resources'), () => settings, () => ({}));
      fs.symlinkSync(path.resolve(program), agent.bin);
      process.env.OPENCODE_CONFIG_CONTENT = JSON.stringify(fake.config);
      agent.writeConfig(path.join(__dirname, '..', 'resources', 'AGENTS.md'));
      let events = [];
      let result = await agent.run({ text: 'RUN node slow.js', model: fake.model, cwd: folder, onEvent: (e) => events.push(e) });
      judge(check, result, events);
      check('this run went through the kept server', agent.fast === true);
      // the next message in the same conversation is followed too
      events = [];
      result = await agent.run({ text: 'RUN node slow.js', session: result.session, model: fake.model, cwd: folder, onEvent: (e) => events.push(e) });
      judge((name, ok, detail) => check('again: ' + name, ok, detail), result, events);
      // without the kept server nothing is lost: the step is listed when it has ended, as before
      process.env.TERMANT_NO_SERVER = '1';
      agent.stopServer();
      events = [];
      result = await agent.run({ text: 'RUN node slow.js', model: fake.model, cwd: folder, onEvent: (e) => events.push(e) });
      delete process.env.TERMANT_NO_SERVER;
      check('without the kept server the run still works', !result.failed && agent.fast === false && (result.steps || []).join('|') === 'node slow.js' &&
        /got 6 of 6 MB/.test(result.text), JSON.stringify({ text: result.text, steps: result.steps, fast: agent.fast }));
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

'use strict';
// Checks YouTube reading, skills, the diary and the ElevenLabs keys against a
// pretend YouTube and a pretend ElevenLabs on this computer. Run: node test/learn-test.js
const http = require('http');
const fs = require('fs');
const os = require('os');
const path = require('path');

const VIDEO = 'dQw4w9WgXcQ';
const renderer = (id, title) => ({ videoRenderer: {
  videoId: id, title: { runs: [{ text: title }] }, ownerText: { runs: [{ text: 'Office Guru' }] },
  lengthText: { simpleText: '4:05' }, viewCountText: { simpleText: '1,234 views' }, publishedTimeText: { simpleText: '2 years ago' },
} });
const found = { contents: { twoColumnSearchResultsRenderer: { primaryContents: { sectionListRenderer: { contents: [
  { itemSectionRenderer: { contents: [renderer(VIDEO, 'Make a table in Word'), { adSlotRenderer: {} }, renderer('aaaaaaaaaaa', 'Word tables & more')] } },
] } } } } };
const played = (host) => ({
  playabilityStatus: { status: 'OK' },
  videoDetails: { videoId: VIDEO, title: 'Make a table in Word', author: 'Office Guru', lengthSeconds: '245', shortDescription: 'Tables, step by step.' },
  captions: { playerCaptionsTracklistRenderer: { captionTracks: [
    { baseUrl: 'https://www.youtube.com/api/timedtext?v=' + VIDEO + '&lang=de&fmt=srv3', languageCode: 'de' },
    { baseUrl: 'https://www.youtube.com/api/timedtext?v=' + VIDEO + '&lang=en&kind=asr&fmt=srv3', languageCode: 'en', kind: 'asr' },
  ] } },
});
const spoken = { events: [
  { tStartMs: 0, segs: [{ utf8: 'open word' }, { utf8: ' and click insert' }] },
  { tStartMs: 4000, segs: [{ utf8: '\n' }] },
  { tStartMs: 30000, segs: [{ utf8: 'then click table' }] },
  { tStartMs: 70000, segs: [{ utf8: 'pick three by three' }] },
  { tStartMs: 130000, segs: [{ utf8: 'type in the cells' }] },
] };
const asked = [];

const server = http.createServer((req, res) => {
  let body = '';
  req.on('data', (d) => { body += d; });
  req.on('end', () => {
    const url = new URL(req.url, 'http://x');
    const key = req.headers['xi-api-key'] || '';
    const send = (code, data, type) => {
      res.writeHead(code, { 'content-type': type || 'application/json' });
      res.end(Buffer.isBuffer(data) ? data : typeof data === 'string' ? data : JSON.stringify(data));
    };
    if (url.pathname === '/youtubei/v1/search') return send(200, JSON.parse(body).query === 'nothing here' ? {} : found);
    if (url.pathname === '/results') return send(200, '<html><script>var ytInitialData = ' + JSON.stringify(url.searchParams.get('search_query') === 'nothing here' ? {} : found) + ';</script></html>', 'text/html');
    if (url.pathname === '/youtubei/v1/player') {
      const id = JSON.parse(body).videoId;
      if (id === 'bbbbbbbbbbb') return send(200, { playabilityStatus: { status: 'LOGIN_REQUIRED', reason: 'Sign in to confirm you are not a bot' } });
      if (id === 'ccccccccccc') return send(200, { videoDetails: { videoId: id, title: 'Silent', author: 'Nobody', lengthSeconds: '10', shortDescription: 'No words.' } });
      return send(200, played());
    }
    if (url.pathname === '/watch') return send(200, '<html><script>var ytInitialPlayerResponse = {"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm you are not a bot"}};</script>', 'text/html');
    if (url.pathname === '/api/timedtext') {
      if (url.searchParams.get('lang') !== 'en') return send(200, '<transcript><text start="1.5" dur="2">Hallo &amp;amp; willkommen</text></transcript>', 'text/xml');
      return send(200, url.searchParams.get('fmt') === 'json3' ? spoken : '', 'application/json');
    }
    if (url.pathname.startsWith('/v1/text-to-speech/')) {
      asked.push(key + ' ' + url.pathname.split('/').pop() + ' ' + JSON.parse(body).text);
      if (key === 'key-used-up-1') return send(401, { detail: { status: 'quota_exceeded', message: 'You have 0 credits left.' } });
      if (key === 'key-wrong-22') return send(401, { detail: { status: 'invalid_api_key', message: 'Invalid API key' } });
      if (url.pathname.endsWith('/no-such-voice')) return send(400, { detail: { status: 'voice_not_found', message: 'A voice with that id was not found.' } });
      return send(200, Buffer.concat([Buffer.from('ID3'), Buffer.alloc(400, 7)]), 'audio/mpeg');
    }
    if (url.pathname === '/v1/user/subscription') {
      if (key === 'key-wrong-22') return send(401, { detail: { status: 'invalid_api_key', message: 'Invalid API key' } });
      return send(200, { tier: 'free', character_count: key === 'key-used-up-1' ? 10000 : 120, character_limit: 10000 });
    }
    send(404, { error: 'not found' });
  });
});

let failed = 0;
function check(name, ok, detail) {
  if (!ok) failed++;
  console.log((ok ? 'PASS ' : 'FAIL ') + name + (ok || detail === undefined ? '' : '  -> ' + JSON.stringify(detail)));
}
async function refused(run) {
  try {
    await run();
    return '';
  } catch (e) {
    return e.message;
  }
}

server.listen(0, '127.0.0.1', async () => {
  const base = 'http://127.0.0.1:' + server.address().port;
  process.env.TERMANT_YOUTUBE_URL = base;
  process.env.TERMANT_ELEVEN_URL = base;
  const learn = require('../src/learn');
  const { Voice, keysFrom } = require('../src/voice');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'termant-learn-'));
  try {
    const list = await learn.youtubeSearch('table in word');
    check('search finds both videos, not the advert', list.length === 2 && list[0].id === VIDEO && list[1].title === 'Word tables & more', list);
    const text = learn.searchText('table in word', list);
    check('search text has title, channel, length and id', /1\. Make a table in Word\n {3}Office Guru \| 4:05 \| 1,234 views \| 2 years ago\n {3}id dQw4w9WgXcQ/.test(text), text);
    check('search with no result says so', /gave no videos/.test(await refused(() => learn.youtubeSearch('nothing here'))));
    check('page fallback reads ytInitialData', learn.videos(learn.embedded('x var ytInitialData = ' + JSON.stringify(found) + ';</script>', 'ytInitialData')).length === 2);

    const v = await learn.youtubeRead('https://www.youtube.com/watch?v=' + VIDEO + '&t=3s');
    check('read picks English and knows it is automatic', v.lang === 'en' && v.auto === true && v.lines.length === 4, v);
    check('read text has a time mark each minute', v.text === '[0:00] open word and click insert then click table\n[1:10] pick three by three\n[2:10] type in the cells', v.text);
    const shown = learn.readText(v);
    check('read text names the video and the link', shown.includes('YouTube video ' + VIDEO + ': Make a table in Word') && shown.includes('4:05 long') && shown.includes('https://youtu.be/' + VIDEO), shown);
    const part = await learn.youtubeRead(VIDEO, { from: 1 });
    check('read --from 1 starts after the first minute', part.text.startsWith('[1:10] pick three'), part.text);
    const short = await learn.youtubeRead(VIDEO, { most: 60 });
    check('a long video is cut and says where to go on', short.more === 1 && learn.readText(short).includes('--from 1'), short.text);
    const german = await learn.youtubeRead(VIDEO, { lang: 'de' });
    check('read --lang de takes the German captions (XML)', german.lang === 'de' && german.text === '[0:02] Hallo & willkommen', german.text);
    const silent = await learn.youtubeRead('ccccccccccc');
    check('a video without captions gives its description', learn.readText(silent).includes('has no captions') && learn.readText(silent).includes('No words.'));
    check('a refused video says why', /Sign in to confirm/.test(await refused(() => learn.youtubeRead('bbbbbbbbbbb'))));
    check('a wrong id is refused', /not a YouTube video/.test(await refused(() => learn.youtubeRead('hello'))));
    check('ids from links', learn.videoId('https://youtu.be/' + VIDEO + '?si=x') === VIDEO && learn.videoId('https://www.youtube.com/shorts/' + VIDEO) === VIDEO);

    const known = new learn.Learned(dir);
    check('no skills at first', known.listText() === 'No skills yet.' && known.guide('pc skill show') === '');
    check('today is empty at first', known.sinceText(1) === 'Learned today (' + learn.day() + '): nothing yet.');
    const steps = '1. Open Word.\n2. Insert > Table.\n3. Pick the size.';
    check('a skill is saved', /^Skill learned: word-table/.test(known.saveSkill('word-table', 'Make a table in Word', steps, 'https://youtu.be/' + VIDEO)));
    check('saving again updates', /^Skill updated/.test(known.saveSkill('word-table', 'Make a table in Word', steps + '\n4. Type.', '')));
    check('the skill can be read', known.showSkill('word-table').includes('4. Type.') && known.showSkill('word-table').includes('what: Make a table in Word'));
    check('the guide names it', known.guide('pc skill show').includes('- `word-table`: Make a table in Word') && known.guide('pc skill show').includes('`pc skill show NAME`'));
    check('bad names are refused', /name must be/.test(await refused(() => known.saveSkill('Word Table', 'x', steps))));
    check('missing steps are refused', /steps are missing/.test(await refused(() => known.saveSkill('abc', 'x', 'short'))));
    check('too long is refused', /letters; keep a skill under/.test(await refused(() => known.saveSkill('abc', 'x', 'a'.repeat(7000)))));
    known.record('note', '', 'The user likes short answers');
    const today = known.sinceText(1);
    check('today lists what was learned', today.startsWith('Learned today (' + learn.day() + '): 3') && today.includes('- skill word-table: Make a table in Word (from https://youtu.be/' + VIDEO + ')') && today.includes('- note: The user likes short answers'), today);
    const old = known.diary();
    old.unshift({ day: '2020-01-01', at: 1, kind: 'note', name: '', text: 'old thing', from: '' });
    fs.writeFileSync(known.diaryFile, JSON.stringify(old));
    check('older days are left out of today', !known.sinceText(1).includes('old thing') && known.sinceText(7).startsWith('Learned in the last 7 days: 3'));
    check('a skill is removed', known.removeSkill('word-table') === 'Skill removed: word-table' && known.listText() === 'No skills yet.');
    check('removing a missing skill is refused', /There is no skill/.test(await refused(() => known.removeSkill('word-table'))));

    // skills that come with the app
    const shipped = path.join(__dirname, '..', 'resources', 'skills');
    const mine = new learn.Learned(path.join(dir, 'with-built-in'));
    const before = mine.sinceText(1);
    check('the skill that comes with the app is put among the skills', JSON.stringify(mine.addBuiltIn(shipped)) === '["remove-ai-marks"]' && /^remove-ai-marks: Find and remove the marks AI tools leave/.test(mine.listText()), mine.listText());
    const marks = mine.showSkill('remove-ai-marks');
    check('it can be read, names its source and the command', marks.includes('from: https://github.com/guillaumemeyer/watermarks-remover') && marks.includes('pc marks check') && marks.includes('pc marks clean') && marks.includes('learned: ' + learn.day()), marks.slice(0, 300));
    check('it says what it does not do and what must not be claimed', /does not remove a logo, stamp or writing that can be SEEN/.test(marks) && /Never say that the content is now "human-written", "undetectable"/.test(marks));
    check('it fits a skill and is plain ASCII', marks.split('\n---\n')[1].length < 6000 && !/[^\x00-\x7f]/.test(marks));
    check('the guide names it', mine.guide('pc skill show').includes('- `remove-ai-marks`: Find and remove the marks'));
    check('it is not counted as learned today', mine.sinceText(1) === before);
    check('a second start adds nothing', mine.addBuiltIn(shipped).length === 0);
    mine.removeSkill('remove-ai-marks');
    check('removed by the user, it does not come back', mine.addBuiltIn(shipped).length === 0 && mine.listText() === 'No skills yet.');
    const newer = path.join(dir, 'shipped-newer');
    fs.mkdirSync(newer, { recursive: true });
    fs.writeFileSync(path.join(newer, 'remove-ai-marks.md'), fs.readFileSync(path.join(shipped, 'remove-ai-marks.md'), 'utf8').replace('version: 1', 'version: 2').replace('## Steps', '## Steps (newer)'));
    check('nor with a newer version of the app', mine.addBuiltIn(newer).length === 0 && mine.listText() === 'No skills yet.');
    const kept = new learn.Learned(path.join(dir, 'kept'));
    kept.addBuiltIn(shipped);
    check('a newer version replaces the one that was put there', JSON.stringify(kept.addBuiltIn(newer)) === '["remove-ai-marks"]' && kept.showSkill('remove-ai-marks').includes('## Steps (newer)'));
    const own = new learn.Learned(path.join(dir, 'own'));
    own.addBuiltIn(shipped);
    own.saveSkill('remove-ai-marks', 'My own way', '1. Do it my way.\n2. Then stop.');
    check('one the user changed is left alone', own.addBuiltIn(newer).length === 0 && own.showSkill('remove-ai-marks').includes('Do it my way'));
    const had = new learn.Learned(path.join(dir, 'had'));
    had.saveSkill('remove-ai-marks', 'Mine from before', '1. Mine from before.\n2. Stop.');
    check('a skill of that name the user had already is left alone', had.addBuiltIn(shipped).length === 0 && had.showSkill('remove-ai-marks').includes('Mine from before'));
    check('a folder that is not there adds nothing', new learn.Learned(path.join(dir, 'none')).addBuiltIn(path.join(dir, 'no-such-folder')).length === 0);

    const keys = keysFrom('key-used-up-1\nkey-wrong-22, key-good-333\nkey-good-333\nshort');
    check('keys are read one per line, doubles and scraps dropped', keys.length === 3, keys);
    const voice = new Voice(dir);
    const first = await voice.speak('namaste  duniya', keys);
    check('the third key speaks after two are refused', first.key === 3 && first.audio.length === 403 && first.audio.slice(0, 3).toString() === 'ID3', { key: first.key, notes: first.notes });
    check('each refusal is named', first.notes.length === 2 && /key 1 \(\.\.\.up-1\): quota_exceeded/.test(first.notes[0]) && /key 2 \(\.\.\.g-22\): invalid_api_key/.test(first.notes[1]), first.notes);
    check('all three were tried, in order', asked.length === 3 && asked[0].startsWith('key-used-up-1 ') && asked[2] === 'key-good-333 JBFqnCBsd6RMkjVDRZzb namaste duniya', asked);
    asked.length = 0;
    const second = await new Voice(dir).speak('again', keys);
    check('next time the used-up keys are not asked at all', second.key === 3 && asked.length === 1 && second.notes.length === 0, asked);
    asked.length = 0;
    check('with only bad keys the reason is told', /None of the 2 ElevenLabs keys can speak now: key 1 .*quota_exceeded; key 2 .*invalid_api_key/.test(await refused(() => new Voice(dir).speak('x', keys.slice(0, 2)))));
    check('resting keys are still tried when nothing else is left', asked.length === 2, asked);
    check('a wrong voice is not blamed on the key', /voice_not_found|was not found/.test(await refused(() => voice.speak('x', keys, { voice: 'no-such-voice' }))) && !voice.resting('key-good-333'));
    const state = JSON.parse(fs.readFileSync(path.join(dir, 'voice.json'), 'utf8'));
    check('no key is written to voice.json', !JSON.stringify(state).includes('key-'), state);
    const report = await voice.report(keys);
    check('the report shows use and rest, never a whole key', /1\. \.\.\.up-1 {2}10000 of 10000 letters used \(free\) {2}resting until .*quota_exceeded/.test(report) && /3\. \.\.\.-333 {2}120 of 10000 letters used \(free\) {2}in use/.test(report) && !report.includes('key-good-333'), report);
    check('no keys gives a clear message', /No ElevenLabs key is saved/.test(await refused(() => voice.speak('x', []))));
    const long = await voice.speak('a'.repeat(3000), keys);
    check('a long text is cut and says so', /first 2500 letters/.test(long.notes.join(' ')), long.notes);
  } catch (e) {
    failed++;
    console.log('FAIL the test itself broke: ' + (e.stack || e));
  }
  fs.rmSync(dir, { recursive: true, force: true });
  server.close();
  console.log(failed ? 'RESULT: FAIL (' + failed + ')' : 'RESULT: PASS');
  process.exit(failed ? 1 : 0);
});

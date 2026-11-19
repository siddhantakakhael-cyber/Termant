// A stand-in for a model service that speaks the "chat completions" protocol, for the tests.
// It answers by simple rules and writes every request to a log, so that a test can also
// check what the app sent.
//   node fake-llm.js <port> <log file>
const http = require('http');
const fs = require('fs');

const port = parseInt(process.argv[2] || '8787', 10);
const logFile = process.argv[3] || '/tmp/fake-llm.log';
const MODELS = ['test/alpha:free', 'test/beta:free', 'test/paid'];

function log(entry) {
  fs.appendFileSync(logFile, JSON.stringify(entry) + '\n');
}

function reply(model, messages) {
  const last = messages[messages.length - 1] || {};
  const text = String(last.content || '');
  const system = String((messages[0] || {}).content || '');
  // a check-in written ahead of time, to be delivered as a notification
  if (text.includes('Write now the message you will send')) {
    const topic = (text.match(/come back to this: "([^"]*)"/) || [])[1];
    const already = (text.match(/^ {2}"/gm) || []).length;
    return topic ? `Arre, ${topic}? Batao na, kaisa raha?` : `Namaste! Din kaisa ja raha hai? (${already + 1})`;
  }
  // the agent speaks first, now
  if (text.includes('this time you speak first')) {
    const topic = (text.match(/come back to this: "([^"]*)"/) || [])[1];
    return topic ? `Abhi poochh raha hoon: ${topic}?` : 'Namaste! Aaj aap kaise hain?';
  }
  if (/yaad rakho/i.test(text)) return 'Theek hai, yaad rakhunga.\n[[note: The user is called Asha.]]';
  if (/exam/i.test(text)) return 'All the best for the exam!\n[[care: 1m | how the exam went]]';
  if (/doctor/i.test(text)) return 'Apna khayal rakhna. [[care: 20h | how the visit to the doctor went]]';
  if (/jagana|remind/i.test(text)) return '[[remind: 7am | Uthiye, subah ho gayi]]Theek hai, subah 7 baje yaad dilaunga.';
  if (/mera naam kya/i.test(text)) return /The user is called Asha/.test(system) ? 'Aapka naam Asha hai.' : 'Mujhe aapka naam nahi pata.';
  if (/think/i.test(text)) return '<think>the user wants a short answer</think>Short answer.';
  if (/bold/i.test(text)) return 'This is **bold** and `code`.';
  return `Echo from ${model}: ${text}`;
}

const server = http.createServer((req, res) => {
  let body = '';
  req.on('data', (chunk) => { body += chunk; });
  req.on('end', () => {
    const auth = req.headers.authorization || '';
    if (req.method === 'GET' && req.url === '/healthz') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      return res.end('{"ok":true}');
    }
    if (auth === 'Bearer wrong-key') {
      log({ url: req.url, auth, status: 401 });
      res.writeHead(401, { 'Content-Type': 'application/json' });
      return res.end(JSON.stringify({ error: { message: 'Invalid API key' } }));
    }
    if (req.method === 'GET' && req.url === '/v1/models') {
      log({ url: req.url, auth, status: 200 });
      res.writeHead(200, { 'Content-Type': 'application/json' });
      return res.end(JSON.stringify({ data: MODELS.map((id) => ({ id })) }));
    }
    if (req.method === 'POST' && req.url === '/v1/chat/completions') {
      let data;
      try { data = JSON.parse(body); } catch (e) { data = {}; }
      const messages = data.messages || [];
      const last = String((messages[messages.length - 1] || {}).content || '');
      // the first free model has "used up its limit" whenever the message says so
      if (data.model === 'test/alpha:free' && /limit/i.test(last)) {
        log({ url: req.url, auth, model: data.model, status: 429, messages });
        res.writeHead(429, { 'Content-Type': 'application/json' });
        return res.end(JSON.stringify({ error: { message: 'Rate limit exceeded: free-models-per-day' } }));
      }
      if (/silent/i.test(last)) {
        log({ url: req.url, auth, model: data.model, status: 200, messages, answer: '' });
        res.writeHead(200, { 'Content-Type': 'text/event-stream' });
        return res.end('data: [DONE]\n\n');
      }
      const answer = reply(data.model, messages);
      log({ url: req.url, auth, model: data.model, status: 200, stream: !!data.stream, messages, answer });
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' });
      res.write(': keep-alive\n\n');
      // in small pieces, cut in the middle of words and of [[...]] lines, like a real stream
      const pieces = answer.match(/[\s\S]{1,7}/g) || [];
      const slow = /slow/i.test(last) ? 400 : 15;
      let i = 0;
      const next = () => {
        if (i >= pieces.length) {
          res.write('data: [DONE]\n\n');
          return res.end();
        }
        res.write('data: ' + JSON.stringify({ choices: [{ index: 0, delta: { content: pieces[i++] } }] }) + '\n\n');
        setTimeout(next, slow);
      };
      next();
      return undefined;
    }
    log({ url: req.url, status: 404 });
    res.writeHead(404, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify({ error: { message: 'Not found' } }));
  });
});

server.listen(port, '127.0.0.1', () => console.log(`fake model service on http://127.0.0.1:${port}/v1`));

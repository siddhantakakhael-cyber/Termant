'use strict';
// The chat page a phone (or another PC) opens from the local network. It talks
// to the same chat the Termant window shows, so both screens see the same talk.

(function () {
  const $ = (id) => document.getElementById(id);
  const HI = /^hi\b/i.test(navigator.language || '');
  const T = HI ? {
    onPc: 'isi PC par',
    open: 'Kholo',
    wrong: 'Galat password',
    password: 'Password',
    send: 'Bhejo',
    stop: 'Roko',
    listening: '🎙 Sun raha hoon — bolo “hey Termant”',
    command: '🎙 Bolo, main sun raha hoon',
    micOff: 'Awaaz band',
    micOn: 'Awaaz chalu',
    sayOn: 'Jawab awaaz mein sunao',
    sayOff: 'Jawab sirf dikhao',
    busyWait: 'Termant abhi kaam kar raha hai; jawab aate hi dikhega.',
    pc: 'PC',
    notFound: 'PC tak pahunch nahi paya. Kya Termant PC par kholi hai?',
    loginHint: 'Password PC wale Termant → Settings → Web chat mein dikhta hai.',
    micNo: 'Is browser mein mic nahi chalta (https chahiye).',
  } : {
    onPc: 'on this PC',
    open: 'Open',
    wrong: 'Wrong password',
    password: 'Password',
    send: 'Send',
    stop: 'Stop',
    listening: '🎙 Listening — say “hey Termant”',
    command: '🎙 Speak, I am listening',
    micOff: 'Voice off',
    micOn: 'Voice on',
    sayOn: 'Speak the answers',
    sayOff: 'Show the answers only',
    busyWait: 'Termant is busy right now; the answer will appear when it is done.',
    pc: 'PC',
    notFound: 'The PC is not reachable. Is Termant open on the PC?',
    loginHint: 'The password is in Termant on the PC → Settings → Web chat.',
    micNo: 'This browser does not allow the microphone (https is wanted).',
  };
  $('head-sub').textContent = T.onPc;
  $('unlock').textContent = T.open;
  $('password').placeholder = T.password;
  $('login-hint').textContent = T.loginHint;
  $('send').title = T.send;
  $('stop').textContent = T.stop;
  $('mic').title = T.micOff;
  $('say').title = T.sayOn;
  document.documentElement.lang = HI ? 'hi' : 'en';
  document.title = 'Termant' + (HI ? ' — ' + T.onPc : '');

  const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  let authed = false;
  let state = null;
  let sawReply = null;
  let voiceOn = false;
  let awake = false;
  let sayOn = true;

  // ------------------------------------------------------------ login
  async function api(url, body) {
    const options = { method: body ? 'POST' : 'GET', headers: { 'content-type': 'application/json' } };
    if (body) options.body = JSON.stringify(body);
    const response = await fetch(url, options);
    let data = {};
    try { data = await response.json(); } catch (e) { /* not json */ }
    return { code: response.status, data };
  }
  async function unlock() {
    const password = $('password').value.trim();
    if (!password) return;
    const r = await api('/api/login', { password });
    if (r.code === 200) {
      authed = true;
      $('login').hidden = true;
      refresh();
    } else {
      $('login-error').textContent = r.data.error || T.wrong;
    }
  }
  $('unlock').addEventListener('click', unlock);
  $('password').addEventListener('keydown', (e) => { if (e.key === 'Enter') unlock(); });

  // ------------------------------------------------------------ the chat
  const messages = $('messages');
  function bubble(m) {
    const div = document.createElement('div');
    div.className = 'msg ' + (m.user ? 'user' : 'assistant') + (m.failed ? ' failed' : '');
    const who = m.user ? (m.from === 'web' ? '' : T.pc) : 'Termant';
    const steps = (m.steps || []).map((s) => '<div>· ' + esc(s) + '</div>').join('');
    div.innerHTML = (who ? '<div class="who">' + esc(who) + '</div>' : '') +
      '<div class="bubble">' + esc(m.text || (m.user ? '' : '…')) + (steps ? '<div class="steps">' + steps + '</div>' : '') + '</div>';
    return div;
  }
  function renderMessages(force) {
    const nearBottom = messages.scrollHeight - messages.scrollTop - messages.clientHeight < 120;
    const first = messages.children.length === 0;
    const list = state ? state.messages : [];
    if (list.length !== messages.children.length) {
      messages.innerHTML = '';
      for (const m of list) messages.appendChild(bubble(m));
    } else {
      // the last answer grows while it is written
      const last = messages.children[messages.children.length - 1];
      if (last && list.length && !list[list.length - 1].user) {
        const b = last.querySelector('.bubble');
        const want = esc(list[list.length - 1].text || '');
        if (b && b.innerHTML !== want) b.innerHTML = want;
      }
    }
    // a poll that comes while the user reads older messages must not pull them back
    if (force || first || nearBottom) messages.scrollTop = messages.scrollHeight;
  }
  function renderBusy() {
    const b = state && state.busy;
    const box = $('busy');
    if (!b) { box.hidden = true; return; }
    box.hidden = false;
    $('busy-line').textContent = (b.detail ? '— ' + b.detail : (HI ? 'kaam kar raha hai' : 'working')) + '…';
    $('busy-out').textContent = b.output || '';
  }
  async function refresh() {
    if (!authed) return;
    let r;
    try {
      r = await api('/api/state');
    } catch (e) {
      $('head-sub').textContent = T.notFound;
      $('dot').classList.add('off');
      return;
    }
    $('dot').classList.remove('off');
    if (r.code === 401) { authed = false; $('login').hidden = false; return; }
    state = r.data;
    if (state.name) $('head-title').textContent = 'Termant · ' + state.name;
    renderMessages(false);
    renderBusy();
    const replies = state.messages.filter((m) => !m.user).length;
    if (sawReply === null) {
      sawReply = replies; // the first look does not re-speak what was said earlier
    } else if (replies > sawReply && !state.busy) {
      // spoken only when the answer is finished, so the whole thing is heard
      const last = state.messages[state.messages.length - 1];
      if (last && !last.user && !last.failed && last.text) speak(last.text);
    }
    sawReply = replies;
  }
  setInterval(() => { if (!document.hidden) refresh(); }, 1500);
  document.addEventListener('visibilitychange', () => { if (!document.hidden) refresh(); });

  async function send(text) {
    text = String(text || '').trim();
    if (!text || !authed) return;
    $('input').value = '';
    const r = await api('/api/message', { text });
    if (r.code === 409) {
      const div = document.createElement('div');
      div.className = 'msg assistant failed';
      div.innerHTML = '<div class="bubble">' + esc(T.busyWait) + '</div>';
      messages.appendChild(div);
      messages.scrollTop = messages.scrollHeight;
    }
    refresh();
  }
  $('send').addEventListener('click', () => send($('input').value));
  $('input').addEventListener('keydown', (e) => { if (e.key === 'Enter') send($('input').value); });
  $('stop').addEventListener('click', async () => { if (authed) await api('/api/stop', {}); });

  // ------------------------------------------------------------ voice
  $('say').addEventListener('click', () => {
    sayOn = !sayOn;
    $('say').classList.toggle('on', sayOn);
    $('say').title = sayOn ? T.sayOn : T.sayOff;
    if (!sayOn && window.speechSynthesis) window.speechSynthesis.cancel();
  });
  $('say').classList.add('on');

  const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  let rec = null;
  let finals = [];
  const WAKE = /t[ea]a?r?m[ae]?nt/; // "termant" and what a microphone writes instead
  const norm = (s) => String(s).toLowerCase().replace(/[^a-z0-9]/g, '');
  // where in the raw words the normalised position sits: the tail after a wake word
  function tailAfter(raw, normalPos) {
    let count = 0;
    for (let i = 0; i < raw.length; i++) {
      if (/[a-z0-9]/i.test(raw[i])) {
        if (count === normalPos) return raw.slice(i);
        count++;
      }
    }
    return '';
  }
  function voiceBar() {
    const bar = $('voicebar');
    if (!voiceOn) { bar.hidden = true; return; }
    bar.hidden = false;
    bar.classList.toggle('awake', awake);
    bar.textContent = awake ? T.command : T.listening;
    $('mic').classList.toggle('on', voiceOn);
    $('mic').title = voiceOn ? T.micOn : T.micOff;
  }
  function cue() {
    if (!sayOn) return;
    speak(HI ? 'Haan, bolo.' : 'Yes, speak.');
  }
  function speak(text) {
    if (!sayOn || !window.speechSynthesis) return;
    const words = String(text).replace(/```[\s\S]*?```/g, ' ').replace(/[*_#`>|]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 400);
    if (!words) return;
    window.speechSynthesis.cancel();
    const u = new SpeechSynthesisUtterance(words);
    u.lang = (navigator.language || 'en-IN').replace(/-[A-Z]+$/, '') + '-IN';
    window.speechSynthesis.speak(u);
  }
  function onWakeOrCommand(raw) {
    if (!voiceOn) return;
    const n = norm(raw);
    const m = n.match(WAKE);
    if (!awake) {
      if (!m) return;
      const after = tailAfter(raw, m.index + m[0].length).replace(/^[^a-zA-Z0-9]+/, '');
      if (norm(after).length >= 3) {
        send(after); // the command came with the wake word
      } else {
        awake = true;
        cue();
      }
      finals = [];
      voiceBar();
    } else if (m) {
      // only the wake word again: wait for the real words
    } else if (n.length >= 2) {
      awake = false;
      voiceBar();
      send(raw);
    }
  }
  function inWindow(text) {
    const now = Date.now();
    return finals.filter((f) => now - f.t < 5000).map((f) => f.text).join(' ') + ' ' + text;
  }
  function startRec() {
    if (!SR) {
      $('voicebar').hidden = false;
      $('voicebar').textContent = T.micNo;
      voiceOn = false;
      voiceBar();
      return;
    }
    try { rec = new SR(); } catch (e) { voiceOn = false; voiceBar(); return; }
    rec.continuous = true;
    rec.interimResults = true;
    rec.lang = (navigator.language || 'en-IN').slice(0, 5);
    rec.onresult = (e) => {
      let interim = '';
      for (let i = e.resultIndex; i < e.results.length; i++) {
        const part = e.results[i];
        const text = String(part[0] && part[0].transcript || '').trim();
        if (!text) continue;
        if (part.isFinal) {
          finals.push({ t: Date.now(), text });
          if (finals.length > 12) finals.shift();
          onWakeOrCommand(text);
        } else interim += (interim ? ' ' : '') + text;
      }
      if (!awake && WAKE.test(norm(inWindow(interim)))) {
        awake = true;
        finals = [];
        cue();
        voiceBar();
      }
    };
    rec.onerror = (e) => {
      if (e.error === 'not-allowed' || e.error === 'service-not-allowed') {
        voiceOn = false;
        awake = false;
        finals = [];
        voiceBar();
      }
    };
    rec.onend = () => { if (voiceOn) setTimeout(startRec, 400); };
    try {
      rec.start();
    } catch (e) {
      setTimeout(startRec, 800);
    }
  }
  $('mic').addEventListener('click', () => {
    voiceOn = !voiceOn;
    awake = false;
    finals = [];
    if (voiceOn) startRec();
    else if (rec) { try { rec.stop(); } catch (e) { /* already stopped */ } }
    if (sayOn && voiceOn) cue();
    voiceBar();
  });

  // a phone that came back with the cookie is let straight in
  (async () => {
    let r;
    try {
      r = await api('/api/state');
    } catch (e) {
      return; // the PC is not there; the login screen stays
    }
    if (r.code === 200) {
      authed = true;
      $('login').hidden = true;
      refresh();
    } else {
      $('login').hidden = false;
    }
  })();
})();

'use strict';
// What a running command has printed so far, as one short line for the chat: for a download
// "45% of 22.3 MB · 1.1 MB/s · 12s left", else the newest line as the command printed it.
// The same rules are in the Android app (Chat.newestLine); keep the two alike.
const fs = require('fs');

// colours and cursor moves that programs print for a terminal
const ESCAPES = /\u001b\[[0-9;?]*[ -\/]*[@-~]|\u001b\][^\u0007]*\u0007|\u001b[@-_]/g;
// curl's meter: % Total  % Received  % Xferd  Dload  Upload  Total  Spent  Left  Speed
const CURL = /^(\d{1,3}) +(\S+) +(\d{1,3}) +(\S+) +(\d{1,3}) +(\S+) +(\S+) +(\S+) +(--:--:--|\d+:\d\d:\d\d) +(--:--:--|\d+:\d\d:\d\d) +(--:--:--|\d+:\d\d:\d\d) +(\S+)$/;
// `curl -#` and others that draw a bar and a percentage after it: "#########      13.7%"
const BAR = /^[#=>\-. ]*?(\d{1,3}(?:[.,]\d)?) ?%$/;
// wget without a terminal: "1050K .......... .......... 12% 1.20M 3s"
const WGET = /^(\d+)K(?: [.,]+)+ +(?:(\d{1,3})% +)?([\d.,]+[KMG]?)(?: (\S+)|=\S+)?$/;
const UNITS = { '': 'B', k: 'kB', K: 'kB', M: 'MB', G: 'GB', T: 'TB', P: 'PB' };

/** "22.3M" as curl and wget write an amount, as "22.3 MB". */
function amount(text) {
  const m = /^(\d+(?:[.,]\d+)?)([kKMGTP]?)$/.exec(text);
  return m ? m[1] + ' ' + UNITS[m[2]] : text;
}

/** "0:00:12" as "12s", "0:01:05" as "1:05"; empty when the time is not known ("--:--:--"). */
function timeLeft(text) {
  const m = /^(\d+):(\d\d):(\d\d)$/.exec(text);
  if (!m) return '';
  if (+m[1]) return +m[1] + ':' + m[2] + ':' + m[3];
  if (+m[2]) return +m[2] + ':' + m[3];
  return +m[3] + 's';
}

function curlSays(m) {
  const sending = m[4] === '0' && m[6] !== '0';
  const done = sending ? m[6] : m[4];
  const percent = sending ? m[5] : m[3];
  // without a known size, curl gives what has arrived so far as the total
  const known = m[2] !== '0' && (m[2] !== done || percent === '100');
  const parts = [known ? percent + '% of ' + amount(m[2]) : amount(done) + (sending ? ' sent' : ' downloaded')];
  if (m[12] !== '0') parts.push(amount(m[12]) + '/s');
  const rest = timeLeft(m[11]);
  if (rest) parts.push(rest + ' left');
  return parts.join(' \u00b7 ');
}

function wgetSays(m) {
  const parts = [m[2] ? m[2] + '%' : amount(m[1] + 'K') + ' downloaded', amount(m[3]) + '/s'];
  if (m[4]) parts.push(m[4] + ' left');
  return parts.join(' \u00b7 ');
}

/**
 * The newest line in what a command has printed (the end of it is enough), made short and readable.
 * A line that redraws itself (it ends with a return, not a new line) counts as lines.
 */
function newestLine(printed) {
  const lines = String(printed || '').replace(ESCAPES, '').split(/[\r\n]+/);
  for (let i = lines.length - 1; i >= 0; i--) {
    const line = lines[i].replace(/[\u0000-\u001f\u007f]+/g, ' ').replace(/\s+/g, ' ').trim();
    // the two heading lines of curl's meter say nothing
    if (!line || /^% Total % Received/.test(line) || /^Dload Upload/.test(line)) continue;
    let m = CURL.exec(line);
    if (m) return curlSays(m);
    m = BAR.exec(line);
    if (m) return m[1] + '%';
    m = WGET.exec(line);
    if (m) return wgetSays(m);
    return line.length > 160 ? line.slice(0, 160) : line;
  }
  return '';
}

/** The end of a file as text (at most `most` bytes); empty when it cannot be read. */
function tailOf(file, most) {
  const want = most || 4096;
  let handle = null;
  try {
    handle = fs.openSync(file, 'r');
    const size = fs.fstatSync(handle).size;
    const length = Math.min(size, want);
    if (!length) return '';
    const bytes = Buffer.alloc(length);
    const got = fs.readSync(handle, bytes, 0, length, size - length);
    const end = bytes.subarray(0, got);
    // Windows programs may write two bytes a letter
    let zeros = 0;
    for (const b of end) if (b === 0) zeros++;
    return zeros > end.length / 4 ? end.subarray(end.length % 2).toString('utf16le') : end.toString('utf8');
  } catch (e) {
    return '';
  } finally {
    if (handle !== null) {
      try {
        fs.closeSync(handle);
      } catch (e) {
        // it was open, so this does not happen
      }
    }
  }
}

module.exports = { newestLine, tailOf };

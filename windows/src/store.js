// Small JSON files in the app's data folder: settings, chats, rules, alarms.
const fs = require('fs');
const path = require('path');

class Store {
  constructor(dir) {
    this.dir = dir;
    fs.mkdirSync(dir, { recursive: true });
  }

  file(name) {
    return path.join(this.dir, name);
  }

  load(name, fallback) {
    try {
      return JSON.parse(fs.readFileSync(this.file(name), 'utf8'));
    } catch (e) {
      return fallback;
    }
  }

  // Written to a second file first, so a crash in the middle never leaves half a file.
  save(name, value) {
    const target = this.file(name);
    const temp = target + '.tmp';
    fs.writeFileSync(temp, JSON.stringify(value, null, 2));
    fs.renameSync(temp, target);
  }

  text(name) {
    try {
      return fs.readFileSync(this.file(name), 'utf8');
    } catch (e) {
      return '';
    }
  }

  writeText(name, value) {
    fs.writeFileSync(this.file(name), value);
  }
}

module.exports = { Store };

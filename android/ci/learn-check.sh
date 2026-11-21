#!/bin/bash
# Checks what the agent uses to teach itself, against a pretend YouTube and a pretend
# ElevenLabs on this machine (fake_web.py): learn youtube, learn skill, learn today, and
# the ElevenLabs keys that take turns. Runs inside Termant's Ubuntu and on any Linux.
#   learn-check.sh [FOLDER WITH learn AND termant-web]     prints PASS/FAIL lines and a RESULT line
here=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
if [ -n "$1" ]; then
  # the files as they are in the repository: put them where commands are found
  mkdir -p "$work/bin" && cp "$1/learn" "$1/termant-web" "$work/bin/" && chmod 755 "$work/bin"/* || exit 2
  PATH="$work/bin:$PATH"
fi
export HOME="$work/home"
mkdir -p "$HOME"
failed=0
check() { # check NAME COMMAND...: passes when the command succeeds
  local name=$1; shift
  if "$@" >/dev/null 2>&1; then echo "PASS $name"; else echo "FAIL $name"; failed=$((failed+1)); fi
}
has() { printf '%s' "$out" | grep -qF -- "$1"; }
hasnt() { ! has "$1"; }
refused() { [ "$code" -ne 0 ] && has "$1"; }
run() { out=$("$@" 2>&1); code=$?; }

python3 "$here/fake_web.py" "$work/port" & fake=$!
trap 'kill $fake 2>/dev/null; rm -rf "$work"' EXIT
i=0; while [ ! -s "$work/port" ] && [ $i -lt 100 ]; do sleep 0.1; i=$((i+1)); done
base="http://127.0.0.1:$(cat "$work/port")"
export TERMANT_YOUTUBE_URL=$base TERMANT_ELEVEN_URL=$base
V=dQw4w9WgXcQ
today=$(date +%F)

run learn youtube search table in word
check "search lists both videos, not the advert" [ "$code" -eq 0 ]
check "search: title, channel, length, id" has "1. Make a table in Word
   Office Guru | 4:05 | 1,234 views | 2 years ago
   id $V"
check "search: the second title keeps its &" has "2. Word tables & more"
run learn youtube search nothing here
check "search with no result says so" refused "gave no videos"
run learn youtube read "https://www.youtube.com/watch?v=$V&t=3s"
check "read names the video, its length and the link" has "YouTube video $V: Make a table in Word
By Office Guru, 4:05 long. Link: https://youtu.be/$V"
check "read picks English and says a machine wrote it" has "What is said (en, written down by a machine, so words can be wrong):"
check "read: a time mark each minute" has "[0:00] open word and click insert then click table
[1:10] pick three by three
[2:10] type in the cells"
run learn youtube read $V --from 1
check "read --from 1 starts after the first minute" hasnt "open word"
check "read --from 1 has the rest" has "[1:10] pick three by three"
run learn youtube read $V --lang de
check "read --lang de takes the German captions (XML)" has "[0:02] Hallo & willkommen"
run learn youtube read ccccccccccc
check "a video without captions gives its description" has "has no captions"
check "... with the description" has "No words."
run learn youtube read bbbbbbbbbbb
check "a refused video says why" refused "Sign in to confirm"
run learn youtube read hello
check "a wrong id is refused" refused "not a YouTube video"

run learn skills
check "no skills at first" has "(no skills yet)"
run learn today
check "today is empty at first" has "Learned today ($today): nothing yet."
printf '1. Open Word.\n2. Insert > Table.\n3. Pick the size.\n' > "$work/steps.md"
run learn skill word-table "Make a table in Word" "$work/steps.md" "https://youtu.be/$V"
check "a skill is saved" has "Skill learned: word-table (Make a table in Word)"
printf '4. Type.\n' >> "$work/steps.md"
run learn skill word-table "Make a table in Word" "$work/steps.md"
check "saving again updates" has "Skill updated: word-table"
run learn show word-table
check "the skill can be read" has "4. Type."
check "... with what it is for" has "what: Make a table in Word"
run learn skills
check "the list names it with its day" has "word-table: Make a table in Word  ($today)"
run cat "$HOME/.config/termant/learned.md"
check "the agent's guide names the skill" has '- `word-table`: Make a table in Word'
check "the guide says how to learn a skill" has 'learn skill NAME "what it is for" FILE'
run learn skill "Word Table" x "$work/steps.md"
check "a bad name is refused" refused "name must be"
echo short > "$work/short.md"
run learn skill abc x "$work/short.md"
check "missing steps are refused" refused "steps are missing"
head -c 7000 /dev/zero | tr '\0' a > "$work/long.md"
run learn skill abc x "$work/long.md"
check "too long is refused" refused "keep a skill under 6000"
run learn skill abc x "$work/nothing.md"
check "a missing file is refused" refused "there is no file"
run learn note "The user likes short answers"
check "a note is saved" has "Saved."
run learn today
check "today counts what was learned" has "Learned today ($today): 3"
check "today: the skill with where it came from" has "- skill word-table: Make a table in Word (from https://youtu.be/$V)"
check "today: the note" has "- note: The user likes short answers"
printf '2020-01-01\tnote\t\told thing\t\n' | cat - "$HOME/.config/termant/learned/journal.tsv" > "$work/j" && cp "$work/j" "$HOME/.config/termant/learned/journal.tsv"
run learn today
check "older days are left out of today" hasnt "old thing"
run learn today 7
check "the last 7 days" has "Learned in the last 7 days: 3"
run learn today 9999
check "all days, under their dates" has "2020-01-01
- note: old thing"
run learn unlearn word-table
check "a skill is removed" has "Skill removed: word-table"
run learn show word-table
check "reading a removed skill is refused" refused "there is no skill"

run termant-web voice
check "no keys: says how to add them" has "No ElevenLabs key is saved"
run termant-web voice speak hello
check "no keys: speaking is refused" refused "No ElevenLabs key is saved"
run termant-web voice add key-used-up-1 key-wrong-22 key-good-333 key-good-333 short
check "three keys are saved, doubles and scraps dropped" has "3 key(s) added; 3 ElevenLabs key(s) are saved now."
check "the key file is for the owner only" [ "$(stat -c %a "$HOME/.config/termant/voice.keys")" = 600 ]
run termant-web voice speak namaste "  duniya"
check "the third key speaks after two are refused" has "Said with ElevenLabs, key 3 of 3 (key 1 (...up-1): quota_exceeded; key 2 (...g-22): invalid_api_key)."
sound=$(printf '%s\n' "$out" | sed -n 1p)
check "the speech is a sound file" [ "$(head -c 4 "$sound")" = RIFF ]
run cat "$work/port.asked"
check "all three were asked, in order" has "key-used-up-1 JBFqnCBsd6RMkjVDRZzb namaste duniya
key-wrong-22 JBFqnCBsd6RMkjVDRZzb namaste duniya
key-good-333 JBFqnCBsd6RMkjVDRZzb namaste duniya"
: > "$work/port.asked"
run termant-web voice speak again
check "next time only the good key is asked" has "Said with ElevenLabs, key 3 of 3."
check "... and the used-up ones not at all" [ "$(wc -l < "$work/port.asked")" -eq 1 ]
run cat "$HOME/.config/termant/voice.json"
check "no key is written to voice.json" hasnt "key-"
run termant-web voice
check "the report: the used-up key rests" has "1. ...up-1  10000 of 10000 letters used (free)  resting until"
check "the report: the good key is in use" has "3. ...-333  120 of 10000 letters used (free)  in use"
check "the report never prints a whole key" hasnt "key-good-333"
run termant-web voice use no-such-voice
run termant-web voice speak x
check "a wrong voice is not blamed on the key" refused "was not found"
run termant-web voice use
run termant-web voice remove 3
check "a key is removed" has "Removed key ...-333; 2 left."
: > "$work/port.asked"
run termant-web voice speak x
check "with only bad keys the reasons are told" refused "None of the 2 ElevenLabs keys can speak now: key 1 (...up-1): quota_exceeded; key 2 (...g-22): invalid_api_key."
check "resting keys are still tried when nothing else is left" [ "$(wc -l < "$work/port.asked")" -eq 2 ]

if [ $failed -eq 0 ]; then echo "RESULT: PASS"; else echo "RESULT: FAIL ($failed)"; fi
[ $failed -eq 0 ]

#!/bin/bash
# Checks the helper agents: the `team` command, and (when OpenCode is on this machine) a real
# run of OpenCode in which a pretend model (fake_model.py) hands two parts of a job to two
# helpers. Runs inside Termant's Ubuntu and on any Linux.
#   team-check.sh [FOLDER WITH team]     prints PASS/FAIL lines and a RESULT line
here=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
opencode=$(PATH="$HOME/.opencode/bin:$PATH" command -v opencode 2>/dev/null)
settings=; [ -n "$1" ] && settings=$(cd "$1" 2>/dev/null && pwd)/opencode.jsonc
if [ -n "$1" ]; then
  # the file as it is in the repository: put it where commands are found
  mkdir -p "$work/bin" && cp "$1/team" "$work/bin/" && chmod 755 "$work/bin"/* || exit 2
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
fake=
trap '[ -n "$fake" ] && kill $fake 2>/dev/null; rm -rf "$work"' EXIT
agents="$HOME/.config/opencode/agents"
about='Writes one short line of verse on a given subject.'
printf 'POET-MARK You write one short line of verse about the subject you are given.\n' > "$work/poet.md"

run team
check "six helpers come with Termant" has "Helpers that come with Termant: 6
- researcher: Finds things out"
check "the list names writer, coder, checker, planner and coach" [ "$(printf '%s\n' "$out" | grep -c '^- \(writer\|coder\|checker\|planner\|coach\): ')" -eq 5 ]
check "no helper made here yet" has "Helpers made here: none yet."
check "each is a file OpenCode reads" [ "$(ls "$agents" | tr '\n' ' ')" = "checker.md coach.md coder.md planner.md researcher.md writer.md " ]
check "a helper file says it is a subagent" grep -qx 'mode: subagent' "$agents/checker.md"
check "every helper is told to leave the screen alone" [ "$(grep -l 'Do not touch the screen' "$agents"/*.md | wc -l)" -eq 6 ]
check "every helper is told not to send, pay or delete, and of the team notebook" [ "$(grep -l 'Never send a message, pay, post, or delete' "$agents"/*.md | xargs grep -l 'team notes' | wc -l)" -eq 6 ]
run team add Poet "  Writes one short line
 of verse on a given subject. " "$work/poet.md"
check "a helper is made" [ "$code" -eq 0 ]
check "making it says what it is for" has "Made the helper \"poet\": $about It can be used from the next message on."
check "its file has the instructions" grep -q '^POET-MARK You write' "$agents/poet.md"
check "its file has what it is for" grep -qxF "description: \"$about\"" "$agents/poet.md"
check "it is told to leave the screen alone too" grep -q 'Do not touch the screen' "$agents/poet.md"
check "the diary of what was learned names it" grep -qxF "$(date +%F)	helper	poet	$about	" "$HOME/.config/termant/learned/journal.tsv"
run team add poet "$about" "$work/poet.md" opencode/big-pickle
check "the same name changes the helper" has 'Changed the helper "poet"'
check "the model is written in its file" grep -qx 'model: opencode/big-pickle' "$agents/poet.md"
run team
check "the list has the helper with its model" has "Helpers made here: 1
- poet: $about [model opencode/big-pickle]"
run team show poet
check "show prints the helper" has "Helper poet (model opencode/big-pickle)
For: $about

POET-MARK You write one short line"
run team show checker
check "show prints a helper of Termant" has "Helper checker
For: Checks finished work"
run team show nobody
check "show: an unknown helper is refused" refused 'there is no helper "nobody"'
run team add checker "$about" "$work/poet.md"
check "a helper of Termant cannot be replaced" refused "already there"
run team add build "$about" "$work/poet.md"
check "a name of OpenCode cannot be taken" refused "already there"
run team add "My Agent" "$about" "$work/poet.md"
check "a wrong name is refused" refused "small letters"
run team add x1 short "$work/poet.md"
check "a helper without a purpose is refused" refused "what this helper is for"
run team add x1 "$about" "$work/none.md"
check "a missing file is refused" refused "there is no file"
printf 'do it' > "$work/short.md"
run team add x1 "$about" "$work/short.md"
check "too short instructions are refused" refused "too short"
head -c 6001 /dev/zero | tr '\0' a > "$work/long.md"
run team add x1 "$about" "$work/long.md"
check "too long instructions are refused" refused "6001 letters"
run team add x1 "$about" "$work/poet.md" "gpt five"
check "a wrong model is refused" refused "provider/model"
run team add quoter 'Says "hello": with a colon, a \ and quotes.' "$work/poet.md"
run team show quoter
check "quotes and colons in what it is for are kept" has 'For: Says "hello": with a colon, a \ and quotes.'
team remove quoter >/dev/null 2>&1
for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19; do team add "helper-$i" "$about" "$work/poet.md" >/dev/null 2>&1; done
run team add one-more "$about" "$work/poet.md"
check "at most 20 helpers made here" refused "already 20"
for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19; do team remove "helper-$i" >/dev/null 2>&1; done
run team remove writer
check "a helper of Termant stays" refused "comes with Termant"
run team remove nobody
check "an unknown helper cannot be removed" refused 'there is no helper "nobody"'
run team remove ../poet
check "a path is not a name" refused "there is no helper"
rm -f "$agents/writer.md"
team refresh
check "refresh writes Termant's helpers again" grep -q "writer of Termant" "$agents/writer.md"
check "refresh leaves the helpers made here" [ -f "$agents/poet.md" ]
run team bogus
check "an unknown word prints the help and fails" refused "team add NAME"
team add poet "$about" "$work/poet.md" >/dev/null 2>&1

if [ -z "$opencode" ] || ! command -v python3 >/dev/null 2>&1; then
  echo "SKIP the real run of OpenCode with helpers (OpenCode is not on this machine)"
else
  # OpenCode itself, with a pretend model in place of a real one
  python3 "$here/fake_model.py" "$work/port" "$work/asked.txt" & fake=$!
  i=0; while [ ! -s "$work/port" ] && [ $i -lt 100 ]; do sleep 0.1; i=$((i+1)); done
  export OPENCODE_CONFIG_CONTENT='{"provider":{"termanttest":{"npm":"@ai-sdk/openai-compatible","name":"Termant test","options":{"baseURL":"http://127.0.0.1:'"$(cat "$work/port")"'/v1","apiKey":"x"},"models":{"fake":{"name":"Fake"}}}}}'
  export NO_COLOR=1 OPENCODE_DISABLE_AUTOUPDATE=1
  mkdir -p "$work/job" && cd "$work/job" || exit 2
  run "$opencode" debug agents --standalone
  check "OpenCode knows the helpers of Termant" [ "$(printf '%s\n' "$out" | grep -c '"id": "\(researcher\|writer\|coder\|checker\|planner\|coach\)"')" -eq 6 ]
  check "OpenCode knows the helper made here" has '"id": "poet"'
  out=$(timeout 240 "$opencode" run --standalone --auto --model termanttest/fake "TEAM please" 2>&1 </dev/null | sed 's/\x1b\[[0-9;]*m//g'); code=$?
  printf '%s\n' "$out" > "$work/run.txt"
  check "the run with helpers ends well" [ "$code" -eq 0 ]
  check "both helpers were started" [ "$(grep -c ' Agent$' "$work/run.txt")" -ge 2 ]
  check "the run waits for the helpers" has "waiting for"
  check "the researcher's report comes back" has "RESEARCH REPORT: chai is tea."
  check "the poet's report comes back" has "POEM REPORT: garam chai"
  check "a helper runs a command by itself" [ -f "$work/job/helper-ran.txt" ]
  # the poet was asked before the researcher had finished: they worked at the same time
  poet=$(grep -n ' poet ' "$work/asked.txt" | head -n 1 | cut -d: -f1)
  done_at=$(grep -n ' researcher RESEARCH REPORT' "$work/asked.txt" | head -n 1 | cut -d: -f1)
  same_time() { [ -n "$poet" ] && [ -n "$done_at" ] && [ "$poet" -lt "$done_at" ]; }
  check "the helpers work at the same time" same_time
  [ "$failed" -eq 0 ] || { echo "-- the run:"; cat "$work/run.txt"; echo "-- the model was asked:"; cat "$work/asked.txt"; }
  # What nobody may do, the main agent or a helper: Termant's settings for OpenCode refuse it.
  [ -f "$settings" ] || settings=/usr/local/lib/termant/opencode.jsonc
  if [ -f "$settings" ]; then
    mkdir -p "$HOME/.config/opencode" && cp "$settings" "$HOME/.config/opencode/opencode.jsonc"
    try() { out=$(timeout 120 "$opencode" run --standalone --auto --model termanttest/fake "RUN $1" 2>&1 </dev/null | sed 's/\x1b\[[0-9;]*m//g'); }
    try 'rm -rf /sdcard/DCIM-not-there'
    check "deleting in the phone's storage with rm is refused" has "permission.rejected"
    try 'cd /tmp && rm -rf ~'
    check "wiping the home folder is refused, also behind another command" has "permission.rejected"
    mkdir -p "$work/job/old-build"
    try 'rm -rf old-build'
    check "a folder of the work itself can still be deleted" [ ! -d "$work/job/old-build" ]
    try 'echo allowed-words'
    check "an ordinary command runs" has "RAN: allowed-words"
  else
    echo "SKIP what OpenCode refuses (Termant's settings file is not here)"
  fi
fi

if [ "$failed" -eq 0 ]; then echo "RESULT: PASS"; else echo "RESULT: FAIL ($failed)"; fi
[ "$failed" -eq 0 ]

#!/usr/bin/env bash
# End-to-end test on an Android emulator: installs the APK, lets the app
# download and set up Ubuntu, then types commands into the terminal like a
# user would and checks that apt can install and run a package.
set -u
OUT="${1:-ci-out}"
PKG=com.termant.app
mkdir -p "$OUT"

shot() { adb exec-out screencap -p > "$OUT/$1.png" || true; }
run_as() { adb shell run-as "$PKG" "$@"; }
fake_model=
finish() {
  shot "final"
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1
  adb logcat -d -t 4000 > "$OUT/logcat.txt" 2>&1 || true
  [ -n "$fake_model" ] && kill "$fake_model" 2>/dev/null  # a test that leaves it running keeps the job open
  echo "RESULT: $1" | tee "$OUT/verdict.txt"
  [ "$1" = PASS ]
  exit $?
}
wait_for_end() { # <file in app data> <tries of 5 s>
  for _ in $(seq 1 "$2"); do
    run_as grep -q TERMANT-TEST-END "$1" 2>/dev/null && return 0
    sleep 5
  done
  return 1
}
type_line() { adb shell input text "$1"; adb shell input keyevent 66; }
tap_on() { # <text that identifies the element in the UI dump>
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  set -- $(adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep "$1" | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]*' | tr '\n' ' ')
  [ $# -eq 4 ] || return 1
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
wait_for_reply() { # <tries of 3 s>: the chat file has no empty reply once the agent is done
  for _ in $(seq 1 "$1"); do
    sleep 3
    run_as cat files/chat.json > "$OUT/chat.json" 2>/dev/null
    grep -q '"text":""' "$OUT/chat.json" || return 0
  done
  return 1
}

# If the emulator itself stops, every adb command would wait for it for ever:
# end the test instead, and say so.
(
  while sleep 30; do
    adb get-state >/dev/null 2>&1 && continue
    sleep 30
    adb get-state >/dev/null 2>&1 && continue
    echo "RESULT: FAIL the emulator stopped" > "$OUT/verdict.txt"
    pkill -f 'adb (shell|exec-out|logcat|pull|install)' 2>/dev/null
    kill $$
    exit 1
  done
) >/dev/null 2>&1 &
WATCH=$!
trap 'kill $WATCH 2>/dev/null' EXIT

# Give the freshly booted emulator a moment to bring its network up.
sleep 20

adb install -g android/Termant-debug.apk || finish "FAIL install"
adb logcat -c
# The app's texts are in Hindi unless Settings say otherwise; this test reads
# the English ones, and switches to Hindi itself at the end. Check-ins of the
# agent's own are off except in their own part: they would write into the
# chats of the other parts.
adb shell "run-as $PKG mkdir -p shared_prefs"
printf '%s\n' "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>" '<map>' '    <string name="lang">en</string>' '    <boolean name="care_on" value="false" />' '</map>' \
  | adb shell "run-as $PKG sh -c 'cat > shared_prefs/termant.xml'"
run_as cat shared_prefs/termant.xml | grep -q 'name="lang">en<' || finish "FAIL could not choose English for the test"
adb shell am start -W -n "$PKG/.MainActivity"
sleep 6
shot 1-setup

echo "waiting for Ubuntu setup"
ok=
for _ in $(seq 1 180); do
  if run_as test -f files/installed; then ok=1; break; fi
  if adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb shell "grep -q 'Setup did not finish' /sdcard/ui.xml"; then
    break
  fi
  sleep 4
done
[ -n "$ok" ] || finish "FAIL setup did not finish"
sleep 8
shot 2-chat
adb shell "grep -q 'Namaste' /sdcard/ui.xml" || { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell "grep -q 'Namaste' /sdcard/ui.xml"; } || finish "FAIL the app did not open on the chat"
tap_on 'content-desc="Open the Ubuntu terminal"' || finish "FAIL Terminal button not found"
sleep 5
shot 3-terminal

adb shell "run-as $PKG sh -c 'cat > files/rootfs/root/t.sh'" < android/ci/guest-test.sh
adb shell "run-as $PKG sh -c 'cat > files/rootfs/root/t2.sh'" < android/ci/guest-test2.sh
adb shell "run-as $PKG sh -c 'cat > files/rootfs/root/t3.sh'" < android/ci/guest-test3.sh
adb shell "run-as $PKG sh -c 'cat > files/rootfs/root/t4.sh'" < android/ci/guest-test4.sh
adb shell "run-as $PKG sh -c 'cat > files/rootfs/root/t5.sh'" < android/ci/guest-test5.sh
adb shell "run-as $PKG sh -c 'cat > files/rootfs/root/learn-check.sh'" < android/ci/learn-check.sh
adb shell "run-as $PKG sh -c 'cat > files/rootfs/root/fake_web.py'" < android/ci/fake_web.py
adb shell "run-as $PKG sh -c 'cat > files/rootfs/root/team-check.sh'" < android/ci/team-check.sh
adb shell "run-as $PKG sh -c 'cat > files/rootfs/root/fake_model.py'" < android/ci/fake_model.py
adb shell "run-as $PKG sh -c 'cat > files/rootfs/root/kit-check.sh'" < android/ci/kit-check.sh
adb shell "run-as $PKG sh -c 'cat > files/rootfs/root/t6.sh'" < android/ci/guest-test6.sh
adb shell "run-as $PKG sh -c 'cat > files/rootfs/root/t7.sh'" < android/ci/guest-test7.sh
adb shell "run-as $PKG sh -c 'cat > files/rootfs/root/t8.sh'" < android/ci/guest-test8.sh
adb shell "run-as $PKG sh -c 'cat > files/rootfs/root/t9.sh'" < android/ci/guest-test9.sh
type_line 'sh%st.sh'
wait_for_end files/rootfs/root/result.txt 180 || { shot 3-timeout; run_as cat files/rootfs/root/result.txt > "$OUT/result.txt"; finish "FAIL test script did not finish"; }
run_as cat files/rootfs/root/result.txt > "$OUT/result.txt"
type_line 'ls%s/'
sleep 3
shot 4-after-test
grep -q 'VERSION_ID="24.04"' "$OUT/result.txt" || finish "FAIL not Ubuntu 24.04"
grep -q '^Hello, world!' "$OUT/result.txt" || finish "FAIL installed package did not run"

echo "restarting the app"
adb shell am force-stop "$PKG"
sleep 2
adb shell am start -W -n "$PKG/.MainActivity"
sleep 10
tap_on 'content-desc="Open the Ubuntu terminal"' || finish "FAIL Terminal button not found after restart"
sleep 5
type_line 'sh%st2.sh'
wait_for_end files/rootfs/root/result2.txt 24 || { run_as cat files/rootfs/root/result2.txt > "$OUT/result2.txt"; finish "FAIL second start"; }
run_as cat files/rootfs/root/result2.txt > "$OUT/result2.txt"
shot 5-second-start
grep -q '^Hello, world!' "$OUT/result2.txt" || finish "FAIL package gone after restart"

echo "AI agent"
type_line 'sh%st3.sh'
wait_for_end files/rootfs/root/result3.txt 420 || { run_as cat files/rootfs/root/result3.txt > "$OUT/result3.txt"; shot 5-agent-timeout; finish "FAIL agent test did not finish"; }
run_as cat files/rootfs/root/result3.txt > "$OUT/result3.txt"
grep -q '^version-exit=0' "$OUT/result3.txt" || finish "FAIL OpenCode did not install or start"

echo "Phone control"
adb logcat -d -s Termant:I > "$OUT/phone-log.txt"
grep -q '^battery-exit=0' "$OUT/result3.txt" || finish "FAIL phone battery"
grep -q '^bad-alarm-exit=1' "$OUT/result3.txt" || finish "FAIL phone alarm accepted a wrong time"
grep -q '^termant clip' "$OUT/result3.txt" || finish "FAIL phone clipboard"
grep -q '^alarm-exit=0' "$OUT/result3.txt" || finish "FAIL phone alarm"
grep -q 'alarm 06:45 sent to the Clock app' "$OUT/phone-log.txt" || finish "FAIL alarm did not reach the Clock app"
grep -q 'ringing: CI-timer' "$OUT/phone-log.txt" || finish "FAIL timer did not ring"
grep -q 'alarm 07:00 sent to the Clock app' "$OUT/phone-log.txt" || finish "FAIL agent did not set the 7:00 alarm"
adb shell dumpsys alarm > "$OUT/dumpsys-alarm.txt" 2>&1

echo "Automation rules"
grep -q '^bad-rule-exit=1' "$OUT/result3.txt" || finish "FAIL rule with a wrong time was accepted"
grep -q '^bad-trigger-exit=1' "$OUT/result3.txt" || finish "FAIL rule with an unknown trigger was accepted"
grep -q '^rule-add-exit=0' "$OUT/result3.txt" || finish "FAIL phone rule add"
grep -q '^manual: .*20[0-9][0-9]' "$OUT/result3.txt" || finish "FAIL phone rule run"
grep -q '^timed-rule-ran' "$OUT/result3.txt" || finish "FAIL the timed rule did not run by itself"
grep -q '^volume-exit=0' "$OUT/result3.txt" || finish "FAIL phone volume"
grep -q '^bad-sms-exit=1' "$OUT/result3.txt" || finish "FAIL phone sms without text was accepted"
# Plug the charger in: the "charging" rule must write its file with nobody asking.
adb shell dumpsys battery unplug; sleep 4
adb shell dumpsys battery set ac 1; sleep 12
adb shell dumpsys battery reset
[ -n "$(run_as cat files/rootfs/root/charged.txt 2>/dev/null)" ] || finish "FAIL the charging rule did not run"

echo "Chat"
tap_on 'content-desc="Back to the chat"' || finish "FAIL Chat button not found"
sleep 3
tap_on 'class="android.widget.EditText"' || finish "FAIL message box not found"
sleep 2
adb shell input text 'mujhe%ssubah%s7%sbaje%sutha%sdena'
sleep 2
shot 6-chat-typing
tap_on 'content-desc="Send"' || finish "FAIL Send button not found"
sleep 4
shot 7-chat-thinking
wait_for_reply 60 || { shot 8-chat-timeout; finish "FAIL the chat got no reply"; }
sleep 2
shot 8-chat-reply
adb logcat -d -s Termant:I > "$OUT/phone-log.txt"
grep -q 'phone alarm 0*7:00' "$OUT/chat.json" || finish "FAIL chat: the agent did not run phone alarm"
adb logcat -d -s Termant:I | grep 'activity:' > "$OUT/activity.txt"
cat "$OUT/activity.txt"
grep -q 'activity: .*\(phone\|shell\)' "$OUT/activity.txt" || finish "FAIL the app did not notice that the agent was running a command"
[ "$(grep -c 'alarm 07:00 sent to the Clock app' "$OUT/phone-log.txt")" -ge 2 ] || finish "FAIL chat: the alarm did not reach the Clock app"

# A follow-up in the same conversation, and a coding request.
tap_on 'class="android.widget.EditText"' && sleep 2
adb shell input text 'Write%sa%sPython%sscript%sthat%sprints%s6%stimes%s7%sand%srun%sit'
sleep 1
tap_on 'content-desc="Send"'
if wait_for_reply 60; then echo "second chat message answered" > "$OUT/chat2.txt"; else echo "second chat message NOT answered" > "$OUT/chat2.txt"; fi
sleep 2
shot 9-chat-code

echo "Rule from the chat"
tap_on 'class="android.widget.EditText"' && sleep 2
adb shell input text 'roz%sraat%s11%sbaje%sWiFi%sband%skaro'
sleep 1
tap_on 'content-desc="Send"'
wait_for_reply 60 || finish "FAIL chat: no reply to the rule request"
sleep 2
shot 10-chat-rule
run_as cat files/rules.json > "$OUT/rules.json"
grep -q '"trigger":"at 23:00","command":"phone wifi off"' "$OUT/rules.json" || finish "FAIL chat: the agent did not create the 23:00 Wi-Fi rule"
# which way the first three messages went: OpenCode started for the message, or the server Termant keeps
run_as cat files/rootfs/root/.cache/termant/server.history > "$OUT/server-history-3.txt" 2>/dev/null
echo "the first chat messages went: $(tr '\n' ';' < "$OUT/server-history-3.txt")"

echo "Model and workspace"
tap_on 'content-desc="Model"' || finish "FAIL model chip not found"
sleep 12
shot 11-models
tap_on 'text="opencode/big-pickle"' || finish "FAIL model list did not show big-pickle"
sleep 2
tap_on 'content-desc="Workspace folder"' || finish "FAIL workspace chip not found"
sleep 2
shot 12-workspaces
tap_on 'New folder' || finish "FAIL New folder not found"
sleep 2
tap_on 'class="android.widget.EditText"'; sleep 1
adb shell input text 'ci-project'
tap_on 'text="Save"' || tap_on 'text="SAVE"' || finish "FAIL Save not found"
sleep 2
run_as cat shared_prefs/termant.xml > "$OUT/prefs.xml"
grep -q 'opencode/big-pickle' "$OUT/prefs.xml" || finish "FAIL the chosen model was not saved"
grep -q '/root/ci-project' "$OUT/prefs.xml" || finish "FAIL the chosen workspace was not saved"
tap_on 'class="android.widget.EditText"' && sleep 2
adb shell input text 'Create%sa%sfile%snamed%snote.txt%scontaining%sthe%sword%shello'
sleep 1
tap_on 'content-desc="Send"'
wait_for_reply 60 || finish "FAIL chat: no reply with the chosen model and workspace"
sleep 2
shot 13-chat-workspace
run_as test -f files/rootfs/root/ci-project/note.txt || finish "FAIL the agent did not work in the chosen workspace"

echo "Arena button"
tap_on 'content-desc="Open Arena.ai in the browser"' || finish "FAIL Arena button not found"
sleep 8
shot 13b-arena
adb shell dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity' > "$OUT/arena.txt"
adb shell dumpsys activity activities | grep -m3 'arena.ai' >> "$OUT/arena.txt"
grep -q "$PKG" "$OUT/arena.txt" && finish "FAIL the Arena button did not open a browser"
grep -q 'arena.ai' "$OUT/arena.txt" || finish "FAIL the browser was not given the arena.ai address"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 3

echo "Settings"
tap_on 'content-desc="Settings"' || finish "FAIL Settings button not found"
sleep 2
shot 14-settings
scroll_to() { # <content-desc of a Settings row>: scrolls down until it is on screen
  for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14; do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb shell "grep -q 'content-desc=.$1.' /sdcard/ui.xml" && return 0
    adb shell input swipe 160 450 160 250 400; sleep 1
  done
  return 1
}
scroll_to GitHub || finish "FAIL GitHub row not found in Settings"
shot 14-settings-tools
scroll_to 'Web search' || finish "FAIL Web search row not found in Settings"
scroll_to 'Screen control' || finish "FAIL Screen control row not found in Settings"
scroll_to OpenRouter
tap_on 'content-desc="OpenRouter"' || finish "FAIL API key row not found"
sleep 2
tap_on 'class="android.widget.EditText"'; sleep 1
adb shell input text 'sk-or-v1-termant-ci-not-a-real-key'
tap_on 'text="Save"' || tap_on 'text="SAVE"' || finish "FAIL Save not found for the key"
sleep 2
adb shell input swipe 160 500 160 120 300; sleep 1
shot 15-settings-keys
adb shell input swipe 160 500 160 100 300; sleep 1
shot 16-settings-rules
run_as grep -q OPENROUTER_API_KEY files/rootfs/root/.config/termant/keys.env || finish "FAIL the API key was not saved"
tap_on 'content-desc="Back to the chat"' || finish "FAIL Back arrow not found in settings"
sleep 2

echo "Settings reach Ubuntu; Wi-Fi switch"
tap_on 'content-desc="Open the Ubuntu terminal"' || finish "FAIL Terminal button not found at the end"
sleep 4
type_line 'sh%st4.sh'
wait_for_end files/rootfs/root/result4.txt 60 || { run_as cat files/rootfs/root/result4.txt > "$OUT/result4.txt"; finish "FAIL settings test did not finish"; }
run_as cat files/rootfs/root/result4.txt > "$OUT/result4.txt"
adb shell dumpsys wifi | grep -m1 'Wi-Fi is' > "$OUT/wifi.txt"
adb shell svc wifi enable
grep -q '^key-set=yes' "$OUT/result4.txt" || finish "FAIL the API key did not reach Ubuntu"
grep -q '^wifi-off-exit=0' "$OUT/result4.txt" || finish "FAIL phone wifi off"
grep -q '^screen-off-exit=1' "$OUT/result4.txt" || finish "FAIL phone screen worked although screen control is off"
grep -q 'Screen control is off' "$OUT/result4.txt" || finish "FAIL phone screen did not say that screen control is off"
grep -q 'disabled' "$OUT/wifi.txt" || finish "FAIL Wi-Fi was not really switched off"

echo "Auto-switch: the chosen model does not exist, a backup must answer"
sleep 15 # Wi-Fi is coming back
adb shell am force-stop "$PKG"
adb shell "run-as $PKG sed -i 's#\\(name=.model.>\\)[^<]*#\\1opencode/no-such-model#' shared_prefs/termant.xml"
run_as cat shared_prefs/termant.xml > "$OUT/prefs2.xml"
grep -q 'no-such-model' "$OUT/prefs2.xml" || finish "FAIL could not prepare the auto-switch test"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 8
tap_on 'class="android.widget.EditText"' || finish "FAIL message box not found for auto-switch"
sleep 2
adb shell input text 'Say%shello%sin%sthree%swords'
sleep 1
tap_on 'content-desc="Send"'
wait_for_reply 80 || finish "FAIL auto-switch: no reply"
sleep 2
shot 17-auto-switch
adb logcat -d -s Termant:I > "$OUT/phone-log.txt"
grep -q "gave no answer, switching to" "$OUT/phone-log.txt" || finish "FAIL auto-switch did not happen"
grep -q 'Switched to' "$OUT/chat.json" || finish "FAIL auto-switch: the reply does not say it switched"
python3 - "$OUT/chat.json" <<'PY' || finish "FAIL auto-switch: the backup model did not answer"
import json, sys
last = json.load(open(sys.argv[1]))["messages"][-1]
print("auto-switch reply:", last)
sys.exit(1 if last["failed"] or not last["text"].strip() else 0)
PY

ask() { # <text with %s for spaces>
  tap_on 'class="android.widget.EditText"' || return 1
  sleep 2
  adb shell input text "$1"
  sleep 1
  tap_on 'content-desc="Send"' || return 1
  sleep 3
}

echo "Free limit in the middle of the work: a model of another service carries on"
# OpenCode's "Free usage limit reached" cannot be caused on purpose, so a stand-in
# (ci/fake-limit.sh) ends the first model's run that way after the first half of the work.
change_prefs() { # <name=value, or name= to remove it> ...: stops the app and changes its settings
  sleep 2
  adb shell am force-stop "$PKG"
  run_as cat shared_prefs/termant.xml > "$OUT/prefs-now.xml"
  python3 - "$OUT/prefs-now.xml" "$@" <<'PY' || return 1
import re, sys
path = sys.argv[1]
x = open(path).read()
if "</map>" not in x:
    sys.exit(1)
for change in sys.argv[2:]:
    name, _, value = change.partition("=")
    n = re.escape(name)
    x = re.sub(r'[ \t]*<string name="%s">.*?</string>\n?' % n, "", x, flags=re.S)
    x = re.sub(r'[ \t]*<(long|int|boolean|float) name="%s"[^>]*/>\n?' % n, "", x)
    if value:
        x = x.replace("</map>", '    <string name="%s">%s</string>\n</map>' % (name, value))
open(path, "w").write(x)
PY
  adb shell "run-as $PKG sh -c 'cat > shared_prefs/termant.xml'" < "$OUT/prefs-now.xml"
}
limit_check() { # <calls expected, space separated> <python condition on the last message "m">
  run_as cat "$CACHE/ci-calls.txt" > "$OUT/limit-calls.txt"
  python3 - "$OUT/chat.json" "$OUT/limit-calls.txt" "$1" "$2" <<'PY'
import json, sys
m = json.load(open(sys.argv[1]))["messages"][-1]
calls = open(sys.argv[2]).read().split()
print("free limit: models asked:", calls)
print("free limit: reply:", m)
ok = calls == sys.argv[3].split() and eval(sys.argv[4])
sys.exit(0 if ok else 1)
PY
}
BIN=files/rootfs/root/.opencode/bin
CACHE=files/rootfs/root/.cache/termant
sleep 2
adb shell am force-stop "$PKG"
run_as cat shared_prefs/termant.xml > "$OUT/prefs-before-limit.xml"
grep -q '</map>' "$OUT/prefs-before-limit.xml" || finish "FAIL free limit: could not read the settings"
change_prefs model=opencode/big-pickle switched= switched_at= free_limit_until= backups=ci/other-free || finish "FAIL free limit: could not prepare the settings"
run_as mv "$BIN/opencode" "$BIN/opencode-real" || finish "FAIL free limit: OpenCode is not where the test expects it"
adb shell "run-as $PKG sh -c 'cat > $BIN/opencode'" < android/ci/fake-limit.sh
run_as chmod 755 "$BIN/opencode"
run_as rm -f "$CACHE/ci-calls.txt" files/rootfs/root/limit-one files/rootfs/root/limit-two
run_as touch "$CACHE/ci-limit" "$CACHE/ci-limit-first"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 8
tap_on 'content-desc="Start a new chat"' || finish "FAIL free limit: New chat button not found"
sleep 2
ask 'First%srun%sthe%scommand:%stouch%s/root/limit-one%s%s%sThen%srun%sthe%scommand:%stouch%s/root/limit-two%s%s%sThen%sreply%swith%sthe%sword%sFINISHED' || finish "FAIL free limit: could not send the request"
wait_for_reply 120 || { shot 17b-limit-timeout; adb logcat -d -s Termant:I | tail -30; finish "FAIL free limit: no reply"; }
sleep 2
shot 17b-limit-midway
adb logcat -d -s Termant:I > "$OUT/phone-log.txt"
cp "$OUT/chat.json" "$OUT/chat-limit.json"
grep 'free limit:\|in the middle of the work\|gave no answer\| session: ' "$OUT/phone-log.txt" | tail -6
grep -q "free limit: OpenCode refused 'opencode/big-pickle'" "$OUT/phone-log.txt" || finish "FAIL free limit: the app did not recognise the refusal"
grep -q "stopped in the middle of the work .* with ci/other-free" "$OUT/phone-log.txt" || finish "FAIL free limit: the work was not handed on in the middle"
limit_check "opencode/big-pickle ci/other-free" \
  'not m["failed"] and m["text"].strip() and "Free usage limit" not in m["text"] and "in the middle of the work" in m["note"] and "limit-one" in " ".join(m["steps"]) and "limit-two" in " ".join(m["steps"])' \
  || finish "FAIL free limit: the reply after the switch is not right"
run_as test -e files/rootfs/root/limit-one || finish "FAIL free limit: the first half of the work was not done"
run_as test -e files/rootfs/root/limit-two || finish "FAIL free limit: the second model did not finish the work"
python3 - "$OUT/phone-log.txt" <<'PY' || finish "FAIL free limit: the second model did not carry on in the same conversation"
import re, sys
log = open(sys.argv[1], errors="replace").read()
handed = re.findall(r"carrying on in (ses_\w+)", log)
ended = re.findall(r" session: (ses_\w+)", log)
print("free limit: handed on in", handed[-1:], "and the chat ended in", ended[-1:])
sys.exit(0 if handed and ended and handed[-1] == ended[-1] else 1)
PY

echo "Free limit known: the next message starts with the other service, no error first"
change_prefs switched= switched_at= || finish "FAIL free limit: could not change the settings"
grep -q 'name="free_limit_until"' "$OUT/prefs-now.xml" || finish "FAIL free limit: how long it lasts was not remembered"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 8
ask 'Reply%sonly%swith%sthe%sword%sAGAIN' || finish "FAIL free limit: could not send the second message"
wait_for_reply 80 || finish "FAIL free limit: no reply to the second message"
adb logcat -d -s Termant:I > "$OUT/phone-log.txt"
grep -q "free limit: OpenCode's free models wait until .* starting with ci/other-free" "$OUT/phone-log.txt" || finish "FAIL free limit: the app did not start with the other service"
limit_check "opencode/big-pickle ci/other-free ci/other-free" \
  'not m["failed"] and "AGAIN" in m["text"].upper() and "Free usage limit" not in m["text"]' \
  || finish "FAIL free limit: the second message was not answered by the other service straight away"

echo "Free limit with no other service: one clear message, the other free models are not tried"
change_prefs switched= switched_at= backups=opencode/glm-5.3-flash-free || finish "FAIL free limit: could not change the settings"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 8
ask 'Reply%sonly%swith%sthe%sword%sLAST' || finish "FAIL free limit: could not send the third message"
wait_for_reply 60 || finish "FAIL free limit: no message when nothing can take over"
sleep 2
shot 17c-limit-message
limit_check "opencode/big-pickle ci/other-free ci/other-free opencode/big-pickle" \
  'm["failed"] and "free limit is used up until" in m["text"] and "Free usage limit reached" not in m["text"]' \
  || finish "FAIL free limit: the message when nothing can take over is not right"
cp "$OUT/chat.json" "$OUT/chat-limit-end.json"

# Back to the real OpenCode and the settings from before.
sleep 2
adb shell am force-stop "$PKG"
run_as rm -f "$CACHE/ci-limit" "$CACHE/ci-limit-first"
run_as mv "$BIN/opencode-real" "$BIN/opencode" || finish "FAIL free limit: could not put OpenCode back"
adb shell "run-as $PKG sh -c 'cat > shared_prefs/termant.xml'" < "$OUT/prefs-before-limit.xml"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 8

echo "Chat history: an earlier chat can be reopened and continued"
tap_on 'content-desc="Start a new chat"' || finish "FAIL New chat button not found"
sleep 2
ask 'Remember%sthis%ssecret%sword:%smango.%sReply%sonly%sOK' || finish "FAIL could not send in a new chat"
shot 18-thinking
wait_for_reply 80 || finish "FAIL history: no reply in chat one"
tap_on 'content-desc="Start a new chat"'
sleep 2
shot 19-new-chat-with-recent
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb shell "grep -q 'Remember this secret' /sdcard/ui.xml" || finish "FAIL the earlier chat is not shown after New chat"
ask 'Say%shi%sin%sone%sword' || finish "FAIL could not send in chat two"
wait_for_reply 80 || finish "FAIL history: no reply in chat two"
tap_on 'content-desc="Chat history"' || finish "FAIL history button not found"
sleep 2
shot 20-history
tap_on 'Remember this secret' || finish "FAIL the earlier chat is not in the history list"
sleep 3
run_as cat files/chat.json > "$OUT/chat-reopened.json"
grep -q 'secret word: mango' "$OUT/chat-reopened.json" || finish "FAIL the earlier chat did not reopen"
ask 'Which%ssecret%sword%sdid%sI%stell%syou?%sAnswer%swith%sthat%sword%sonly' || finish "FAIL could not send in the reopened chat"
wait_for_reply 80 || finish "FAIL history: no reply in the reopened chat"
sleep 2
shot 21-reopened
adb logcat -d -s Termant:I | grep 'session:' > "$OUT/sessions.txt"
python3 - "$OUT/chat.json" <<'PY' || finish "FAIL the reopened chat did not remember what was said in it"
import json, sys
m = json.load(open(sys.argv[1]))["messages"]
print("reopened chat:", len(m), "messages; last:", m[-1]["text"])
sys.exit(0 if len(m) == 4 and "mango" in m[-1]["text"].lower() else 1)
PY

echo "Learning: notes and own tools are kept across chats"
NOTES=files/rootfs/root/.config/termant/learned/notes.txt
tap_on 'content-desc="Open the Ubuntu terminal"' || finish "FAIL learning: Terminal button not found"
sleep 4
type_line 'sh%st7.sh'
wait_for_end files/rootfs/root/result7.txt 24 || { run_as cat files/rootfs/root/result7.txt > "$OUT/result7.txt"; finish "FAIL learning: the test script did not finish"; }
run_as cat files/rootfs/root/result7.txt > "$OUT/result7.txt"
tap_on 'content-desc="Back to the chat"' || finish "FAIL learning: Chat button not found"
sleep 2
for want in note-exit=0 again-exit=0 papaya-notes=1 long-exit=1 forget-exit=0 forgotten=yes tool-exit=0 \
    tool-says=hello-from-tool-42 clash-exit=1 badname-exit=1 syntax-exit=1 bad-kept=no guide-note=1 guide-tool=1 \
    off-exit=1 off-guide=gone on-guide=there; do
  grep -q "^$want\$" "$OUT/result7.txt" || finish "FAIL learning: the learn command ($want expected)"
done
last_reply() { # <python condition on the last message "m">
  python3 - "$OUT/chat.json" "$1" <<'PY'
import json, sys
m = json.load(open(sys.argv[1]))["messages"][-1]
print("learning: reply:", {"text": m["text"][:200], "steps": m["steps"], "failed": m["failed"]})
sys.exit(0 if eval(sys.argv[2]) else 1)
PY
}
# A note saved before this chat began must be known in it.
tap_on 'content-desc="Start a new chat"' || finish "FAIL learning: New chat button not found"
sleep 2
ask 'What%sis%smy%ssecret%sfruit?%sAnswer%swith%sone%sword' || finish "FAIL learning: could not ask about the note"
wait_for_reply 80 || finish "FAIL learning: no reply about the note"
run_as cat files/rootfs/root/.config/opencode/opencode.jsonc > "$OUT/opencode-learn.jsonc"
grep -q 'learned.md' "$OUT/opencode-learn.jsonc" || finish "FAIL learning: OpenCode is not told to read what was learned"
last_reply '"papaya" in m["text"].lower()' || finish "FAIL learning: a note from before was not known in a new chat"
# Told to remember something, the agent must save it by itself.
ask 'Remember%sfor%sall%sfuture%schats:%smy%sfavourite%scolour%sis%steal' || finish "FAIL learning: could not ask to remember"
wait_for_reply 80 || finish "FAIL learning: no reply to the request to remember"
sleep 2
shot 21b-learn-note
last_reply 'True'
run_as cat "$NOTES" > "$OUT/learn-notes.txt"
grep -qi 'teal' "$OUT/learn-notes.txt" || finish "FAIL learning: the agent did not save what it was told to remember"
# A tool saved before must be usable by name.
tap_on 'content-desc="Start a new chat"'
sleep 2
ask 'Use%syour%sown%stool%sci-hello%sand%stell%sme%sexactly%swhat%sit%sprints' || finish "FAIL learning: could not ask for the tool"
wait_for_reply 80 || finish "FAIL learning: no reply about the tool"
last_reply '"hello-from-tool-42" in m["text"]' || finish "FAIL learning: the agent did not use its own tool"
# Making a tool by itself needs more from the model: reported, does not fail the test.
ask 'Make%syourself%sa%stool%snamed%sci-sum%sthat%sprints%sthe%ssum%sof%sthe%stwo%snumbers%sgiven%sto%sit.%sKeep%sit%sfor%slater' \
  && wait_for_reply 80
sleep 2
last_reply 'True'
if run_as head -n 3 files/rootfs/usr/local/bin/ci-sum 2>/dev/null | grep -q '^# termant-tool:'; then
  echo "the agent made the tool ci-sum by itself: yes" | tee "$OUT/learn.txt"
  run_as cat files/rootfs/usr/local/bin/ci-sum >> "$OUT/learn.txt"
else
  echo "the agent made the tool ci-sum by itself: NO" | tee "$OUT/learn.txt"
fi
run_as cat files/rootfs/root/.config/termant/learned.md > "$OUT/learned.md"
# Settings shows what was learned, and takes a note away.
run_as cat "$NOTES" > "$OUT/learn-notes.txt"
at=$(cut -f 2- "$OUT/learn-notes.txt" | grep -n papaya | head -n 1 | cut -d: -f1)
[ -n "$at" ] || finish "FAIL learning: the first note is gone"
tap_on 'content-desc="Settings"' || finish "FAIL learning: Settings button not found"
sleep 2
found=
for _ in $(seq 1 45); do
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  if adb shell "grep -q 'content-desc=.Remove note $at.' /sdcard/ui.xml"; then found=1; break; fi
  adb shell input swipe 160 500 160 200 300; sleep 1
done
[ -n "$found" ] || { shot 21c-learn-settings; finish "FAIL learning: Settings does not show the notes"; }
adb shell input swipe 160 400 160 300 300; sleep 1 # away from the screen's edge
shot 21c-learn-settings
adb shell "grep -q 'Remove tool ci-hello' /sdcard/ui.xml" || { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell "grep -q 'Remove tool ci-hello' /sdcard/ui.xml"; } \
  || echo "learning: the tool is further down in Settings than this screen"
tap_on "content-desc=\"Remove note $at\"" || finish "FAIL learning: the remove button of a note was not found"
sleep 2
run_as cat "$NOTES" > "$OUT/learn-notes-after.txt"
grep -q papaya "$OUT/learn-notes-after.txt" && finish "FAIL learning: Settings did not remove the note"
grep -qi teal "$OUT/learn-notes-after.txt" || finish "FAIL learning: Settings removed the wrong note"
tap_on 'content-desc="Back to the chat"' || finish "FAIL learning: Back arrow not found in Settings"
sleep 2

echo "Looking after the user: the agent writes first, nobody asked"
care_log() { adb logcat -d -s Termant:I | grep 'care:' > "$OUT/care-log.txt"; }
care_wait() { # <text in the app's log> <how many such lines> <tries of 3 s>
  for _ in $(seq 1 "$3"); do
    [ "$(adb logcat -d -s Termant:I | grep -c "$1")" -ge "$2" ] && return 0
    sleep 3
  done
  return 1
}
tap_on 'content-desc="Open the Ubuntu terminal"' || finish "FAIL care: Terminal button not found"
sleep 4
type_line 'sh%st8.sh'
wait_for_end files/rootfs/root/result8.txt 24 || { run_as cat files/rootfs/root/result8.txt > "$OUT/result8.txt"; finish "FAIL care: the test script did not finish"; }
run_as cat files/rootfs/root/result8.txt > "$OUT/result8.txt"
for want in off-exit=1 off-now-exit=1 badtime-exit=1 manytimes-exit=1 times-exit=0 on-exit=0 times-kept=1 plan-exit=0 \
    twice-exit=1 listed=1 cancel-exit=0 cancel-again-exit=1 left=0 zero-exit=1 notopic-exit=1 badwhen-exit=1 \
    at-exit=0 at-cancel-exit=0 soon-exit=0; do
  grep -q "^$want\$" "$OUT/result8.txt" || finish "FAIL care: the phone care command ($want expected)"
done
tap_on 'content-desc="Back to the chat"' || finish "FAIL care: Chat button not found"
sleep 2
tap_on 'content-desc="Start a new chat"' || finish "FAIL care: New chat button not found"
sleep 2
# The user is elsewhere when the planned moment comes: the message must arrive as a notification,
# and as a card over the other app when that is allowed. No "working" card: nobody is waiting.
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
adb shell input keyevent 3
care_cards() { adb logcat -d -s Termant:I | grep -c "overlay: $1"; }
working=$(care_cards 'status shown'); results=$(care_cards 'result shown')
if ! care_wait 'care: asked:' 1 130; then
  care_log; cat "$OUT/care-log.txt"; shot 21d-care-nothing
  finish "FAIL care: the agent did not write at the moment it had planned"
fi
sleep 3
shot 21c-care-card
care_log; cat "$OUT/care-log.txt"
adb logcat -d -s Termant:I | grep 'overlay:' | tail -n 6
[ "$(care_cards 'result shown')" -gt "$results" ] || finish "FAIL care: no card over the other app with the agent's message"
[ "$(care_cards 'status shown')" -eq "$working" ] || finish "FAIL care: a working card was shown for a check-in nobody asked for"
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW default
grep -q 'care: check-in started about: which colour' "$OUT/care-log.txt" || finish "FAIL care: the check-in was not about what was planned"
grep -q 'care: shown as a notification' "$OUT/care-log.txt" || finish "FAIL care: no notification while Termant was not on screen"
adb shell dumpsys notification --noredact > "$OUT/care-notification.txt" 2>&1
grep -qE "channel=care|mChannelId=care|mId='care'" "$OUT/care-notification.txt" \
  || finish "FAIL care: Android does not show the check-in's notification"
adb shell cmd statusbar expand-notifications; sleep 3
shot 21d-care-notification
adb shell cmd statusbar collapse; sleep 2
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 4
shot 21e-care-chat
run_as cat files/chat.json > "$OUT/chat.json"
cp "$OUT/chat.json" "$OUT/chat-care.json"
python3 - "$OUT/chat.json" <<'PY' || finish "FAIL care: the agent's own message is not in the chat as it should be"
import json, sys
ms = json.load(open(sys.argv[1]))["messages"]
print("care: chat:", [{"user": m["user"], "first": m.get("first"), "text": m["text"][:240], "steps": m["steps"]} for m in ms])
m = ms[-1]
ok = len(ms) == 1 and not m["user"] and m.get("first") and not m["failed"] and m["text"].strip() and "this time you speak first" not in m["text"]
print("care: mentions the colour it has a note on:", "yes" if "teal" in m["text"].lower() else "NO")
print("care: ends with a question:", "yes" if "?" in m["text"] else "NO")
sys.exit(0 if ok else 1)
PY
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb shell "grep -q 'Termant asked by itself' /sdcard/ui.xml" || finish "FAIL care: the chat does not mark the message as the agent's own"
run_as cat files/care.json > "$OUT/care-after.json"
grep -q 'which colour' "$OUT/care-after.json" && finish "FAIL care: the planned moment is still planned after it came"
# The user answers in the chat, and mentions something a friend would ask about later (reported, does not fail the test).
ask 'It%sis%steal,%syes.%sTomorrow%sat%s10%sin%sthe%smorning%sI%shave%smy%sdriving%stest' && wait_for_reply 80
sleep 2
shot 21f-care-answer
last_reply 'True'
if ! last_reply 'not m["failed"]' >/dev/null && run_as cat shared_prefs/termant.xml | grep -q 'name="free_limit_until"'; then
  # OpenCode refused the answer to a check-in with its free limit. Find out whether it refuses everything now
  # (this runner's share is used up) or only the conversation that the agent began.
  tap_on 'content-desc="Open the Ubuntu terminal"' && sleep 4 && type_line 'sh%st9.sh' && wait_for_end files/rootfs/root/result9.txt 40
  run_as cat files/rootfs/root/result9.txt > "$OUT/result9.txt"
  tail -n 12 "$OUT/result9.txt"
  if grep -q '^fresh-exit=0' "$OUT/result9.txt"; then
    finish "FAIL care: OpenCode refused the answer to a check-in with its free limit, but answers a new conversation"
  fi
  finish "FAIL care: OpenCode's real free limit was reached on this runner, no model answers any more (run it again later)"
fi
run_as cat files/care.json > "$OUT/care-planned.json"
if grep -q '"topic"' "$OUT/care-planned.json"; then
  echo "the agent planned by itself to come back to the driving test: yes" | tee "$OUT/care.txt"
else
  echo "the agent planned by itself to come back to the driving test: NO" | tee "$OUT/care.txt"
fi
cat "$OUT/care-planned.json" >> "$OUT/care.txt"; echo >> "$OUT/care.txt"
# Settings: "Ask me now" starts a check-in, and the switch turns all of it off.
care_row() { # <text of the row>: scrolls Settings until it shows
  for _ in $(seq 1 45); do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    top=$(adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep "text=\"$1\"" | head -1 \
      | grep -o 'bounds="[^"]*"' | grep -o '[0-9]*' | sed -n 2p)
    if [ -n "$top" ]; then
      # low on the screen: bring it away from the edge (higher up, the same swipe would push it out of sight)
      if [ "$top" -gt 380 ]; then adb shell input swipe 160 400 160 300 300; sleep 1; fi
      return 0
    fi
    adb shell input swipe 160 500 160 200 300; sleep 1
  done
  return 1
}
tap_on 'content-desc="Start a new chat"'
sleep 2
tap_on 'content-desc="Settings"' || finish "FAIL care: Settings button not found"
sleep 2
care_row 'Ask me now' || { shot 21g-care-settings; finish "FAIL care: Settings does not show the check-ins"; }
shot 21g-care-settings
asked=$(adb logcat -d -s Termant:I | grep -c 'care: asked:')
tap_on 'text="Ask me now"' || finish "FAIL care: the Ask me now row was not found"
sleep 2
tap_on 'content-desc="Back to the chat"' || finish "FAIL care: Back arrow not found in Settings"
sleep 2
shot 21h-care-writing
if ! care_wait 'care: asked:' $((asked + 1)) 60; then
  care_log; cat "$OUT/care-log.txt"
  # not a fault of the app: OpenCode counts free use per network, and this runner's share can be used up
  run_as cat shared_prefs/termant.xml | grep -q 'name="free_limit_until"' \
    && finish "FAIL care: OpenCode's real free limit was reached on this runner, no model answers any more (run it again later)"
  finish "FAIL care: Ask me now brought no message"
fi
sleep 2
shot 21i-care-now
run_as cat files/chat.json > "$OUT/chat.json"
last_reply 'not m["user"] and m.get("first") and not m["failed"] and m["text"].strip()' || finish "FAIL care: the message after Ask me now is not right"
# The conversation the agent began must go on like any other when the user answers.
ask 'I%sam%sfine,%sthank%syou.%sAnswer%sin%sone%sshort%ssentence' || finish "FAIL care: could not answer the check-in"
wait_for_reply 80 || finish "FAIL care: no reply to the answer to a check-in"
sleep 2
shot 21i2-care-answered
last_reply 'not m["failed"] and m["text"].strip()' || finish "FAIL care: the answer to a check-in got no proper reply"
tap_on 'content-desc="Settings"' || finish "FAIL care: Settings button not found the second time"
sleep 2
care_row 'Ask me by itself' || finish "FAIL care: the switch is not in Settings"
tap_on 'text="Ask me by itself"' || finish "FAIL care: the switch was not found"
sleep 2
shot 21j-care-off
run_as cat shared_prefs/termant.xml > "$OUT/prefs-care.xml"
grep -q 'name="care_on" value="false"' "$OUT/prefs-care.xml" || finish "FAIL care: the switch in Settings did not turn the check-ins off"
adb shell dumpsys alarm > "$OUT/dumpsys-alarm-care.txt" 2>&1
grep -q 'termant://care' "$OUT/dumpsys-alarm-care.txt" && echo "care: alarms are still set after turning off" | tee -a "$OUT/care.txt"
care_log
tap_on 'content-desc="Back to the chat"' || finish "FAIL care: Back arrow not found in Settings the second time"
sleep 2

echo "Streaming: the words of a reply show up while it is being written"
tap_on 'content-desc="Start a new chat"'
sleep 2
ask 'Write%sa%sstory%sof%sabout%s150%swords%sabout%sa%skite.%sDo%snot%srun%sany%scommand' || finish "FAIL could not send the streaming request"
for i in 1 2 3 4 5 6 7 8; do sleep 2; shot "22-stream-$i"; done
wait_for_reply 80 || finish "FAIL streaming: no reply"
sleep 2
shot 23-stream-done
adb logcat -d -s Termant:I | grep 'stream:' > "$OUT/stream.txt"
cat "$OUT/stream.txt"
feed=files/rootfs/root/.cache/termant/events.txt
run_as cat "$feed.log" 2>&1 | tail -n 60 > "$OUT/stream-server-log.txt"
run_as cat "$feed" 2>/dev/null | grep -o '"type":"[^"]*"' | sort | uniq -c > "$OUT/stream-event-types.txt"
run_as cat "$feed" 2>/dev/null | grep -m 3 'session.text.delta' | cut -c 1-600 > "$OUT/stream-sample.txt"
cat "$OUT/stream-event-types.txt"
grep -q 'stream: [1-9][0-9]* pieces' "$OUT/stream.txt" || finish "FAIL streaming: no reply arrived piece by piece"

echo "Helper agents in the chat: the main agent hands two parts of a job to helpers"
# OpenCode is the real one. The model is a pretend one on the test machine (fake_model.py),
# so that the result does not hang on whether a real model chooses to use helpers.
rm -f "$OUT/fake-model-port" "$OUT/helpers-model-asked.txt"
python3 android/ci/fake_model.py "$OUT/fake-model-port" "$OUT/helpers-model-asked.txt" 'touch /root/helper-ran.txt' 0.0.0.0 > "$OUT/fake-model.log" 2>&1 < /dev/null &
fake_model=$!
for _ in $(seq 1 50); do [ -s "$OUT/fake-model-port" ] && break; sleep 0.2; done
[ -s "$OUT/fake-model-port" ] || finish "FAIL helpers: the pretend model did not start"
KEYS=files/rootfs/root/.config/termant/keys.env
keys_before=$(mktemp)
sleep 2
adb shell am force-stop "$PKG"
run_as cat shared_prefs/termant.xml > "$OUT/prefs-before-helpers.xml"
grep -q '</map>' "$OUT/prefs-before-helpers.xml" || finish "FAIL helpers: could not read the settings"
had_keys=; run_as test -f "$KEYS" && had_keys=1 && run_as cat "$KEYS" > "$keys_before"
# the keys file is read before every chat message: it points OpenCode at the pretend model (10.0.2.2 is the test machine)
{
  cat "$keys_before"
  echo "export OPENCODE_CONFIG_CONTENT='{\"provider\":{\"termanttest\":{\"npm\":\"@ai-sdk/openai-compatible\",\"name\":\"Termant test\",\"options\":{\"baseURL\":\"http://10.0.2.2:$(cat "$OUT/fake-model-port")/v1\",\"apiKey\":\"x\"},\"models\":{\"fake\":{\"name\":\"Fake\"}}}}}'"
} | adb shell "run-as $PKG sh -c 'mkdir -p files/rootfs/root/.config/termant && cat > $KEYS'"
change_prefs model=termanttest/fake switched= switched_at= free_limit_until= || finish "FAIL helpers: could not prepare the settings"
run_as rm -f files/rootfs/root/helper-ran.txt
# a helper of the user's own, as "team add poet ..." writes it
adb shell "run-as $PKG sh -c 'mkdir -p files/rootfs/root/.config/opencode/agents && cat > files/rootfs/root/.config/opencode/agents/poet.md'" <<'POET'
---
description: "Writes one short line of verse on a given subject."
mode: subagent
---
POET-MARK You write one short line of verse about the subject you are given.

Do not touch the screen. Do not ask the user anything. Your last message is your report to the main agent.
POET
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 8
tap_on 'content-desc="Start a new chat"' || finish "FAIL helpers: New chat button not found"
sleep 2
ask 'TEAM%splease' || finish "FAIL helpers: could not send the request"
for i in 1 2 3; do sleep 2; shot "23b-helpers-$i"; done
wait_for_reply 100 || { shot 23c-helpers-timeout; adb logcat -d -s Termant:I | tail -30; cat "$OUT/helpers-model-asked.txt"; finish "FAIL helpers: no reply"; }
sleep 2
shot 23d-helpers-done
cp "$OUT/chat.json" "$OUT/chat-helpers.json"
# the newest "activity:" line is that of this reply
adb logcat -d -s Termant:I | grep 'activity:' | tail -n 1 > "$OUT/helpers-activity.txt"
cat "$OUT/helpers-activity.txt" "$OUT/helpers-model-asked.txt"
python3 - "$OUT/chat-helpers.json" <<'PY' || finish "FAIL helpers: the chat does not show the two helpers and what they reported"
import json, sys
m = json.load(open(sys.argv[1]))["messages"][-1]
print("helpers: reply:", m)
ok = (not m["failed"] and "RESEARCH REPORT: chai is tea." in m["text"] and "POEM REPORT: garam chai" in m["text"]
      and "Helper researcher: Find out about chai" in m["steps"] and "Helper poet: A poem about chai" in m["steps"]
      and not any(s.startswith("Helper: ") for s in m["steps"]))
sys.exit(0 if ok else 1)
PY
run_as test -f files/rootfs/root/helper-ran.txt || finish "FAIL helpers: the helper did not run its command"
grep -q 'activity: .*helper' "$OUT/helpers-activity.txt" || finish "FAIL helpers: the app did not notice that helper agents were working"
# Back to the real models and the settings from before.
kill $fake_model 2>/dev/null; fake_model=
run_as rm -f files/rootfs/root/.config/opencode/agents/poet.md
sleep 2
adb shell am force-stop "$PKG"
if [ -n "$had_keys" ]; then adb shell "run-as $PKG sh -c 'cat > $KEYS'" < "$keys_before"; else run_as rm -f "$KEYS"; fi
rm -f "$keys_before"
adb shell "run-as $PKG sh -c 'cat > shared_prefs/termant.xml'" < "$OUT/prefs-before-helpers.xml"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 8

echo "Voice button: this emulator has no speech service, so only that nothing breaks"
tap_on 'content-desc="Speak"' || finish "FAIL microphone button not found"
sleep 3
shot 24-voice
adb logcat -d -s Termant:I | grep 'voice:' > "$OUT/voice.txt"
adb shell pidof "$PKG" >/dev/null || finish "FAIL the app stopped when the microphone was tapped"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 3

echo "MCP: OpenCode starts a server added in Settings (reported, does not fail the test)"
adb shell "run-as $PKG sh -c 'cat > files/rootfs/root/mcp-ci.sh'" < android/ci/mcp-server.sh
adb shell am force-stop "$PKG"
run_as cat shared_prefs/termant.xml > "$OUT/prefs-mcp.xml"
python3 - "$OUT/prefs-mcp.xml" <<'PY'
import sys
path = sys.argv[1]
text = open(path).read()
text = text.replace('</map>', '    <string name="mcp_servers">{&quot;ci&quot;:&quot;bash /root/mcp-ci.sh&quot;}</string>\n</map>')
open(path, 'w').write(text)
PY
adb shell "run-as $PKG sh -c 'cat > shared_prefs/termant.xml'" < "$OUT/prefs-mcp.xml"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 8
if ask 'Say%shi%sin%sone%sword' && wait_for_reply 80; then echo "chat with an MCP server: answered" > "$OUT/mcp.txt"; else echo "chat with an MCP server: NOT answered" > "$OUT/mcp.txt"; fi
run_as cat files/rootfs/root/.config/opencode/opencode.jsonc > "$OUT/opencode.jsonc" 2>&1
echo "requests the MCP server got:" >> "$OUT/mcp.txt"
run_as cat files/rootfs/root/mcp-ci.log >> "$OUT/mcp.txt" 2>&1
if grep -q '^tools/list' "$OUT/mcp.txt"; then echo "MCP: connected" >> "$OUT/mcp.txt"; else echo "MCP: NOT connected" >> "$OUT/mcp.txt"; fi
cat "$OUT/mcp.txt"
sleep 2
shot 25-mcp

echo "Tools: GitHub token, web search, screen control"
tap_on 'content-desc="Settings"' || finish "FAIL Settings button not found for the tools test"
sleep 2
scroll_to GitHub || finish "FAIL GitHub row not found"
tap_on 'content-desc="GitHub"' || finish "FAIL GitHub row could not be tapped"
sleep 2
shot 32-github-dialog
tap_on 'class="android.widget.EditText"'; sleep 1
adb shell input text 'ghp_termantCiNotARealToken0000000000000'
tap_on 'text="Save"' || tap_on 'text="SAVE"' || finish "FAIL Save not found for the GitHub token"
sleep 8
shot 33-github-answer
adb logcat -d -s Termant:I | grep 'github:' > "$OUT/github.txt"
cat "$OUT/github.txt"
grep -q 'github: token check gave' "$OUT/github.txt" || finish "FAIL the GitHub token was not checked"
# GitHub says 401 to a made-up token, and then it must not be kept
if grep -q 'gave 401' "$OUT/github.txt"; then
  run_as grep -q GH_TOKEN files/rootfs/root/.config/termant/keys.env && finish "FAIL a token GitHub refused was saved anyway"
fi
scroll_to 'Screen control' || finish "FAIL Screen control row not found"
tap_on 'content-desc="Screen control"' || finish "FAIL Screen control row could not be tapped"
sleep 2
shot 34-screen-control-ask
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb shell "grep -q 'Open settings' /sdcard/ui.xml" || adb shell "grep -q 'OPEN SETTINGS' /sdcard/ui.xml" || finish "FAIL the screen control explanation did not open"
tap_on 'text="Cancel"' || tap_on 'text="CANCEL"' || adb shell input keyevent 4
sleep 1
tap_on 'content-desc="Back to the chat"' || finish "FAIL Back arrow not found after the tools rows"
sleep 2

echo "Over other apps: a card says what the agent is doing while Termant is not on screen"
wait_log() { # <text in the app's log> <how many such lines there must be> <tries of 1 s>: prints "X Y" from the last one
  for _ in $(seq 1 "$3"); do
    if [ "$(adb logcat -d -s Termant:I | grep -c "$1")" -ge "$2" ]; then
      adb logcat -d -s Termant:I | grep "$1" | tail -n 1 | grep -o '[0-9]*,[0-9]*$' | tr ',' ' '
      return 0
    fi
    sleep 1
  done
  return 1
}
count_log() { adb logcat -d -s Termant:I | grep -c "$1"; }
ask_and_leave() { # <text with %s for spaces>: sends it and goes to the home screen at once, as someone who does not wait would
  tap_on 'class="android.widget.EditText"' || return 1
  sleep 2
  adb shell input text "$1"
  sleep 1
  tap_on 'content-desc="Send"' || return 1
  adb shell input keyevent 3
}
last_overlay_line() { adb logcat -d -s Termant:I | grep 'overlay:' | tail -n 1; }
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
tap_on 'content-desc="Start a new chat"'
sleep 2
tap_on 'class="android.widget.EditText"' || finish "FAIL message box not found for the card test"
sleep 2
adb shell input text 'Write%sa%sstory%sof%sabout%s400%swords%sabout%sa%sriver.%sDo%snot%srun%sany%scommand'
sleep 1
tap_on 'content-desc="Send"' || finish "FAIL Send button not found for the card test"
adb shell input keyevent 3 # Home: the user goes elsewhere while the agent works
sleep 3
shot 42-card-over-home
adb logcat -d -s Termant:I | grep 'overlay:' > "$OUT/overlay.txt"
grep -q 'overlay: over other apps' "$OUT/overlay.txt" || finish "FAIL no card over other apps while the agent works"
grep -q 'overlay: status shown' "$OUT/overlay.txt" || finish "FAIL the card does not show what the agent is doing"
if stop=$(wait_log 'overlay: stop at' 1 5); then adb shell input tap $stop; fi
sleep 3
shot 43-card-stopped
adb logcat -d -s Termant:I | grep 'overlay:' > "$OUT/overlay.txt"
if grep -q 'overlay: stopped by the user' "$OUT/overlay.txt"; then echo "stop button on the card: worked"; else echo "stop button on the card: not reached (the reply may have ended first)"; fi | tee "$OUT/overlay-stop.txt"
grep -q 'overlay: hidden' "$OUT/overlay.txt" || finish "FAIL the card stayed after the agent had stopped"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 3
wait_for_reply 20 || finish "FAIL the chat did not end after the card's stop button"
shot 43b-after-stop

echo "Over other apps: the answer stays for 10 seconds, takes the next message, and can be swiped away"
tap_on 'content-desc="Start a new chat"'
sleep 2
results=$(count_log 'overlay: result shown')
ask_and_leave 'Reply%sonly%swith%sthe%sword%sOK' || finish "FAIL could not send the request for the answer card"
wait_log 'overlay: result shown' $((results + 1)) 80 >/dev/null || { shot 48-no-result; finish "FAIL the answer did not appear over the other app"; }
sleep 2
shot 48-result-card
sleep 12
adb logcat -d -s Termant:I | grep 'overlay:' > "$OUT/overlay-result.txt"
python3 - "$OUT/overlay-result.txt" <<'PY' || finish "FAIL the answer did not stay for about 10 seconds"
import datetime, sys
lines = open(sys.argv[1]).read().splitlines()
at = lambda line: datetime.datetime.strptime(line[:18], "%m-%d %H:%M:%S.%f")
shown = max(i for i, line in enumerate(lines) if "overlay: result shown" in line)
gone = [line for line in lines[shown + 1:] if "overlay: hidden" in line]
if not gone:
    print("the answer card is still there after 14 seconds")
    sys.exit(1)
stayed = (at(gone[0]) - at(lines[shown])).total_seconds()
print("the answer stayed for %.1f seconds" % stayed)
sys.exit(0 if 9.5 <= stayed <= 12 else 1)
PY
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 3
results=$(count_log 'overlay: result shown'); replies=$(count_log 'overlay: reply at')
boxes=$(count_log 'overlay: box at'); sends=$(count_log 'overlay: send at')
ask_and_leave 'Reply%sonly%swith%sthe%sword%sYES' || finish "FAIL could not send the second request for the answer card"
wait_log 'overlay: result shown' $((results + 1)) 80 >/dev/null || finish "FAIL the second answer did not appear over the other app"
at=$(wait_log 'overlay: reply at' $((replies + 1)) 5) || finish "FAIL the answer card has no place to type the next message"
adb shell input tap $at
at=$(wait_log 'overlay: box at' $((boxes + 1)) 10) || { shot 49-no-box; finish "FAIL the box for the next message did not open on the card"; }
adb shell input tap $at
sleep 1
adb shell input text 'Reply%sonly%swith%sthe%sword%sBYE'
sleep 12 # longer than the 10 seconds: a card someone is typing on must stay
shot 49-result-typing
last_overlay_line | grep -q 'overlay: hidden' && finish "FAIL the card went away while a message was being typed on it"
at=$(wait_log 'overlay: send at' $((sends + 1)) 5) || finish "FAIL no send button on the answer card"
adb shell input tap $at
wait_log 'overlay: sent from the card' 1 10 >/dev/null || finish "FAIL the message typed on the card was not sent"
wait_log 'overlay: result shown' $((results + 2)) 80 >/dev/null || { shot 50-no-answer; finish "FAIL no answer came to the message typed on the card"; }
sleep 1
shot 50-result-after-card-message
at=$(wait_log 'overlay: reply at' $((replies + 2)) 5) || finish "FAIL the last answer card was not laid out"
set -- $at
adb shell input swipe "$1" "$2" "$1" $(( $2 > 130 ? $2 - 120 : 5 )) 150
wait_log 'overlay: swiped away' 1 5 >/dev/null || { shot 51-not-swiped; finish "FAIL swiping the card up did not take it away"; }
sleep 1
shot 51-after-swipe
last_overlay_line | grep -q 'overlay: hidden' || finish "FAIL the card is still there after the swipe"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 3
run_as cat files/chat.json > "$OUT/chat-card.json" 2>/dev/null
grep -q 'only with the word BYE' "$OUT/chat-card.json" || finish "FAIL the message typed on the card is not in the chat"
shot 52-chat-after-card
tap_on 'content-desc="Open the Ubuntu terminal"' || finish "FAIL Terminal button not found for the screen control test"
sleep 3
# What the user does by hand in Android's Accessibility settings.
adb shell settings put secure enabled_accessibility_services "$PKG/$PKG.ScreenService"
# A photo for `phone look`: a dog in front of a bicycle, with a pickup truck behind (from the darknet
# project's examples; fetched at a fixed revision, not kept in this repository). One copy goes into
# Ubuntu, one into the phone's Pictures as its newest picture.
curl -sSL --fail --retry 3 -o "$OUT/dog.jpg" https://raw.githubusercontent.com/pjreddie/darknet/f6afaabcdf85f77e7aff2ec55c020c0e297c77f9/data/dog.jpg \
  && [ "$(sha256sum < "$OUT/dog.jpg" | cut -d' ' -f1)" = "5a9522051c3cec2bbd2f6323fccba32e8fbf3ddcc2b3e2fd46b04c720bc6f866" ] || finish "FAIL the test photo could not be fetched"
adb push "$OUT/dog.jpg" /data/local/tmp/dog.jpg >/dev/null
run_as cp /data/local/tmp/dog.jpg files/rootfs/root/dog.jpg || adb shell "run-as $PKG sh -c 'cat > files/rootfs/root/dog.jpg'" < "$OUT/dog.jpg"
adb shell mkdir -p /sdcard/Pictures
adb push "$OUT/dog.jpg" /sdcard/Pictures/termant-test-photo.jpg >/dev/null
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/termant-test-photo.jpg >/dev/null 2>&1
rm -f "$OUT/dog.jpg"
adb shell settings put secure accessibility_enabled 1
sleep 6
adb logcat -d -s Termant:I | grep 'screen control:' > "$OUT/screen-control.txt"
cat "$OUT/screen-control.txt"
grep -q 'screen control: on' "$OUT/screen-control.txt" || finish "FAIL Android did not start the screen control service"
type_line 'sh%st5.sh'
wait_for_end files/rootfs/root/result5.txt 220 || { run_as cat files/rootfs/root/result5.txt > "$OUT/result5.txt"; shot 35-screen-timeout; finish "FAIL screen control test did not finish"; }
run_as cat files/rootfs/root/result5.txt > "$OUT/result5.txt"
cat "$OUT/result5.txt"
sleep 2
shot 35-after-screen-control
grep -q '^screen-exit=0' "$OUT/result5.txt" || finish "FAIL phone screen"
grep -q '^App: ' "$OUT/result5.txt" || finish "FAIL phone screen did not describe the screen"
grep -q '^apps-exit=0' "$OUT/result5.txt" || finish "FAIL phone apps"
grep -q '^app-exit=0' "$OUT/result5.txt" || finish "FAIL phone app"
grep -q '^app-opened=yes' "$OUT/result5.txt" || finish "FAIL phone app did not bring the Settings app to the screen"
run_as cat files/rootfs/root/screen-ocr.txt > "$OUT/screen-ocr.txt" 2>/dev/null
adb logcat -d -s Termant:I | grep 'mirage:' > "$OUT/mirage-log.txt"; cat "$OUT/mirage-log.txt"
grep -q '^ocr-exit=0' "$OUT/result5.txt" || finish "FAIL phone screen ocr"
grep -q '^Read from a picture of the screen by Mirage' "$OUT/screen-ocr.txt" || finish "FAIL phone screen ocr did not read a picture of the screen"
grep -q '^ocr-item=[0-9]' "$OUT/result5.txt" || finish "FAIL the picture of Settings was read without its \"Network & internet\""
grep -q '^ocr-tap-exit=0' "$OUT/result5.txt" || finish "FAIL phone tap on an item seen in the picture"
grep -q '^ocr-tap-opened=yes' "$OUT/result5.txt" || finish "FAIL the tap on \"Network & internet\" as seen in the picture did not open that page"
grep -q '^ocr-again-exit=0' "$OUT/result5.txt" || finish "FAIL the second phone screen ocr"
# what is in a photo and where
for f in look-file look-screen look-last; do run_as cat files/rootfs/root/$f.txt > "$OUT/$f.txt" 2>/dev/null; done
grep -q '^look-file-exit=0' "$OUT/result5.txt" || finish "FAIL phone look FILE"
grep -q '^look-file-head=yes' "$OUT/result5.txt" || finish "FAIL phone look FILE did not name the picture and its size"
grep -q '^look-file-dog=yes' "$OUT/result5.txt" || finish "FAIL phone look did not find the dog in the photo"
grep -q '^look-file-dog-left=yes' "$OUT/result5.txt" || finish "FAIL phone look did not place the dog on the left of the photo"
grep -q '^look-relative=yes' "$OUT/result5.txt" || finish "FAIL phone look with a file name relative to the folder"
grep -q '^look-missing-exit=1' "$OUT/result5.txt" || finish "FAIL phone look accepted a file that is not there"
grep -q '^look-not-a-picture-exit=1' "$OUT/result5.txt" || finish "FAIL phone look accepted a file that is no picture"
grep -q '^look-usage-exit=1' "$OUT/result5.txt" || finish "FAIL phone look accepted two files"
grep -q '^look-screen-exit=0' "$OUT/result5.txt" || finish "FAIL phone look (the screen)"
grep -q '^look-screen-head=yes' "$OUT/result5.txt" || finish "FAIL phone look did not look at a picture of the screen"
grep -q '^look-last-exit=0' "$OUT/result5.txt" || finish "FAIL phone look last"
grep -q '^look-last-head=yes' "$OUT/result5.txt" || finish "FAIL phone look last did not take the newest picture on the phone"
grep -q '^look-last-dog=yes' "$OUT/result5.txt" || finish "FAIL phone look last did not find the dog"
run_as cat files/rootfs/root/learn-check.txt > "$OUT/learn-check.txt" 2>/dev/null
run_as cat files/rootfs/root/youtube-search.txt > "$OUT/youtube-search.txt" 2>/dev/null
run_as cat files/rootfs/root/youtube-read.txt > "$OUT/youtube-read.txt" 2>/dev/null
grep -q '^learn-check-exit=0' "$OUT/result5.txt" || finish "FAIL learning (YouTube, skills, today, ElevenLabs keys) inside Ubuntu"
run_as cat files/rootfs/root/team-check.txt > "$OUT/team-check.txt" 2>/dev/null
grep -q '^team-check-exit=0' "$OUT/result5.txt" || finish "FAIL helper agents (the team command, OpenCode with helpers) inside Ubuntu"
grep -q '^team-real-run=yes' "$OUT/result5.txt" || finish "FAIL helper agents: OpenCode did not run with helpers inside Ubuntu"
grep -q '^team-add-exit=0' "$OUT/result5.txt" || finish "FAIL team add"
run_as cat files/rootfs/root/kit-check.txt > "$OUT/kit-check.txt" 2>/dev/null
grep -q '^kit-check-exit=0' "$OUT/result5.txt" || finish "FAIL the kit (undo, tidy, study, readfile, me, watch, diary, attached files) inside Ubuntu"
grep -q '^every-rule-exit=0' "$OUT/result5.txt" || finish "FAIL phone rule add every 20m"
grep -q '^bad-every-exit=1' "$OUT/result5.txt" || finish "FAIL a rule for every 5 minutes was accepted"
grep -q '^bad-job-exit=1' "$OUT/result5.txt" || finish "FAIL phone job without a task was accepted"
grep -q '^notifications-said=yes' "$OUT/result5.txt" || finish "FAIL phone notifications did not say how to allow it"
grep -q '^sms-alone-said=yes' "$OUT/result5.txt" || finish "FAIL phone sms in a job the agent does alone was not held back"
grep -q '^voice-add-exit=0' "$OUT/result5.txt" || finish "FAIL phone voice add"
grep -q '^say-exit=0' "$OUT/result5.txt" || finish "FAIL phone say with ElevenLabs keys"
grep -q '^say-second-key=yes' "$OUT/result5.txt" || finish "FAIL phone say did not move on to the second ElevenLabs key"
grep -q '^say-skips-used-up-key=yes' "$OUT/result5.txt" || finish "FAIL phone say asked the used-up ElevenLabs key again"
grep -q '^voice-report=yes' "$OUT/result5.txt" || finish "FAIL phone voice did not report the keys"
grep -q '^play-exit=0' "$OUT/result5.txt" || finish "FAIL phone play"
grep -q '^play-length=yes' "$OUT/result5.txt" || finish "FAIL phone play did not play the one-second sound"
grep -q '^play-not-a-sound-exit=1' "$OUT/result5.txt" || finish "FAIL phone play accepted a file that is no sound"
grep -q '^play-missing-exit=1' "$OUT/result5.txt" || finish "FAIL phone play accepted a file that is not there"
grep -q '^swipe-exit=0' "$OUT/result5.txt" || finish "FAIL phone swipe"
grep -q '^swipe-moved=yes' "$OUT/result5.txt" || finish "FAIL after a swipe the screen was described as before it"
grep -q '^tap-exit=0' "$OUT/result5.txt" || finish "FAIL phone tap by words"
grep -q '^tap-number-exit=0' "$OUT/result5.txt" || finish "FAIL phone tap by number"
grep -q '^key-back-exit=0' "$OUT/result5.txt" || finish "FAIL phone key back"
grep -q '^key-home-exit=0' "$OUT/result5.txt" || finish "FAIL phone key home"
grep -q '^bad-key-exit=1' "$OUT/result5.txt" || finish "FAIL phone key accepted an unknown key"
grep -q '^bad-app-exit=1' "$OUT/result5.txt" || finish "FAIL phone app accepted an unknown app"
grep -q '^return-exit=0' "$OUT/result5.txt" || finish "FAIL phone app could not bring Termant back"
grep -q '^cred: password=ci-token-not-real' "$OUT/result5.txt" || finish "FAIL git was not given the GitHub token"
grep -q '^cred: username=ci-user' "$OUT/result5.txt" || finish "FAIL git was not given the GitHub account name"
grep -q '^other: password=ci-token-not-real' "$OUT/result5.txt" && finish "FAIL the GitHub token was offered to another site"
grep -q '^git-name=ci-user' "$OUT/result5.txt" || finish "FAIL git did not get the account's name for commits"
grep -q '"websearch"' "$OUT/result5.txt" || finish "FAIL web search is not in the agent's settings"
grep -q '"provider": *"exa"' "$OUT/result5.txt" || finish "FAIL web search has no search service chosen"

echo "Questions from the agent: over another app, then inside Termant"
choices=$(count_log 'overlay: choice 1 at'); boxes=$(count_log 'overlay: box at')
sends=$(count_log 'overlay: send at'); closes=$(count_log 'overlay: close at')
type_line 'sh%st6.sh'
at=$(wait_log 'overlay: choice 1 at' $((choices + 1)) 40) || { shot 44-no-question; finish "FAIL the question did not appear over the other app"; }
sleep 1
shot 44-question-choices
adb shell input tap $at
at=$(wait_log 'overlay: box at' $((boxes + 1)) 30) || { shot 45-no-box; finish "FAIL the question with a box to type in did not appear"; }
sleep 1
adb shell input tap $at
sleep 1
adb shell input text 'hello'
sleep 1
shot 45-question-typing
at=$(wait_log 'overlay: send at' $((sends + 1)) 5) || finish "FAIL no send button on the question"
adb shell input tap $at
at=$(wait_log 'overlay: close at' $((closes + 3)) 30) || { shot 46-no-third; finish "FAIL the third question did not appear"; }
sleep 1
shot 46-question-to-skip
adb shell input tap $at
# the last question is asked with Termant on screen: a dialog there
answered=
for _ in 1 2 3 4 5 6 7 8 9 10; do
  sleep 2
  if tap_on 'text="One"'; then answered=1; break; fi
done
shot 47-question-in-termant
[ -n "$answered" ] || finish "FAIL the question did not appear inside Termant"
wait_for_end files/rootfs/root/result6.txt 20 || { run_as cat files/rootfs/root/result6.txt > "$OUT/result6.txt"; finish "FAIL the questions test did not finish"; }
run_as cat files/rootfs/root/result6.txt > "$OUT/result6.txt"
cat "$OUT/result6.txt"
adb logcat -d -s Termant:I | grep 'overlay:\|ask:' > "$OUT/overlay.txt"
grep -q 'overlay: with screen control' "$OUT/overlay.txt" || finish "FAIL the card was not shown through screen control"
grep -q '^ask1-exit=0 answer=Alpha$' "$OUT/result6.txt" || finish "FAIL the chosen answer did not come back"
grep -q '^ask2-exit=0 answer=hello$' "$OUT/result6.txt" || finish "FAIL the typed answer did not come back"
grep -q '^ask3-exit=1' "$OUT/result6.txt" || finish "FAIL a skipped question still gave an answer"
grep -q '^ask-usage-exit=1' "$OUT/result6.txt" || finish "FAIL phone ask without a question was accepted"
grep -q '^ask-many-exit=1' "$OUT/result6.txt" || finish "FAIL phone ask accepted too many choices"
grep -q '^ask4-exit=0 answer=One$' "$OUT/result6.txt" || finish "FAIL the answer given inside Termant did not come back"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 3
tap_on 'content-desc="Settings"' || { tap_on 'content-desc="Back to the chat"'; sleep 2; tap_on 'content-desc="Settings"'; }
sleep 2
scroll_to 'Screen control'
shot 36-screen-control-on
tap_on 'content-desc="Back to the chat"'
sleep 2

echo "Web search and another app from the chat (reported, do not fail the test: they need the model to choose the tool)"
tap_on 'content-desc="Start a new chat"'
sleep 2
if ask 'Search%sthe%sweb%sfor%sthe%snewest%sstable%sPython%sversion%sand%stell%sme%sthe%snumber'; then
  for i in 1 2 3 4 5 6; do sleep 2; shot "37-search-$i"; done
  if wait_for_reply 80; then echo "web search request: answered" > "$OUT/search.txt"; else echo "web search request: NOT answered" > "$OUT/search.txt"; fi
  sleep 2
  shot 38-search-done
  adb logcat -d -s Termant:I | grep 'activity:' | tail -n 1 >> "$OUT/search.txt"
  if tail -n 1 "$OUT/search.txt" | grep -q 'search'; then echo "web search: USED" >> "$OUT/search.txt"; else echo "web search: not used" >> "$OUT/search.txt"; fi
  python3 - "$OUT/chat.json" >> "$OUT/search.txt" 2>&1 <<'PY'
import json, sys
m = json.load(open(sys.argv[1]))["messages"]
print("reply:", m[-1]["text"][:600])
print("steps:", m[-1].get("steps"))
PY
  cat "$OUT/search.txt"
fi
if ask 'Open%sthe%sClock%sapp%sand%stell%sme%swhich%sbuttons%sit%sshows.%sThen%scome%sback%sto%sTermant'; then
  for i in 1 2 3 4 5 6; do sleep 3; shot "39-screen-chat-$i"; done
  if wait_for_reply 80; then echo "screen request: answered" > "$OUT/screen-chat.txt"; else echo "screen request: NOT answered" > "$OUT/screen-chat.txt"; fi
  adb logcat -d -s Termant:I | grep 'activity:' | tail -n 1 >> "$OUT/screen-chat.txt"
  python3 - "$OUT/chat.json" >> "$OUT/screen-chat.txt" 2>&1 <<'PY'
import json, sys
m = json.load(open(sys.argv[1]))["messages"]
print("reply:", m[-1]["text"][:600])
print("steps:", m[-1].get("steps"))
PY
  cat "$OUT/screen-chat.txt"
  adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
  sleep 3
  shot 40-screen-chat-done
fi
adb logcat -d -s Termant:I | grep 'activity:' > "$OUT/activity.txt"
# the look test reads the screen with a tool that pauses services like ours; leave it off from here
adb shell settings delete secure enabled_accessibility_services
sleep 2

echo "Look: light theme, then Hindi"
tap_on 'content-desc="Settings"' || finish "FAIL Settings button not found for the look test"
sleep 2
tap_on 'content-desc="Theme"' || finish "FAIL Theme row not found"
sleep 5
shot 26-settings-light
run_as cat shared_prefs/termant.xml > "$OUT/prefs3.xml"
grep -q 'name="theme">light<' "$OUT/prefs3.xml" || finish "FAIL the light theme was not saved"
tap_on 'content-desc="Back to the chat"' || finish "FAIL Settings did not come back after the theme change"
sleep 2
shot 27-chat-light
tap_on 'content-desc="Open the Ubuntu terminal"' || finish "FAIL Terminal button not found in the light theme"
sleep 4
shot 28-terminal-light
python3 android/ci/pixel.py "$OUT/28-terminal-light.png" 90 20 90 28 90 36 60 40 40 30 > "$OUT/terminal-light.txt" 2>&1
cat "$OUT/terminal-light.txt"
[ "$(tail -n 1 "$OUT/terminal-light.txt")" = light ] || finish "FAIL the terminal stayed dark in the light theme"
tap_on 'content-desc="Back to the chat"' || finish "FAIL Chat button not found in the light theme"
sleep 2
tap_on 'content-desc="Settings"' || finish "FAIL Settings button not found in the light theme"
sleep 2
tap_on 'content-desc="Language"' || finish "FAIL Language row not found"
sleep 5
shot 29-settings-hindi
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb shell "grep -q 'Bhasha aur rang' /sdcard/ui.xml" || finish "FAIL Settings are not in Hindi after choosing it"
adb shell "grep -q 'Rang (Theme)' /sdcard/ui.xml" || finish "FAIL the Theme row is not in Hindi"
adb shell "grep -q 'Kaam ka folder' /sdcard/ui.xml" || adb shell "grep -q 'AI model' /sdcard/ui.xml" || finish "FAIL the agent rows are not in Hindi"
tap_on 'content-desc="Chat par wapas jayein"' || finish "FAIL Hindi back button not found"
sleep 2
shot 30-chat-hindi
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb shell "grep -q 'Nayi chat shuru karein' /sdcard/ui.xml" || finish "FAIL the chat is not in Hindi after choosing it"
tap_on 'content-desc="Nayi chat shuru karein"'
sleep 2
shot 31-welcome-hindi
tap_on 'content-desc="Purani chats"' || tap_on 'content-desc="Chat history"'
sleep 2
shot 41-history-hindi
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -o 'text="[^"]*\(pehle\|Abhi\|ago\)[^"]*"' | head -n 5 > "$OUT/history-hindi.txt"
cat "$OUT/history-hindi.txt"
grep -q ' ago"' "$OUT/history-hindi.txt" && finish "FAIL the chat list still says 'ago' in Hindi"
grep -q 'pehle\|Abhi' "$OUT/history-hindi.txt" || finish "FAIL the chat list shows no time in Hindi"

# Which way every chat message of this test went. At least one must have used the
# server that Termant keeps between messages, or keeping it does not work on a phone.
run_as cat files/rootfs/root/.cache/termant/server.history > "$OUT/server-history.txt" 2>/dev/null
echo "chat messages that started OpenCode themselves: $(grep -c ' own$' "$OUT/server-history.txt"), that used the kept server: $(grep -c ' kept$' "$OUT/server-history.txt")"
grep -q ' kept$' "$OUT/server-history.txt" || finish "FAIL no chat message used the kept OpenCode server"

finish PASS

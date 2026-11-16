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
finish() {
  shot "final"
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1
  adb logcat -d -t 4000 > "$OUT/logcat.txt" 2>&1 || true
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
# the English ones, and switches to Hindi itself at the end.
adb shell "run-as $PKG mkdir -p shared_prefs"
printf '%s\n' "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>" '<map>' '    <string name="lang">en</string>' '</map>' \
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
grep -q 'activity: .*phone' "$OUT/activity.txt" || finish "FAIL the app did not notice that the agent was using the phone"
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

echo "Chat history: an earlier chat can be reopened and continued"
ask() { # <text with %s for spaces>
  tap_on 'class="android.widget.EditText"' || return 1
  sleep 2
  adb shell input text "$1"
  sleep 1
  tap_on 'content-desc="Send"' || return 1
  sleep 3
}
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
tap_on 'content-desc="Open the Ubuntu terminal"' || finish "FAIL Terminal button not found for the screen control test"
sleep 3
# What the user does by hand in Android's Accessibility settings.
adb shell settings put secure enabled_accessibility_services "$PKG/$PKG.ScreenService"
adb shell settings put secure accessibility_enabled 1
sleep 6
adb logcat -d -s Termant:I | grep 'screen control:' > "$OUT/screen-control.txt"
cat "$OUT/screen-control.txt"
grep -q 'screen control: on' "$OUT/screen-control.txt" || finish "FAIL Android did not start the screen control service"
type_line 'sh%st5.sh'
wait_for_end files/rootfs/root/result5.txt 40 || { run_as cat files/rootfs/root/result5.txt > "$OUT/result5.txt"; shot 35-screen-timeout; finish "FAIL screen control test did not finish"; }
run_as cat files/rootfs/root/result5.txt > "$OUT/result5.txt"
cat "$OUT/result5.txt"
sleep 2
shot 35-after-screen-control
grep -q '^screen-exit=0' "$OUT/result5.txt" || finish "FAIL phone screen"
grep -q '^App: ' "$OUT/result5.txt" || finish "FAIL phone screen did not describe the screen"
grep -q '^apps-exit=0' "$OUT/result5.txt" || finish "FAIL phone apps"
grep -q '^app-exit=0' "$OUT/result5.txt" || finish "FAIL phone app"
grep -q '^app-opened=yes' "$OUT/result5.txt" || finish "FAIL phone app did not bring the Settings app to the screen"
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

finish PASS

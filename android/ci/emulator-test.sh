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

# Give the freshly booted emulator a moment to bring its network up.
sleep 20

adb install -g android/Termant-debug.apk || finish "FAIL install"
adb logcat -c
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
[ "$(grep -c 'alarm 07:00 sent to the Clock app' "$OUT/phone-log.txt")" -ge 2 ] || finish "FAIL chat: the alarm did not reach the Clock app"

# A follow-up in the same conversation, and a coding request.
tap_on 'class="android.widget.EditText"' && sleep 2
adb shell input text 'Write%sa%sPython%sscript%sthat%sprints%s6%stimes%s7%sand%srun%sit'
sleep 1
tap_on 'content-desc="Send"'
if wait_for_reply 60; then echo "second chat message answered" > "$OUT/chat2.txt"; else echo "second chat message NOT answered" > "$OUT/chat2.txt"; fi
sleep 2
shot 9-chat-code

finish PASS

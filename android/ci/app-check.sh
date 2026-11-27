#!/usr/bin/env bash
# The app, checked as source: the theme the user picks, the live call that runs
# on the phone, and the rules the guest agent must keep. Nothing here needs a
# device or the internet; the build job compiles the rest.
set -u
failed=0

check() { # <name> <command...>
  local name="$1"; shift
  if "$@" >/dev/null 2>&1; then echo "PASS $name"; else echo "FAIL $name"; failed=$((failed + 1)); fi
}

A=android
check "the ember theme exists" grep -q "EMBER = {" $A/src/com/termant/app/Theme.java
check "the theme can be chosen (English)" grep -q "theme_ember" $A/res/values/strings.xml
check "the theme can be chosen (Hindi)" grep -q "theme_ember" $A/res/values-hi/strings.xml
check "the live call speaks to Gemini" grep -q "bidiGenerateContent" $A/src/com/termant/app/LiveCall.java
check "the live call uses the native audio model" grep -q "native-audio" $A/src/com/termant/app/LiveCall.java
check "the live call can run a command" grep -q "functionDeclarations" $A/src/com/termant/app/LiveCall.java
check "the live call remembers a used-up quota" grep -q "liveBlocked" $A/src/com/termant/app/Config.java
check "the live screen exists" test -f $A/src/com/termant/app/LiveCallView.java
check "the chat has the live button" grep -q "onLive()" $A/src/com/termant/app/ChatView.java
check "the microphone is declared" grep -q "RECORD_AUDIO" $A/AndroidManifest.xml
check "the live strings are in English" grep -q "live_quota" $A/res/values/strings.xml
check "the live strings are in Hindi" grep -q "live_quota" $A/res/values-hi/strings.xml

G=$A/assets/guest/AGENTS.md
check "a picture question asks about smol" grep -q "ask in one line whether to install it" $G
check "the persona is checked before a reply" grep -q "check it before you reply" $G

if [ "$failed" -eq 0 ]; then echo "RESULT: PASS"; else echo "RESULT: FAIL ($failed)"; exit 1; fi

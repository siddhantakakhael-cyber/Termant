#!/usr/bin/env bash
# Build the iOS app, pack it into an IPA, and smoke-test it on the simulator:
# install it, launch it, and check that it stays up for a few seconds.
set -u
# the log folder is made absolute before the cd below, so the workflow's
# "Publish logs" step (which runs from the repo root) finds it
OUT="$(pwd)/${1:-ci-out}"
mkdir -p "$OUT"
cd "$(dirname "$0")/.."   # ios/

step() { echo "$(date '+%H:%M:%S') $*" >> "$OUT/progress.log"; }

finish() {
  step "RESULT: $1"
  echo "RESULT: $1" | tee "$OUT/verdict.txt"
  [ "$1" = PASS ]
  exit $?
}

step "script started; xcodegen: $(command -v xcodegen || echo not-installed)"

# 1. Make the Xcode project from the spec
step "installing xcodegen"
if ! command -v xcodegen >/dev/null 2>&1; then
  brew install xcodegen > "$OUT/brew.log" 2>&1 || { step "brew failed"; cat "$OUT/brew.log"; finish FAIL; }
fi
step "xcodegen: $(xcodegen --version 2>&1 | head -1)"
step "generating the project"
xcodegen generate > "$OUT/xcodegen.log" 2>&1 || { step "xcodegen failed"; cat "$OUT/xcodegen.log"; finish FAIL; }
step "project generated" 

# 2. Build for a phone -> the .app that goes into the IPA
step "building for a phone"
xcodebuild -project Termant.xcodeproj -scheme Termant -configuration Release \
  -destination 'generic/platform=iOS' -derivedDataPath build \
  CODE_SIGNING_ALLOWED=NO CODE_SIGN_IDENTITY= \
  build > "$OUT/build.log" 2>&1
if [ $? -ne 0 ]; then step "build failed"; tail -40 "$OUT/build.log"; finish FAIL; fi
step "build ok" 

APP=build/Build/Products/Release-iphoneos/Termant.app
[ -d "$APP" ] || { echo "no .app was built"; tail -20 "$OUT/build.log"; finish FAIL; }
echo "built: $APP" | tee "$OUT/built.txt"
# the microphone and speech permissions must be declared, or iOS asks nothing and the mic is dead
plutil -p "$APP/Info.plist" | grep -q NSMicrophoneUsageDescription \
  || { echo "NSMicrophoneUsageDescription is missing from the app"; finish FAIL; }
plutil -p "$APP/Info.plist" | grep -q NSSpeechRecognitionUsageDescription \
  || { echo "NSSpeechRecognitionUsageDescription is missing from the app"; finish FAIL; }

# 3. Pack the IPA. It is unsigned on purpose: Sideloadly or AltStore signs it
#    for the user's own phone, the way a personal app is installed.
step "packing the IPA"
rm -rf ipa Termant.ipa
mkdir -p ipa/Payload
cp -R "$APP" ipa/Payload/
( cd ipa && zip -qr Termant.ipa Payload )
mv ipa/Termant.ipa Termant.ipa
ls -la Termant.ipa | tee "$OUT/ipa.txt"
unzip -l Termant.ipa | head -10 >> "$OUT/ipa.txt"
unzip -l Termant.ipa | grep -q 'Payload/Termant.app/Termant$' \
  || { step "the IPA has no executable inside"; finish FAIL; }
SIZE=$(stat -f%z Termant.ipa)
# a three-file app with no pictures is small on purpose; an empty one is not
[ "$SIZE" -gt 20000 ] || { step "the IPA is suspiciously small ($SIZE bytes)"; finish FAIL; }
step "IPA packed: $SIZE bytes"

# 4. Smoke test on the simulator: install, launch, and it must stay up
step "building for the simulator"
xcodebuild -project Termant.xcodeproj -scheme Termant -configuration Debug \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath build-sim \
  CODE_SIGNING_ALLOWED=NO CODE_SIGN_IDENTITY= \
  build > "$OUT/sim-build.log" 2>&1 || { echo "simulator build failed"; tail -40 "$OUT/sim-build.log"; finish FAIL; }
SIMAPP=build-sim/Build/Products/Debug-iphonesimulator/Termant.app
[ -d "$SIMAPP" ] || { echo "no simulator .app"; tail -20 "$OUT/sim-build.log"; finish FAIL; }

step "looking for a simulator"
SIM=$(xcrun simctl list devices available | grep 'iPhone' | grep -o '[0-9A-Fa-f]\{8\}-[0-9A-Fa-f]\{4\}-[0-9A-Fa-f]\{4\}-[0-9A-Fa-f]\{4\}-[0-9A-Fa-f]\{12\}' | head -1)
[ -n "$SIM" ] || { echo "no simulator is available"; xcrun simctl list devices available | tee "$OUT/sims.txt"; finish FAIL; }
echo "simulator: $SIM" | tee "$OUT/sim.txt"
xcrun simctl boot "$SIM" > "$OUT/boot.log" 2>&1 || true
xcrun simctl bootstatus "$SIM" -b > "$OUT/bootstatus.log" 2>&1 || true
xcrun simctl install "$SIM" "$SIMAPP" > "$OUT/install.log" 2>&1 || { cat "$OUT/install.log"; finish FAIL; }
step "launching on the simulator"
LAUNCH=$(xcrun simctl launch "$SIM" in.termant.app 2>&1)
echo "$LAUNCH" | tee "$OUT/launch.txt"
PID=$(echo "$LAUNCH" | grep -o '[0-9]\{1,\}$' | head -1)
if [ -z "$PID" ]; then echo "the launch did not give a process number"; finish FAIL; fi
sleep 6
if kill -0 "$PID" 2>/dev/null; then
  echo "the app is still up after 6 s (pid $PID)" | tee -a "$OUT/launch.txt"
else
  echo "the app died soon after the launch"; finish FAIL
fi
xcrun simctl terminate "$SIM" in.termant.app 2>/dev/null || true
finish PASS

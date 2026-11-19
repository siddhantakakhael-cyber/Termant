#!/bin/bash
# Runs the tests on an iPhone simulator: the unit tests and the tests of the screens.
# It starts what they talk to: a stand-in model service (fake-llm.js) and the real
# Termant terminal server (../server.js, needs "npm ci" in the repository root once).
#   ios/ci/test.sh [output folder]
set -uo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
OUT="${1:-$HERE/ci-out}"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
cd "$HERE"

node ci/fake-llm.js 8787 "$OUT/fake-llm.jsonl" > "$OUT/fake-llm.out" 2>&1 &
LLM=$!
( cd "$ROOT" && TERMANT_PASSWORD=ci-password PORT=3111 HOST=127.0.0.1 exec node server.js ) > "$OUT/server.log" 2>&1 &
SERVER=$!
trap 'kill $LLM $SERVER 2>/dev/null' EXIT
for url in http://127.0.0.1:8787/healthz http://127.0.0.1:3111/healthz; do
  for i in $(seq 1 30); do
    curl -fs "$url" > /dev/null && break
    sleep 1
  done
  if ! curl -fs "$url" > /dev/null; then
    echo "RESULT: FAIL ($url did not start)"
    cat "$OUT/fake-llm.out" "$OUT/server.log"
    exit 1
  fi
done

[ -d Termant.xcodeproj ] || xcodegen generate || exit 1

# An iPhone 16 on the newest iOS 18 that is installed, or else any iPhone.
UDID="$(xcrun simctl list devices available -j | python3 -c '
import json, re, sys
want = sys.argv[1]
best = None
for runtime, devices in json.load(sys.stdin)["devices"].items():
    m = re.search(r"iOS-(\d+)-(\d+)", runtime)
    if not m:
        continue
    version = (int(m.group(1)), int(m.group(2)))
    for d in devices:
        if not d.get("isAvailable") or "iPhone" not in d["name"]:
            continue
        score = (d["name"] == want, version[0] == 18, version)
        if best is None or score > best[0]:
            best = (score, d["udid"], d["name"], runtime)
if best:
    sys.stderr.write("simulator: %s on %s\n" % (best[2], best[3]))
    print(best[1])
' "${TERMANT_SIMULATOR:-iPhone 16}" 2> "$OUT/simulator.txt")"
cat "$OUT/simulator.txt"
if [ -z "$UDID" ]; then
  echo "RESULT: FAIL (no iPhone simulator)"
  xcrun simctl list devices available
  exit 1
fi
# the keyboard on the screen, as on a phone
defaults write com.apple.iphonesimulator ConnectHardwareKeyboard -bool false
xcrun simctl boot "$UDID" 2> /dev/null
xcrun simctl bootstatus "$UDID" -b > /dev/null

rm -rf "$OUT/result.xcresult"
xcodebuild test -project Termant.xcodeproj -scheme Termant -destination "id=$UDID" \
  -derivedDataPath "$HERE/.derived" -resultBundlePath "$OUT/result.xcresult" \
  > "$OUT/xcodebuild-test.log" 2>&1
status=$?

grep -E "^Test Case .*(passed|failed)|error:|Executed [0-9]+ tests|\*\* TEST|XCTAssert|: Fatal error" \
  "$OUT/xcodebuild-test.log" > "$OUT/tests.txt"

# the pictures the tests took, by the names they gave them
if [ -d "$OUT/result.xcresult" ]; then
  rm -rf "$OUT/attachments" "$OUT/screens"
  mkdir -p "$OUT/screens"
  xcrun xcresulttool export attachments --path "$OUT/result.xcresult" --output-path "$OUT/attachments" > /dev/null 2>&1
  python3 - "$OUT/attachments" "$OUT/screens" <<'PY'
import json, os, re, shutil, sys
src, dst = sys.argv[1], sys.argv[2]
try:
    manifest = json.load(open(os.path.join(src, "manifest.json")))
except Exception as e:
    print("no attachments:", e)
    manifest = []
for test in manifest:
    for a in test.get("attachments", []):
        name = a.get("suggestedHumanReadableName") or a.get("exportedFileName")
        file = os.path.join(src, a.get("exportedFileName", ""))
        if not os.path.isfile(file) or not name.lower().endswith((".png", ".jpg", ".jpeg", ".heic")):
            continue
        name = re.sub(r"_\d+_[0-9A-F-]{36}", "", name)
        shutil.copy(file, os.path.join(dst, name))
PY
  for f in "$OUT"/screens/*; do
    [ -f "$f" ] && sips -Z 1400 "$f" > /dev/null 2>&1
  done
  rm -rf "$OUT/attachments" "$OUT/result.xcresult"
fi
ls "$OUT/screens" 2> /dev/null | sed 's/^/screen: /'

passed=$(grep -c "^Test Case .* passed" "$OUT/tests.txt")
failed=$(grep -c "^Test Case .* failed" "$OUT/tests.txt")
echo "tests passed: $passed, failed: $failed"
if [ $status -eq 0 ] && [ "$passed" -gt 0 ] && [ "$failed" -eq 0 ]; then
  echo "RESULT: PASS"
  exit 0
fi
grep -vE "^Test Case .* passed" "$OUT/tests.txt" | head -80
echo "RESULT: FAIL (xcodebuild test ended with $status)"
exit 1

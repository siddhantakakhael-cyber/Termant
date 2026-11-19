#!/bin/bash
# Builds the app for a real iPhone without signing it and packs it as Termant.ipa.
# SideStore, AltStore or Sideloadly sign it with the owner's Apple ID on install.
#   ios/ci/package.sh [output folder]
set -uo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$HERE/ci-out}"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
cd "$HERE"

[ -d Termant.xcodeproj ] || xcodegen generate || exit 1
xcodebuild build -project Termant.xcodeproj -scheme Termant -configuration Release \
  -sdk iphoneos -destination 'generic/platform=iOS' -derivedDataPath "$HERE/.derived-device" \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" \
  > "$OUT/xcodebuild-device.log" 2>&1
status=$?
grep -E "error:|warning: .*(deprecated|unused|never)|\*\* BUILD" "$OUT/xcodebuild-device.log" | sort | uniq -c > "$OUT/build.txt"
if [ $status -ne 0 ]; then
  grep -E "error:" "$OUT/xcodebuild-device.log" | sort -u | head -60
  echo "RESULT: FAIL (the build for the iPhone ended with $status)"
  exit 1
fi

APP="$HERE/.derived-device/Build/Products/Release-iphoneos/Termant.app"
STAGE="$(mktemp -d)"
mkdir -p "$STAGE/Payload"
cp -R "$APP" "$STAGE/Payload/"
rm -f "$HERE/Termant.ipa"
( cd "$STAGE" && zip -qry "$HERE/Termant.ipa" Payload )

# what is inside
{
  echo "size: $(stat -f %z "$HERE/Termant.ipa") bytes"
  for key in CFBundleIdentifier CFBundleShortVersionString CFBundleVersion MinimumOSVersion CFBundleExecutable; do
    echo "$key: $(/usr/libexec/PlistBuddy -c "Print :$key" "$STAGE/Payload/Termant.app/Info.plist")"
  done
  echo "architectures: $(lipo -archs "$STAGE/Payload/Termant.app/Termant")"
  echo "platform: $(vtool -show-build "$STAGE/Payload/Termant.app/Termant" | awk '/platform/ {print $2}' | sort -u | tr '\n' ' ')"
  echo "signature: $(codesign -dv "$STAGE/Payload/Termant.app" 2>&1 | head -3 | tr '\n' ';')"
  echo "files:"
  ( cd "$STAGE" && find Payload -type f | sort | sed 's/^/  /' )
} > "$OUT/ipa.txt" 2>&1
cat "$OUT/ipa.txt"
rm -rf "$STAGE"

ok=1
grep -q "^CFBundleIdentifier: com.termant.app$" "$OUT/ipa.txt" || ok=0
grep -q "^architectures: arm64$" "$OUT/ipa.txt" || ok=0
grep -qi "^platform: .*ios" "$OUT/ipa.txt" || ok=0
grep -q "Assets.car" "$OUT/ipa.txt" || ok=0
if [ $ok -ne 1 ]; then
  echo "RESULT: FAIL (the IPA is not what it should be)"
  exit 1
fi
echo "RESULT: PASS"

#!/usr/bin/env bash
# Builds Termant.apk with plain SDK tools (no Gradle).
#
# With the Android SDK installed, set ANDROID_HOME and run:  ./build.sh
# Each tool can be overridden: JAVAC, D8, AAPT2, ANDROID_JAR.
# Without zipalign/apksigner, tools/pack_sign.py is used instead.
set -euo pipefail
cd "$(dirname "$0")"

API=34
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -n "$SDK" ] && [ -d "$SDK/build-tools" ]; then
  BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
  PATH="$BT:$PATH"
  if [ -z "${ANDROID_JAR:-}" ]; then
    ANDROID_JAR="$SDK/platforms/android-$API/android.jar"
    [ -f "$ANDROID_JAR" ] || ANDROID_JAR="$(ls "$SDK"/platforms/android-*/android.jar | sort -V | tail -1)"
  fi
fi
: "${ANDROID_JAR:?Set ANDROID_HOME or ANDROID_JAR}"
JAVAC="${JAVAC:-javac}"
D8="${D8:-d8}"
AAPT2="${AAPT2:-aapt2}"

OUT=out
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "== resources"
$AAPT2 compile --dir res -o "$OUT/res.zip"
$AAPT2 link -o "$OUT/unsigned.apk" -I "$ANDROID_JAR" --manifest AndroidManifest.xml \
  --java "$OUT/gen" "$OUT/res.zip"

echo "== java"
$JAVAC -source 8 -target 8 -nowarn -bootclasspath "$ANDROID_JAR" -d "$OUT/classes" \
  $(find src "$OUT/gen" -name '*.java')

echo "== dex"
$D8 --release --min-api 24 --lib "$ANDROID_JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
(cd "$OUT/dex" && zip -q -j ../unsigned.apk classes.dex)

echo "== sign"
# The key is created once and reused. Keep keystore/ safe: an APK signed with
# a different key cannot update an installed one (you would have to uninstall).
if [ ! -f keystore/key.pk8 ]; then
  mkdir -p keystore
  openssl req -x509 -newkey rsa:2048 -nodes -days 10950 -subj "/CN=Termant" \
    -keyout keystore/key.pem -out keystore/cert.pem 2>/dev/null
  openssl pkcs8 -topk8 -nocrypt -inform PEM -outform DER -in keystore/key.pem -out keystore/key.pk8
  rm keystore/key.pem
fi

if command -v apksigner >/dev/null && command -v zipalign >/dev/null; then
  zipalign -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
  apksigner sign --key keystore/key.pk8 --cert keystore/cert.pem --out Termant.apk "$OUT/aligned.apk"
  apksigner verify Termant.apk
else
  python3 tools/pack_sign.py "$OUT/unsigned.apk" keystore/key.pk8 keystore/cert.pem Termant.apk
fi

rm -f Termant.apk.idsig
ls -l Termant.apk

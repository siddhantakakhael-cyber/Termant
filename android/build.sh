#!/usr/bin/env bash
# Builds the Termant APKs with plain Android SDK tools (no Gradle, no Maven).
#
# Needs: Android SDK (ANDROID_HOME) with build-tools, a platform and the NDK;
#        JDK 17; python3; dpkg-deb; zip.
# Makes: Termant.apk        release build
#        Termant-debug.apk  same code, debuggable (used by the emulator test)
#
# SIGN_KEY / SIGN_CERT choose the signing key (default: the public test key).
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:?Set ANDROID_HOME to the Android SDK}}"
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
ANDROID_JAR="${ANDROID_JAR:-$(ls "$SDK"/platforms/android-*/android.jar | sort -V | tail -1)}"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-$(ls -d "$SDK"/ndk/* | sort -V | tail -1)}}"
SIGN_KEY="${SIGN_KEY:-keystore/testkey.pk8}"
SIGN_CERT="${SIGN_CERT:-keystore/testkey.pem}"
ABIS="arm64-v8a armeabi-v7a x86_64"
echo "build-tools: $BT"
echo "platform:    $ANDROID_JAR"
echo "ndk:         $NDK"

OUT="$PWD/out"
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/apk/lib"

echo "== proot (from Termux packages)"
python3 tools/fetch_proot.py "$OUT/apk/lib"

echo "== terminal pty helper (NDK)"
"$NDK/ndk-build" -C "$OUT" \
  NDK_PROJECT_PATH="$OUT" \
  APP_BUILD_SCRIPT="$PWD/vendor/termux/jni/Android.mk" \
  APP_ABI="$ABIS" APP_PLATFORM=android-24 \
  NDK_OUT="$OUT/obj" NDK_LIBS_OUT="$OUT/apk/lib" >/dev/null
for abi in $ABIS; do
  for f in libproot.so libproot-loader.so libtalloc.so libtermux.so; do
    [ -f "$OUT/apk/lib/$abi/$f" ] || { echo "missing lib/$abi/$f"; exit 1; }
  done
done
(cd "$OUT/apk" && find lib -type f | sort | xargs ls -l)

echo "== resources"
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"

echo "== java"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$ANDROID_JAR" --manifest AndroidManifest.xml \
  --java "$OUT/gen" --extra-packages com.termux.view "$OUT/res.zip"
javac -source 8 -target 8 -nowarn -Xlint:-options \
  -bootclasspath "$ANDROID_JAR:$BT/core-lambda-stubs.jar" -d "$OUT/classes" \
  $(find src vendor/termux/java stubs "$OUT/gen" -name '*.java')

echo "== dex"
"$BT/d8" --release --min-api 24 --lib "$ANDROID_JAR" --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')
cp "$OUT/dex/classes.dex" "$OUT/apk/"

package() { # <name> [extra aapt2 link flags]
  local name="$1"; shift
  "$BT/aapt2" link -o "$OUT/$name-unsigned.apk" -I "$ANDROID_JAR" \
    --manifest AndroidManifest.xml --extra-packages com.termux.view "$@" "$OUT/res.zip"
  (cd "$OUT/apk" && zip -q -r "$OUT/$name-unsigned.apk" classes.dex lib)
  "$BT/zipalign" -f -p 4 "$OUT/$name-unsigned.apk" "$OUT/$name-aligned.apk"
  "$BT/apksigner" sign --key "$SIGN_KEY" --cert "$SIGN_CERT" --out "$name.apk" "$OUT/$name-aligned.apk"
  "$BT/apksigner" verify "$name.apk"
  rm -f "$name.apk.idsig"
}

echo "== package and sign"
package Termant
package Termant-debug --debug-mode
ls -l Termant.apk Termant-debug.apk

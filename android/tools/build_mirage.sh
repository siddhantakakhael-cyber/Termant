#!/usr/bin/env bash
# Builds the reader of pictures (mirage/reader.c) for the phone: one small
# program per kind of processor, with the Cactus engine linked in.
#
#   build_mirage.sh <lib dir> [work dir]
#
# Writes <lib dir>/<android abi>/libmirage.so for arm64-v8a, x86_64 and armeabi-v7a. The name is the one Android
# accepts for bundled native files; it is a program, not a library.
#
# - The engine (Cactus 4.1.4, Apache 2.0) comes as a ready-built library from
#   a fixed revision on Hugging Face and is checked against its SHA-256.
# - It is linked with Zig's C++ compiler (from PyPI), because the engine needs
#   LLVM's C++ library, and against musl, so the program carries everything it
#   needs and runs on Android as it is.
#
# Needs: python3 with pip, curl, sha256sum.
set -euo pipefail
cd "$(dirname "$0")/.."
LIBS="$(mkdir -p "$1" && cd "$1" && pwd)"
WORK="${2:-out/mirage}"
mkdir -p "$WORK"
ZIG_VERSION=0.17.1
BASE=https://huggingface.co/Cactus-Compute/cactus/resolve/f4ab2741203a282c6e7b89983de2b5e5b2e805a8/releases/4.1.4

python3 -m pip install --quiet --disable-pip-version-check --target "$WORK/zig" "ziglang==$ZIG_VERSION"
zig() { PYTHONPATH="$WORK/zig" python3 -m ziglang "$@"; }

build() { # <android abi> <engine folder> <sha256 of its libcactus.a> <zig target> [compiler flags]
  local abi="$1" platform="$2" sha="$3" target="$4"; shift 4
  local lib="$WORK/$platform-libcactus.a"
  curl -sSL --fail --retry 3 -o "$lib" "$BASE/$platform/libcactus.a"
  echo "$sha  $lib" | sha256sum --check --quiet
  mkdir -p "$LIBS/$abi"
  # musl gives a thread 128 KB of stack unless told otherwise; the engine was built for 8 MB
  zig c++ -target "$target" "$@" -O2 -static -s -Wl,-z,stack-size=8388608 -x c mirage/reader.c -x none "$lib" -o "$LIBS/$abi/libmirage.so"
  echo "mirage reader, $abi: $(wc -c < "$LIBS/$abi/libmirage.so") bytes"
}
build arm64-v8a linux-arm64  31ee6ce7b0b1390fc8ffa6d242d2aa3cdae18b405d7d885d483cd79258016dd0 aarch64-linux-musl
build x86_64    linux-x86_64 a977d506ed9545369628cfe9fbf87747d73ab766ac376d8c1c098146485327c4 x86_64-linux-musl
# 32-bit ARM: the engine's own 32-bit build crashes when linked this way (tried on an ARM machine), so a
# phone with a 32-bit Android gets the 64-bit program. It runs there only if the phone's kernel is 64-bit;
# otherwise the app says that this phone cannot run the reader.
mkdir -p "$LIBS/armeabi-v7a"
cp "$LIBS/arm64-v8a/libmirage.so" "$LIBS/armeabi-v7a/libmirage.so"
echo "mirage reader, armeabi-v7a: the 64-bit program"

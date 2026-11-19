#!/bin/bash
# Tries the shell of the iPhone app on a Linux computer: the same engine, the same root file
# system and the same code of ios/Shell, without an iPhone. Ends with "RESULT: PASS" or "RESULT: FAIL".
# Needs clang, lld, meson, ninja and the headers of sqlite3 and zlib.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
"$here/build.sh" linux
work=$here/work/linux
${CC:-clang} -g -I "$here/../Shell" "$here/../Shell/selftest.c" -o "$work/selftest" \
    "$work/libtermantshell.a" "$work/libish.a" "$work/libish_emu.a" "$work/libfakefs.a" \
    -lsqlite3 -lz -lpthread -lm -ldl
rm -rf "$work/try"
mkdir -p "$work/try"
"$work/selftest" "$here/rootfs/alpine.tar.gz" "$work/try"

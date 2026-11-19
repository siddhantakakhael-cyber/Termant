#!/bin/bash
# Fetches what the shell is built from, at the versions in versions.sh:
# the iSH sources into ios/ish/src and the Alpine root file system into ios/ish/rootfs.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
. "$here/versions.sh"

src=$here/src
if [ "$(git -C "$src" rev-parse HEAD 2>/dev/null || true)" != "$ISH_COMMIT" ] || [ "$(git -C "$src" rev-parse --show-toplevel 2>/dev/null || true)" != "$src" ]; then
    rm -rf "$src"
    mkdir -p "$src"
    git -C "$src" init -q
    git -C "$src" remote add origin "$ISH_REPO"
    git -C "$src" fetch -q --depth 1 origin "$ISH_COMMIT"
    git -C "$src" -c advice.detachedHead=false checkout -q FETCH_HEAD
fi
echo "iSH $(git -C "$src" rev-parse --short HEAD) in $src"

sum() {
    if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}
rootfs=$here/rootfs/alpine.tar.gz
mkdir -p "$here/rootfs"
if [ ! -f "$rootfs" ] || [ "$(sum "$rootfs")" != "$ROOTFS_SHA256" ]; then
    curl -fsSL --retry 3 -o "$rootfs.part" "$ROOTFS_URL"
    if [ "$(sum "$rootfs.part")" != "$ROOTFS_SHA256" ]; then
        echo "$ROOTFS_URL is not the file that was expected (its checksum differs)" >&2
        rm -f "$rootfs.part"
        exit 1
    fi
    mv "$rootfs.part" "$rootfs"
fi
echo "$ROOTFS_NAME in $rootfs ($(wc -c < "$rootfs" | tr -d ' ') bytes)"

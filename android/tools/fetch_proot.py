#!/usr/bin/env python3
"""Fetch PRoot and libtalloc for Android from the Termux package repository.

    fetch_proot.py <output dir>

Termux maintains the PRoot build that is known to run Ubuntu on Android. The
packages are looked up in the repository index, checked against the SHA-256
listed there, and unpacked to <output dir>/<android abi>/ under the names
Android accepts for bundled native files (lib*.so):

    libproot.so           the proot program
    libproot-loader.so    its program loader
    libproot-loader32.so  loader for 32-bit programs (64-bit ARM only)
    libtalloc.so          the library proot links against (libtalloc.so.2)
"""
import glob
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.request

REPO = "https://packages.termux.dev/apt/termux-main"
ABIS = {"aarch64": "arm64-v8a", "arm": "armeabi-v7a", "x86_64": "x86_64"}
PREFIX = "data/data/com.termux/files/usr"


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "termant-build"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()


def stanzas(text):
    for block in text.split("\n\n"):
        fields = {}
        for line in block.splitlines():
            if line[:1] not in (" ", "\t") and ":" in line:
                key, value = line.split(":", 1)
                fields[key] = value.strip()
        if fields:
            yield fields


def fetch_package(index, name, workdir):
    pkg = next(s for s in index if s.get("Package") == name)
    data = get(f"{REPO}/{pkg['Filename']}")
    if hashlib.sha256(data).hexdigest() != pkg["SHA256"]:
        raise SystemExit(f"checksum mismatch for {pkg['Filename']}")
    deb = os.path.join(workdir, name + ".deb")
    with open(deb, "wb") as f:
        f.write(data)
    out = os.path.join(workdir, name)
    subprocess.run(["dpkg-deb", "-x", deb, out], check=True)
    print(f"  {name} {pkg['Version']} ({pkg['Architecture']})")
    return os.path.join(out, PREFIX)


def main(dest):
    for arch, abi in ABIS.items():
        print(f"{abi}:")
        index = list(stanzas(get(f"{REPO}/dists/stable/main/binary-{arch}/Packages").decode()))
        target = os.path.join(dest, abi)
        os.makedirs(target, exist_ok=True)
        with tempfile.TemporaryDirectory() as work:
            proot = fetch_package(index, "proot", work)
            talloc = fetch_package(index, "libtalloc", work)
            shutil.copy(os.path.join(proot, "bin/proot"), os.path.join(target, "libproot.so"))
            shutil.copy(os.path.join(proot, "libexec/proot/loader"),
                        os.path.join(target, "libproot-loader.so"))
            loader32 = os.path.join(proot, "libexec/proot/loader32")
            if os.path.exists(loader32):
                shutil.copy(loader32, os.path.join(target, "libproot-loader32.so"))
            libs = [p for p in glob.glob(os.path.join(talloc, "lib/libtalloc.so*"))
                    if not os.path.islink(p)]
            if len(libs) != 1:
                raise SystemExit(f"expected one libtalloc, found {libs}")
            shutil.copy(libs[0], os.path.join(target, "libtalloc.so"))
        for f in sorted(os.listdir(target)):
            os.chmod(os.path.join(target, f), 0o755)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    main(sys.argv[1])

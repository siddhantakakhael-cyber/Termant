#!/bin/bash
# Builds the shell for one platform:
#   ios/ish/build.sh iphoneos          for an iPhone or iPad
#   ios/ish/build.sh iphonesimulator   for the simulator of this Mac
#   ios/ish/build.sh linux             for this Linux computer (to try it; see selftest.sh)
# The result is four libraries in ios/ish/lib/<platform>: iSH's libish.a, libish_emu.a and
# libfakefs.a, and libtermantshell.a with the code of ios/Shell that the app talks to.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
. "$here/versions.sh"
platform=${1:?"which platform: iphoneos, iphonesimulator or linux"}
src=$here/src
work=$here/work/$platform
lib=$here/lib/$platform

[ -f "$src/meson.build" ] && [ -f "$here/rootfs/alpine.tar.gz" ] || "$here/fetch.sh"

tools=$here/work/tools
if [ "$platform" != linux ] || ! command -v meson >/dev/null || ! command -v ninja >/dev/null; then
    if [ ! -x "$tools/venv/bin/meson" ] || [ ! -x "$tools/venv/bin/ninja" ]; then
        # meson needs Python 3.10 or newer; the python3 of Xcode's command line tools is older
        python=
        for candidate in python3 python3.14 python3.13 python3.12 python3.11 python3.10; do
            if command -v "$candidate" >/dev/null && "$candidate" -c 'import sys; sys.exit(sys.version_info < (3, 10))'; then
                python=$candidate
                break
            fi
        done
        [ -n "$python" ] || { echo "Python 3.10 or newer is needed (for meson)" >&2; exit 1; }
        "$python" -m venv "$tools/venv"
        "$tools/venv/bin/pip" install -q "meson==$MESON_VERSION" "ninja==$NINJA_VERSION"
    fi
    PATH=$tools/venv/bin:$PATH
fi

mkdir -p "$work" "$lib"
# iSH's files as they came (the iPhone builds change one line, below)
git -C "$src" checkout -q -- vdso/meson.build
setup=()
case "$platform" in
linux)
    # needs clang and lld (for the vdso), and the headers of sqlite3 and zlib
    export CC=${CC:-clang}
    ar=${AR:-ar}
    ;;
iphoneos|iphonesimulator)
    # The vdso is a piece of 32-bit Linux code. iSH's build looks for a clang that can link such a thing
    # (Xcode's cannot); zig brings one, so iSH's build is pointed at a "clang" that hands over to it.
    if ! "$tools/venv/bin/python" -c 'import ziglang' 2>/dev/null; then
        "$tools/venv/bin/pip" install -q "ziglang==$ZIG_VERSION"
    fi
    mkdir -p "$tools/vdso-cc"
    cat > "$tools/vdso-cc/clang" <<WRAPPER
#!/bin/bash
args=()
for a in "\$@"; do
    case "\$a" in
        i386-linux) a=x86-linux-musl ;;   # the same target, in zig's words
        -fuse-ld=lld) continue ;;         # zig links with lld anyway
    esac
    args+=("\$a")
done
# zig adds checks that call for help the vdso does not have
exec "$tools/venv/bin/python" -m ziglang cc -fno-sanitize=undefined "\${args[@]}"
WRAPPER
    chmod +x "$tools/vdso-cc/clang"
    sed -i.orig "s|^clang = find_program(.*|clang = find_program('$tools/vdso-cc/clang')|" "$src/vdso/meson.build"
    grep -q "vdso-cc/clang" "$src/vdso/meson.build"

    arch=$(uname -m)
    [ "$platform" = iphoneos ] && arch=arm64
    target=$arch-apple-ios$IOS_MINIMUM
    [ "$platform" = iphonesimulator ] && target=$target-simulator
    sdk=$(xcrun --sdk "$platform" --show-sdk-path)
    cc=$(xcrun -f clang)
    ar=$(xcrun -f ar)
    family=$arch
    [ "$arch" = arm64 ] && family=aarch64
    cat > "$work/cross.txt" <<CROSS
[binaries]
c = '$cc'
ar = '$ar'

[host_machine]
system = 'darwin'
cpu_family = '$family'
cpu = '$family'
endian = 'little'

[built-in options]
c_args = ['-target', '$target', '-isysroot', '$sdk']
c_link_args = ['-target', '$target', '-isysroot', '$sdk']

[properties]
needs_exe_wrapper = true
CROSS
    export CC_FOR_BUILD=$cc
    setup=(--cross-file "$work/cross.txt")
    ;;
*)
    echo "unknown platform: $platform" >&2
    exit 2
    ;;
esac

if [ ! -f "$work/build.ninja" ]; then
    # the settings of iSH's own release build: optimised, with its checks left in
    meson setup "$work" "$src" "${setup[@]}" --buildtype debugoptimized -Db_ndebug=false -Dlog_handler=dprintf
fi
ninja -C "$work" libish.a libish_emu.a libfakefs.a

# The code of ios/Shell uses iSH's headers, so it is compiled exactly as iSH's own files were:
# with the command the build recorded for kernel/init.c, pointed at another file.
objects=()
for name in shell_engine shell_import; do
    python3 - "$work" "$here/../Shell" "$name" <<'PY'
import json, os, shlex, subprocess, sys
work, shell, name = sys.argv[1:4]
with open(os.path.join(work, 'compile_commands.json')) as f:
    entry = next(c for c in json.load(f) if c['file'].endswith('kernel/init.c'))
words = shlex.split(entry['command'])
out = []
skip = 0
for w in words:
    if skip:
        skip -= 1
    elif w in ('-MQ', '-MF', '-o'):
        skip = 1
    elif w in ('-MD', '-c') or w.endswith('kernel/init.c'):
        pass
    else:
        out.append(w)
out += ['-I', shell, '-c', os.path.join(shell, name + '.c'), '-o', os.path.join(work, name + '.o')]
sys.exit(subprocess.call(out, cwd=entry['directory']))
PY
    objects+=("$work/$name.o")
done
rm -f "$work/libtermantshell.a"
"$ar" rcs "$work/libtermantshell.a" "${objects[@]}"

if [ "$platform" = linux ]; then
    # meson makes "thin" libraries on Linux, which only work where they were built
    echo "built in $work"
else
    cp "$work/libish.a" "$work/libish_emu.a" "$work/libfakefs.a" "$work/libtermantshell.a" "$lib/"
    ls -l "$lib"
fi

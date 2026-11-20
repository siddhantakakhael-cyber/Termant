#!/bin/bash
# Temporary probe: can Mirage be built into a small static Linux program for the Android app?
out="$1"; mkdir -p "$out" work; exec > >(tee "$out/probe.log") 2>&1
base=https://huggingface.co/Cactus-Compute/cactus/resolve/f4ab2741203a282c6e7b89983de2b5e5b2e805a8/releases/4.1.4
get() { curl -sSL --fail -o "work/$2" "$1" && echo "$2: $(stat -c %s "work/$2") bytes, sha256 $(sha256sum "work/$2" | cut -d' ' -f1)"; }
for p in linux-x86_64 linux-arm64 linux-armv7; do
  mkdir -p work/$p
  get $base/$p/cactus $p/cactus; get $base/$p/libcactus.a $p/libcactus.a; get $base/$p/cactus.h $p/cactus.h
done
get https://huggingface.co/Cactus-Compute/mirage/resolve/76c1e9e53381aca8fc3843c636216c96ff220122/mirage.cact mirage.cact
echo "== the vendor's runner"
chmod +x work/*/cactus
file work/*/cactus; ldd work/linux-x86_64/cactus
work/linux-x86_64/cactus --help 2>&1 | head -60
strings work/linux-x86_64/cactus | grep -E '^--[a-z]' | sort -u | head -60
echo "== what the library needs"
for p in linux-x86_64 linux-arm64 linux-armv7; do
  echo "-- $p: $(ar t work/$p/libcactus.a | wc -l) objects: $(ar t work/$p/libcactus.a | head -8 | tr '\n' ' ')"
  nm -u work/$p/libcactus.a 2>/dev/null | awk '{print $2}' | sort -u > "$out/undefined-$p.txt"
  wc -l < "$out/undefined-$p.txt"; grep -c '_ZNSt\|_ZSt\|__cxa\|__gxx' "$out/undefined-$p.txt"; grep -E 'dlopen|omp|GLIBC|pthread_create|__cxa_throw|_ZNSt3__1|_ZNSt7__cxx11' "$out/undefined-$p.txt" | head -12
done
echo "== build (zig brings the C++ library the engine was built with, and a libc that needs nothing on the phone)"
sudo apt-get update -qq >/dev/null; sudo apt-get install -y -qq qemu-user-static 2>&1 | tail -2
pip install -q "ziglang==${ZIG:-0.17.1}" pillow; python3 -m ziglang version
build() { # <platform> <zig target> [more flags]
  local p="$1" t="$2"; shift 2
  echo "\$ zig c++ -target $t $*"
  if python3 -m ziglang c++ -target "$t" "$@" -O2 -static -s -x c .github/probe/reader.c -x none -Iwork/$p work/$p/libcactus.a -o work/reader-$p 2> work/err.txt; then
    echo "built: $(stat -c %s work/reader-$p) bytes; $(file -b work/reader-$p)"
  else head -20 work/err.txt | cut -c1-300; fi
}
build linux-x86_64 x86_64-linux-musl
build linux-arm64 aarch64-linux-musl
build linux-armv7 arm-linux-musleabihf -mcpu=generic+v7a
[ -f work/reader-linux-armv7 ] || build linux-armv7 arm-linux-musleabihf
echo "== read"
pip install -q pillow
python3 .github/probe/tiles.py work > work/names.txt
while read -r name; do
  echo "=== $name"
  work/reader-linux-x86_64 work/mirage.cact < work/$name.req 2> work/time.txt | cut -c1-3000; grep ' in ' work/time.txt | tr '\n' ' '; echo
done < work/names.txt
echo "== the arm programs, emulated (slow): do they start and read?"
for p in linux-arm64 linux-armv7; do
  q=qemu-aarch64-static; [ $p = linux-armv7 ] && q=qemu-arm-static
  echo "-- $p"; timeout 600 $q work/reader-$p work/mirage.cact < work/question-halves.req 2>&1 | cut -c1-1500
done
echo "== options"
for o in '{"sections":["text"],"text_conf":0.2,"max_text_lines":100}' '{"sections":["text","objects"]}'; do
  echo "--- $o"; work/reader-linux-x86_64 work/mirage.cact "$o" < work/question-halves.req 2>&1 | cut -c1-2500
done
echo done

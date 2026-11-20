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
echo "== build"
sudo apt-get update -qq >/dev/null; sudo apt-get install -y -qq gcc-aarch64-linux-gnu g++-aarch64-linux-gnu gcc-arm-linux-gnueabihf g++-arm-linux-gnueabihf qemu-user-static >/dev/null 2>&1
build() { # <platform> <compiler prefix>
  local cc="${2}gcc" cxx="${2}g++"
  for how in "$cc -O2 -static -o work/reader-$1 .github/probe/reader.c -Iwork/$1 work/$1/libcactus.a -lm -lpthread" \
             "$cxx -O2 -static -x c .github/probe/reader.c -x none -Iwork/$1 work/$1/libcactus.a -o work/reader-$1 -lm -lpthread"; do
    echo "\$ $how"
    if $how 2> work/err.txt; then echo "built: $(stat -c %s work/reader-$1) bytes; $(file -b work/reader-$1)"; ${2}strip work/reader-$1; echo "stripped: $(stat -c %s work/reader-$1)"; return 0; fi
    head -12 work/err.txt
  done
  return 1
}
build linux-x86_64 ""; build linux-arm64 aarch64-linux-gnu-; build linux-armv7 arm-linux-gnueabihf-
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

#!/bin/bash
# CI only. Stands in front of OpenCode to imitate its "Free usage limit
# reached" refusal, which cannot be caused on purpose. While the file
# ci-limit exists:
#   - a run with one of OpenCode's own models does the first half of the work
#     once (while ci-limit-first exists), then ends the way OpenCode ends when
#     the free limit is reached: with that error line and exit code 1;
#   - a run with a model named ci/... stands for "a model of another service":
#     it is answered by a real model.
# Every run is noted in ci-calls.txt. Everything else goes straight to OpenCode.
real="$HOME/.opencode/bin/opencode-real"
dir="$HOME/.cache/termant"
[ "$1" = run ] && [ -e "$dir/ci-limit" ] || exec "$real" "$@"

model=; prev=
for a in "$@"; do [ "$prev" = --model ] && model=$a; prev=$a; done
echo "${model:-automatic}" >> "$dir/ci-calls.txt"

case "$model" in
  ""|opencode/*) ;; # OpenCode's own free models: handled below
  ci/*)
    args=(); prev=
    for a in "$@"; do
      if [ "$prev" = --model ]; then args+=("opencode/big-pickle"); else args+=("$a"); fi
      prev=$a
    done
    exec "$real" "${args[@]}" ;;
  *) exec "$real" "$@" ;;
esac

if [ -e "$dir/ci-limit-first" ]; then
  rm -f "$dir/ci-limit-first"
  "$real" "${@:1:$#-1}" "Run this one command: touch /root/limit-one   Then reply with the single word FIRST and stop."
fi
echo "Error: Free usage limit reached. Connect a provider or subscribe to OpenCode Go to keep going, or switch models."
exit 1

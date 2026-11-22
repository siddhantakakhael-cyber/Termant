#!/bin/bash
# Asks the two real free picture services that `draw` uses (android/assets/guest/kit) and writes
# down what they answered. They are other people's free services and change without notice, so
# this never fails a build: it is a record of whether they drew today, and how long it took.
#   draw-live.sh FOLDER_WITH_kit OUT_FOLDER
kit=$1/kit
out=$2
mkdir -p "$out"
work=$(mktemp -d)
mkdir -p "$work/bin" "$work/home"
cp "$kit" "$work/bin/draw"; chmod 755 "$work/bin/draw"
export PATH="$work/bin:$PATH" TERMANT_KIT_DIR="$work/kit" HOME="$work/home" TERMANT_PHONE=false
try() { # NAME, then the draw arguments
  local name=$1 began=$SECONDS; shift
  echo "== $name"
  draw "$@" 2>&1
  local code=$?
  echo "$name: exit=$code seconds=$((SECONDS - began))"
  echo "$name exit=$code seconds=$((SECONDS - began))" >> "$out/summary.txt"
}
{
  date -u
  try first-as-the-app-asks --out "$out/1-cat.jpg" "a cat drinking tea at a window, warm morning light, photo"
  # at once again: what the first service says to a second picture from the same address
  TERMANT_DRAW_GAP=0 try second-at-once --wide --out "$out/2-mountain.jpg" "snowy mountains at sunrise, oil painting"
  # and after the wait `draw` keeps by itself
  try third-after-the-wait --tall --out "$out/3-tree.jpg" "an old banyan tree in the rain, watercolour"
  # the second service alone (the first one's address leads nowhere)
  TERMANT_DRAW_POLLINATIONS=http://127.0.0.1:9 try horde-alone --out "$out/4-boat.jpg" "a small wooden boat on a calm lake, evening, photo"
  echo "== what the services say when asked plainly"
  echo "-- image.pollinations.ai without a key"
  curl -s -m 90 -o "$out/raw-pollinations" -D - "https://image.pollinations.ai/prompt/a%20red%20apple%20on%20a%20table?width=256&height=256&seed=5" | head -n 25
  file "$out/raw-pollinations" 2>/dev/null; head -c 300 "$out/raw-pollinations" | tr -c '[:print:]\n' '.'; echo
  echo "-- gen.pollinations.ai without a key"
  curl -s -m 90 -o "$out/raw-gen" -D - "https://gen.pollinations.ai/image/a%20red%20apple%20on%20a%20table?width=256&height=256&seed=5" | head -n 25
  file "$out/raw-gen" 2>/dev/null; head -c 300 "$out/raw-gen" | tr -c '[:print:]\n' '.'; echo
  echo "-- AI Horde: how busy"
  curl -s -m 30 https://aihorde.net/api/v2/status/performance; echo
  echo "-- AI Horde: who it thinks the nameless key is"
  curl -s -m 30 -H "apikey: 0000000000" -H "Client-Agent: termant:1:https://github.com/siddhantakakhael-cyber/Termant" https://aihorde.net/api/v2/find_user | head -c 600; echo
} > "$out/draw-live.txt" 2>&1
cat "$out/draw-live.txt"
ls -la "$out"
exit 0

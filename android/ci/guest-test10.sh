#!/bin/bash
# The video engine inside Ubuntu: ffmpeg, the clip tool, and a copy in the phone's gallery.
# The finished lines are read by the emulator test (result10.txt).
out=/root/result10.txt
exec > "$out" 2>&1
export DEBIAN_FRONTEND=noninteractive
mkdir -p /root/uploads /root/videos

command -v ffmpeg >/dev/null 2>&1 || apt-get update -qq
if ! command -v ffmpeg >/dev/null 2>&1; then
  apt-get install -y -qq ffmpeg >/dev/null
fi
echo "ffmpeg-exit=$?"

ffmpeg -y -hide_banner -loglevel error -f lavfi -i "testsrc=duration=6:size=320x240:rate=15" \
  -f lavfi -i "sine=frequency=440:duration=6" -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest \
  /root/uploads/demo.mp4
echo "demo-exit=$?"

clip info /root/uploads/demo.mp4
echo "info-exit=$?"

clip trim /root/uploads/demo.mp4 --from 2 --to 4 --out /root/uploads/trim.mp4
echo "trim-exit=$?"
clip info /root/uploads/trim.mp4 | head -1
echo "trim-info-exit=$?"

clip speed /root/uploads/demo.mp4 --rate 2 --out /root/uploads/fast.mp4
echo "speed-exit=$?"

clip text /root/uploads/demo.mp4 "hello" --from 1 --to 3 --out /root/uploads/word.mp4
echo "text-exit=$?"

clip rotate /root/uploads/demo.mp4 --deg 90 --out /root/uploads/turn.mp4
echo "rotate-exit=$?"

clip crop /root/uploads/demo.mp4 --aspect 9:16 --out /root/uploads/tall.mp4
echo "crop-exit=$?"

# the whole list at once: two parts, one of them twice as fast, words, the look
cat > /tmp/clip-ci.json <<'J'
{"segments": [[0, 2], [4, 6, 2]], "texts": [{"from": 0.5, "to": 1.5, "text": "CI video", "place": "bottom"}], "brightness": 0.1}
J
clip edit /root/uploads/demo.mp4 --json-file /tmp/clip-ci.json --out /root/videos/ci-edit.mp4
echo "edit-exit=$?"

# a finished video goes to the phone's gallery (Movies/Termant), by the bridge
phone gallery /root/uploads/trim.mp4
echo "gallery-exit=$?"

clip last
echo "last-exit=$?"

echo "TERMANT-TEST-END"

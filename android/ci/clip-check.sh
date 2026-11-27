#!/bin/bash
# clip's tools, tested the way kit-check tests the others: real ffmpeg, real video.
#   clip-check.sh FOLDER_WITH_kit
set -u
FF="${CLIP_FFMPEG:-ffmpeg}"
work=$(mktemp -d)
here=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$work/bin" "$work/home"
if [ -n "${1:-}" ]; then kit=$1/kit; else kit=$(command -v termant-kit); fi
[ -f "$kit" ] || { echo "RESULT: FAIL (no kit)"; exit 2; }
cp "$kit" "$work/bin/clip"; chmod 755 "$work/bin/clip"
# clip finds ffmpeg by name; point it at the ffmpeg this test makes its video with
ln -s "$(command -v "$FF")" "$work/bin/ffmpeg" 2>/dev/null || true
export PATH="$work/bin:$PATH" TERMANT_KIT_DIR="$work/kit" TERMANT_PHONE=false HOME="$work/home"
mkdir -p "$HOME" "$TERMANT_KIT_DIR"
trap 'rm -rf "$work"' EXIT
export FF
d="$work/files"; mkdir -p "$d"
failed=0
check() { local name=$1; shift; if "$@" >/dev/null 2>&1; then echo "PASS $name"; else echo "FAIL $name"; failed=$((failed+1)); fi; }
dur() { "$FF" -hide_banner -i "$1" 2>&1 | grep -o 'Duration: [0-9:]*\.[0-9]*' | awk '{print $2}'; }
near() { # FILE EXPECTED-SECONDS [TOLERANCE]
  local got
  got=$(dur "$1") || return 1
  [ -n "$got" ] || return 1
  python3 -c "
import sys
def sec(t):
    p = t.split(':'); p = [float(x) for x in p]
    while len(p) > 1: p = [p[0]*60 + p[1]] + p[2:]
    return p[0]
ok = abs(sec('$got') - float('$2')) <= float('${3:-0.5}') + 0.15
sys.exit(0 if ok else 1)"
}
vsize() { "$FF" -hide_banner -i "$1" 2>&1 | grep -o 'Stream.*Video.*[0-9]*x[0-9]*' | grep -o '[0-9]*x[0-9]*' | head -1; }
vsize2() { "$FF" -hide_banner -i "$1" 2>&1 | grep -oE '([0-9]{2,5})x([0-9]{2,5})' | head -1; }
hasaudio() { "$FF" -hide_banner -i "$1" 2>&1 | grep -q 'Stream.*Audio:'; }
export -f hasaudio vsize2 near dur 2>/dev/null || true

# the test video: six seconds, sound in it
"$FF" -y -f lavfi -i "testsrc=duration=6:size=320x240:rate=15" -f lavfi -i "sine=frequency=440:duration=6" \
  -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest "$d/src.mp4" 2>"$d/make.log"
"$FF" -y -f lavfi -i "sine=frequency=880:duration=4" -c:a pcm_s16le "$d/song.wav" 2>>"$d/make.log"
[ -s "$d/src.mp4" ] || { echo "FAIL could not make the test video"; tail -n 6 "$d/make.log" 2>/dev/null; echo "RESULT: FAIL (1 failed)"; exit 1; }

# ---- info and thumbnails
clip info "$d/src.mp4" > "$d/info.txt" 2>&1
check "info: how long it is" grep -q "(6.0 s)" "$d/info.txt"
check "info: how big it is" grep -q "320x240" "$d/info.txt"
check "info: it has sound" grep -q "audio: yes" "$d/info.txt"
clip thumbs "$d/src.mp4" "$d/thumbs" 2>&1 | grep -q "6 thumbnails"
check "thumbs: one for every second" test "$(ls "$d/thumbs"/*.jpg 2>/dev/null | wc -l)" -ge 6
check "thumbs: a wrong FPS is refused" bash -c '! clip thumbs "$d/src.mp4" "$d/t2" 99 2>/dev/null'

# ---- the quick cut
clip trim "$d/src.mp4" --from 2 --to 4 --out "$d/trim.mp4" 2>&1 | grep -q "Video saved"
check "trim: keeps that part only" near "$d/trim.mp4" 2
clip cut "$d/src.mp4" --from 2 --to 4 --out "$d/cut.mp4" 2>&1 | grep -q "Video saved"
check "cut: that part is out" near "$d/cut.mp4" 4
clip mute "$d/src.mp4" --out "$d/mute.mp4" 2>&1 | grep -q "Video saved"
check "mute: the sound is gone" bash -c '! hasaudio "'"$d/mute.mp4"'"'
check "mute: the video is still there" test -s "$d/mute.mp4"
check "trim: a wrong --from is refused" bash -c '! clip trim "'"$d/src.mp4"'" --from -1 --to 4 2>/dev/null'
check "cut: --to before --from is refused" bash -c '! clip cut "'"$d/src.mp4"'" --from 4 --to 2 2>/dev/null'

# ---- speed
clip speed "$d/src.mp4" --rate 2 --out "$d/spd.mp4" 2>&1 | grep -q "Video saved"
check "speed: twice as fast is half as long" near "$d/spd.mp4" 3
clip speed "$d/src.mp4" --rate 0.5 --out "$d/slow.mp4" 2>&1 | grep -q "Video saved"
check "speed: half speed is twice as long" near "$d/slow.mp4" 12
check "speed: a wrong rate is refused" bash -c '! clip speed "'"$d/src.mp4"'" --rate 9 2>/dev/null'

# ---- words on the video
clip text "$d/src.mp4" "Namaste" --from 1 --to 3 --out "$d/word.mp4" 2>&1 | grep -q "Video saved"
check "text: the words go on the video" test -s "$d/word.mp4"
check "text: the seconds stay on the video" near "$d/word.mp4" 6
check "text: no seconds is refused" bash -c '! clip text "'"$d/src.mp4"'" "hi" 2>/dev/null'
clip text "$d/src.mp4" "words" --from 1 --to 3 --place top --size 80 --color "#FF0000" --out "$d/word2.mp4" 2>&1 | grep -q "Video saved"
check "text: place, size and colour" test -s "$d/word2.mp4"

# ---- music under the video
clip music "$d/src.mp4" "$d/song.wav" --out "$d/music.mp4" 2>&1 | grep -q "Video saved"
check "music: the video goes on" near "$d/music.mp4" 6
check "music: the sound is in" bash -c 'hasaudio "'"$d/music.mp4"'"'
check "music: a missing song is refused" bash -c '! clip music "'"$d/src.mp4"'" "$d/nope.wav" 2>/dev/null'

# ---- the look
clip filter "$d/src.mp4" --brightness 0.2 --contrast 1.1 --saturation 1.3 --out "$d/look.mp4" 2>&1 | grep -q "Video saved"
check "filter: brightness, contrast, saturation" test -s "$d/look.mp4"
$("$FF" -hide_banner -filters 2>/dev/null | grep -qw sepia) && HAVE_SEPIA=1 || HAVE_SEPIA=
if [ -n "$HAVE_SEPIA" ]; then
  clip filter "$d/src.mp4" --sepia 0.5 --out "$d/old.mp4" 2>&1 | grep -q "Video saved"
  check "filter: the sepia look" test -s "$d/old.mp4"
else
  echo "SKIP the sepia filter is not in this ffmpeg build"
fi
clip rotate "$d/src.mp4" --deg 90 --out "$d/turn.mp4" 2>&1 | grep -q "Video saved"
check "rotate: the sides change places" bash -c '[ "$(vsize2 "'"$d/turn.mp4"'")" = "240x320" ]'
clip rotate "$d/src.mp4" --deg 180 --flip v --out "$d/turn2.mp4" 2>&1 | grep -q "Video saved"
check "rotate: a turn and a flip together" test -s "$d/turn2.mp4"
clip crop "$d/src.mp4" --aspect 1:1 --out "$d/sq.mp4" 2>&1 | grep -q "Video saved"
check "crop: the frame is square" bash -c '[ "$(vsize2 "'"$d/sq.mp4"'")" = "960x960" ]'
clip crop "$d/src.mp4" --aspect 9:16 --out "$d/tall.mp4" 2>&1 | grep -q "Video saved"
check "crop: the frame is tall" bash -c '[ "$(vsize2 "'"$d/tall.mp4"'")" = "720x1280" ]'

# ---- the whole list at once: two parts, one fast, words and music, the sound off its own
cat > "$d/edit.json" <<'J'
{
  "segments": [[0, 2], [4, 6, 2]],
  "texts": [{"from": 0.5, "to": 1.5, "text": "do baar", "place": "center"}],
  "music": "SONG",
  "music_volume": 1.0,
  "mute": true,
  "brightness": 0.1,
  "aspect": "16:9"
}
J
sed -i "s#SONG#$d/song.wav#" "$d/edit.json"
clip edit "$d/src.mp4" --json-file "$d/edit.json" --out "$d/all.mp4" > "$d/all.txt" 2>&1
check "edit: the finished video is made" grep -q "Video saved" "$d/all.txt"
check "edit: 2 s + 1 s at twice the speed = 3 s" near "$d/all.mp4" 3
check "edit: the music is in" bash -c 'hasaudio "'"$d/all.mp4"'"'
check "edit: the progress said how far" grep -q "^progress 1.00$" "$d/all.txt"
# the finished video's frame is 16:9, as the list asked
check "edit: the frame was changed" bash -c '[ "$(vsize2 "'"$d/all.mp4"'")" = "1280x720" ]'
# a list the video cannot give
check "edit: a part outside the video is refused" bash -c '! clip edit "'"$d/src.mp4"'" --json "{\"segments\":[[0,99]]}" 2>/dev/null'
check "edit: a broken list is refused" bash -c '! clip edit "'"$d/src.mp4"'" --json "not json" 2>/dev/null'
check "edit: two overlapping parts are refused" bash -c '! clip edit "'"$d/src.mp4"'" --json "{\"segments\":[[0,4],[2,5]]}" 2>/dev/null'

# ---- the screen, the speed curve
"$FF" -y -f lavfi -i "color=c=0x00ff00:s=320x240:d=4:rate=15" \
  -vf "drawtext=text=HI:fontcolor=white:fontsize=60:x=(w-tw)/2:y=(h-th)/2" \
  -c:v libx264 -pix_fmt yuv420p "$d/screen.mp4" 2>/dev/null
"$FF" -y -f lavfi -i "color=c=0x1133cc:s=320x240:d=6:rate=15" \
  -c:v libx264 -pix_fmt yuv420p "$d/blue.mp4" 2>/dev/null
"$FF" -y -f lavfi -i "color=c=0x224499:s=320x240:d=1" -frames:v 1 -q:v 3 "$d/blue.jpg" 2>/dev/null
clip edit "$d/screen.mp4" --json "{\"background\": \"$d/blue.mp4\"}" --out "$d/scr.mp4" 2>&1 | grep -q "Video saved"
check "screen: the green goes away over the blue" near "$d/scr.mp4" 4
clip edit "$d/screen.mp4" --json "{\"background\": \"$d/blue.jpg\"}" --out "$d/scr3.mp4" 2>&1 | grep -q "Video saved"
check "screen: a picture, held, is a background too" near "$d/scr3.mp4" 4
check "screen: a missing background is refused" bash -c '! clip edit "$d/screen.mp4" --json "{\"background\": \"$d/nope.mp4\"}" 2>/dev/null'
clip edit "$d/screen.mp4" --json '{"key_color": "#00ff00"}' --out "$d/scr2.mp4" 2>&1 | grep -q "Video saved"
check "screen: over the black, when no background" near "$d/scr2.mp4" 4
clip curve "$d/src.mp4" --points "0:1,3:2,6:1" --out "$d/curve.mp4" 2>&1 | grep -q "Video saved"
check "curve: the middle goes twice as fast" near "$d/curve.mp4" 4.3 0.4
clip curve "$d/src.mp4" --points "0:2,6:2" --out "$d/curve2.mp4" 2>&1 | grep -q "Video saved"
check "curve: the constant curve is the constant speed" near "$d/curve2.mp4" 3
check "curve: with a part's speed, it is refused" bash -c '! clip edit "$d/src.mp4" --json "{\"segments\": [[0,6,2]], \"speed_curve\": [[0,1],[6,1]]}" 2>/dev/null'

# ---- what stays for next time
check "last: the newest edited video is named" bash -c "clip last | grep -q 'all.mp4'"
mkdir -p "$work/freshclip"
check "last: without an edit, it says so" bash -c 'TERMANT_KIT_DIR='"$work"'/freshclip clip last 2>&1 | grep -q "no video has been edited yet"'

# ---- without ffmpeg, a plain line (python runs from its full path; the PATH is empty)
mkdir -p "$work/noffbin"
env PATH="$work/noffbin" "$(command -v python3)" "$work/bin/clip" info "$d/src.mp4" > "$d/noff.txt" 2>&1
check "no ffmpeg: it says so plainly and tells the fix" grep -q "ffmpeg is not installed yet" "$d/noff.txt"
check "no ffmpeg: it names the fix" grep -q "apt install -y ffmpeg" "$d/noff.txt"

echo "RESULT: $([ $failed -eq 0 ] && echo PASS || echo "FAIL ($failed failed)")"
[ $failed -eq 0 ]

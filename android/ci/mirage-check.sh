#!/usr/bin/env bash
# Runs the reader of pictures as built for one kind of processor, on this machine: on a real
# screenshot of the phone (docs/screenshots/question.png) it must read the writing, and in a
# photo it must find the dog and where it is.
#   mirage-check.sh <libmirage.so> <work dir>
set -uo pipefail
program="$1"; work="$2"; mkdir -p "$work"
root="$(cd "$(dirname "$0")" && git rev-parse --show-toplevel)"
model="$work/mirage.cact"
if [ ! -f "$model" ]; then
  curl -sSL --fail --retry 3 -o "$model" https://huggingface.co/Cactus-Compute/mirage/resolve/76c1e9e53381aca8fc3843c636216c96ff220122/mirage.cact || exit 1
fi
echo "4249a0f7515272ff76b9414bec17d7e7daf84b15b66c7fb4f17fce1e66f9f237  $model" | sha256sum --check --quiet || exit 1
[ -d "$work/py/PIL" ] || python3 -m pip install --quiet --disable-pip-version-check --target "$work/py" pillow
PYTHONPATH="$work/py" python3 - "$root/docs/screenshots/question.png" "$work/question.req" <<'PY'
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert('RGB')
w, h = im.size
with open(sys.argv[2], 'wb') as f:
    for part in (im.crop((0, 0, w, 360)), im.crop((0, h - 360, w, h))):   # the two halves, as the app cuts a screen
        f.write(f'{part.width} {part.height}\n'.encode()); f.write(part.tobytes())
PY
[ -s "$work/question.req" ] || { echo "FAIL the test picture could not be prepared"; exit 1; }
echo "machine: $(uname -m), $(nproc) processors; program: $(wc -c < "$program") bytes"
start=$(date +%s%N)
"$program" "$model" < "$work/question.req" > "$work/answer.txt" 2> "$work/errors.txt"; code=$?
ms=$(( ($(date +%s%N) - start) / 1000000 ))
echo "exit $code, $ms ms for 2 pictures (with loading the model)"
cat "$work/errors.txt"
PYTHONPATH="$work/py" python3 - "$work/answer.txt" <<'PY'
import json, sys
for line in open(sys.argv[1]):
    print(' | ' + ' · '.join(f"{t['string']} ({t['score']:.2f})" for t in json.loads(line)['text']))
PY
failed=0
for want in 'Termant asks' 'Alpha' 'Beta' 'Type something else' 'Network & internet' 'Connected devices' 'Notifications'; do
  grep -q "\"string\":\"$want\"" "$work/answer.txt" || { echo "FAIL not read: $want"; failed=1; }
done
[ "$code" = 0 ] || failed=1
[ "$failed" = 0 ] && echo "PASS the reader read the screenshot on $(uname -m)"

# The same program, asked for all it sees (options "{}"), on a photo: a dog in front of a bicycle
# (from the darknet project's examples; fetched at a fixed revision, not kept in this repository).
curl -sSL --fail --retry 3 -o "$work/dog.jpg" https://raw.githubusercontent.com/pjreddie/darknet/f6afaabcdf85f77e7aff2ec55c020c0e297c77f9/data/dog.jpg || exit 1
echo "5a9522051c3cec2bbd2f6323fccba32e8fbf3ddcc2b3e2fd46b04c720bc6f866  $work/dog.jpg" | sha256sum --check --quiet || exit 1
PYTHONPATH="$work/py" python3 - "$work/dog.jpg" "$work/dog.req" <<'PY'
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert('RGB')
with open(sys.argv[2], 'wb') as f:
    f.write(f'{im.width} {im.height}\n'.encode()); f.write(im.tobytes())
PY
start=$(date +%s%N)
"$program" "$model" '{}' < "$work/dog.req" > "$work/dog.json" 2> "$work/errors.txt"; code=$?
echo "exit $code, $(( ($(date +%s%N) - start) / 1000000 )) ms for the photo (with loading the model)"
cat "$work/errors.txt"
[ "$code" = 0 ] || failed=1
python3 - "$work/dog.json" <<'PY' || failed=1
import json, sys
answer = json.load(open(sys.argv[1], encoding='utf-8'))
things = answer.get('objects', [])
for o in things: print(f" | {o.get('name')} {o.get('score')} {o.get('bbox')} {o.get('position')}")
dogs = [o for o in things if o.get('name') == 'dog']
if not dogs: sys.exit('FAIL no dog found in the photo')
left, top, right, bottom = dogs[0]['bbox']
if not (left + right < 2000 / 3 * 2 and top + bottom > 2000 / 3): sys.exit('FAIL the dog is not where it is in the photo (left, lower half)')
if 'scene' not in answer: sys.exit('FAIL no scene in the answer')
print('PASS the reader found the dog in the photo, and where it is')
PY
exit $failed

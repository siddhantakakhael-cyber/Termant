#!/usr/bin/env bash
# Runs the reader of pictures as built for one kind of processor, on this machine, on a real
# screenshot of the phone (docs/screenshots/question.png), and checks what it read.
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
exit $failed

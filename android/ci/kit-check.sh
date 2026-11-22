#!/bin/bash
# Checks Termant's kit (android/assets/guest/kit): undo and the bin (safe), tidying a folder,
# question cards (study), reading files of many kinds (readfile), the user's details (me),
# watches, the work diary, the team notebook, which model gets a message with files, and
# pictures made from words (draw) against a pretend picture service (fake_draw.py), and
# APILayer's APIs (apilayer) against a pretend APILayer (fake_apilayer.py, with saved real pages).
# Runs inside Termant's Ubuntu and on any Linux with python3.
#   kit-check.sh [FOLDER WITH kit]     prints PASS/FAIL lines and a RESULT line
work=$(mktemp -d)
here=$(cd "$(dirname "$0")" && pwd)
opencode=$(PATH="$HOME/.opencode/bin:$PATH" command -v opencode 2>/dev/null)
mkdir -p "$work/bin"
if [ -n "$1" ]; then kit=$1/kit; else kit=$(command -v termant-kit); fi
[ -f "$kit" ] || { echo "RESULT: FAIL (no kit)"; exit 2; }
for name in termant-kit safe tidy study readfile me watch diary draw apilayer; do cp "$kit" "$work/bin/$name"; done
chmod 755 "$work/bin"/*
# a stand-in for the phone command: answers questions, makes and removes rules
cat > "$work/bin/fakephone" <<'P'
#!/bin/bash
echo "$*" >> "$TERMANT_KIT_DIR/phone.log"
case "$1 $2" in
  "ask "*) echo "${FAKE_ANSWER:-Nahi}" ;;
  "rule add") echo "Rule 7 added: Every 15 minutes, run: ${*:6}" ;;
  "rule remove") echo "Rule $3 removed." ;;
  "gallery "*)
    [ -z "$FAKE_NO_GALLERY" ] || { echo "phone gallery: Termant may not write to the phone's pictures yet." >&2; exit 1; }
    echo "A copy is in the phone's gallery: Pictures/Termant/$(basename "$2")"
    echo "content://media/external/images/media/41" ;;
  *) echo ok ;;
esac
P
chmod 755 "$work/bin/fakephone"
PATH="$work/bin:$PATH"
export TERMANT_KIT_DIR="$work/kit" TERMANT_PHONE=fakephone TERMANT_NO_INSTALL=1 HOME="$work/home"
mkdir -p "$HOME" "$TERMANT_KIT_DIR"
failed=0
check() { local name=$1; shift; if "$@" >/dev/null 2>&1; then echo "PASS $name"; else echo "FAIL $name"; failed=$((failed+1)); fi; }
has() { printf '%s' "$out" | grep -qF -- "$1"; }
hasnt() { ! has "$1"; }
refused() { [ "$code" -ne 0 ] && has "$1"; }
run() { out=$("$@" 2>&1); code=$?; }
fake_draw=
fake_apilayer=
trap '[ -n "$fake_draw" ] && kill $fake_draw 2>/dev/null; [ -n "$fake_apilayer" ] && kill $fake_apilayer 2>/dev/null; rm -rf "$work"' EXIT
d="$work/files"; mkdir -p "$d"

# ---- safe: copies, the bin, undo
export TERMANT_TURN=turn-one
echo "first words" > "$d/letter.txt"; echo "to delete" > "$d/old.txt"
run safe keep "$d/letter.txt" "$d/new.txt"
check "safe keep copies a file before it is changed" has "Kept a copy of $d/letter.txt"
check "safe keep notes a file that is not there yet" has "new.txt does not exist yet"
echo "changed words" > "$d/letter.txt"; echo "made by the agent" > "$d/new.txt"
run safe rm "$d/old.txt"
check "safe rm deletes into the bin" [ "$code" -eq 0 ] && check "the deleted file is gone" [ ! -e "$d/old.txt" ]
run safe mv "$d/letter.txt" "$d/sub/renamed.txt"
check "safe mv moves a file" [ -f "$d/sub/renamed.txt" ]
run safe list
check "safe list shows the changes of the message" has "turn-one" && check "the list names what was deleted" has "deleted  $d/old.txt"
run safe has turn-one
check "safe has says how many changes a message made" [ "$out" = 4 ]
run safe undo
check "safe undo reports what it put back" has "Undone: 4 change(s) of message turn-one put back."
check "undo brings the old words back" [ "$(cat "$d/letter.txt" 2>/dev/null)" = "first words" ]
check "undo brings the deleted file back" [ "$(cat "$d/old.txt" 2>/dev/null)" = "to delete" ]
check "undo removes the file the agent made" [ ! -e "$d/new.txt" ]
check "undo moves the renamed file back" [ ! -e "$d/sub/renamed.txt" ]
run safe undo
check "a second undo has nothing to do" refused "there is nothing to undo"
run safe has turn-one
check "an undone message has no undo left" [ "$code" -ne 0 ]
export TERMANT_TURN=turn-two
mkdir -p "$d/album"; for i in 1 2 3 4 5 6 7; do echo "p$i" > "$d/album/p$i.jpg"; done
run safe rm "$d/album"
check "deleting a folder asks the user first" grep -q '^ask Delete 1 thing' "$TERMANT_KIT_DIR/phone.log"
check "without a yes the folder stays" refused "the user did not say yes" && check "the folder is still there" [ -d "$d/album" ]
run safe rm "$d/album"/p*.jpg
check "more than 5 files at once need a yes too" refused "Nothing was deleted"
TERMANT_UNATTENDED=1 run safe rm "$d/album"
check "with nobody there (a rule, a night job) a folder is never deleted" refused "did not say yes"
FAKE_ANSWER=Haan run safe rm "$d/album"
check "with a yes the folder goes to the bin" [ "$code" -eq 0 ] && check "the folder is gone" [ ! -e "$d/album" ]
run safe rm /etc
check "a system folder is refused as a whole" refused "not something to delete as a whole"
run safe undo turn-two
check "undo by name brings the folder back" [ -f "$d/album/p7.jpg" ]
run safe rm "$d/not-there.txt"
check "safe rm says when a file is not there" refused "there is no"

# ---- tidy
export TERMANT_TURN=turn-tidy
t="$work/Download"; mkdir -p "$t/inner"
echo aaa > "$t/photo.JPG"; echo bbb > "$t/song.mp3"; echo ccc > "$t/report.pdf"; echo ddd > "$t/app.apk"
echo aaa > "$t/photo copy.jpg"; echo eee > "$t/strange"; echo aaa > "$t/inner/photo-again.jpg"; head -c 3000 /dev/zero > "$t/big.zip"
run tidy scan "$t"
check "tidy scan counts the files" has "7 file(s)"
check "tidy scan counts by kind" [ "$(printf '%s\n' "$out" | grep -c '^  \(Pictures\|Music\|Documents\|Apps\|Archives\|Other\) ')" -eq 6 ]
check "tidy scan names the biggest file first" [ "$(printf '%s\n' "$out" | sed -n '/^Biggest:/{n;p}' | grep -c 'big.zip')" -eq 1 ]
check "tidy scan sees a file that is there twice" has "There twice or more: 1 extra file(s)"
run tidy dupes "$t" -r
check "tidy dupes -r looks into folders too" [ "$(printf '%s\n' "$out" | grep -c '^  extra ')" -eq 2 ]
check "tidy dupes gives the command for the extras" has "  safe rm '"
run tidy sort
check "tidy sort wants the folder named" refused "usage: tidy sort FOLDER"
run tidy sort "$t"
check "tidy sort reports what it moved" has "Sorted 7 file(s)"
check "pictures went to Pictures" [ -f "$t/Pictures/photo.JPG" ] && check "an unknown file went to Other" [ -f "$t/Other/strange" ]
check "a folder that was there is left alone" [ -f "$t/inner/photo-again.jpg" ]
run tidy sort "$t"
check "a sorted folder has nothing left to sort" has "Nothing to sort"
run safe undo
check "undo puts a sorted folder back as it was" [ -f "$t/photo.JPG" ] && check "the folders made by sorting are gone again" [ ! -d "$t/Pictures" ]

# ---- study
run study add Hindi-Grammar "What is a noun in Hindi?" "sangya"
check "study add makes a card" has "Card 1 added to hindi-grammar (1 card(s) there now)."
study add hindi-grammar "What is a verb in Hindi?" "kriya" >/dev/null
run study add hindi-grammar "What is a verb in Hindi?" "kriya"
check "the same question is not added twice" refused "already in hindi-grammar"
run study due
check "study due lists the cards with their answers" has "1  [hindi-grammar]  Q: What is a noun in Hindi?" && check "the answer is shown to the agent" has "A: sangya"
run study right 1
check "a right answer comes back tomorrow" has "It comes back in 1 day(s)."
run study wrong 2
check "a wrong answer comes back soon" has "comes back in 10 minutes"
run study due
check "answered cards are not due any more" has "Nothing is due now. The next card comes back on"
run study topics
check "study topics says how a topic is going" has "hindi-grammar: 2 card(s), 0 due now, 0 known well, answered right 1 and wrong 1 time(s)"
run study remove hindi-grammar
check "study remove takes a topic away" has "Removed 2 card(s)."
run study add "Bad Topic" q a
check "a topic name with spaces is refused" refused "small letters"

# ---- readfile
r="$work/read"; mkdir -p "$r"
python3 - "$r" <<'PY'
import sys, zipfile, zlib, struct, tarfile, io, os
r = sys.argv[1]
with zipfile.ZipFile(r + "/letter.docx", "w") as z:
    z.writestr("word/document.xml", '<w:document><w:body><w:p><w:r><w:t>Namaste from Word.</w:t></w:r></w:p><w:p><w:r><w:t>Second &amp; last line.</w:t></w:r></w:p></w:body></w:document>')
with zipfile.ZipFile(r + "/marks.xlsx", "w") as z:
    z.writestr("xl/sharedStrings.xml", '<sst><si><t>Name</t></si><si><t>Marks</t></si><si><t>Asha</t></si></sst>')
    z.writestr("xl/worksheets/sheet1.xml", '<worksheet><sheetData><row><c t="s"><v>0</v></c><c t="s"><v>1</v></c></row><row><c t="s"><v>2</v></c><c><v>91</v></c></row></sheetData></worksheet>')
with zipfile.ZipFile(r + "/talk.pptx", "w") as z:
    z.writestr("ppt/slides/slide1.xml", '<p:sld><a:p><a:r><a:t>Slide one title</a:t></a:r></a:p></p:sld>')
    z.writestr("ppt/slides/slide2.xml", '<p:sld><a:p><a:r><a:t>Slide two words</a:t></a:r></a:p></p:sld>')
with zipfile.ZipFile(r + "/bundle.zip", "w") as z:
    z.writestr("docs/a.txt", "inside a")
    z.writestr("b.txt", "inside b")
with zipfile.ZipFile(r + "/evil.zip", "w") as z:
    z.writestr("../escaped.txt", "no")
with tarfile.open(r + "/bundle.tar.gz", "w:gz") as t:
    data = b"in the tar"
    info = tarfile.TarInfo("t/one.txt"); info.size = len(data)
    t.addfile(info, io.BytesIO(data))
stream = zlib.compress(b"BT /F1 12 Tf 20 100 Td (Hello PDF chai) Tj ET")
pdf = b"%%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]/Contents 4 0 R>>endobj\n4 0 obj<</Length %d/Filter/FlateDecode>>stream\n" % len(stream) + stream + b"\nendstream endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n"
open(r + "/paper.pdf", "wb").write(pdf)
open(r + "/noname", "wb").write(pdf)
def png(w, h):
    raw = b"".join(b"\x00" + b"\xff\x00\x00" * w for _ in range(h))
    ch = lambda t, d: struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + ch(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + ch(b"IDAT", zlib.compress(raw)) + ch(b"IEND", b"")
open(r + "/red.png", "wb").write(png(12, 8))
open(r + "/long.txt", "w").write("word " * 9000)
open(r + "/hindi.txt", "w", encoding="utf-8").write("नमस्ते दुनिया\n")
open(r + "/blob.bin", "wb").write(bytes(range(32)) * 300 + b"READABLE-PIECE-HERE" + bytes(200))
PY
run readfile "$r/letter.docx"
check "readfile reads a Word document" has "Namaste from Word." && check "it keeps the lines and the & sign" has "Second & last line."
run readfile "$r/marks.xlsx"
check "readfile reads an Excel sheet as rows" has "Asha	91"
run readfile "$r/talk.pptx"
check "readfile reads PowerPoint slides in order" has "--- Slide 2 ---
Slide two words"
run readfile "$r/paper.pdf"
check "readfile reads a PDF" has "Hello PDF chai"
run readfile "$r/noname"
check "a file without an ending is known by its first bytes" has "a PDF" && check "and read" has "Hello PDF chai"
run readfile "$r/red.png"
check "readfile gives a picture's size and how to see it" has "12 x 8" && check "it points at phone look" has "phone look '$r/red.png'"
run readfile "$r/bundle.zip"
check "readfile lists what is in a zip" has "docs/a.txt" && check "it says how to unpack" has "--unpack"
run readfile "$r/bundle.zip" --unpack
check "readfile unpacks a zip next to it" [ "$(cat "$r/bundle/docs/a.txt" 2>/dev/null)" = "inside a" ]
run readfile "$r/bundle.tar.gz" --unpack "$r/out"
check "readfile unpacks a tar.gz into a named folder" [ "$(cat "$r/out/t/one.txt" 2>/dev/null)" = "in the tar" ]
run readfile "$r/evil.zip" --unpack "$r/evil"
check "an archive that writes outside its folder is refused" refused "outside its folder" && check "nothing escaped" [ ! -e "$r/escaped.txt" ]
run readfile "$r/long.txt"
check "a long text is cut with the way to go on" has "[cut here; go on with: readfile '$r/long.txt' --from 20000]"
run readfile "$r/long.txt" --from 40000
check "readfile goes on from a place" has "shown from 40000 to 45000"
run readfile "$r/hindi.txt"
check "readfile reads Hindi text" has "नमस्ते दुनिया"
run readfile "$r/blob.bin"
check "a file of no known kind is said to be so, with its readable pieces" has "not a text file" && check "the pieces" has "READABLE-PIECE-HERE"
run readfile "$r/missing.pdf"
check "readfile says when a file is not there" refused "there is no file"

# ---- me
run me
check "me starts empty" has "Nothing is kept about the user yet"
run me set full-name Asha Devi
check "me set keeps a detail" has "Kept: full-name = Asha Devi"
me set pin-code 757001 >/dev/null; me set phone "+91 98765 43210" >/dev/null
run me
check "me lists the details" has "  full-name: Asha Devi" && check "a pin code (of an address) is kept" has "  pin-code: 757001"
run me set password hunter2
check "a password is refused" refused "are not kept here"
run me set bank 4111111111111111
check "a card number is refused" refused "are not kept here"
run me set atm-pin 1234
check "a PIN is refused" refused "are not kept here"
run me remove phone
check "me remove takes a detail away" has "Removed phone."

# ---- watch
: > "$TERMANT_KIT_DIR/phone.log"
run watch add parcel every 5m --when "true" --then "true"
check "a watch more often than every 15 minutes is refused" refused "every 15m to 24h"
run watch add parcel every 15m --when "test -e $work/arrived" --then "echo done > $work/acted"
check "watch add makes a watch" has 'Watching "parcel" every 15m.'
check "the watch is a rule of the app" grep -q '^rule add every 15m -- watch check parcel$' "$TERMANT_KIT_DIR/phone.log"
run watch check parcel
check "a watch that sees nothing does nothing" has "not yet (looked 1 time(s))" && check "nothing ran" [ ! -e "$work/acted" ]
touch "$work/arrived"
run watch check parcel
check "when it happens the command runs" [ "$(cat "$work/acted" 2>/dev/null)" = done ]
check "then the watch ends and its rule is removed" grep -q '^rule remove 7$' "$TERMANT_KIT_DIR/phone.log"
run watch list
check "an ended watch is off the list" has "Nothing is being watched."
watch add again every 1h --when true --then "echo x >> $work/kept" --keep >/dev/null
watch check again >/dev/null; watch check again >/dev/null
check "a watch with --keep stays and acts every time" [ "$(wc -l < "$work/kept")" -eq 2 ]
run watch remove again
check "watch remove stops a watch" has 'Stopped watching "again".'

# ---- diary
run diary
check "the diary starts empty" has "Nothing was done today."
TERMANT_TURN=turn-one termant-kit diary add --seconds 75 --model opencode/big-pickle --ok 0 --helpers 2 --turn turn-two -- "Compare three   phones
and write a report"
termant-kit diary add --seconds 12 --ok 1 --kind night -- "Night job"
run diary
check "the diary sums the day up" has "Work diary, today: 2 job(s), 1 min 27 s of work, 1 did not work, 2 helper(s) used, 1 file change(s) that can be undone"
check "the diary has one line for each job" has "opencode/big-pickle" && check "the request is on one line" has "Compare three phones and write a report  [2 helper(s), 1 file change(s)]"
check "a job that failed is marked" has "Night job  [night, did not work]"
run diary 7
check "the diary of several days" has "the last 7 days"

# ---- team: notebook, repeated tasks, model for helpers
run termant-kit team notes
check "the team notebook starts empty" has "nothing in it yet"
run termant-kit team note "The Redmi 15 costs 12,999 rupees on Flipkart"
check "team note writes into the notebook" has "Written into the team notebook (1 line(s))."
termant-kit team note "The user wants prices in rupees" >/dev/null
run termant-kit team note "The user wants prices in rupees"
check "the same note is not written twice" has "in the team notebook already"
run termant-kit team notes redmi
check "team notes finds by words" has "Flipkart" && check "only the lines that fit" hasnt "prices in rupees"
run termant-kit team seen translate-letter
check "team seen counts a kind of task" has '"translate-letter" has come up 1 time(s).'
termant-kit team seen translate-letter >/dev/null
run termant-kit team seen translate-letter
check "the third time it says to make a helper" has 'Make a helper for it now' && check "with the command" has 'team add translate-letter "what it is for" FILE'
run termant-kit team model nonsense
check "a helper model must be provider/model" refused "usage: team model"
run termant-kit team model opencode/glm-5.3-flash-free
check "team model sets the helpers' model" [ "$(cat "$TERMANT_KIT_DIR/data/helper-model")" = "opencode/glm-5.3-flash-free" ]
run termant-kit team model off
check "team model off goes back" [ ! -e "$TERMANT_KIT_DIR/data/helper-model" ]

# ---- attach: which model gets a message with files
mkdir -p "$TERMANT_KIT_DIR/cache"
cat > "$TERMANT_KIT_DIR/cache/models.json" <<'J'
{"opencode/big-pickle": {"input": ["text"], "free": true},
 "opencode/glm-5.3-flash-free": {"input": ["text", "image"], "free": true},
 "opencode/zzz-free": {"input": ["text", "image"], "free": true},
 "anthropic/claude": {"input": ["text", "image", "pdf"], "free": false},
 "groq/words-only": {"input": ["text"], "free": false}}
J
run termant-kit attach opencode/big-pickle "what is this [file: $r/red.png]"
check "a picture goes to a free model that can see, when the chosen one cannot" has "model=opencode/glm-5.3-flash-free" && check "the picture is handed to the model" has "file=$r/red.png"
check "the change of model is told" has "note=model:opencode/glm-5.3-flash-free"
run termant-kit attach "" "what is this [file: $r/red.png]"
check "with the automatic model a seeing one is named" has "model=opencode/glm-5.3-flash-free"
run termant-kit attach anthropic/claude "read [file: $r/paper.pdf] and [file: $r/red.png]"
check "a model that takes pictures and PDFs keeps both" [ "$(printf '%s\n' "$out" | grep -c '^file=')" -eq 2 ] && check "and stays the chosen one" has "model=anthropic/claude"
run termant-kit attach opencode/big-pickle "read [file: $r/paper.pdf] and [file: $r/red.png]"
check "no free model reads PDFs: the picture is shown, the PDF is left to readfile" [ "$(printf '%s\n' "$out" | grep -c '^file=')" -eq 1 ] && check "one file is left to read" has "read=1"
run termant-kit attach groq/words-only "sum up [file: $r/paper.pdf]"
check "a PDF alone with a model that cannot read it: nothing is forced" has "model=groq/words-only" && check "no file is handed over" hasnt "file="
run termant-kit attach opencode/big-pickle "see [file: $r/letter.docx] and [file: $r/missing.png]"
check "a Word file needs no other model" has "model=opencode/big-pickle" && check "it is left to readfile" has "read=1"
run termant-kit attach opencode/big-pickle "plain words"
check "a message without files keeps its model" [ "$out" = "model=opencode/big-pickle" ]
head -c 5000000 /dev/zero > "$r/huge.png"
run termant-kit attach opencode/big-pickle "see [file: $r/huge.png]"
check "a picture over 4 MB is not pushed into the request" hasnt "file="

# ---- run by the agent in a chat: the app wrote down which message it is, and whether anybody is there
unset TERMANT_TURN
mkdir -p "$TERMANT_KIT_DIR/cache"; echo 20260101-101010 > "$TERMANT_KIT_DIR/cache/turn"
echo "chat words" > "$d/chat.txt"
TERMANT_CHAT=1 safe keep "$d/chat.txt" >/dev/null 2>&1; echo "changed" > "$d/chat.txt"
run safe has 20260101-101010
check "a command the agent runs belongs to the chat's message" [ "$out" = 1 ]
run safe undo
check "undo without a name puts that message's change back" [ "$(cat "$d/chat.txt")" = "chat words" ]
echo "undo said: $out"
mkdir -p "$d/album2"; echo p > "$d/album2/p.jpg"
: > "$TERMANT_KIT_DIR/cache/alone"; asked=$(grep -c '^ask ' "$TERMANT_KIT_DIR/phone.log")
TERMANT_CHAT=1 FAKE_ANSWER=Haan run safe rm "$d/album2"
check "in a job the agent does alone a folder is never deleted" refused "did not say yes" && check "the folder stays" [ -d "$d/album2" ]
check "and nobody is asked" [ "$(grep -c '^ask ' "$TERMANT_KIT_DIR/phone.log")" -eq "$asked" ]
rm -f "$TERMANT_KIT_DIR/cache/alone"
TERMANT_CHAT=1 FAKE_ANSWER=Haan run safe rm "$d/album2"
check "in a chat with the user there, a yes deletes it" [ ! -e "$d/album2" ]

# ---- draw: a picture made from words, by two free services (here: a pretend one that plays both)
if [ -f "$here/fake_draw.py" ]; then
  python3 "$here/fake_draw.py" "$work/draw-port" "$work/draw.log" > "$work/draw-server.txt" 2>&1 < /dev/null & fake_draw=$!
  i=0; while [ ! -s "$work/draw-port" ] && [ $i -lt 100 ]; do sleep 0.1; i=$((i+1)); done
fi
if [ -s "$work/draw-port" ]; then
  at="http://127.0.0.1:$(cat "$work/draw-port")"
  export TERMANT_DRAW_GAP=0 TERMANT_DRAW_POLL=0.1 TERMANT_DRAW_WAIT=3
  services() { export TERMANT_DRAW_POLLINATIONS="$at/$1" TERMANT_DRAW_HORDE="$at/$2"; }
  drawn() { printf '%s\n' "$out" | sed -n 's/^Picture saved: \(.*\) (.*/\1/p' | head -n 1; }
  kind() { head -c 12 "$1" 2>/dev/null | od -An -c | tr -d ' \n'; }
  unset TERMANT_CHAT; export TERMANT_TURN=draw-by-hand

  services ok horde
  run draw "a cat drinking tea, photo"
  check "draw saves the picture the first service gives" [ "$code" -eq 0 ] && check "and says where, how big and who drew it" has "(96x64, 522 bytes, drawn by Pollinations)"
  f=$(drawn)
  check "the picture is in ~/pictures, named after the words" [ "$f" != "${f#$HOME/pictures/a-cat-drinking-tea-photo-}" ]
  check "it is a JPEG and ends in .jpg" [ "${f##*.}" = jpg ] && check "the file holds the picture" [ "$(stat -c %s "$f" 2>/dev/null)" = 522 ]
  check "the phone's gallery gets a copy" grep -qxF "gallery $f" "$TERMANT_KIT_DIR/phone.log"
  check "and draw says where the copy is" has "A copy is in the phone's gallery: Pictures/Termant/$(basename "$f")"
  check "the first service was asked for the words and a square picture" grep -q "^GET /ok/prompt/a%20cat%20drinking%20tea%2C%20photo?width=768&height=768&seed=[0-9]*" "$work/draw.log"
  check "the second service was left alone" [ "$(grep -c '/horde/' "$work/draw.log")" -eq 0 ]
  check "typed by hand, nothing is listed for a chat" [ ! -e "$TERMANT_KIT_DIR/cache/drawn.txt" ]
  run draw last
  check "draw last names the newest picture" [ "$out" = "$f" ]

  services busy horde
  TERMANT_CHAT=1 TERMANT_TURN=draw-turn run draw --wide --seed 7 "snowy mountains at sunrise"
  check "when the first service is busy the second one draws" has "drawn by AI Horde" && check "and draw says why it changed over" has "Pollinations did not draw (busy: it draws for one address only now and then (HTTP 402))"
  g=$(drawn)
  check "a wide picture has the wide size the free queue allows" has "(576x320, "
  check "the second service's picture is a PNG, named so" [ "${g##*.}" = png ] && check "and the file is a PNG" [ "$(kind "$g")" = '211PNG\r\n032\n\0\0\0\r' ]
  check "the nameless free key and the app's name are sent" grep -q '^POST /horde/generate/async key=0000000000 agent=termant:1:' "$work/draw.log"
  check "the request asks for a safe picture" grep -q '^POST /horde/generate/async .*"nsfw": false, "censor_nsfw": true' "$work/draw.log"
  check "the seed that was given is used" grep -q '^POST /horde/generate/async .*"seed": "7"' "$work/draw.log"
  check "it waits in the queue, looking again" [ "$(grep -c '^GET /horde/generate/check/' "$work/draw.log")" -ge 3 ]
  check "in a chat the picture is listed for the reply, with its address on the phone" [ "$(cat "$TERMANT_KIT_DIR/cache/drawn.txt" 2>/dev/null)" = "$g	content://media/external/images/media/41" ]
  check "the agent is told the chat shows it" has "The chat shows this picture under your answer by itself"

  services wait horde
  run draw --tall "an old banyan tree"
  check "asked to wait a second, draw waits and asks again" has "drawn by Pollinations" && check "a tall picture is tall" has "(432x768, "

  services html b64
  run draw "a boat on a lake"
  check "a web page instead of a picture is not taken for one" has "Pollinations did not draw (gave no picture)" && check "the second service's picture inside its answer is read" has "(512x512, "

  services busy nomodel
  run draw "a red kite"
  check "when no computer has the chosen models, any model draws" has "drawn by AI Horde"
  check "the second request names no models" grep -q '^POST /nomodel/generate/async .*"models": \[\]' "$work/draw.log"

  before=$(ls "$HOME/pictures" | wc -l)
  services down censored
  run draw "a quiet street"
  check "a picture held back as unsafe is not saved" refused "held the picture back as not safe to show" && check "nothing new is in ~/pictures" [ "$(ls "$HOME/pictures" | wc -l)" -eq "$before" ]
  services busy faulted
  run draw "a quiet street"
  check "a failed worker is reported" refused "AI Horde: its worker failed"
  services busy never
  run draw "a quiet street"
  check "a queue that never ends is given up after the wait" refused "was not ready after 3 s" && check "and the waiting request is taken back" grep -q '^DELETE /never/generate/status/' "$work/draw.log"
  check "the agent is told not to describe a picture that was not made" has "Tell the user plainly that no picture was made"
  services down down
  run draw "a quiet street"
  check "both services down: said plainly, with both reasons" refused "Pollinations: HTTP 503. AI Horde: HTTP 503"
  export TERMANT_DRAW_POLLINATIONS=http://127.0.0.1:9 TERMANT_DRAW_HORDE=http://127.0.0.1:9
  run draw "a quiet street"
  check "no connection: asks whether the internet is on" refused "Is the internet on?"

  services ok horde
  : > "$work/draw.log"
  run draw --slow "a boat on a lake"
  check "draw --slow asks the slow service first" has "drawn by AI Horde" && check "and leaves the quick one alone" [ "$(grep -c '/ok/prompt/' "$work/draw.log")" -eq 0 ]
  services ok down
  run draw --slow "a boat on a lake"
  check "draw --slow falls back to the quick service" has "AI Horde did not draw (HTTP 503" && check "which then draws" has "drawn by Pollinations"

  services png horde
  run draw --out "$d/made/kite.jpg" "a red kite over a field"
  check "draw --out saves where asked, with the ending the picture really has" [ -f "$d/made/kite.png" ] && check "and says so" has "Picture saved: $d/made/kite.png (768x768, "
  rm -f "$TERMANT_KIT_DIR/cache/drawn.txt"
  TERMANT_CHAT=1 run draw --out "$d/made/field.png" "a red kite over a field"
  check "in a chat a picture saved outside the home folder is listed by a copy the chat can read" [ "$(cut -f1 "$TERMANT_KIT_DIR/cache/drawn.txt" 2>/dev/null)" = "$HOME/pictures/field.png" ] && check "and the copy is there" cmp -s "$d/made/field.png" "$HOME/pictures/field.png"
  FAKE_NO_GALLERY=1 run draw "a red kite over a field"
  check "when the gallery refuses, the picture is still saved" [ "$code" -eq 0 ] && check "and draw says it is only in Ubuntu" has "Not copied to the phone's gallery (phone gallery: Termant may not write"
  run draw
  check "draw alone explains itself" has 'draw "WHAT TO DRAW"'
  run draw --big "a kite"
  check "an unknown choice is refused" refused "there is no --big"
  run draw "a"
  check "one letter is not something to draw" refused 'usage: draw "what to draw"'
  run termant-kit draw help
  check "termant-kit draw is the same command" has "a picture made from words"
  kill $fake_draw 2>/dev/null; fake_draw=
  unset TERMANT_DRAW_POLLINATIONS TERMANT_DRAW_HORDE TERMANT_DRAW_GAP TERMANT_DRAW_POLL TERMANT_DRAW_WAIT
else
  echo "FAIL draw: the pretend picture service did not start"; failed=$((failed+1))
fi

# ---- apilayer: APILayer's APIs with the user's key (here: a pretend APILayer; its pages are real ones, saved)
if [ -f "$here/fake_apilayer.py" ] && [ -f "$here/apilayer-pages/list.html" ]; then
  python3 "$here/fake_apilayer.py" "$work/al-port" "$work/al.log" > "$work/al-server.txt" 2>&1 < /dev/null & fake_apilayer=$!
  i=0; while [ ! -s "$work/al-port" ] && [ $i -lt 100 ]; do sleep 0.1; i=$((i+1)); done
fi
if [ -s "$work/al-port" ]; then
  at="http://127.0.0.1:$(cat "$work/al-port")"
  export TERMANT_APILAYER_MARKET="$at" TERMANT_APILAYER="$at/gw"
  unset TERMANT_CHAT APILAYER_KEY
  good=goodkey0123456789goodkey01234567
  keyfile="$TERMANT_KIT_DIR/data/apilayer.json"
  rm -f "$TERMANT_KIT_DIR/cache/drawn.txt"
  run apilayer help
  check "apilayer help says how the key is saved" has "apilayer key THE_KEY" && check "and that nothing there draws" has "that is \`draw\`"

  # the list of APIs, from the marketplace
  run apilayer list
  check "apilayer list reads the marketplace's list" has "APIs the user's APILayer key can call (26)" && check "and says where it is from" has "(from the marketplace, read just now)"
  check "an API is listed with the name for calls and what it does" has "exchangerates_data   Exchange Rates Data API: Real-Time, Intraday & Historical Market Data API."
  check "the free plan is named" has "[free: 100 a month]" && check "and an API that has none" has "URL Shortener API: URL Shortener service with an API which provides extensive analytics backend. [no free plan]"
  check "other companies' services are only counted" has "6 more are listed in the marketplace" && check "and not shown as callable" hasnt "  pika "
  check "the marketplace is asked under the tool's own name (it refuses Python's)" [ "$(grep -c '^GET /get-api-data' "$work/al.log")" -eq 1 ]
  run apilayer list
  check "the list is kept for a day, not fetched again" [ "$(grep -c '^GET /get-api-data' "$work/al.log")" -eq 1 ] && check "and the kept one says when it was read" has "(from the marketplace, read "
  run apilayer list currency
  check "apilayer list WORD keeps the APIs about it" has "  currency_data " && check "also fixer" has "  fixer " && check "not the others" hasnt "  whois "
  check "a listed service of another company is shown apart" has "each is another company's service" && check "by name" has "  coinlayer "
  check "the marketplace's own search is asked too" grep -q '^GET /get-api-data?sort_by=&q=currency' "$work/al.log"
  run apilayer list zzqqnothing
  check "a word no API is about is refused" refused "no API is about \"zzqqnothing\""
  # the marketplace cannot be read: first the list kept from before, then the one in the tool
  python3 - "$TERMANT_KIT_DIR/cache/apilayer-list.json" <<'PY'
import json, sys
kept = json.load(open(sys.argv[1])); kept["when"] -= 3 * 86400
json.dump(kept, open(sys.argv[1], "w"))
PY
  TERMANT_APILAYER_MARKET="$at/down" run apilayer list
  check "when the marketplace is down the list read before is used" has "(26)" && check "and that is said" has "it could not be read now"
  rm -f "$TERMANT_KIT_DIR/cache/apilayer-list.json"
  TERMANT_APILAYER_MARKET="$at/down" run apilayer list
  check "with nothing kept, the list inside the tool is used" has "APIs the user's APILayer key can call (60)" && check "and that is said too" has "the list kept in this tool, of 22 Nov 2026 (the marketplace could not be read now: HTTP 503"
  check "the tool's list has the sixty APIs" has "  world_news " && check "with their free plans" has "Geography API: Searchable database of countries, cities, calling codes, currencies and a lot more. [free: 30000 a month]"

  # one API: plan, endpoints, parameters, the command for each
  run apilayer about exchangerates_data
  check "apilayer about names the API" has "Exchange Rates Data API (exchangerates_data): Real-Time, Intraday & Historical Market Data API."
  check "the free plan and where to subscribe" has "Free plan: 100 requests a month, no card. To use it the user presses \"Subscribe for Free\" once at https://marketplace.apilayer.com/exchangerates_data-api"
  check "that no key is saved yet" has "Key: none saved yet (apilayer key THE_KEY)"
  check "its endpoints" has "Endpoints (6):" && check "with what each does" has "  GET /latest   Returns real-time exchange rate data"
  check "a parameter, whether it is needed, and what it is" has "      amount           required, string, name=value: The amount to be converted."
  check "a value that goes into the address" has "      date             required, string, in the address, in place of {date}"
  check "and the command that calls it" has "      -> apilayer get exchangerates_data/convert amount=... from=... to=..."
  check "a plan written only in a comment of the page is not taken for one" hasnt "requests a day"
  run apilayer about image_to_text
  check "an endpoint that takes a file says so" has "      body             required, file, --file FILE" && check "in its command too" has "      -> apilayer post image_to_text/upload --file FILE"
  run apilayer about geo city
  check "apilayer about NAME WORD keeps the endpoints about it" has "Endpoints (2 of 14):" && check "those only" hasnt "/country/lang/"
  run apilayer about short_url
  check "an API without a free plan says what the cheapest costs" has "No free plan: the cheapest is Starter Plan at \$1.99 a month (30,000 Requests / Monthly)"
  run apilayer about Pika-API
  check "another company's service is named as one" [ "$code" -eq 0 ] && check "that the key does not open" has "This one is not behind APILayer's gateway" && check "with its page" has "https://marketplace.apilayer.com/pika-api"
  run apilayer about exchangerate_data
  check "a name that is not an API is refused" refused "there is no API named exchangerate_data" && check "with the names near it" has "Near it: exchangerates_data"
  n=$(grep -c '^GET /exchangerates_data-api' "$work/al.log")
  run apilayer about exchangerates_data latest
  check "what was read about an API is kept for a day" [ "$(grep -c '^GET /exchangerates_data-api' "$work/al.log")" -eq "$n" ] && check "and still answers" has "Endpoints (1 of 6):"
  TERMANT_APILAYER_MARKET="$at/down" run apilayer about world_news
  check "an API that cannot be read now is not invented" refused "so what world_news offers could not be read now" && check "only what the tool's list knows is said" has "From the list kept in this tool: World News API"

  # the key
  : > "$work/al.log"
  run apilayer get exchangerates_data/latest base=USD symbols=INR
  check "without a key nothing is asked" refused "no APILayer key is saved, so nothing was asked" && check "really nothing" [ ! -s "$work/al.log" ]
  check "it says where the free key comes from" has "press \"Subscribe for Free\"" && check "how to save it" has "apilayer key THE_KEY" && check "and not to make up an answer" has "do not make up an answer"
  run apilayer key "https://app.apilayer.com/auth/github/callback#access_token=eyJhbGciOi.eyJ1c2VyIjo.c2lnbmF0dXJl"
  check "a sign-in link is not taken for the key" refused "that is not the API key" && check "it says what the link is" has "whoever has it is signed in as the user" && check "and saves nothing" [ ! -e "$keyfile" ]
  run apilayer key eyJhbGciOi.eyJ1c2VyIjo.c2lnbmF0dXJl
  check "nor is a sign-in token by itself" refused "Nothing was saved"
  run apilayer key "my key"
  check "something that cannot be a key is refused" refused "does not look like an APILayer key"
  run apilayer key
  check "apilayer key says when none is saved" refused "no key is saved"
  run apilayer key wrongwrongwrongwrongwrongwrong00
  check "apilayer key saves a key" has "Saved the key wron...00 (32 letters)" && check "without printing it whole" hasnt "wrongwrongwrongwrongwrongwrong00"
  check "only the user can read the saved key" [ "$(stat -c %a "$keyfile")" = 600 ]
  run apilayer get exchangerates_data/latest base=USD
  check "a key APILayer does not know is said to be wrong" refused "APILayer does not know this key (HTTP 401: Invalid authentication credentials)" && check "the key is not printed whole there either" hasnt "wrongwrongwrongwrongwrongwrong00"
  run apilayer key nosubscription0123456789abcdefgh
  run apilayer get exchangerates_data/latest base=USD
  check "an API the user has not subscribed to is explained" refused "Most likely the user has not subscribed to this API" && check "with the page where that is done" has "https://marketplace.apilayer.com/exchangerates_data-api once and press \"Subscribe for Free\""
  run apilayer key usedup0123456789usedup0123456789
  run apilayer get exchangerates_data/latest base=USD
  check "requests that are used up are explained" refused "The requests of this plan are used up (HTTP 429"
  run apilayer used
  check "and counted" has "exchangerates_data   0 of 100 left this month"

  # calls
  run apilayer key "$good"
  : > "$work/al.log"
  run apilayer get exchangerates_data/latest base=USD symbols=INR
  check "apilayer get prints the answer" [ "$code" -eq 0 ] && check "as it came" has '"INR": 83.512345'
  check "the key goes in the apikey header, the values in the address" grep -q "^GET /gw/exchangerates_data/latest?base=USD&symbols=INR key=$good " "$work/al.log"
  check "it says how many requests are left" has "apilayer: 99 of 100 requests are left this month for exchangerates_data."
  out=$(apilayer get exchangerates_data/latest base=USD symbols=INR 2>/dev/null)
  check "the count is not mixed into the answer" python3 -c 'import json,sys; assert json.loads(sys.argv[1])["rates"]["INR"] == 83.512345' "$out"
  run apilayer get exchangerates_data-api/latest base=EUR
  check "the name of the API's page works as its name" has '"base": "EUR"'
  run apilayer get "$TERMANT_APILAYER/exchangerates_data/latest" base=GBP
  check "the gateway's whole address works too" has '"base": "GBP"'
  run apilayer get "https://example.com/exchangerates_data/latest" base=GBP
  check "another address is not called" refused "only APILayer's gateway is called" && check "the key did not go there" [ "$(grep -c example "$work/al.log")" -eq 0 ]
  run apilayer get exchangerates_data/latest
  check "a request the API does not accept gives its reason" refused "exchangerates_data did not accept the request (HTTP 400: The base currency is missing.)"
  run apilayer get nosuch/thing
  check "an API that is not there is said" refused "APILayer has nothing at nosuch/thing (HTTP 404: no Route matched with those values)"
  run apilayer get exchangerates_data/latest loose
  check "a word that is not name=value is refused" refused "is neither name=value nor an option"
  run apilayer get exchangerates_data/symbols
  check "a long answer is cut" has "... (cut here: 6000 of " && check "to about that much" [ "${#out}" -lt 7000 ]
  f=$(printf '%s' "$out" | sed -n 's/.*The whole answer is in \(.*\); read the part.*/\1/p')
  check "and kept whole in a file" python3 -c 'import json,sys; assert len(json.load(open(sys.argv[1]))["symbols"]) == 400' "$f"
  run apilayer get exchangerates_data/symbols --all
  check "--all prints it whole" hasnt "cut here" && check "all of it" has '"C399"'
  run apilayer get exchangerates_data/latest base=USD --out "$work/rates.json"
  check "--out saves the answer" has "Saved: $work/rates.json" && check "where it was told" grep -q '"base": "USD"' "$work/rates.json"

  # answers that are files
  run apilayer get screenshot url=https://example.com
  check "a picture that comes back is saved" has "/pictures/screenshot-" && check "and described" has "(a picture, 64x40, "
  f=$(printf '%s\n' "$out" | sed -n 's/^Saved: \(.*\) (a picture.*/\1/p')
  check "the file is the picture" [ "$(head -c 4 "$f" | tail -c 3)" = PNG ]
  check "typed by hand, it is not listed for a chat" [ ! -e "$TERMANT_KIT_DIR/cache/drawn.txt" ]
  TERMANT_CHAT=1 run apilayer get screenshot url=https://example.com --out "$work/shot.png"
  check "in a chat the picture is listed for the reply, from the home folder" [ "$(cat "$TERMANT_KIT_DIR/cache/drawn.txt" 2>/dev/null)" = "$HOME/pictures/shot.png	" ] && check "which is said" has "The chat shows this picture under your answer by itself"
  check "and it is where it was told to be as well" [ -s "$work/shot.png" ]
  rm -f "$TERMANT_KIT_DIR/cache/drawn.txt"
  run apilayer get url_to_pdf url=https://example.com
  check "a PDF that comes back is saved" has "/apilayer/url_to_pdf-" && check "as a PDF" has ".pdf (PDF, "

  # sending things
  printf '\211PNG\r\n\032\nabcd' > "$work/card.png"
  run apilayer post image_to_text/upload --file "$work/card.png"
  check "post --file sends the file" has '"got_bytes": 12' && check "as what it is" has '"got_type": "image/png"' && check "and prints the answer" has '"all_text": "HELLO FROM THE PICTURE"'
  run apilayer post image_to_text/upload --file "$work/none.png"
  check "a file that is not there is refused" refused "there is no file"
  run apilayer post echo/a/b x=1 "q=new delhi" --text "नमस्ते hello"
  check "post --text sends the words" has '"body": "नमस्ते hello"' && check "as text" has '"type": "text/plain; charset=utf-8"' && check "a value with a space arrives whole" has '"q": "new delhi"'
  run apilayer put echo/c --json '{"a": 1}' --header encryption_key=secret
  check "put --json sends JSON" has '"method": "PUT"' && check "named as JSON" has '"type": "application/json"' && check "--header sends a header" has '"encryption_key": "secret"'
  run apilayer post echo/e --form a=1 --form "b=two words"
  check "--form sends a form" has '"body": "a=1&b=two+words"'
  run apilayer delete echo/d
  check "delete works" has '"method": "DELETE"'
  run apilayer post echo/e --json '{bad'
  check "--json that is not JSON is refused before anything is sent" refused "is not JSON"
  run apilayer get echo/e --text hello
  check "get with something to send is refused" refused "use post"
  run apilayer fetch echo/e
  check "a command that is not there is refused" refused "there is no \`fetch\`"

  # a key for one API only, the count, a gateway that is down
  run apilayer key usedup0123456789usedup0123456789 for screenshot
  check "a key can be saved for one API" has "for screenshot only"
  run apilayer get screenshot url=https://example.com
  check "that API is asked with its own key" refused "used up"
  run apilayer get exchangerates_data/latest base=USD
  check "the others with the main one" [ "$code" -eq 0 ]
  run apilayer key
  check "apilayer key lists the keys without showing them" has "A key is saved: good...67 (32 letters)" && check "both" has "A key for screenshot only: used...89" && check "never whole" hasnt "$good"
  run apilayer key remove screenshot
  check "the key for one API can be removed" has "Removed the key for screenshot."
  run apilayer used
  check "apilayer used says what is left for each API" has "of 100 left this month" && check "by name" has "image_to_text "
  TERMANT_APILAYER="$at/down/gw" run apilayer get exchangerates_data/latest base=USD
  check "a gateway that is down is said" refused "exchangerates_data answered HTTP 503"
  TERMANT_APILAYER="http://127.0.0.1:1" run apilayer get exchangerates_data/latest base=USD
  check "a gateway that cannot be reached is said" refused "APILayer could not be reached" && check "without an answer made up" has "do not make up an answer"
  APILAYER_KEY=usedup0123456789usedup0123456789 run apilayer get exchangerates_data/latest base=USD
  check "the saved key comes before one in APILAYER_KEY" [ "$code" -eq 0 ]
  run apilayer key remove
  check "apilayer key remove forgets the key" has "Removed the key."
  run apilayer get exchangerates_data/latest base=USD
  check "after that nothing is asked again" refused "no APILayer key is saved"
  run termant-kit apilayer help
  check "termant-kit apilayer is the same command" has "apilayer about NAME"
  kill $fake_apilayer 2>/dev/null; fake_apilayer=
  unset TERMANT_APILAYER TERMANT_APILAYER_MARKET
else
  echo "FAIL apilayer: the pretend APILayer did not start"; failed=$((failed+1))
fi
unset TERMANT_TURN

if [ -n "$opencode" ]; then
  export PATH="$(dirname "$opencode"):$PATH"
  rm -f "$TERMANT_KIT_DIR/cache/models.json"
  run termant-kit models refresh
  if has "opencode/"; then
    # needs the internet, and which models are free changes from week to week: said, not required
    echo "real-models=yes"
    echo "free-models-that-take-pictures=$(printf '%s\n' "$out" | grep -c '^opencode/.*image.*free')"
  else
    echo "SKIP the real list of models (no answer from OpenCode)"
  fi
else
  echo "SKIP the real list of models (OpenCode is not on this machine)"
fi
if [ "$failed" -eq 0 ]; then echo "RESULT: PASS"; else echo "RESULT: FAIL ($failed)"; exit 1; fi

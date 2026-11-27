# Screen control (the accessibility service is on by now) and GitHub sign-in.
exec > /root/result5.txt 2>&1
echo "== screen"
# the service comes back a moment after the test's own look at the screen
got=1
for _ in 1 2 3 4 5 6 7 8 9 10; do
  if phone screen > /root/screen1.txt 2>&1; then got=0; break; fi
  sleep 2
done
echo "screen-exit=$got"; head -n 30 /root/screen1.txt
echo "== apps"; phone apps > /root/apps.txt 2>&1; echo "apps-exit=$?"; head -n 40 /root/apps.txt
echo "== open another app"
phone app Settings > /root/screen2.txt 2>&1; echo "app-exit=$?"; head -n 30 /root/screen2.txt
grep -q 'com.android.settings' /root/screen2.txt && echo "app-opened=yes"
echo "== read the screen from a picture (Mirage) and tap what was seen"
phone screen ocr > /root/screen-ocr.txt 2>&1; echo "ocr-exit=$?"
sed -n '/Read from a picture/,$p' /root/screen-ocr.txt | head -n 40
grep -q 'could not be read' /root/screen-ocr.txt && grep 'could not be read' /root/screen-ocr.txt
seen=$(sed -n 's/^\([0-9]*\)\. \[seen\] "Network & internet".*/\1/p' /root/screen-ocr.txt | head -n 1)
echo "ocr-item=$seen"
if [ -n "$seen" ]; then
  phone tap "$seen" > /root/screen-ocr-tap.txt 2>&1; echo "ocr-tap-exit=$?"; head -n 12 /root/screen-ocr-tap.txt
  grep -q 'Airplane mode\|Hotspot\|SIMs\|Calls & SMS' /root/screen-ocr-tap.txt && echo "ocr-tap-opened=yes"
  phone key back > /dev/null 2>&1
fi
start=$(date +%s); phone screen ocr > /root/screen-ocr2.txt 2>&1; echo "ocr-again-exit=$? in $(( $(date +%s) - start )) s"
grep 'Read from a picture' /root/screen-ocr2.txt
echo "== look: what is in a photo and where (Mirage)"
ls -l /root/dog.jpg
start=$(date +%s); phone look /root/dog.jpg > /root/look-file.txt 2>&1; echo "look-file-exit=$? in $(( $(date +%s) - start )) s"
cat /root/look-file.txt
grep -q '^Picture dog.jpg (768x576), seen by Mirage' /root/look-file.txt && echo "look-file-head=yes"
grep -q '^- dog, [0-9]*%: ' /root/look-file.txt && echo "look-file-dog=yes"
grep -q '^- dog, [0-9]*%: left, ' /root/look-file.txt && echo "look-file-dog-left=yes"
grep -q '^- bicycle, ' /root/look-file.txt && echo "look-file-bicycle=yes"
(cd /root && phone look dog.jpg 2>&1 | head -n 1 | grep -q '^Picture dog.jpg' && echo "look-relative=yes")
phone look /root/no-such-picture.jpg; echo "look-missing-exit=$?"
phone look /usr/local/bin/phone; echo "look-not-a-picture-exit=$?"
phone look one two; echo "look-usage-exit=$?"
echo "-- the screen as a picture"
phone look > /root/look-screen.txt 2>&1; echo "look-screen-exit=$?"; cat /root/look-screen.txt
grep -q '^The screen as a picture ([0-9]*x[0-9]*), seen by Mirage' /root/look-screen.txt && echo "look-screen-head=yes"
echo "-- the newest picture on the phone"
phone look last > /root/look-last.txt 2>&1; echo "look-last-exit=$?"; head -n 9 /root/look-last.txt
grep -q '^Picture termant-test-photo.jpg, the newest on the phone (768x576)' /root/look-last.txt && echo "look-last-head=yes"
grep -q '^- dog, ' /root/look-last.txt && echo "look-last-dog=yes"
echo "== learning: YouTube, skills, today, ElevenLabs keys (against a pretend YouTube and ElevenLabs in Ubuntu)"
bash /root/learn-check.sh > /root/learn-check.txt 2>&1; echo "learn-check-exit=$?"
grep -v '^PASS' /root/learn-check.txt; echo "learn-check-passed=$(grep -c '^PASS' /root/learn-check.txt)"
echo "== helper agents: the team command, and OpenCode handing parts of a job to helpers (a pretend model answers)"
bash /root/team-check.sh > /root/team-check.txt 2>&1; echo "team-check-exit=$?"
grep -v '^PASS' /root/team-check.txt; echo "team-check-passed=$(grep -c '^PASS' /root/team-check.txt)"
grep -q '^PASS a helper runs a command by itself' /root/team-check.txt && echo "team-real-run=yes"
echo "== the kit: undo, tidy, study, readfile, me, watch, diary, draw, apilayer, attached files"
# the saved APILayer pages the kit check reads (sent as text by emulator-test.sh)
base64 -d /root/apilayer-pages.b64 | tar -xzf - -C /root; echo "apilayer-pages=$(ls /root/apilayer-pages 2>/dev/null | wc -l)"
bash /root/kit-check.sh > /root/kit-check.txt 2>&1; echo "kit-check-exit=$?"
grep -v '^PASS' /root/kit-check.txt; echo "kit-check-passed=$(grep -c '^PASS' /root/kit-check.txt)"
echo "-- a picture into the phone's gallery, and the draw command"
phone gallery /root/dog.jpg > /root/gallery.txt 2>&1; echo "gallery-exit=$?"; cat /root/gallery.txt
sed -n 1p /root/gallery.txt | grep -q "^A copy is in the phone's gallery: Pictures/Termant/dog" && sed -n 2p /root/gallery.txt | grep -q '^content://media/' && echo "gallery-said=yes"
phone gallery /root/t5.sh; echo "gallery-not-a-picture-exit=$?"
draw help | grep -q 'a picture made from words' && echo "draw-command=yes"
phone draw help | grep -q 'draw --wide' && echo "phone-draw=yes"
echo "-- the apilayer command"
apilayer help | grep -q 'apilayer key THE_KEY' && echo "apilayer-command=yes"
apilayer get exchangerates_data/latest base=USD 2>&1 | grep -q 'no APILayer key is saved, so nothing was asked' && echo "apilayer-no-key=yes"
echo "-- rules that repeat, jobs, notifications, a message that needs a yes"
phone rule add every 5m -- true; echo "bad-every-exit=$?"
made=$(phone rule add every 20m -- true); echo "every-rule-exit=$?"; echo "$made"
phone rule remove "$(printf '%s\n' "$made" | sed -n 's/^Rule \([0-9]*\) added.*/\1/p')"
phone job hi; echo "bad-job-exit=$?"
phone notifications 2>&1 | tee /root/notifications.txt | head -n 5
grep -q 'phone notifications allow\|^Notifications, newest last\|^No notifications' /root/notifications.txt && echo "notifications-said=yes"
mkdir -p /root/.cache/termant; : > /root/.cache/termant/alone
TERMANT_CHAT=1 phone sms +911234567890 "termant test, must not go out" 2>&1 | tee /root/sms-alone.txt
grep -q 'not sent' /root/sms-alone.txt && echo "sms-alone-said=yes"
rm -f /root/.cache/termant/alone
# the helper that the chat test hands a part to, later
printf 'POET-MARK You write one short line of verse about the subject you are given.\n' > /root/poet.md
team add poet "Writes one short line of verse on a given subject." /root/poet.md; echo "team-add-exit=$?"
team | tail -n 2
echo "-- phone say with ElevenLabs keys: the second key speaks, the phone plays it"
python3 /root/fake_web.py /root/fake-port & fake=$!
i=0; while [ ! -s /root/fake-port ] && [ $i -lt 100 ]; do sleep 0.2; i=$((i+1)); done
export TERMANT_ELEVEN_URL="http://127.0.0.1:$(cat /root/fake-port)"
phone say before any key; echo "say-own-voice-exit=$?"
phone voice add key-used-up-1 key-good-333; echo "voice-add-exit=$?"
phone say namaste from termant > /root/say.txt 2>&1; echo "say-exit=$?"; cat /root/say.txt
grep -q '^Said with ElevenLabs, key 2 of 2 (key 1 (...up-1): quota_exceeded)\.$' /root/say.txt && echo "say-second-key=yes"
phone say again > /root/say2.txt 2>&1; cat /root/say2.txt
grep -q '^Said with ElevenLabs, key 2 of 2\.$' /root/say2.txt && echo "say-skips-used-up-key=yes"
phone voice > /root/voice.txt 2>&1; cat /root/voice.txt
grep -q '^1\. \.\.\.up-1 .*resting until' /root/voice.txt && grep -q '^2\. \.\.\.-333 .*in use' /root/voice.txt && ! grep -q 'key-good' /root/voice.txt && echo "voice-report=yes"
phone play /root/.cache/termant/speech.mp3 > /root/play.txt 2>&1; echo "play-exit=$?"; cat /root/play.txt
grep -q '^Playing speech.mp3 (1 s)' /root/play.txt && echo "play-length=yes"
phone play /usr/local/bin/phone; echo "play-not-a-sound-exit=$?"
phone play /root/no-such-sound.mp3; echo "play-missing-exit=$?"
echo "-- the Gemini voice: a free Google key that speaks Hindi and Odia"
export TERMANT_GEMINI_URL="http://127.0.0.1:$(cat /root/fake-port)"
phone voice gemini add not-a-key; echo "gemini-bad-key-exit=$?"
phone voice gemini add AIzaSyDfakegeminikeygood00000 > /root/gemini.txt 2>&1; echo "gemini-add-exit=$?"; cat /root/gemini.txt
grep -qF 'Gemini key ...0000 saved; the Gemini voice speaks now' /root/gemini.txt && echo "gemini-add-said=yes"
phone say "namaste, kaise ho?" > /root/gsay.txt 2>&1; echo "gsay-exit=$?"; cat /root/gsay.txt
grep -q '^Said with the Gemini voice (Google, your free key)\.$' /root/gsay.txt && echo "gsay-said=yes"
grep -q 'AIzaSyDfakegeminikeygood00000 gemini-3.8-flash-lite-tts namaste, kaise ho?' /root/fake-port.gemini-asked && echo "gsay-asked=yes"
python3 -c "
import wave
w = wave.open('/root/.cache/termant/speech.wav')
assert w.getnchannels() == 1 and w.getframerate() == 24000 and w.getnframes() == 24000
" && echo "gsay-wav=yes"
phone play /root/.cache/termant/speech.wav > /root/gplay.txt 2>&1; echo "gplay-exit=$?"; cat /root/gplay.txt
grep -q '^Playing speech.wav (1 s)' /root/gplay.txt && echo "gplay-length=yes"
phone voice > /root/gvoice.txt 2>&1; cat /root/gvoice.txt
grep -qxF 'Gemini key ...0000 saved; the Gemini voice speaks now.' /root/gvoice.txt && echo "gvoice-report=yes"
phone voice gemini add AIzaSyDfakegeminikeywrong00000 > /dev/null
phone say the key is wrong > /root/gbad.txt 2>&1; echo "gbad-exit=$?"; cat /root/gbad.txt
grep -q 'Google refused the key' /root/gbad.txt && grep -q 'the voice did not speak, so the phone' /root/gbad.txt && echo "gbad-fell-back=yes"
phone voice engine elevenlabs > /root/gengine.txt 2>&1; echo "gengine-exit=$?"; cat /root/gengine.txt
grep -q 'The elevenlabs voice speaks now' /root/gengine.txt && echo "gengine-switch=yes"
kill $fake 2>/dev/null; unset TERMANT_ELEVEN_URL TERMANT_GEMINI_URL
rm -f /root/.config/termant/voice.keys /root/.config/termant/voice.json /root/.config/termant/gemini.key /root/fake-port.gemini-asked
echo "-- the real YouTube from this machine (told, not required: YouTube often refuses data centres)"
learn youtube search how to make a table in Word > /root/youtube-search.txt 2>&1; echo "youtube-real-search-exit=$?"; head -n 12 /root/youtube-search.txt
real=$(sed -n 's/^   id \([A-Za-z0-9_-]\{11\}\)$/\1/p' /root/youtube-search.txt | head -n 1)
if [ -n "$real" ]; then
  learn youtube read "$real" > /root/youtube-read.txt 2>&1; echo "youtube-real-read-exit=$?"; head -c 900 /root/youtube-read.txt; echo
  grep -q '^What is said (' /root/youtube-read.txt && echo "youtube-real-words=yes"
fi
echo "-- how long OpenCode needs to start, and how long a kept server needs to answer"
export PATH="$HOME/.opencode/bin:$PATH" NO_COLOR=1
if command -v opencode >/dev/null 2>&1; then
  ms() { date +%s%3N; }
  for n in 1 2; do s=$(ms); opencode session list --standalone --max-count 1 --format json >/dev/null 2>&1 </dev/null; echo "start-own-server-$n=$(( $(ms) - s )) ms (exit $?)"; done
  export OPENCODE_PASSWORD=ci-speed-test
  ( sleep 120 ) | opencode serve --stdio --port 0 > /root/speed-server.txt 2>/dev/null &
  url=; i=0
  while [ $i -lt 600 ] && [ -z "$url" ]; do url=$(sed -n 's/.*"url":"\([^"]*\)".*/\1/p' /root/speed-server.txt | head -n 1); [ -n "$url" ] || sleep 0.1; i=$((i+1)); done
  echo "speed-server-url=${url:+yes}"
  if [ -n "$url" ]; then
    for n in 1 2 3; do s=$(ms); opencode session list --server "$url" --max-count 1 --format json >/dev/null 2>&1 </dev/null; echo "ask-kept-server-$n=$(( $(ms) - s )) ms (exit $?)"; done
  fi
  pkill -f 'sleep 120' 2>/dev/null
  unset OPENCODE_PASSWORD
fi
echo "== swipe"; phone swipe up > /root/screen3.txt 2>&1; echo "swipe-exit=$?"; head -n 14 /root/screen3.txt
# the list must be the scrolled one, not the one remembered from before
[ "$(grep '^[0-9]' /root/screen2.txt)" != "$(grep '^[0-9]' /root/screen3.txt)" ] && echo "swipe-moved=yes"
phone swipe down > /dev/null 2>&1
echo "== tap by its words"
label=$(phone screen | sed -n 's/^[0-9]*\. \[[a-z, ]*\] "\([^"]*\)".*/\1/p' | sed -n 2p)
echo "tapping: $label"
phone tap "$label" > /root/screen4.txt 2>&1; echo "tap-exit=$?"; head -n 14 /root/screen4.txt
echo "== tap by number"; phone tap 1 > /root/screen5.txt 2>&1; echo "tap-number-exit=$?"; head -n 6 /root/screen5.txt
echo "== type (reported only: depends on what is on the screen)"; phone type "wifi"; echo "type-exit=$?"
echo "== keys"
phone key back > /dev/null 2>&1; echo "key-back-exit=$?"
phone key home > /root/screen6.txt 2>&1; echo "key-home-exit=$?"; head -n 4 /root/screen6.txt
phone key sideways; echo "bad-key-exit=$?"
phone app NoSuchAppAtAll; echo "bad-app-exit=$?"
echo "== back to Termant"; phone app Termant > /dev/null 2>&1; echo "return-exit=$?"

echo "== github"
(
  export HOME=/root/ci-gh GH_TOKEN=ci-token-not-real GITHUB_USER=ci-user GITHUB_EMAIL=1+ci-user@users.noreply.github.com
  unset GIT_CONFIG_COUNT
  mkdir -p "$HOME"
  . /usr/local/lib/termant/github.sh
  echo "token-for-gh=${GITHUB_TOKEN:+yes}"
  printf 'protocol=https\nhost=github.com\n\n' | GIT_TERMINAL_PROMPT=0 git credential fill 2>&1 | sed 's/^/cred: /'
  printf 'protocol=https\nhost=example.com\n\n' | GIT_TERMINAL_PROMPT=0 git credential fill 2>&1 | sed 's/^/other: /'
  echo "git-name=$(git config --global user.name)"
  echo "git-email=$(git config --global user.email)"
)
echo "== web search in the agent's settings"; cat /root/.config/opencode/opencode.jsonc
echo "== the persona file the app writes before the agent (none is chosen: empty)"
if [ -f /root/.config/opencode/persona ]; then
  echo "persona-file=kept"
  echo "persona-bytes=$(wc -c < /root/.config/opencode/persona)"
else
  echo "persona-file=missing"
fi
echo TERMANT-TEST-END

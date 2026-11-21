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
echo TERMANT-TEST-END

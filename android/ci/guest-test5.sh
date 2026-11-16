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

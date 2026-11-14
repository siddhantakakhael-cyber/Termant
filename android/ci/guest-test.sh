# Runs inside Ubuntu on the emulator, typed into the terminal by emulator-test.sh.
exec > /root/result.txt 2>&1
echo "== os"; grep -E '^(PRETTY_NAME|VERSION_ID)=' /etc/os-release
echo "== id"; id
echo "== uname"; uname -a
echo "== tty"; tty; stty size
echo "== apt update"; apt-get update; echo "apt-update-exit=$?"
echo "== apt install"
DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends hello
echo "apt-install-exit=$?"
echo "== run"; hello; echo "hello-exit=$?"
echo "== misc"
ls -la / | head -40
uptime
ls /sdcard | head -5
echo TERMANT-TEST-END

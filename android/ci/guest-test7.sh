# Learning: the "learn" command keeps notes and tools across chats, and the
# file the agent reads at the start of every chat follows them.
exec > /root/result7.txt 2>&1
guide=/root/.config/termant/learned.md
echo "== notes the agent saved by itself before this test"; learn notes
echo "== note"
learn note "The user's secret fruit is papaya."; echo "note-exit=$?"
learn note "The user's secret fruit is papaya."; echo "again-exit=$?"
learn notes | grep -c papaya | sed 's/^/papaya-notes=/'
learn note "$(head -c 300 /dev/zero | tr '\0' x)"; echo "long-exit=$?"
learn note "temporary note for the test"; n=$(learn notes | grep -c .)
learn forget "$n"; echo "forget-exit=$?"
if learn notes | grep -q "temporary note"; then echo "forgotten=no"; else echo "forgotten=yes"; fi
echo "== tool"
printf 'echo hello-from-tool-$((6*7))\n' > /tmp/ci-hello.sh
learn tool ci-hello "Prints the greeting of the test" /tmp/ci-hello.sh; echo "tool-exit=$?"
echo "tool-says=$(ci-hello)"
learn tool ls "Lists files" /tmp/ci-hello.sh; echo "clash-exit=$?"
learn tool Bad_Name "Something" /tmp/ci-hello.sh; echo "badname-exit=$?"
printf 'if then\n' > /tmp/ci-bad.sh
learn tool ci-bad "Broken on purpose" /tmp/ci-bad.sh; echo "syntax-exit=$?"
if [ -e /usr/local/bin/ci-bad ]; then echo "bad-kept=yes"; else echo "bad-kept=no"; fi
learn tools
echo "== the file the agent reads"
grep -c papaya "$guide" | sed 's/^/guide-note=/'
grep -c '`ci-hello`: Prints the greeting of the test' "$guide" | sed 's/^/guide-tool=/'
echo "== turned off"
touch /root/.config/termant/learned/off
learn note "must not be saved"; echo "off-exit=$?"
learn refresh
if [ -e "$guide" ]; then echo "off-guide=there"; else echo "off-guide=gone"; fi
rm /root/.config/termant/learned/off
learn refresh
if [ -e "$guide" ]; then echo "on-guide=there"; else echo "on-guide=gone"; fi
echo TERMANT-TEST-END

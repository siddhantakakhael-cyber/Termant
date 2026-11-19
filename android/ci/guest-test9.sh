# Asked for by the emulator test when a reply was refused with OpenCode's
# free limit: is a brand-new conversation refused too, or only that one?
exec > /root/result9.txt 2>&1
echo "== the last events of the refused reply"
tail -c 3000 /root/.cache/termant/events.txt 2>/dev/null
echo
echo "== a new conversation with one of OpenCode's free models"
cd /root && timeout 120 opencode run --standalone --model opencode/big-pickle "Reply only with the word OK"
echo "fresh-exit=$?"
echo TERMANT-TEST-END

# Questions from the agent: over another app (a card), then inside Termant (a dialog).
# The test on the computer taps the answers; this script only asks and writes down what came back.
exec > /root/result6.txt 2>&1
phone app Settings > /dev/null 2>&1; echo "open-exit=$?"
a=$(phone ask "CI: which one?" "Alpha" "Beta"); echo "ask1-exit=$? answer=$a"
b=$(phone ask "CI: type a word"); echo "ask2-exit=$? answer=$b"
c=$(phone ask "CI: skip this one" "Yes" "No" 2>&1); echo "ask3-exit=$? said=$c"
phone ask; echo "ask-usage-exit=$?"
phone ask "Too many" 1 2 3 4 5 6 7; echo "ask-many-exit=$?"
phone app Termant > /dev/null 2>&1; echo "return-exit=$?"
sleep 2
d=$(phone ask "CI: inside Termant" "One" "Two"); echo "ask4-exit=$? answer=$d"
echo TERMANT-TEST-END

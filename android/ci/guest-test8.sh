# Check-ins: the "phone care" command plans the moments at which the agent
# writes to the user first. Ends by planning one a few minutes from now.
exec > /root/result8.txt 2>&1
echo "== before: turned off by the test's settings"
phone care
phone care in 5m "must be refused while off"; echo "off-exit=$?"
phone care now; echo "off-now-exit=$?"
echo "== times"
phone care times 25:00; echo "badtime-exit=$?"
phone care times 1:00 2:00 3:00 4:00 5:00; echo "manytimes-exit=$?"
phone care times 3:33am; echo "times-exit=$?"
phone care on; echo "on-exit=$?"
phone care | grep -c '^Daily at: 03:33$' | sed 's/^/times-kept=/'
echo "== planning"
phone care in 3d "CI topic far away"; echo "plan-exit=$?"
phone care in 2d "ci TOPIC far away"; echo "twice-exit=$?"
phone care | grep -c 'CI topic far away' | sed 's/^/listed=/'
id=$(phone care | grep 'CI topic far away' | awk '{print $1}')
phone care cancel "$id"; echo "cancel-exit=$?"
phone care cancel "$id"; echo "cancel-again-exit=$?"
phone care | grep -c 'CI topic far away' | sed 's/^/left=/'
phone care in 0m "too soon"; echo "zero-exit=$?"
phone care in 3h; echo "notopic-exit=$?"
phone care in soon "what"; echo "badwhen-exit=$?"
phone care at 12:30 "CI lunch"; echo "at-exit=$?"
id=$(phone care | grep 'CI lunch' | awk '{print $1}')
phone care cancel "$id"; echo "at-cancel-exit=$?"
echo "== in four minutes, about something the agent has a note on"
phone care in 4m "which colour the user likes best: name the colour from your notes in your question"; echo "soon-exit=$?"
phone care
echo TERMANT-TEST-END

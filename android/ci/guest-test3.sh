# AI agent: OpenCode must install and start inside Ubuntu.
exec > /root/result3.txt 2>&1
termant-agent --install-only; echo "agent-install-exit=$?"
export PATH="$HOME/.opencode/bin:$PATH"
echo "== version"; opencode --version; echo "version-exit=$?"
echo "== auth"; timeout 60 opencode auth list 2>&1 | head -20
echo "== models"; timeout 120 opencode models 2>&1 | head -60
mkdir -p /root/workspace && cd /root/workspace
echo "== run"; timeout 240 opencode run --standalone "Reply with exactly this text and nothing else: TERMANT-AGENT-OK" 2>&1 | tail -40; echo "run-exit=$?"
echo "== phone"
phone battery; echo "battery-exit=$?"
phone info; echo "info-exit=$?"
phone alarm 25:00; echo "bad-alarm-exit=$?"
phone notify "Termant test" "Hello from Ubuntu"; echo "notify-exit=$?"
phone clipboard set "termant clip"; phone clipboard get; echo "clipboard-exit=$?"
phone alarm 06:45 CI-direct; echo "alarm-exit=$?"
phone timer 4s CI-timer; echo "timer-exit=$?"
sleep 9; phone stop; echo "stop-exit=$?"
echo "== rules"
phone rule add at 25:00 -- true; echo "bad-rule-exit=$?"
phone rule add sometimes -- true; echo "bad-trigger-exit=$?"
phone rule add charging -- 'date > /root/charged.txt'; echo "rule-add-exit=$?"
phone rule add at "$(date -d '+2 min' +%H:%M)" -- 'echo timed-rule-ran > /root/timed.txt'; echo "rule-timed-exit=$?"
phone rule add unplugged -- 'echo this rule fails; exit 3'; echo "rule-fail-add-exit=$?"
phone rule run 1; sleep 6; echo "manual: $(cat /root/charged.txt)"; rm -f /root/charged.txt
phone rule off 3; phone rule list
phone volume 30; echo "volume-exit=$?"
phone sms 12345; echo "bad-sms-exit=$?"
echo "== agent sets an alarm"
timeout 240 opencode run --standalone --auto "mujhe subah 7 baje utha dena" 2>&1 | tail -40; echo "wake-exit=$?"
echo "== raw output of a coding request (what the chat has to read)"
timeout 240 opencode run --standalone --auto --continue "Write hi.py that prints hi, run it, and tell me what it printed" </dev/null 2>&1 | cat -v | tail -60; echo "code-exit=$?"
echo "== timed rule"; for i in $(seq 1 30); do [ -f /root/timed.txt ] && break; sleep 5; done; date; cat /root/timed.txt; echo "timed-exit=$?"; phone rule list
echo TERMANT-TEST-END

# AI agent: OpenCode must install and start inside Ubuntu.
exec > /root/result3.txt 2>&1
termant-agent --install-only; echo "agent-install-exit=$?"
export PATH="$HOME/.opencode/bin:$PATH"
echo "== version"; opencode --version; echo "version-exit=$?"
echo "== auth"; timeout 60 opencode auth list 2>&1 | head -20
echo "== models"; timeout 120 opencode models 2>&1 | head -60
mkdir -p /root/workspace && cd /root/workspace
echo "== run"; timeout 240 opencode run --standalone "Reply with exactly this text and nothing else: TERMANT-AGENT-OK" 2>&1 | tail -40; echo "run-exit=$?"
echo TERMANT-TEST-END

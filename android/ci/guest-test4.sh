# Settings made in the app must reach Ubuntu; then switch Wi-Fi off like a rule would.
exec > /root/result4.txt 2>&1
export PATH="$HOME/.opencode/bin:$PATH"
echo "== key"; . /root/.config/termant/keys.env; echo "key-set=${OPENROUTER_API_KEY:+yes}"
ls -l /root/.config/termant/keys.env
echo "== auth"; timeout 60 opencode auth list --standalone 2>&1 | head -10
echo "== models with the key"; timeout 120 opencode models --standalone 2>/dev/null | grep -c '^openrouter/'
echo "== every provider name in the settings list"
for v in ANTHROPIC_API_KEY OPENAI_API_KEY GOOGLE_GENERATIVE_AI_API_KEY GROQ_API_KEY DEEPSEEK_API_KEY CEREBRAS_API_KEY MISTRAL_API_KEY; do export $v=termant-ci-not-a-real-key; done
timeout 60 opencode auth list --standalone 2>&1 | head -20
echo "== models per provider"; timeout 120 opencode models --standalone 2>/dev/null | cut -d/ -f1 | sort | uniq -c
echo "== rules"; phone rule list
echo "charged: $(cat /root/charged.txt 2>&1)"
echo "== screen control is off until the user turns it on"; phone screen; echo "screen-off-exit=$?"
echo "== wifi"; phone wifi off; echo "wifi-off-exit=$?"
sleep 4
echo TERMANT-TEST-END

# Settings made in the app must reach Ubuntu; then switch Wi-Fi off like a rule would.
exec > /root/result4.txt 2>&1
export PATH="$HOME/.opencode/bin:$PATH"
echo "== key"; . /root/.config/termant/keys.env; echo "key-set=${OPENROUTER_API_KEY:+yes}"
ls -l /root/.config/termant/keys.env
echo "== auth"; timeout 60 opencode auth list --standalone 2>&1 | head -10
echo "== models with the key"; timeout 120 opencode models --standalone 2>/dev/null | grep -c '^openrouter/'
echo "== rules"; phone rule list
echo "charged: $(cat /root/charged.txt 2>&1)"
echo "== wifi"; phone wifi off; echo "wifi-off-exit=$?"
sleep 4
echo TERMANT-TEST-END

export PATH="$HOME/.opencode/bin:$PATH"
if [ ! -e "$HOME/.termant-welcome" ]; then
  touch "$HOME/.termant-welcome"
  printf '\nWelcome to Ubuntu on Termant.\n\nInstall software:\n  apt update\n  apt install <package>\n\nAI agent: tap Chat and just ask.\nPhone controls: phone help\n\n'
fi
# API keys entered in Termant's settings screen.
[ -f "$HOME/.config/termant/keys.env" ] && . "$HOME/.config/termant/keys.env"
[ -f /usr/local/lib/termant/github.sh ] && . /usr/local/lib/termant/github.sh

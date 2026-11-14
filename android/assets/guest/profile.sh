export PATH="$HOME/.opencode/bin:$PATH"
if [ ! -e "$HOME/.termant-welcome" ]; then
  touch "$HOME/.termant-welcome"
  printf '\nWelcome to Ubuntu on Termant.\n\nInstall software:\n  apt update\n  apt install <package>\n\nAI agent: tap AI, then just ask.\nPhone controls: phone help\n\n'
fi

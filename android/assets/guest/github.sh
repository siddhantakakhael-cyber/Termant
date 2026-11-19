# GitHub: with a token saved in Termant's settings, git (over https) and the
# `gh` tool are signed in. Read by login shells and by the chat.
if [ -n "$GH_TOKEN" ]; then
  export GITHUB_TOKEN="$GH_TOKEN"
  if [ -z "$GIT_CONFIG_COUNT" ]; then
    export GIT_CONFIG_COUNT=2
    export GIT_CONFIG_KEY_0=credential.https://github.com.helper
    export GIT_CONFIG_VALUE_0=/usr/local/lib/termant/git-credential
    export GIT_CONFIG_KEY_1=credential.https://gist.github.com.helper
    export GIT_CONFIG_VALUE_1=/usr/local/lib/termant/git-credential
  fi
  # commits need a name; use the GitHub account unless git already has settings
  if [ -n "$GITHUB_USER" ] && [ ! -e "$HOME/.gitconfig" ] && [ ! -e "$HOME/.config/git/config" ]; then
    printf '[user]\n\tname = %s\n\temail = %s\n[init]\n\tdefaultBranch = main\n' \
      "$GITHUB_USER" "${GITHUB_EMAIL:-$GITHUB_USER@users.noreply.github.com}" > "$HOME/.gitconfig"
  fi
fi

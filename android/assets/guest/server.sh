# Sourced by the chat script and by termant-server (Termant's Chat.java runs both).
# OpenCode needs seconds to start. Termant keeps one OpenCode server running
# between messages, so that only the first message pays for the start.
termant_keep="$HOME/.cache/termant/server"

# What must stay the same for a kept server to be used again: the keys, the
# GitHub sign-in, what the agent has learned, and OpenCode's settings.
termant_stamp() {
  cat "$HOME/.config/termant/keys.env" /usr/local/lib/termant/github.sh "$HOME/.config/termant/github.env" \
    "$HOME/.config/termant/learned.md" "$HOME"/.config/opencode/opencode.json* \
    /usr/local/lib/termant/AGENTS.md 2>/dev/null | cksum
}

# open_feed URL FILE: copies the server's event feed to FILE; 0 when the feed is open.
# Sets $feed. The feed must be open before the question is asked, or the first words are lost.
open_feed() {
  local i=0
  : > "$2"
  curl -sN -u "opencode:$OPENCODE_PASSWORD" "$1/api/event" > "$2" 2>/dev/null &
  feed=$!
  while [ $i -lt 50 ] && [ ! -s "$2" ] && kill -0 $feed 2>/dev/null; do sleep 0.1; i=$((i+1)); done
  [ -s "$2" ] && return 0
  kill $feed 2>/dev/null
  feed=
  return 1
}

# use_kept FILE: when the kept server is there and still fits, sets $url, $where and
# OPENCODE_PASSWORD and opens its feed into FILE. Otherwise leaves everything as it was.
use_kept() {
  local kurl kstamp kpass
  # which way each message went, newest last: "own" (started OpenCode itself) or "kept"
  mkdir -p "$(dirname "$termant_keep")" 2>/dev/null
  echo "$(date +%T) own" >> "$termant_keep.history" 2>/dev/null
  [ -n "$1" ] && [ -s "$termant_keep.env" ] && command -v curl >/dev/null 2>&1 || return 1
  kurl=$(sed -n 's/^url=//p' "$termant_keep.env")
  kpass=$(sed -n 's/^password=//p' "$termant_keep.env")
  kstamp=$(sed -n 's/^stamp=//p' "$termant_keep.env")
  [ -n "$kurl" ] && [ -n "$kpass" ] || return 1
  if [ "$kstamp" != "$(termant_stamp)" ]; then
    # something it was started with has changed: it goes, and the next message gets a new one
    : > "$termant_keep.stop"
    return 1
  fi
  mkdir -p "$(dirname "$1")" || return 1
  export OPENCODE_PASSWORD=$kpass
  if open_feed "$kurl" "$1"; then
    url=$kurl
    where="--server $url"
    sed -i '$ s/ own$/ kept/' "$termant_keep.history" 2>/dev/null
    # says "in use" for as long as this chat script lives, however long the answer takes
    ( while kill -0 $$ 2>/dev/null; do touch "$termant_keep.used"; sleep 30; done ) >/dev/null 2>&1 &
    return 0
  fi
  unset OPENCODE_PASSWORD
  return 1
}

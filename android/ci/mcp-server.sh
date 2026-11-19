#!/bin/bash
# A tiny MCP server for the emulator test: answers the handshake and offers
# one tool, and notes every request so the test can see that OpenCode really
# started it from the configuration Termant wrote.
log=/root/mcp-ci.log
while IFS= read -r line; do
  id=$(printf '%s' "$line" | sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p')
  method=$(printf '%s' "$line" | sed -n 's/.*"method":"\([^"]*\)".*/\1/p')
  echo "$method" >> "$log"
  [ -n "$id" ] || continue # a notification: no answer expected
  case "$method" in
    initialize)
      printf '{"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":"2024-11-05","capabilities":{"tools":{}},"serverInfo":{"name":"termant-ci","version":"1.0"}}}\n' "$id" ;;
    tools/list)
      printf '{"jsonrpc":"2.0","id":%s,"result":{"tools":[{"name":"ci_ping","description":"Returns pong. Only for a test.","inputSchema":{"type":"object","properties":{}}}]}}\n' "$id" ;;
    tools/call)
      printf '{"jsonrpc":"2.0","id":%s,"result":{"content":[{"type":"text","text":"pong"}]}}\n' "$id" ;;
    ping)
      printf '{"jsonrpc":"2.0","id":%s,"result":{}}\n' "$id" ;;
    *)
      printf '{"jsonrpc":"2.0","id":%s,"error":{"code":-32601,"message":"Method not found"}}\n' "$id" ;;
  esac
done

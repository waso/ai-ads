#!/usr/bin/env bash
# An interactive chat with Claude Code about the account, using the chat loop's token. Same rules as the scheduled loops:
# Claude can read and propose; you approve on the admin page.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOME_DIR="${AUTOPILOT_HOME:-$HOME/.ads-autopilot}"
[ -f "$HOME_DIR/env" ] || { echo "Run scripts/dev-init.sh first" >&2; exit 1; }
set -a
# shellcheck disable=SC1091
. "$HOME_DIR/env"
set +a
[ -n "${MCP_TOKEN_CHAT:-}" ] || { echo "MCP_TOKEN_CHAT is not set in $HOME_DIR/env" >&2; exit 1; }

port="${PORT:-8080}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
chmod 700 "$work"
cat > "$work/mcp.json" <<JSON
{"mcpServers":{"ads-autopilot":{"type":"http","url":"http://127.0.0.1:$port/mcp","headers":{"Authorization":"Bearer $MCP_TOKEN_CHAT"}}}}
JSON
chmod 600 "$work/mcp.json"

cd "$work"
claude --mcp-config "$work/mcp.json" --strict-mcp-config --tools "" --allowedTools "mcp__ads-autopilot__*" \
  --append-system-prompt "$(cat "$ROOT/prompts/chat-v1.md")" "$@"

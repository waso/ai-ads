#!/usr/bin/env bash
# Runs one agent loop with Claude Code against the local server, using that loop's own token and prompt.
#   scripts/run-loop.sh triage|daily|weekly
# Claude Code gets no built-in tools (no shell, no files, no web): only this server's MCP tools. Uses your Claude Code login
# (or ANTHROPIC_API_KEY when set). Every run costs tokens; MAX_BUDGET_USD caps one run (default 2).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOME_DIR="${AUTOPILOT_HOME:-$HOME/.ads-autopilot}"
loop="${1:-}"
case "$loop" in triage|daily|weekly) ;; *) echo "usage: $0 triage|daily|weekly" >&2; exit 2 ;; esac

[ -f "$HOME_DIR/env" ] || { echo "Run scripts/dev-init.sh first" >&2; exit 1; }
set -a
# shellcheck disable=SC1091
. "$HOME_DIR/env"
set +a

upper="$(echo "$loop" | tr '[:lower:]' '[:upper:]')"
token_var="MCP_TOKEN_$upper"
token="${!token_var:-}"
[ -n "$token" ] || { echo "$token_var is not set in $HOME_DIR/env" >&2; exit 1; }
prompt_file="$ROOT/prompts/$loop-v1.md"
[ -f "$prompt_file" ] || { echo "missing $prompt_file" >&2; exit 1; }
command -v claude >/dev/null || { echo "Claude Code (claude) is not installed" >&2; exit 1; }

port="${PORT:-8080}"
curl -fsS "http://127.0.0.1:$port/actuator/health" >/dev/null 2>&1 || { echo "The server is not running on port $port. Start scripts/dev-start.sh first." >&2; exit 1; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
chmod 700 "$work"
mkdir -p "$HOME_DIR/runs"

# The token goes into a private temporary config file that is deleted when the run ends.
cat > "$work/mcp.json" <<JSON
{"mcpServers":{"ads-autopilot":{"type":"http","url":"http://127.0.0.1:$port/mcp","headers":{"Authorization":"Bearer $token"}}}}
JSON
chmod 600 "$work/mcp.json"

stamp="$(date +%Y%m%d-%H%M%S)"
out="$HOME_DIR/runs/$stamp-$loop.json"
args=(-p "Run the $loop loop now. Follow your instructions exactly and finish with record_run_summary."
  --mcp-config "$work/mcp.json" --strict-mcp-config
  --tools ""
  --allowedTools "mcp__ads-autopilot__*"
  --permission-mode dontAsk
  --append-system-prompt "$(cat "$prompt_file")"
  --max-budget-usd "${MAX_BUDGET_USD:-2}"
  --no-session-persistence
  --output-format json)
[ -n "${MAX_TURNS:-}" ] && args+=(--max-turns "$MAX_TURNS")
[ -n "${CLAUDE_MODEL:-}" ] && args+=(--model "$CLAUDE_MODEL")

echo "Running the $loop loop (output: $out)"
# An empty working directory keeps any project instructions or settings out of the run.
cd "$work"
timeout_s="${RUN_TIMEOUT_SECONDS:-900}"
set +e
if command -v perl >/dev/null; then
  # SIGALRM ends Claude Code after the timeout; perl is on every Mac
  perl -e 'alarm shift; exec @ARGV' "$timeout_s" claude "${args[@]}" > "$out"
else
  claude "${args[@]}" > "$out"
fi
code=$?
set -e

if [ "$code" -eq 142 ]; then
  echo "The run was stopped after ${timeout_s}s. See $out" >&2
  exit "$code"
elif [ "$code" -ne 0 ]; then
  echo "Claude Code exited with status $code. See $out" >&2
  exit "$code"
fi
if command -v jq >/dev/null; then
  jq -r '.result // .' "$out" 2>/dev/null | head -60
  jq -r '(.total_cost_usd // empty) | "Cost: $\(.)"' "$out" 2>/dev/null || true
else
  head -c 3000 "$out"
fi
echo
echo "Full output: $out"

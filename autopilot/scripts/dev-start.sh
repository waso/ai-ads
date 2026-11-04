#!/usr/bin/env bash
# Starts the whole stack on this machine: PostgreSQL (embedded, data in ~/.ads-autopilot/pg), the server on 127.0.0.1:8080,
# the in-process watchdog. Stop it with Ctrl-C.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOME_DIR="${AUTOPILOT_HOME:-$HOME/.ads-autopilot}"

[ -f "$HOME_DIR/env" ] || "$ROOT/scripts/dev-init.sh"
set -a
# shellcheck disable=SC1091
. "$HOME_DIR/env"
set +a

cd "$ROOT"
command -v mvn >/dev/null || { echo "Maven (mvn) is required" >&2; exit 1; }
mvn -q -B -DskipTests test-compile

CP_FILE="target/local-classpath.txt"
if [ ! -s "$CP_FILE" ] || [ pom.xml -nt "$CP_FILE" ]; then
  mvn -q -B dependency:build-classpath -Dmdep.outputFile="$CP_FILE" -Dmdep.includeScope=test
fi

echo
echo "Admin page:      http://localhost:${PORT:-8080}/admin   (password: grep DEV_LOGIN_PASSWORD $HOME_DIR/env)"
[ "${ADS_MODE:-sandbox}" = "sandbox" ] && echo "Sandbox page:    http://localhost:${PORT:-8080}/dev/sandbox"
echo "MCP endpoint:    http://127.0.0.1:${PORT:-8080}/mcp"
echo

exec java -XX:+EnableDynamicAgentLoading --enable-native-access=ALL-UNNAMED \
  -cp "target/classes:target/test-classes:$(cat "$CP_FILE")" app.autopilot.LocalDevApplication

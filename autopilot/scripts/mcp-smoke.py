#!/usr/bin/env python3
"""Checks that the local server answers MCP with a loop's token: initialize, then list the tools.

    python3 scripts/mcp-smoke.py [chat|daily|triage|weekly]

Reads ~/.ads-autopilot/env (or $AUTOPILOT_HOME/env). It starts no run and changes nothing.
"""
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

loop = (sys.argv[1] if len(sys.argv) > 1 else "chat").lower()
home = Path(os.environ.get("AUTOPILOT_HOME", Path.home() / ".ads-autopilot"))
env = {}
for line in (home / "env").read_text().splitlines():
    line = line.strip()
    if line and not line.startswith("#") and "=" in line:
        k, v = line.split("=", 1)
        env[k] = v
token = os.environ.get(f"MCP_TOKEN_{loop.upper()}") or env.get(f"MCP_TOKEN_{loop.upper()}")
if not token:
    sys.exit(f"MCP_TOKEN_{loop.upper()} is not set")
url = f"http://127.0.0.1:{os.environ.get('PORT', env.get('PORT', '8080'))}/mcp"


def post(payload, session=None):
    headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json",
               "Accept": "application/json, text/event-stream", "MCP-Protocol-Version": "2025-06-18"}
    if session:
        headers["Mcp-Session-Id"] = session
    req = urllib.request.Request(url, json.dumps(payload).encode(), headers)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            body = r.read().decode()
            return r.headers.get("Mcp-Session-Id"), body
    except urllib.error.HTTPError as e:
        sys.exit(f"HTTP {e.code} from {url}: {e.read().decode()[:300]}")
    except urllib.error.URLError as e:
        sys.exit(f"Cannot reach {url}: {e.reason}. Is scripts/dev-start.sh running?")


def data(body):
    # the server may answer with plain JSON or a single server-sent event
    for line in body.splitlines():
        if line.startswith("data:"):
            return json.loads(line[5:])
    return json.loads(body)


session, body = post({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
    "protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "smoke", "version": "0"}}})
info = data(body)["result"]["serverInfo"]
post({"jsonrpc": "2.0", "method": "notifications/initialized"}, session)
_, body = post({"jsonrpc": "2.0", "id": 2, "method": "tools/list"}, session)
tools = [t["name"] for t in data(body)["result"]["tools"]]
print(f"{info['name']} {info['version']}: {len(tools)} tools with the {loop} token")
print(", ".join(sorted(tools)))

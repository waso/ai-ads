# Handoff: continuing on the Raspberry Pi

Written on the owner's MacBook at the end of the first build session (2026-11-04). The previous session's conversation is
not available here; this file and `CLAUDE.md` replace it. Update this file as you go (state, decisions, open questions),
because the next session starts from it too.

## State at handoff

* Built and green on the Mac: `mvn clean verify` 352 tests, 0 failures; `check-acceptance.py` 49 of 49; 16 deliberate
  mutations of the safety code were each caught by a test.
* Runs end to end on the Mac in local mode: embedded PostgreSQL, simulated vibefactory.ai account (`ADS_MODE=sandbox`),
  localhost-only login, per-loop Claude Code tokens, 30 MCP tools, outcome reviews with learnings.
* **Never done:** a real Claude loop (costs tokens, needs the owner's yes); any contact with the real Google Ads API;
  any run on this Pi. The Pi bring-up below is the first time the code meets arm64 Linux.
* One change was made for the Pi just before handoff: `pom.xml` got `embedded-postgres-binaries-linux-arm64v8` (test scope),
  because zonky does not include arm64 Linux by default. Only the Mac side of it is tested.

## Job 1: bring it up on this Pi, report each result

Run in `autopilot/`. Stop and tell the owner at the first failure, with the exact error.

1. Environment: `uname -m` must print `aarch64`; `java -version` 21 or newer; `mvn -v`; `claude --version`; free RAM
   (`free -m`, 4 GB or more is comfortable). Install what is missing with the owner's OK (`sudo` is theirs to approve).
2. Prove the embedded Postgres binary starts on arm64: `mvn -q -B -Dtest=LocalStackTest -Dsurefire.failIfNoSpecifiedTests=false test`.
   Expect 5 tests, 0 failures. If Postgres does not start, look at the log for the missing library or page-size message
   before changing anything.
3. Full suite in tmux (slow on a Pi): `mvn -B verify`, then `python3 scripts/check-acceptance.py`. Expect 352 tests and 49/49.
   Anything that differs from the Mac is a finding: timing-based tests may need a look, safety tests may not be loosened.
4. Start the stack in tmux: `scripts/dev-start.sh` (it runs `scripts/dev-init.sh` itself and writes fresh secrets to
   `~/.ads-autopilot/env`). Then `python3 scripts/mcp-smoke.py`: expect "30 tools".
5. Tell the owner how to open the admin page: SSH tunnel, then `http://localhost:8080/admin`; the password is
   `DEV_LOGIN_PASSWORD` in the env file (tell them where it is, do not print it).
6. Ask the owner whether to run the first real loop against the sandbox (`scripts/run-loop.sh daily`). Only on a yes.

## Job 2: keep it running by itself (not built yet)

* The server only runs while `dev-start.sh` runs. A systemd unit (user service, restart on failure, starts after boot)
  would keep it up; write it in `deploy/pi/` and let the owner install it.
* Nothing schedules the loops yet. `scripts/run-loop.sh triage|daily|weekly` must be started by cron or systemd timers
  (suggested: triage hourly, daily early morning, weekly Monday; the allowed windows are the `autopilot.runs.*` settings in
  `AutopilotProperties`, and a loop gets one run per window). Write the timer files,
  set `LOOPS_ANY_TIME=false` for real use (it is true for local testing, which bypasses the run windows), and let the owner install them.
* The watchdog runs inside the server (every 15 minutes), so it is only as available as the server. Consider the
  external dead-man's switch (`HEARTBEAT_URL`) once it runs for real.

## Job 3: connect the real Google Ads account (the owner supplies the credentials)

Needed from the owner: Google Ads API developer token (Explorer access is enough), the customer id (plus the manager's
id if the account is reached through one), a service account JSON key added to the account as a **Standard** user.
Put them in `~/.ads-autopilot/env` (`ADS_MODE=google`, `ADS_DEVELOPER_TOKEN`, `ADS_CUSTOMER_ID`, `ADS_LOGIN_CUSTOMER_ID`,
`ADS_SERVICE_ACCOUNT_EMAIL`, `GOOGLE_APPLICATION_CREDENTIALS`), keep `AUTONOMY_LEVEL=L0`.

Before trusting anything, follow `docs/google-ads-adapter-notes.md`: run the reads and the `validate_only` mutations against
a Google Ads **test account** first. Unconfirmed assumptions to check there: `ad_group.cpc_bid_micros` under automated bidding,
and the Search-only campaign check. Expect some GAQL queries to need adjusting. Fix with a failing test first.

## Open questions for the owner (ask, do not guess)

* Price: the pricing page title says "from $0.99", the owner said $10 a month. Which is the plan the ads sell?
* Real location targets: `ALLOWED_GEO_TARGETS=2616` (Poland) is only a placeholder.
* Lifetime value and gross margin: unknown, so the break-even target-CPA guard is inert until `LIFETIME_VALUE_MICROS` and
  `GROSS_MARGIN_PCT` are set.
* Which landing pages (`ALLOWED_DOMAINS` is vibefactory.ai) and which protected terms (brand) the agent may use.

## Known caveats

* The Claude Code CLI (2.1.259 on the Mac) does not list `--max-turns` or `--append-system-prompt-file`; the scripts avoid
  them by default (`MAX_TURNS` is optional). Check the Pi's version.
* The Google Ads adapter is tested only against protobuf objects.
* Mac-only bits are skipped on Linux: the notification banners (`MacNotificationChannel` does nothing off macOS); alerts
  go to the server log. A real alert channel for the Pi (push or email via `ALERT_*`) is worth adding later.
* Raspberry Pi deployment through Docker/Kubernetes is not the plan; the `deploy/` manifests are for a cloud setup the owner
  does not currently want.

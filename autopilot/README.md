# Google Ads Autopilot

A private, single-user MCP server that lets Claude monitor one Google Ads account around the clock and change it only
inside limits the server enforces. Nothing depends on Claude being available to stop overspend: a deterministic
watchdog runs without it. The product requirements are in `../Google Ads Autopilot MCP and Agent PRD.pdf`; the
acceptance criteria this build was measured against are in [ACCEPTANCE.md](ACCEPTANCE.md).

## What is built, and what is not

Built and covered by automated tests (see [ACCEPTANCE.md](ACCEPTANCE.md); `scripts/check-acceptance.py` prints the status):

* The guard, with every PRD limit, the tier decision, scope labels, cooldowns, caps and loop binding.
* 30 typed MCP tools (15 read, 15 change) over Streamable HTTP, behind OAuth bearer-token validation or, for Claude Code
  on the owner's own machine, one static token per loop that can only start and use runs of its own loop.
* An approval page (signed single-use links, owner's Google sign-in, CSRF, diff, re-check on approval) and an admin page
  (kill switch, circuit breaker).
* Append-only audit and change history enforced by PostgreSQL privileges, idempotent replay, rollback proposals.
* The watchdog: spend against caps, circuit breaker, disapprovals, stopped delivery, outside changes, reconciliation of
  this system's own changes, conversion drops, quota, heartbeat. It runs as a one-shot job.
* A conversion-health check (read tool plus a daily watchdog alert) so the agent never optimizes toward a missing or non-sale conversion, and
  unit-economics guards: no target CPA above break-even, a cap on enabled campaigns and a minimum daily budget.
* A Google Ads API v26 adapter (GAQL reads, atomic campaign and ad group creation, `validate_only` first).
* A closed learning loop: the effect of each change is measured from daily per-campaign snapshots (7 days before against the
  matured days after), a reviewer records a verdict and a short learning once, and later runs see those learnings. With few
  sales a month most reviews honestly come out UNKNOWN; the server says why instead of inventing a verdict.
* A one-machine mode for a laptop: embedded PostgreSQL, a simulated Google Ads account, a localhost-only password login, the
  watchdog inside the server, scripts to run each loop with Claude Code. See [docs/local-quickstart.md](docs/local-quickstart.md).
* Dockerfile, Kubernetes and Cloud Run manifests, per-loop prompts.

**Not verified here, because it needs real accounts or infrastructure** (these are the deploy-time checks):

* **The Google Ads adapter has never talked to Google.** It is tested against protobuf objects only. Run it against a
  Google Ads *test account* with `validate_only` before trusting any query or mutation.
  [docs/google-ads-adapter-notes.md](docs/google-ads-adapter-notes.md) lists every assumption, the queries most likely
  to need adjusting, and the real quota cost per call (a watchdog run is about 8 operations, not the PRD's 5).
* **The OAuth authorization server for MCP tokens is not part of this project.** The server validates tokens (signature,
  issuer, expiry, audience, the owner's verified email) and publishes the RFC 9728 metadata, but something has to issue
  them: a hosted identity provider, or Spring Authorization Server added as a separate small service in front of Google
  sign-in. Until `MCP_JWK_SET_URI` is set, every MCP request is refused (it fails closed). For Claude Code on your own
  machine or network the per-loop static tokens (`MCP_TOKEN_*`) replace it, with no authorization server at all.
* The Google policy reading for a self-run MCP server, the Claude connector sign-in on web and mobile, approval from a
  phone, a scheduled Claude task running unattended, and the external dead-man's switch.

Known limits of the design as built:

* **With OAuth sign-in, loop binding narrows the gap, it does not close it.** Every run that comes through the connector
  shares one identity, so a chat that claims the DAILY loop inside its window could use that loop's rights (it burns the
  one-shot slot, which raises an alert). With per-loop static tokens (`MCP_TOKEN_*`) the gap is closed: a token can only
  start and use runs of its own loop, and cannot use another loop's run even knowing its id.
* Outcome reviews: measured by the server, judged and recorded by Claude through `record_outcome_review`. The watchdog
  closes reviews nobody recorded after another 7 days as `UNREVIEWED`. Sales alone rarely prove anything at a few sales a
  month; the measurement says so (confidence NONE or LOW, suggested verdict UNKNOWN).
* The local mode is for one person on one machine. The password login exists only there, and a sleeping laptop is an
  unwatched account (the watchdog lives in the server).

## How a change flows

```
Claude (MCP tool) ─► ChangeService ─► Guard ─┬─ refused ──► audit REFUSED, tool says which rule and limit
                      idempotency, lock      ├─ proposal ─► audit PROPOSED ─► owner gets a signed link
                                             │                                 │ (never returned to Claude)
                                             │              approval page: GET shows the diff, POST (CSRF) decides;
                                             │              the server re-checks limits and the entity's state hash
                                             └─ apply ────► audit ATTEMPT ─► validate_only ─► real mutate ─► read back
```

* Tier is decided by the server per call. Tier 1 is applied only at L2+ and only by the loop that may apply it
  (the daily loop); everything else is a proposal. Creating ads, enabling campaigns and raising budgets are Tier 2 at
  every autonomy level.
* Packages: `guard` (rules), `exec` (apply lifecycle), `store` (PostgreSQL), `ads` (port + metering), `ads.google`
  (adapter) and `ads.sandbox` (the simulated account), `mcp` (tools), `approval` + `security` (owner side), `outcome`
  (measuring what changes did), `watchdog`, `notify`, `dev` (sandbox page, local mode only).
* The `mcp` package may not depend on `approval` or `security`: an architecture test enforces that Claude has no route
  to approve a proposal or change limits.

### Where this build differs from, or adds to, the PRD

These are deliberate and tested:

* A protective pause (`emergency_pause_campaign`, the circuit breaker) is the one write the kill switch and an open
  breaker let through, and the breaker works from cached limits and logs its audit record when the database is down.
* Additive changes (negatives, keywords, ads) have no per-entity cooldown; the volume limits govern them. Otherwise
  "25 negatives per day" would mean one batch per campaign every three days.
* The 72-hour cooldown makes the 24-hour decrease interval redundant by default; the longer of the two applies.
* Rollbacks are exempt from the per-change percentage and frequency limits (restoring a value is not riskier than the
  value), but not from scope, caps, the kill switch or owner approval.
* Negatives are refused if they would block an active keyword, a protected term or a term that converted in the last
  90 days. Keyword pauses need clicks *and* a cost of at least 2x target CPA.
* While a conversion-drop alert is open, changes that rely on conversion data wait (spend reductions and rollbacks
  are still allowed).
* One write at a time per account (PostgreSQL advisory lock), a daily digest of proposals instead of a message each, and
  a stale-heartbeat check inside the server in addition to the external dead-man's switch.

## Configure

All configuration is environment variables (see `src/main/resources/application.yml`). Secrets belong in Secret Manager.
With nothing set the server is read-only: autonomy L0, no cap, no owner.

| Variable | Meaning |
|---|---|
| `OWNER_EMAIL` | The only Google account that can use the approval and admin pages and obtain MCP tokens |
| `ADS_CUSTOMER_ID` | The one allowlisted Google Ads account (digits) |
| `MONTHLY_CAP_MICROS` | Required before any write. Account-currency micros (3000.00 = `3000000000`) |
| `AUTONOMY_LEVEL` | `L0` observe, `L1` propose, `L2` limited autonomy, `L3` extended. Start at `L0` |
| `ALLOWED_DOMAINS`, `ALLOWED_GEO_TARGETS` | Final-URL domains and location ids new ads and campaigns may use (comma separated) |
| `PRIMARY_GOAL`, `TARGET_CPA_MICROS`, `PROTECTED_TERMS`, `CONVERSION_ACTIONS` | What Claude optimizes for |
| `PUBLIC_BASE_URL`, `APPROVAL_LINK_SECRET` | Base URL and 32+ character HMAC secret for approval links |
| `MCP_AUDIENCE`, `MCP_ISSUER`, `MCP_JWK_SET_URI` | The authorization server that issues MCP tokens |
| `MCP_TOKEN_TRIAGE`, `_DAILY`, `_WEEKLY`, `_CHAT` | Static per-loop bearer tokens (32+ random characters, all different) for clients that cannot do OAuth, such as Claude Code on your own machine |
| `ADS_MODE` | `google` (default), `sandbox` (a simulated account, nothing is sent to Google) or `none` |
| `DEV_LOGIN_PASSWORD`, `LOOPS_ANY_TIME`, `MACOS_NOTIFICATIONS`, `WATCHDOG_INTERVAL_MINUTES` | Local profile only (`SPRING_PROFILES_ACTIVE=local`); see the quickstart |
| `GOOGLE_OAUTH_CLIENT_ID`, `GOOGLE_OAUTH_CLIENT_SECRET` | The owner's Google sign-in for the approval page |
| `ADS_DEVELOPER_TOKEN`, `ADS_LOGIN_CUSTOMER_ID`, `ADS_SERVICE_ACCOUNT_EMAIL` | Google Ads API access (credentials come from Workload Identity) |
| `DB_URL`, `DB_USER`, `DB_PASSWORD`, `FLYWAY_USER`, `FLYWAY_PASSWORD` | PostgreSQL: the app role cannot update or delete the audit trail |
| `ALERT_EMAIL_TO`, `SMTP_*`, `ALERT_PUSH_URL`, `ALERT_PUSH_TOKEN`, `HEARTBEAT_URL` | Alert channels (sent by the watchdog directly) and the dead-man's switch |
| `KILL_SWITCH` | `true` makes the server read-only, even when the database is down |

Every other limit has the PRD's proposed default (`autopilot.limits.*`). Each change to limits or goals is stored as a
new version in `config_limits` and `goals`.

## Run it on your laptop

```bash
scripts/dev-init.sh && scripts/dev-start.sh     # then open http://localhost:8080/admin
scripts/chat.sh                                  # or: scripts/run-loop.sh daily
```

[docs/local-quickstart.md](docs/local-quickstart.md) walks through it, including how to break the simulated account on purpose
and what to provide to connect a real Google Ads account (developer token, customer id, credentials).

## Build and test

```bash
mvn verify                      # unit, property and integration tests against an embedded PostgreSQL (no Docker)
python3 scripts/check-acceptance.py   # which acceptance criteria have passing tests
```

`mvn verify` needs only Maven Central. Tests use a real PostgreSQL started from Maven artifacts, a fake Google Ads
account and a real RSA key for MCP tokens.

## Deploy

1. Create a dedicated Google Cloud project; enable the Google Ads API; apply for Explorer access; create a Google Ads
   **test account** and a service account added to the real account as a **Standard** user (never Admin).
2. Create the roles and database with `deploy/sql/roles.sql`; store secrets in Secret Manager.
3. Provide an OAuth authorization server for MCP tokens and set `MCP_*` (see above).
4. Build the image (`docker build -t ads-autopilot .`) and apply `deploy/k8s/*` (GKE) or `deploy/cloudrun/*`.
   The watchdog is the same image with `SPRING_PROFILES_ACTIVE=watchdog`, run every 15 minutes.
5. Put `/mcp` behind rate limits and Anthropic's published IP ranges. Keep `/approve` and `/admin` reachable from your
   phone: Google sign-in is their protection.
6. Add the dead-man's switch URL as `HEARTBEAT_URL`, then add the server as a custom connector in Claude and create
   scheduled tasks from `prompts/` (triage hourly, daily early morning, weekly Monday).

## Operate

* **Stop everything:** admin page → *Turn kill switch on*, or deploy with `KILL_SWITCH=true`. Claude can read and
  propose nothing while it is on. Only the owner can clear it.
* **Circuit breaker:** trips at 1.8x summed daily budgets or at the monthly cap, pauses agent-managed campaigns and blocks
  writes. Review what it paused in Google Ads, then clear it on the admin page. It never resumes anything itself.
* **Rollout:** raise `AUTONOMY_LEVEL` one step at a time only after the gate for the previous step passed (the PRD's
  rollout section). A serious incident moves the system back one level until its cause is fixed and tested.
* **Audit:** `audit_events` has every attempt including refusals; `changes` has everything sent to Google with old and new
  values and the Google request id; both are append-only for the application role. Keep audit records 24 months.
* **Monthly drill:** turn the kill switch on and off, inject a test breach in the test account, roll back a change.
* **API upgrades:** Google ships two API releases a month and four majors a year; plan at most four upgrades a year.
  The adapter targets v26 (client 48.1.0).

# Run everything on your Mac

One machine, nothing to install except Java 21, Maven and Claude Code. The server, its PostgreSQL, the watchdog and a
simulated Google Ads account all run from one command, so you can watch every safety mechanism work before any real
account is connected.

```
Claude Code (scripts/run-loop.sh, scripts/chat.sh)
      │  MCP over http://127.0.0.1:8080/mcp, one token per loop
      ▼
the server  ── guard ── simulated Google Ads account   (ADS_MODE=sandbox, the default)
      │                  └─ or the real API            (ADS_MODE=google)
      ├─ PostgreSQL (embedded, data in ~/.ads-autopilot/pg)
      ├─ watchdog (in the server, every 15 minutes)
      └─ admin and approval pages: http://localhost:8080/admin
```

Everything listens on 127.0.0.1 only. The password login for the admin pages refuses to start on any other address.

## 1. First start

```bash
scripts/dev-init.sh      # writes ~/.ads-autopilot/env with fresh secrets (never overwrites)
scripts/dev-start.sh     # compiles, starts PostgreSQL and the server; Ctrl-C stops both
```

The first start downloads Maven artifacts and takes a few minutes; later starts take about ten seconds. When it says
`Started LocalDevApplication`, in another terminal:

```bash
python3 scripts/mcp-smoke.py      # initialize + list tools with the chat token: expect "30 tools"
grep DEV_LOGIN_PASSWORD ~/.ads-autopilot/env
```

Open <http://localhost:8080/admin> and sign in with that password. You land on the admin page (autonomy, caps, kill switch,
breaker, alerts, proposals, recent changes). <http://localhost:8080/dev/sandbox> is the simulated account.

## 2. Talk to it

```bash
scripts/chat.sh                   # an interactive Claude Code session wired to the chat token
```

Try: *"How is the account doing? Is conversion tracking healthy? Which search terms waste money?"* Ask it to propose
something (a negative keyword, a new ad group). It becomes a **proposal**: open the admin page, follow its link, read the
diff and approve or reject. Claude never receives that link and has no tool that approves.

Claude Code runs with **no built-in tools**: no shell, no files, no web. Only this server's tools.

## 3. Run a loop

```bash
scripts/run-loop.sh daily         # reads the account, reviews earlier changes, files proposals, writes a summary
scripts/run-loop.sh triage
scripts/run-loop.sh weekly
```

Each loop uses its own token and its own prompt from `prompts/`. A run is capped by `MAX_BUDGET_USD` (default 2) and 15
minutes. Output is saved to `~/.ads-autopilot/runs/`. Each run uses your Claude Code login (or `ANTHROPIC_API_KEY`) and
costs tokens.

Locally, loops may start at any time of day, once per window. To run the same loop again today, press **Let every loop run
again** on the sandbox page.

## 4. Break things on purpose

On the sandbox page: pretend a campaign spent 250 PLN today and press **Run the watchdog now** (the breaker pauses every
agent-managed campaign and blocks writes until you clear it on the admin page), make conversion tracking go silent, or
disapprove an ad. Then run `scripts/run-loop.sh triage` and see what the agent reports and refuses.

The default autonomy is **L1** (everything is a proposal). Try L2 by setting `AUTONOMY_LEVEL=L2` in `~/.ads-autopilot/env` and
restarting: negatives, keyword pauses and small bid or budget trims are then applied directly inside the limits.

## 5. What the default limits are

From `~/.ads-autopilot/env` (edit and restart; every change is stored as a new version):

| Setting | Default | Meaning |
|---|---|---|
| `MONTHLY_CAP_MICROS` | 2,200 PLN | about 500 PLN a week with room for rounding |
| `ALLOWED_DOMAINS` | vibefactory.ai | final URLs of new ads must be https on these |
| `ALLOWED_GEO_TARGETS` | 2616 (Poland) | **placeholder, set your real locations** |
| `MAX_ACTIVE_CAMPAIGNS` | 4 | a small budget spread thin starves every campaign (3 is better with 500 PLN a week) |
| `MIN_DAILY_BUDGET_MICROS` | 5 PLN | no campaign created or trimmed below this |
| `COOLDOWN_HOURS` | 168 | a week between changes to the same thing: with a few sales a month a change needs weeks to read |
| `VALUE_PER_SALE_MICROS`, `LIFETIME_VALUE_MICROS`, `GROSS_MARGIN_PCT`, `TARGET_CPA_MICROS` | unset | fill in once you know retention; the server then refuses a target CPA above break-even |

## 6. Connect the real Google Ads account

Do this only after step 4 behaved the way you expect.

You provide four things:

1. **A developer token.** In a Google Ads *manager* account: Admin → API Center. This server is sized for *Explorer*
   access (2,880 operations a day, production accounts allowed). If the token page shows only test-account access, apply
   for Explorer there; Google decides how it is granted.
2. **The customer id** of the account to manage (digits, no dashes), and, if you reach it through a manager account, that
   manager's id as the login customer id.
3. **Credentials.** The server uses Application Default Credentials with the Google Ads scope. The simplest route is a
   service account: create one in a Google Cloud project, enable the Google Ads API there, create a JSON key, and add
   the service account's email as a **Standard** user (never Admin) on the Google Ads account (Admin → Access and
   security → Users).
4. **Edit `~/.ads-autopilot/env`:**

```
ADS_MODE=google
ADS_DEVELOPER_TOKEN=...
ADS_CUSTOMER_ID=1234567890
ADS_LOGIN_CUSTOMER_ID=...                       # only when going through a manager account
ADS_SERVICE_ACCOUNT_EMAIL=name@project.iam.gserviceaccount.com
GOOGLE_APPLICATION_CREDENTIALS=/Users/you/keys/ads-autopilot.json   # keep it outside any repository
AUTONOMY_LEVEL=L0                                # observe only, first
```

Restart. Work through [google-ads-adapter-notes.md](google-ads-adapter-notes.md): the adapter has never talked to Google, so
run it against a Google Ads **test account** with `validate_only` first. Then follow the PRD's rollout: two weeks at L0
(reads and reports), then L1 (proposals you approve), then L2 only after the proposals have been good.

## 7. Scheduling on a Mac

The loops are plain commands. Example `crontab -e` (times are your Mac's local time):

```
0 * * * *     /path/to/autopilot/scripts/run-loop.sh triage  >> ~/.ads-autopilot/cron.log 2>&1
30 6 * * *    /path/to/autopilot/scripts/run-loop.sh daily   >> ~/.ads-autopilot/cron.log 2>&1
0 7 * * 1     /path/to/autopilot/scripts/run-loop.sh weekly  >> ~/.ads-autopilot/cron.log 2>&1
```

**The watchdog only runs while the server runs, and the server only runs while your Mac is awake.** A sleeping laptop is
an unwatched account. Until the server moves to the Raspberry Pi, use `caffeinate -s scripts/dev-start.sh`, keep the Mac on
power, and set the monthly cap low enough that an unwatched weekend cannot hurt. Also set `LOOPS_ANY_TIME=false` once you
schedule loops, so each keeps its production window.

## Where things are

| What | Where |
|---|---|
| Secrets and settings | `~/.ads-autopilot/env` (mode 600) |
| Database files | `~/.ads-autopilot/pg` |
| Run outputs | `~/.ads-autopilot/runs/` |
| Audit trail | table `audit_events` (append-only for the application's database role) |

To start over, stop the server and delete `~/.ads-autopilot/pg`.

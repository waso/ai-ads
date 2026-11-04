# Acceptance criteria

These are the criteria I (Claude) set for v1 of the Google Ads Autopilot server, derived from the PRD
(`../Google Ads Autopilot MCP and Agent PRD.pdf`) and from the review findings that I made the design
answer. A criterion is **met** only when an automated test whose display name starts with its ID
(for example `[AC-07] ...`) exists and passes. `scripts/check-acceptance.py` runs that check against the
Surefire reports and prints a status table; it exits non-zero if any criterion has no passing test or has a
failing one.

Criteria that can only be settled against real infrastructure are listed at the bottom as **deploy-time
checks**. They are not claimed as met by the build.

## A. Guard: limits, tiers, scope

| ID | Criterion |
|----|-----------|
| AC-01 | Every hard limit has a boundary test: a call at the limit is allowed, a call one step over is refused. Covers budget increase/decrease %, per-period frequency, bid change %, negatives per day, keyword pause threshold, Tier 1 daily volume, 72 h cooldown, monthly cap, sum of daily budgets x 30.4, final-URL domains, allowed locations, proposal lifetime. |
| AC-02 | A refusal names the rule and the limit (for example `+35% exceeds the +20% limit`). |
| AC-03 | The server decides the tier. Tier 2 tools never apply directly at any autonomy level; Tier 1 tools apply only at L2 and above, become proposals at L1, and are refused at L0. |
| AC-04 | Scope: an entity outside the allowlisted account, without the `agent-managed` label, or carrying `no-agent` is refused. `no-agent` wins over `agent-managed`. A budget shared with an unmanaged campaign counts as unmanaged. |
| AC-05 | Replaying a change with the same idempotency key returns the earlier result and sends exactly one mutation to Google. The same tool + entity + target value under a new key is also rejected as a duplicate. |
| AC-06 | Cooldown: no second change to an entity within 72 h, except an emergency pause. A change the owner makes in the Google Ads interface starts the cooldown as well and is never reverted. |
| AC-07 | No write is accepted until the monthly cap is set. Budget increases and campaign creation are refused when the sum of daily budgets x 30.4 would exceed the cap. |
| AC-08 | Run binding: a run can use only the tools its loop allows (chat and weekly: proposals only; triage: emergency pause and proposals; daily: Tier 1 at L2+). A loop's run slot is one-shot per window, and a consumed slot raises an alert. |
| AC-09 | Negative keywords are refused when they would block an active keyword of the account, a protected term, or a term that converted in the lookback window. |
| AC-10 | Keyword pause needs both a click minimum and a cost minimum relative to target CPA, keeps at least one active keyword in the ad group, and requires data older than the 3-day conversion lag. |

## B. Approval path

| ID | Criterion |
|----|-----------|
| AC-11 | Claude has no MCP route to approve, reject, change limits or autonomy, or clear a breaker or kill switch: the registered tool names equal the PRD catalog, and the `mcp` package has no dependency on the `approval` package. |
| AC-12 | Bearer tokens issued for MCP are rejected by the approval and admin endpoints. Those endpoints require a browser session of the owner's verified Google account; any other account is refused. |
| AC-13 | Opening an approval link (GET) changes nothing. Approving needs a POST with a CSRF token. A link is single-use, expires with the proposal, and a tampered link is rejected. |
| AC-14 | On approval the server re-checks the entity state hash and the limits, and rejects a stale proposal. Proposals expire after 48 h. |
| AC-15 | No MCP tool result contains an approval link or approval token. |
| AC-16 | The owner can edit nothing but approve or reject; a rejected or expired proposal is recorded with the reason and nothing is applied. |

## C. Audit, apply and rollback

| ID | Criterion |
|----|-----------|
| AC-17 | Every attempt, including refused ones, is stored with run, reason, expected effect, old and new values, tier, approver and Google request ID where one exists. |
| AC-18 | The audit record is written before the Google call; if it cannot be written, Google is not called. |
| AC-19 | `changes` and the audit table are append-only for the application's database role: UPDATE and DELETE are denied by PostgreSQL itself. |
| AC-20 | Every mutation runs `validate_only` first, then for real with partial failure off, then is read back; a read-back mismatch raises an alert. |
| AC-21 | Rollback restores the recorded old values through a new Tier 2 proposal and adds its own audit record. Change types without a safe inverse are refused rather than guessed. |
| AC-22 | Only one write runs at a time per account. Two concurrent changes cannot both pass the cooldown and volume checks. |

## D. Stop mechanisms and failure modes

| ID | Criterion |
|----|-----------|
| AC-23 | Kill switch and an open breaker refuse every write except protective pauses. Only the owner chain can clear them. Clearing lists the campaigns the breaker paused. |
| AC-24 | With the database unavailable, writes are refused and reads continue; the circuit breaker still pauses using cached limits and writes its audit record to the log. |
| AC-25 | The watchdog runs as a one-shot process with no Claude involved. An injected spend breach raises an alert in the same tick and, at 1.8x summed daily budgets or the monthly cap, trips the breaker and pauses only agent-managed campaigns. When the overspend comes from unmanaged campaigns, it raises a critical alert and pauses nothing else. |
| AC-26 | At 1.5x summed daily budgets the watchdog alerts but does not pause. |
| AC-27 | The watchdog detects disapprovals, stopped delivery, unexpected changes by other API clients, and a conversion drop, deduplicates open alerts, and every run emits a heartbeat. A stale heartbeat is detected. |
| AC-28 | Quota: every Ads operation is counted, including rejected ones. At 80% of the daily allowance Claude's reads are refused while the watchdog's queries continue. |
| AC-29 | Reconciliation: Google change-history entries of the service account that have no matching audit record raise an alert. |

## E. Prompt injection and data handling

| ID | Criterion |
|----|-----------|
| AC-30 | Free text in tool results (search terms, ad text, recommendation text, agent notes) is returned in fields marked untrusted, apart from system fields. |
| AC-31 | Search terms that look like instructions go to a review list and are never turned into keywords automatically. An injected term telling the agent to raise a budget changes nothing end to end. |
| AC-32 | `run_report` accepts only a single read query on allowlisted resources with a row cap; anything else is refused. |
| AC-33 | Agent notes and run summaries are length-limited and expire. Secrets never appear in tool results, audit rows or logs. |

## F. Platform

| ID | Criterion |
|----|-----------|
| AC-34 | Flyway migrations apply cleanly to a real PostgreSQL and create every table of the PRD data model. |
| AC-35 | The MCP server starts and, over Streamable HTTP with a valid token, lists exactly the catalog tools and completes a read call and a refused write call. Without a token it answers 401. |
| AC-36 | The Google Ads adapter builds every mutation for API v26: campaigns are created PAUSED in one atomic request, `target_content_network=false`, search partners off, geo targeting PRESENCE, no language criteria, `validate_only` first, partial failure off. |
| AC-37 | Deployment files (Dockerfile, Kubernetes Deployment/Service/CronJob, Cloud Run alternative) exist and parse; the README states how to run, configure and operate it. |
| AC-38 | `mvn verify` passes from a clean checkout without network services other than Maven Central. (Checked by the script from the build itself: no failing test in any report and the packaged jar exists, rather than by a tagged test.) |
| AC-39 | Each loop (triage, daily, weekly, chat) has a versioned prompt in `prompts/` that follows the run contract: `get_agent_context` first, `record_run_summary` last, only catalog tools, and the untrusted-data and no-approval rules. |
| AC-40 | The server shows whether the signal it optimizes toward is real: a read tool lists the conversion actions and their recent results and flags a missing sale conversion, non-sale primaries, no recent sales and sales without a value. The watchdog checks this daily, raises an alert per finding and resolves it when fixed, and while the sale conversion is missing, changes that rely on conversion data wait. |
| AC-41 | A target cost per sale above break-even (lifetime value x gross margin) is refused, on campaign creation and on a bidding strategy change. With no break-even configured nothing is checked. |
| AC-42 | A small budget cannot be fragmented: at the configured number of enabled campaigns no campaign is created or enabled, and no campaign is created or has its budget lowered below the configured daily minimum. |
| AC-43 | Each Claude loop can have its own static bearer token, so the identity of a run is the token and not a claim: a token bound to a loop can only start and use runs of that loop, an unknown or malformed token is refused, tokens are compared in constant time and never logged, and an OAuth-issued caller is unaffected. |
| AC-44 | A new ad group (keywords plus one responsive search ad) can be added to a campaign the owner already runs: always a Tier 2 proposal that shows keywords and ad copy, limited to agent-managed campaigns, allowed domains, ad-asset limits and sane keyword shapes, created together or not at all, and reversible by pausing it. |
| AC-45 | The approval and admin pages can be used on a laptop without a Google OAuth client, and only there: the password login does not exist unless switched on; it makes the server refuse to start unless it listens on a loopback address, has an owner and a password of at least 12 characters; it serves only requests from the machine itself, needs the CSRF token, locks for a minute after five wrong passwords, and a bearer token opens nothing on these pages. |
| AC-46 | The whole stack runs on one machine and works end to end: a simulated Google Ads account, a daily run with a loop token reads the account, files a proposal, the owner approves it on the admin page and it lands in the account; simulated overspend trips the breaker, which stops writes until the owner clears it; the kill switch stops writes and only the owner clears it; the sandbox page is owner-only; alerts can appear as macOS banners without any alert text reaching a script. |
| AC-47 | What a change did is measured, not guessed: the watchdog keeps a daily row per campaign from the read it already makes, and a change is compared as seven days before against the matured days after on the campaigns it touched. Too little data (under 3 days a side) or under 10 sales gives UNKNOWN with the reason; cost per sale 20% lower or 25% higher counts as better or worse only with enough sales. |
| AC-48 | The loop is closed: a due change is reviewed with a verdict and a learning (at most 500 characters, plain prose, not instruction-like), recorded once, audited, never overwritten, only through a run of the caller's own loop; later runs see the learnings, as untrusted text. |
| AC-49 | Trying the system is fast: for local testing the daily and weekly loops can start at any time (still one run per window), and the sandbox page can free the windows again. Production windows are the default. |

## Deploy-time checks (not claimed by the build)

These need real accounts or infrastructure, so they cannot be proven here:

* The Google policy reading for a self-run MCP server, confirmed against the policy text or with Google.
* A live run against a Google Ads **test account**: GAQL accepted by the API, mutations accepted by
  `validate_only`, quota figures.
* The OAuth authorization server that issues MCP tokens, and the Claude connector sign-in on web and mobile.
* Owner approval from a phone, over the real Google sign-in.
* GKE or Cloud Run deployment, the Anthropic IP allowlist on the MCP path, and the external dead-man's switch.
* A scheduled Claude task completing unattended and calling `record_run_summary`.
* The 2-week read-only baseline and the proposal-phase gate.

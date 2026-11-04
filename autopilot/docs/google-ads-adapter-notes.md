# Google Ads adapter: what is verified and what is not

`app.autopilot.ads.google` (client 48.1.0, API v26) was written and tested **without access to Google**. It is tested
against protobuf objects and a fake transport: request building, field masks, row mapping, error translation and
GAQL text. Nothing here has been accepted by the real API. Before turning on autonomy, run it against a Google Ads
**test account** (with a test manager account) and `validate_only`, and work through this list.

## Choices that may be rejected for some accounts

1. **Campaign creation turns off AI Max and text asset automation.** v26 `Campaign` has no `url_expansion_opt_out`, so
   the adapter sets `ai_max_setting.enable_ai_max=false` and `asset_automation_settings` OPTED_OUT for
   TEXT_ASSET_AUTOMATION and FINAL_URL_EXPANSION_TEXT_ASSET_AUTOMATION. If `validate_only` rejects these, build
   `GoogleMutations` with its third constructor argument `false`.
2. The campaign budget is created without a name (Google derives one for non-shared budgets).
3. Responsive search ads, including the one in `create_campaign`, are created ENABLED. The campaign itself is PAUSED.
4. Manual CPC campaigns get an ad group default bid equal to the highest keyword bid in the spec. With no keyword bid,
   none is set and Google may require one.
5. Field masks for switching the bidding strategy oneof mirror what the library's `FieldMasks` produces; confirm each
   strategy type switch (`MANUAL_CPC`, `MAXIMIZE_CONVERSIONS`, `TARGET_CPA`).
6. `TARGET_CPA` is sent as `campaign.target_cpa`. A campaign set up as "maximize conversions with target CPA" reads back
   as `MAXIMIZE_CONVERSIONS` plus `targetCpaMicros`, so a rollback of a strategy change should be checked by hand.
7. `apply_recommendation` has no `validate_only` in the API: a validate-only call only checks the recommendation is still
   pending. The guard's diff (the recommendation's real change) is the safeguard.
8. v26 `AdGroupCriterion` has no `policy_summary`, so `policyIssues` finds disapproved *keywords* only; limited keywords
   are not visible to the watchdog.
9. Recommendation `status` (ACTIVE or DISMISSED) is the adapter's own. Recommendation ids may end in `=`.
10. Labels: before creating a campaign the adapter looks up the `agent-managed` label (one extra query) and creates it in
    the same atomic request when missing. Two creations racing for a new label can hit DUPLICATE_NAME; a retry works.

## GAQL most likely to need a real-account check

All field names exist in the v26 `GoogleAdsRow` (a test verifies it). Filterability, selectability and zero-row
semantics are the open questions:

* `policyIssues`: filter on `ad_group_ad.policy_summary.approval_status IN (...)`.
* `keywords()` and the keyword metrics used for pause thresholds: with a date range only in WHERE, zero-impression keywords
  may be omitted, and the pause check assumes "no row" means zero.
* `changeHistory`: the date-time literal format and the 29-day lower bound (Google keeps 30).
* `recommendation(...)` and `recommendations(...)`: the `recommendation.resource_name` filter, and whether dismissed ones
  come back at all (they are filtered in Java).
* `*.labels` fields (campaign, ad group, ad group criterion, ad group ad): assumed to hold Label resource names, mapped
  through `label.resource_name`.
* Budget users: the filter `campaign.campaign_budget = '<resource name>'`.
* `performance`, `searchTerms` and `keywords()`: unsegmented metrics with the date window only in WHERE.
* `conversionActions()` (three queries: the actions, then results for `LAST_7_DAYS` and `LAST_30_DAYS`): whether
  `segments.date DURING ...` is accepted with `FROM conversion_action` and returns a row for an action with no results (the
  adapter assumes no row means zero). For server-side events, `type` is typically `UPLOAD_CLICKS`; confirm the category of your paid
  subscription action is `SUBSCRIBE_PAID` or `PURCHASE`, since the sale checks key on it.

## Quota in practice

Each GAQL query is one operation, but one gateway call can run several queries:

| Call | Operations |
|---|---|
| `campaigns()` | 2 to 3 (3 when campaigns have labels) |
| `budgets()`, `policyIssues()` | 2 |
| `changeHistory()` | 1 |
| `entityState()` | 2 to 4 (a keyword with labels costs 4) |
| `negatives()` | 1 (campaign scope) to 3 (ad group scope) |
| `create_campaign` | 1 extra for the label lookup |
| `add_ad_group` read-back | 2 to 3 (the ad group, its labels when it or its campaign has any, and a count of enabled keywords) |
| Reviewing a change's outcome | 0 for campaign and budget changes, 2 to 4 for keyword, ad group and ad changes (finding the campaign); the numbers come from the daily snapshots the watchdog already keeps |

A watchdog run therefore costs roughly 8 operations (campaigns 3, budgets 2, policy 2, change history 1), about 770 a day
at 96 runs, not the 480 the PRD estimated. Triage, the daily run, changes with validation and the guard's own state reads
come on top. The server counts real operations and pauses Claude's reads at 80% of 2,880, so measure actual usage during
the read-only phase; if the daily run needs more, apply for Basic access (15,000 a day, brand verification required).

## `add_ad_group`

One atomic request: the ad group (temporary id), one criterion per keyword, one responsive search ad. Things only a live
`validate_only` run can settle:

* Whether Google accepts `ad_group.cpc_bid_micros` in a campaign that bids automatically. It is sent only when the caller
  supplies a bid; a rejection fails the whole request, so nothing half-exists.
* The adapter does not check that the target campaign is a Search campaign; Google rejects the ad group otherwise.
* Duplicate keywords inside one request are not rejected locally; Google rejects them.
* The read-back checks the ad group (exists, ENABLED, right campaign) and the number of enabled keywords. It does not
  re-read the ad or the ad group name.

## Credentials

`GoogleAdsClientFactory` uses Application Default Credentials only (Workload Identity on the cluster, `gcloud auth
application-default login` on a laptop). There are no key files or refresh tokens in the code. Confirm early that the
Ads Java client accepts these credentials as built; organizations that block service account key creation make this the
only workable route.

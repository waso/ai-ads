package app.autopilot.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import app.autopilot.domain.BiddingStrategy;
import app.autopilot.domain.ChangeMeta;
import app.autopilot.domain.ChangeOutcome;
import app.autopilot.domain.ChangeRequest;
import app.autopilot.domain.ChangeRequest.*;
import app.autopilot.domain.EntityKind;
import app.autopilot.domain.EntityRef;
import app.autopilot.domain.MatchType;
import app.autopilot.domain.Loop;
import app.autopilot.exec.ChangeService;
import app.autopilot.exec.RunService;
import app.autopilot.store.RunStore.Run;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.dao.DataAccessException;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The change tools. Each one is narrow and typed; there is no generic mutate. They only ask: the server's guard
 * decides whether the change is applied, filed as a proposal, or refused, and which tier it is. Nothing here can
 * approve a proposal, change a limit or the autonomy level, or clear a stop mechanism.
 *
 * <p>Every tool requires {@code reason}, {@code expected_effect}, {@code run_id} (from get_agent_context) and
 * {@code idempotency_key} (a fresh unique value per intended change; repeating a call with the same key returns the
 * earlier result). Money is in account-currency micros (1.00 = 1,000,000).
 */
@Component
public class ChangeTools {

    private static final String COMMON = " Requires reason, expected_effect, run_id and idempotency_key. The server decides the tier: "
            + "the result says APPLIED, PROPOSED (waiting for the owner) or REFUSED with the rule and limit.";

    private final ChangeService changes;
    private final RunService runs;

    public ChangeTools(ChangeService changes, RunService runs) {
        this.changes = changes;
        this.runs = runs;
    }

    // ---- Tier 1 ----------------------------------------------------------------------------------------

    @McpTool(name = "add_negative_keywords", description = "Add exact or phrase negative keywords to a campaign or ad group. Refused if a negative would block an "
            + "active keyword, a protected term or a search term that converted; at most 25 per day." + COMMON)
    public ChangeOutcome addNegativeKeywords(
            @McpToolParam(description = "CAMPAIGN or AD_GROUP", required = true) String scope_kind,
            @McpToolParam(description = "Campaign or ad group id", required = true) String scope_id,
            @McpToolParam(description = "Negative terms", required = true) List<String> terms,
            @McpToolParam(description = "EXACT or PHRASE", required = true) String match_type,
            @McpToolParam(description = "Why this change", required = true) String reason,
            @McpToolParam(description = "What you expect to happen", required = true) String expected_effect,
            @McpToolParam(description = "run_id from get_agent_context", required = true) String run_id,
            @McpToolParam(description = "Unique key for this intended change", required = true) String idempotency_key,
            McpTransportContext context) {
        return submit(() -> {
            EntityKind kind = parseKind(scope_kind, EntityKind.CAMPAIGN, EntityKind.AD_GROUP);
            return new AddNegativeKeywords(new EntityRef(kind, scope_id), terms == null ? List.of() : terms, parseMatch(match_type));
        }, reason, expected_effect, run_id, idempotency_key, context);
    }

    @McpTool(name = "pause_keyword", description = "Pause a keyword that has enough data to judge: at least 100 clicks and no conversions in the last 30 days "
            + "(before the 3-day conversion lag) and a cost of at least twice the target CPA. The ad group must keep one active keyword." + COMMON)
    public ChangeOutcome pauseKeyword(
            @McpToolParam(description = "Keyword id, adGroupId~criterionId", required = true) String keyword_id,
            @McpToolParam(description = "Why this change", required = true) String reason,
            @McpToolParam(description = "What you expect to happen", required = true) String expected_effect,
            @McpToolParam(description = "run_id from get_agent_context", required = true) String run_id,
            @McpToolParam(description = "Unique key for this intended change", required = true) String idempotency_key,
            McpTransportContext context) {
        return submit(() -> new PauseKeyword(EntityRef.keyword(keyword_id)), reason, expected_effect, run_id, idempotency_key, context);
    }

    @McpTool(name = "adjust_keyword_bid", description = "Change a keyword's max CPC bid by at most +-10%, once per 7 days per keyword. Manual CPC campaigns only." + COMMON)
    public ChangeOutcome adjustKeywordBid(
            @McpToolParam(description = "Keyword id, adGroupId~criterionId", required = true) String keyword_id,
            @McpToolParam(description = "New max CPC in micros", required = true) long new_cpc_bid_micros,
            @McpToolParam(description = "Why this change", required = true) String reason,
            @McpToolParam(description = "What you expect to happen", required = true) String expected_effect,
            @McpToolParam(description = "run_id from get_agent_context", required = true) String run_id,
            @McpToolParam(description = "Unique key for this intended change", required = true) String idempotency_key,
            McpTransportContext context) {
        return submit(() -> new AdjustKeywordBid(EntityRef.keyword(keyword_id), new_cpc_bid_micros), reason, expected_effect, run_id, idempotency_key, context);
    }

    @McpTool(name = "decrease_budget", description = "Lower a daily budget by at most 20% per change (once per 24 hours, and not within the 72-hour cooldown)." + COMMON)
    public ChangeOutcome decreaseBudget(
            @McpToolParam(description = "Budget id", required = true) String budget_id,
            @McpToolParam(description = "New daily amount in micros", required = true) long new_amount_micros,
            @McpToolParam(description = "Why this change", required = true) String reason,
            @McpToolParam(description = "What you expect to happen", required = true) String expected_effect,
            @McpToolParam(description = "run_id from get_agent_context", required = true) String run_id,
            @McpToolParam(description = "Unique key for this intended change", required = true) String idempotency_key,
            McpTransportContext context) {
        return submit(() -> new DecreaseBudget(EntityRef.budget(budget_id), new_amount_micros), reason, expected_effect, run_id, idempotency_key, context);
    }

    @McpTool(name = "emergency_pause_campaign", description = "Pause a campaign while a spend cap is breached. Only triage runs, and only while a spend alert is open or the "
            + "breaker is open; otherwise refused." + COMMON)
    public ChangeOutcome emergencyPauseCampaign(
            @McpToolParam(description = "Campaign id", required = true) String campaign_id,
            @McpToolParam(description = "Why this change", required = true) String reason,
            @McpToolParam(description = "What you expect to happen", required = true) String expected_effect,
            @McpToolParam(description = "run_id from get_agent_context", required = true) String run_id,
            @McpToolParam(description = "Unique key for this intended change", required = true) String idempotency_key,
            McpTransportContext context) {
        return submit(() -> new EmergencyPauseCampaign(EntityRef.campaign(campaign_id)), reason, expected_effect, run_id, idempotency_key, context);
    }

    // ---- Tier 2 ----------------------------------------------------------------------------------------

    @McpTool(name = "pause_ad", description = "Propose pausing an ad. Tier 2: the owner approves. The ad group must keep one enabled ad." + COMMON)
    public ChangeOutcome pauseAd(
            @McpToolParam(description = "Ad id, adGroupId~adId", required = true) String ad_id,
            @McpToolParam(description = "Why this change", required = true) String reason,
            @McpToolParam(description = "What you expect to happen", required = true) String expected_effect,
            @McpToolParam(description = "run_id from get_agent_context", required = true) String run_id,
            @McpToolParam(description = "Unique key for this intended change", required = true) String idempotency_key,
            McpTransportContext context) {
        return submit(() -> new PauseAd(EntityRef.ad(ad_id)), reason, expected_effect, run_id, idempotency_key, context);
    }

    /** One keyword in add_keywords or create_campaign. */
    public record KeywordArg(String text, String matchType, @JsonProperty(required = false) Long cpcBidMicros) {}

    @McpTool(name = "add_keywords", description = "Propose adding keywords to an ad group. Tier 2: match type and ad group appear in the diff the owner approves." + COMMON)
    public ChangeOutcome addKeywords(
            @McpToolParam(description = "Ad group id", required = true) String ad_group_id,
            @McpToolParam(description = "Keywords: text, matchType (EXACT, PHRASE, BROAD), optional cpcBidMicros", required = true) List<KeywordArg> keywords,
            @McpToolParam(description = "Why this change", required = true) String reason,
            @McpToolParam(description = "What you expect to happen", required = true) String expected_effect,
            @McpToolParam(description = "run_id from get_agent_context", required = true) String run_id,
            @McpToolParam(description = "Unique key for this intended change", required = true) String idempotency_key,
            McpTransportContext context) {
        return submit(() -> new AddKeywords(EntityRef.adGroup(ad_group_id), specs(keywords)), reason, expected_effect, run_id, idempotency_key, context);
    }

    @McpTool(name = "create_ad", description = "Propose a responsive search ad: 3 to 15 unique headlines (30 characters each), 2 to 4 descriptions (90 characters each), "
            + "and an https final URL on an allowed domain. Tier 2: the owner approves all new ad copy." + COMMON)
    public ChangeOutcome createAd(
            @McpToolParam(description = "Ad group id", required = true) String ad_group_id,
            @McpToolParam(description = "Headlines", required = true) List<String> headlines,
            @McpToolParam(description = "Descriptions", required = true) List<String> descriptions,
            @McpToolParam(description = "Final URL", required = true) String final_url,
            @McpToolParam(description = "Why this change", required = true) String reason,
            @McpToolParam(description = "What you expect to happen", required = true) String expected_effect,
            @McpToolParam(description = "run_id from get_agent_context", required = true) String run_id,
            @McpToolParam(description = "Unique key for this intended change", required = true) String idempotency_key,
            McpTransportContext context) {
        return submit(() -> new CreateAd(EntityRef.adGroup(ad_group_id), orEmpty(headlines), orEmpty(descriptions), final_url),
                reason, expected_effect, run_id, idempotency_key, context);
    }

    /** The campaign to create. Always created PAUSED, Search network only, location targeting by presence. */
    public record CampaignArgs(
            String name,
            long dailyBudgetMicros,
            String bidding,
            @JsonProperty(required = false) Long targetCpaMicros,
            List<Long> geoTargetIds,
            String adGroupName,
            List<KeywordArg> keywords,
            List<String> headlines,
            List<String> descriptions,
            String finalUrl,
            String euPoliticalAds) {}

    @McpTool(name = "create_campaign", description = """
            Propose a new Search campaign: budget, targeting, one ad group, keywords and one ad in a single atomic request. \
            It is always created PAUSED, on Google Search only (no search partners, no Display), with location \
            targeting by presence. bidding is MANUAL_CPC, MAXIMIZE_CONVERSIONS or TARGET_CPA (with targetCpaMicros). \
            euPoliticalAds must be NO_POLITICAL_ADS or HAS_POLITICAL_ADS and is confirmed by the owner in the diff. \
            Enabling it later is a separate approval (enable_entity).""" + COMMON)
    public ChangeOutcome createCampaign(
            @McpToolParam(description = "The campaign definition", required = true) CampaignArgs spec,
            @McpToolParam(description = "Why this change", required = true) String reason,
            @McpToolParam(description = "What you expect to happen", required = true) String expected_effect,
            @McpToolParam(description = "run_id from get_agent_context", required = true) String run_id,
            @McpToolParam(description = "Unique key for this intended change", required = true) String idempotency_key,
            McpTransportContext context) {
        return submit(() -> {
            if (spec == null) throw new IllegalArgumentException("spec is required");
            BiddingStrategy bidding = bidding(spec.bidding(), spec.targetCpaMicros());
            ChangeRequest.EuPoliticalAds eu;
            try {
                eu = spec.euPoliticalAds() == null ? null : ChangeRequest.EuPoliticalAds.valueOf(spec.euPoliticalAds().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("euPoliticalAds must be NO_POLITICAL_ADS or HAS_POLITICAL_ADS");
            }
            return new CreateCampaign(new CampaignSpec(spec.name(), spec.dailyBudgetMicros(), bidding, orEmpty(spec.geoTargetIds()),
                    spec.adGroupName(), specs(spec.keywords()), orEmpty(spec.headlines()), orEmpty(spec.descriptions()),
                    spec.finalUrl(), eu));
        }, reason, expected_effect, run_id, idempotency_key, context);
    }

    @McpTool(name = "add_ad_group", description = "Propose a new ad group in an existing agent-managed campaign: a tightly themed set of keywords and one responsive "
            + "search ad (3 to 15 unique headlines up to 30 characters, 2 to 4 descriptions up to 90, an https final URL on an allowed domain). "
            + "Tier 2: the owner sees the keywords and the ad copy. Prefer one more theme in an existing campaign to a new campaign, since a small "
            + "budget spread over many campaigns starves each one." + COMMON)
    public ChangeOutcome addAdGroup(
            @McpToolParam(description = "Campaign id", required = true) String campaign_id,
            @McpToolParam(description = "Ad group name", required = true) String name,
            @McpToolParam(description = "Keywords: text, matchType (EXACT, PHRASE, BROAD), optional cpcBidMicros", required = true) List<KeywordArg> keywords,
            @McpToolParam(description = "Headlines", required = true) List<String> headlines,
            @McpToolParam(description = "Descriptions", required = true) List<String> descriptions,
            @McpToolParam(description = "Final URL", required = true) String final_url,
            @McpToolParam(description = "Default max CPC in micros for Manual CPC campaigns", required = false) Long default_cpc_bid_micros,
            @McpToolParam(description = "Why this change", required = true) String reason,
            @McpToolParam(description = "What you expect to happen", required = true) String expected_effect,
            @McpToolParam(description = "run_id from get_agent_context", required = true) String run_id,
            @McpToolParam(description = "Unique key for this intended change", required = true) String idempotency_key,
            McpTransportContext context) {
        return submit(() -> new AddAdGroup(EntityRef.campaign(campaign_id), name, specs(keywords), orEmpty(headlines), orEmpty(descriptions), final_url,
                default_cpc_bid_micros), reason, expected_effect, run_id, idempotency_key, context);
    }

    @McpTool(name = "increase_budget", description = "Propose raising a daily budget by at most 20%, once per 7 days, within all spend caps. Tier 2 at every autonomy level." + COMMON)
    public ChangeOutcome increaseBudget(
            @McpToolParam(description = "Budget id", required = true) String budget_id,
            @McpToolParam(description = "New daily amount in micros", required = true) long new_amount_micros,
            @McpToolParam(description = "Why this change", required = true) String reason,
            @McpToolParam(description = "What you expect to happen", required = true) String expected_effect,
            @McpToolParam(description = "run_id from get_agent_context", required = true) String run_id,
            @McpToolParam(description = "Unique key for this intended change", required = true) String idempotency_key,
            McpTransportContext context) {
        return submit(() -> new IncreaseBudget(EntityRef.budget(budget_id), new_amount_micros), reason, expected_effect, run_id, idempotency_key, context);
    }

    @McpTool(name = "change_bidding_strategy", description = "Propose a different bidding strategy for a campaign. Tier 2: the diff shows the old and new strategy. "
            + "strategy is MANUAL_CPC, MAXIMIZE_CONVERSIONS or TARGET_CPA (with target_cpa_micros)." + COMMON)
    public ChangeOutcome changeBiddingStrategy(
            @McpToolParam(description = "Campaign id", required = true) String campaign_id,
            @McpToolParam(description = "MANUAL_CPC, MAXIMIZE_CONVERSIONS or TARGET_CPA", required = true) String strategy,
            @McpToolParam(description = "Required for TARGET_CPA, in micros", required = false) Long target_cpa_micros,
            @McpToolParam(description = "Why this change", required = true) String reason,
            @McpToolParam(description = "What you expect to happen", required = true) String expected_effect,
            @McpToolParam(description = "run_id from get_agent_context", required = true) String run_id,
            @McpToolParam(description = "Unique key for this intended change", required = true) String idempotency_key,
            McpTransportContext context) {
        return submit(() -> new ChangeBiddingStrategy(EntityRef.campaign(campaign_id), bidding(strategy, target_cpa_micros)),
                reason, expected_effect, run_id, idempotency_key, context);
    }

    @McpTool(name = "enable_entity", description = "Propose enabling a paused campaign, ad group, ad or keyword. This starts delivery, so it is always approved separately by the owner." + COMMON)
    public ChangeOutcome enableEntity(
            @McpToolParam(description = "CAMPAIGN, AD_GROUP, AD or KEYWORD", required = true) String kind,
            @McpToolParam(description = "Entity id", required = true) String entity_id,
            @McpToolParam(description = "Why this change", required = true) String reason,
            @McpToolParam(description = "What you expect to happen", required = true) String expected_effect,
            @McpToolParam(description = "run_id from get_agent_context", required = true) String run_id,
            @McpToolParam(description = "Unique key for this intended change", required = true) String idempotency_key,
            McpTransportContext context) {
        return submit(() -> new EnableEntity(new EntityRef(parseKind(kind, EntityKind.CAMPAIGN, EntityKind.AD_GROUP, EntityKind.AD, EntityKind.KEYWORD), entity_id)),
                reason, expected_effect, run_id, idempotency_key, context);
    }

    @McpTool(name = "apply_recommendation", description = "Propose applying one Google recommendation, with the real change shown in the diff. Recommendations that move spend or "
            + "bidding are refused: use the dedicated tools instead." + COMMON)
    public ChangeOutcome applyRecommendation(
            @McpToolParam(description = "Recommendation id from get_recommendations", required = true) String recommendation_id,
            @McpToolParam(description = "Why this change", required = true) String reason,
            @McpToolParam(description = "What you expect to happen", required = true) String expected_effect,
            @McpToolParam(description = "run_id from get_agent_context", required = true) String run_id,
            @McpToolParam(description = "Unique key for this intended change", required = true) String idempotency_key,
            McpTransportContext context) {
        return submit(() -> new ApplyRecommendation(new EntityRef(EntityKind.RECOMMENDATION, recommendation_id)),
                reason, expected_effect, run_id, idempotency_key, context);
    }

    @McpTool(name = "propose_rollback", description = "Propose restoring the recorded values of an earlier change. Becomes a new Tier 2 proposal that follows the same checks and has its "
            + "own audit record. Changes without a safe inverse (keywords, ads, recommendations) are refused: remove those in the Google Ads interface." + COMMON)
    public ChangeOutcome proposeRollback(
            @McpToolParam(description = "The change id to roll back (from a change result or get_proposal)", required = true) String change_id,
            @McpToolParam(description = "Why roll back", required = true) String reason,
            @McpToolParam(description = "What you expect to happen", required = true) String expected_effect,
            @McpToolParam(description = "run_id from get_agent_context", required = true) String run_id,
            @McpToolParam(description = "Unique key for this intended change", required = true) String idempotency_key,
            McpTransportContext context) {
        Optional<ChangeOutcome> blocked = tokenGate(context, run_id);
        if (blocked.isPresent()) return blocked.get();
        try {
            return changes.proposeRollback(change_id, new ChangeMeta(reason, expected_effect, run_id, idempotency_key));
        } catch (IllegalArgumentException e) {
            return ChangeOutcome.refused("invalid_argument", e.getMessage());
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private ChangeOutcome submit(Supplier<ChangeRequest> build, String reason, String expectedEffect, String runId, String key,
                                 McpTransportContext context) {
        Optional<ChangeOutcome> blocked = tokenGate(context, runId);
        if (blocked.isPresent()) return blocked.get();
        ChangeRequest request;
        ChangeMeta meta;
        try {
            meta = new ChangeMeta(reason, expectedEffect, runId, key);
            request = build.get();
        } catch (IllegalArgumentException | NullPointerException e) {
            return ChangeOutcome.refused("invalid_argument", e.getMessage() == null ? "A required argument is missing or malformed." : e.getMessage());
        }
        return changes.submit(request, meta);
    }

    /** A per-loop token can only use runs of its own loop, even if it somehow learned another run's id. */
    private Optional<ChangeOutcome> tokenGate(McpTransportContext context, String runId) {
        Optional<Loop> bound = LoopBinding.boundLoop(context);
        if (bound.isEmpty()) return Optional.empty();
        try {
            Optional<Run> run = runs.find(runId == null ? "" : runId);
            if (run.isEmpty() || run.get().loop() != bound.get()) {
                return Optional.of(ChangeOutcome.refused("run_not_yours", "This credential is for the " + bound.get()
                        + " loop and cannot use that run_id. Start a run with get_agent_context."));
            }
        } catch (DataAccessException e) {
            return Optional.of(ChangeOutcome.refused(ChangeService.DATABASE_UNAVAILABLE, "The database is unavailable, so the run cannot be checked."));
        }
        return Optional.empty();
    }

    private static EntityKind parseKind(String kind, EntityKind... allowed) {
        try {
            EntityKind k = EntityKind.valueOf(kind.trim().toUpperCase(Locale.ROOT));
            for (EntityKind a : allowed) if (a == k) return k;
        } catch (RuntimeException e) {
            // falls through to the message below
        }
        throw new IllegalArgumentException("kind must be one of " + java.util.Arrays.toString(allowed));
    }

    private static MatchType parseMatch(String match) {
        try {
            return MatchType.valueOf(match.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("match type must be EXACT, PHRASE or BROAD");
        }
    }

    private static BiddingStrategy bidding(String type, Long targetCpa) {
        try {
            BiddingStrategy.Type t = BiddingStrategy.Type.valueOf(type.trim().toUpperCase(Locale.ROOT));
            return new BiddingStrategy(t, targetCpa, null);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(e.getMessage() != null && e.getMessage().startsWith("TARGET_")
                    ? e.getMessage() : "bidding must be MANUAL_CPC, MAXIMIZE_CONVERSIONS or TARGET_CPA (with target_cpa_micros)");
        } catch (NullPointerException e) {
            throw new IllegalArgumentException("bidding strategy is required");
        }
    }

    private static List<KeywordSpec> specs(List<KeywordArg> keywords) {
        if (keywords == null) return List.of();
        return keywords.stream().map(k -> new KeywordSpec(k.text(), parseMatch(k.matchType()), k.cpcBidMicros())).toList();
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}

package app.autopilot.guard;

import app.autopilot.ads.AdsGateway;
import app.autopilot.ads.AdsGateway.BudgetInfo;
import app.autopilot.ads.AdsGateway.CampaignInfo;
import app.autopilot.ads.AdsGateway.KeywordQuery;
import app.autopilot.ads.AdsGateway.KeywordRow;
import app.autopilot.ads.AdsGateway.NegativeRow;
import app.autopilot.ads.AdsGateway.SearchTermRow;
import app.autopilot.domain.AutonomyLevel;
import app.autopilot.domain.BiddingStrategy;
import app.autopilot.domain.ChangeMeta;
import app.autopilot.domain.ChangeRequest;
import app.autopilot.domain.ChangeRequest.*;
import app.autopilot.domain.ControlState;
import app.autopilot.domain.Decision;
import app.autopilot.domain.Diff;
import app.autopilot.domain.EntityKind;
import app.autopilot.domain.EntityRef;
import app.autopilot.domain.EntityState;
import app.autopilot.domain.Goals;
import app.autopilot.domain.Limits;
import app.autopilot.domain.MatchType;
import app.autopilot.domain.Plan;
import app.autopilot.domain.Tier;
import app.autopilot.store.AlertStore;
import app.autopilot.store.LedgerStore;
import app.autopilot.store.RunStore.Run;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Every write passes through here, whichever part of the system asked for it. The guard reads live account
 * state, applies the owner's limits and decides the tier. It never trusts anything Claude concluded: refusals
 * name the rule and the limit so Claude can adjust or file a proposal.
 *
 * <p>The guard has no side effects. Idempotency, locking, audit and the actual write belong to
 * {@link app.autopilot.exec.ChangeService}.
 */
@Component
public class Guard {

    public enum Origin {
        /** A Claude run, bound to one loop. */
        CLAUDE,
        /** The owner approved a proposal; the server re-checks everything because the account may have changed. */
        APPROVAL
    }

    /** One change to evaluate. {@code approvedStateHash} is set for {@link Origin#APPROVAL}. */
    public record Request(ChangeRequest change, ChangeMeta meta, Origin origin, Run run,
                          boolean rollback, String approvedStateHash) {}

    /**
     * @param spendCapBreached        a spend alert is open (the only time an emergency pause is allowed)
     * @param conversionDropOpen      conversions dropped sharply or no sale conversion exists, so every performance
     *                                judgement would be wrong; changes that rely on conversion data wait
     * @param noRecentSales           no sale was recorded in 30 days while spending: a keyword with zero conversions
     *                                then proves nothing, so keyword pauses wait
     */
    public record Context(Limits limits, Goals goals, ControlState control, boolean spendCapBreached, boolean conversionDropOpen,
                          boolean noRecentSales) {}

    /** Rule names, stable because tests and Claude's retries key on them. */
    public static final class Rule {
        public static final String ROLLBACK_ONLY = "rollback_only";
        public static final String KILL_SWITCH = "kill_switch";
        public static final String BREAKER_OPEN = "breaker_open";
        public static final String CAPS_NOT_SET = "caps_not_set";
        public static final String AUTONOMY_L0 = "autonomy_l0";
        public static final String RUN_INVALID = "run_invalid";
        public static final String LOOP_NOT_ALLOWED = "loop_not_allowed";
        public static final String UNKNOWN_ENTITY = "unknown_entity";
        public static final String NOT_MANAGED = "not_agent_managed";
        public static final String NO_AGENT = "no_agent_label";
        public static final String SHARED_BUDGET = "shared_budget_scope";
        public static final String COOLDOWN = "cooldown";
        public static final String DUPLICATE = "duplicate";
        public static final String NO_CHANGE = "no_change";
        public static final String BUDGET_INCREASE_PCT = "budget_increase_pct";
        public static final String BUDGET_INCREASE_INTERVAL = "budget_increase_interval";
        public static final String BUDGET_DECREASE_PCT = "budget_decrease_pct";
        public static final String BUDGET_DECREASE_INTERVAL = "budget_decrease_interval";
        public static final String BUDGET_FLOOR = "budget_floor";
        public static final String MONTHLY_CAP = "monthly_cap";
        public static final String BID_CHANGE_PCT = "bid_change_pct";
        public static final String BID_INTERVAL = "bid_interval";
        public static final String MANUAL_CPC_ONLY = "manual_cpc_only";
        public static final String NEGATIVES_PER_DAY = "negatives_per_day";
        public static final String NEGATIVE_MATCH_TYPE = "negative_match_type";
        public static final String NEGATIVE_INVALID = "negative_term_invalid";
        public static final String NEGATIVE_CONFLICT = "negative_conflict";
        public static final String NEGATIVE_EXISTS = "negative_exists";
        public static final String SUSPICIOUS_TEXT = "suspicious_text";
        public static final String KEYWORD_THRESHOLD = "keyword_pause_threshold";
        public static final String TARGET_CPA_MISSING = "target_cpa_missing";
        public static final String LAST_KEYWORD = "last_active_keyword";
        public static final String LAST_AD = "last_enabled_ad";
        public static final String TIER1_VOLUME = "tier1_volume";
        public static final String EMERGENCY_ONLY = "emergency_only";
        public static final String FINAL_URL_DOMAIN = "final_url_domain";
        public static final String AD_ASSETS = "ad_assets";
        public static final String GEO_NOT_ALLOWED = "geo_not_allowed";
        public static final String CAMPAIGN_INVALID = "campaign_invalid";
        public static final String STATE_CHANGED = "state_changed";
        public static final String RECOMMENDATION_TYPE = "recommendation_type";
        public static final String INVALID_STATE = "invalid_state";
        public static final String CONVERSION_DROP = "conversion_tracking_suspect";
        public static final String TARGET_CPA_ABOVE_BREAK_EVEN = "target_cpa_above_break_even";
        public static final String CAMPAIGN_LIMIT = "campaign_limit";
        public static final String BUDGET_BELOW_MINIMUM = "budget_below_minimum";
        private Rule() {}
    }

    /** Recommendations that move spend or bidding must go through their dedicated, limited tools. */
    private static final Set<String> BLOCKED_RECOMMENDATIONS = Set.of(
            "CAMPAIGN_BUDGET", "FORECASTING_CAMPAIGN_BUDGET", "MARGINAL_ROI_CAMPAIGN_BUDGET", "MOVE_UNUSED_BUDGET",
            "SEARCH_PARTNERS_OPT_IN", "DISPLAY_EXPANSION_OPT_IN", "PERFORMANCE_MAX_OPT_IN",
            "PERFORMANCE_MAX_FINAL_URL_OPT_IN", "TARGET_CPA_OPT_IN", "TARGET_ROAS_OPT_IN", "MAXIMIZE_CONVERSIONS_OPT_IN",
            "MAXIMIZE_CONVERSION_VALUE_OPT_IN", "MAXIMIZE_CLICKS_OPT_IN", "ENHANCED_CPC_OPT_IN", "RAISE_TARGET_CPA",
            "LOWER_TARGET_ROAS", "RAISE_TARGET_CPA_BID_TOO_LOW", "SET_TARGET_CPA", "SET_TARGET_ROAS",
            "FORECASTING_SET_TARGET_CPA", "FORECASTING_SET_TARGET_ROAS", "USE_BROAD_MATCH_KEYWORD",
            "KEYWORD_MATCH_TYPE", "AI_MAX_OPT_IN");

    private final AdsGateway ads;
    private final LedgerStore ledger;
    private final AlertStore alerts;
    private final Clock clock;

    public Guard(AdsGateway ads, LedgerStore ledger, AlertStore alerts, Clock clock) {
        this.ads = ads;
        this.ledger = ledger;
        this.alerts = alerts;
        this.clock = clock;
    }

    /** Thrown inside the guard to stop at the first failed rule. */
    private static final class Refusal extends RuntimeException {
        final String rule;
        Refusal(String rule, String message) {
            super(message, null, false, false);
            this.rule = rule;
        }
    }

    private static Refusal refuse(String rule, String message) {
        return new Refusal(rule, message);
    }

    // ------------------------------------------------------------------------------------------

    public Decision evaluate(Request req, Context ctx) {
        try {
            return decide(req, ctx);
        } catch (Refusal r) {
            return new Decision.Refused(r.rule, r.getMessage());
        }
    }

    private Decision decide(Request req, Context ctx) {
        ChangeRequest change = req.change();
        Limits limits = ctx.limits();
        Instant now = clock.instant();
        boolean emergency = change instanceof EmergencyPauseCampaign;

        if (change.rollbackOnly() && !req.rollback()) {
            throw refuse(Rule.ROLLBACK_ONLY, change.tool() + " is only reachable through propose_rollback");
        }

        // Stop mechanisms. A protective pause is the one write they let through.
        ControlState control = ctx.control();
        if (!emergency) {
            if (control.killSwitch()) {
                throw refuse(Rule.KILL_SWITCH, "The kill switch is on: the server is read-only. Only the owner can clear it.");
            }
            if (control.breakerOpen()) {
                throw refuse(Rule.BREAKER_OPEN, "The circuit breaker is open: writes are blocked until the owner clears it.");
            }
            if (!limits.capsConfigured()) {
                throw refuse(Rule.CAPS_NOT_SET, "Writes are disabled until the account id and the monthly cap are set in server configuration.");
            }
        }

        if (ctx.conversionDropOpen() && !emergency && !req.rollback() && !(change instanceof DecreaseBudget)) {
            throw refuse(Rule.CONVERSION_DROP, "The conversion signal is suspect (conversions dropped sharply, or no sale conversion is "
                    + "set up) and an alert is open, so decisions based on performance would be wrong. Only spend reductions and "
                    + "rollbacks are allowed until it clears.");
        }

        if (req.origin() == Origin.CLAUDE) {
            checkRun(req, limits, now);
        }

        // State and scope.
        List<EntityState> related = new ArrayList<>();
        EntityState target = null;
        if (change.target() != null) {
            target = state(change.target(), limits, now);
            related.add(target);
            related.addAll(checkScope(change, target, limits, now));
        }

        if (req.origin() == Origin.APPROVAL && req.approvedStateHash() != null) {
            String current = related.isEmpty() ? null : StateHasher.hash(related);
            if (current != null && !current.equals(req.approvedStateHash())) {
                throw refuse(Rule.STATE_CHANGED, "The entity changed after this proposal was made; a new proposal is needed.");
            }
        }

        Diff diff = switch (change) {
            case AddNegativeKeywords c -> addNegatives(c, target, ctx, now);
            case PauseKeyword c -> pauseKeyword(c, target, ctx);
            case AdjustKeywordBid c -> adjustBid(c, target, ctx, req, now);
            case DecreaseBudget c -> decreaseBudget(c, target, ctx, req, now);
            case EmergencyPauseCampaign c -> emergencyPause(c, target, ctx);
            case PauseAd c -> pauseAd(c, target);
            case AddKeywords c -> addKeywords(c, target);
            case CreateAd c -> createAd(c, target, limits);
            case CreateCampaign c -> createCampaign(c, ctx);
            case AddAdGroup c -> addAdGroup(c, target, ctx.limits());
            case IncreaseBudget c -> increaseBudget(c, target, ctx, req, now);
            case ChangeBiddingStrategy c -> changeBidding(c, target, ctx);
            case EnableEntity c -> enableEntity(c, target, ctx);
            case ApplyRecommendation c -> applyRecommendation(c, target);
            case RemoveNegativeKeywords c -> removeNegatives(c, target);
            case PauseEntity c -> pauseEntity(c, target);
        };

        // Cooldown and duplicates (a protective pause and a rollback are exempt from the cooldown).
        if (change.target() != null && change.cooldownApplies() && !emergency && !req.rollback()) {
            checkCooldown(change.target().key(), limits, now, 0);
        }
        if (!req.rollback() && ledger.appliedWithFingerprintSince(change.fingerprint(), now.minus(Duration.ofDays(30)))) {
            throw refuse(Rule.DUPLICATE, "The same change (" + change.tool() + " on "
                    + (change.target() == null ? "a new entity" : change.target()) + ") was already applied in the last 30 days.");
        }

        String hash = related.isEmpty() ? StateHasher.sha256(change.fingerprint()) : StateHasher.hash(related);
        Tier tier = change.tier();
        Plan plan = new Plan(change, req.meta(), tier, diff, hash, now);

        if (req.origin() == Origin.APPROVAL) {
            return new Decision.Apply(plan);
        }
        return tierDecision(req, plan, limits, now);
    }

    // ---- run binding, autonomy, tier decision ---------------------------------------------------

    private void checkRun(Request req, Limits limits, Instant now) {
        Run run = req.run();
        if (run == null || !run.activeAt(now)) {
            throw refuse(Rule.RUN_INVALID, "run_id is unknown, has ended or has expired. Call get_agent_context to start a run.");
        }
        if (limits.autonomy() == AutonomyLevel.L0) {
            throw refuse(Rule.AUTONOMY_L0, "Autonomy is L0 (observe): changes and proposals are off. The owner raises it in server configuration.");
        }
        if (req.change() instanceof EmergencyPauseCampaign && run.loop() != app.autopilot.domain.Loop.TRIAGE) {
            throw refuse(Rule.LOOP_NOT_ALLOWED, "emergency_pause_campaign is only available to triage runs; this run belongs to the "
                    + run.loop() + " loop.");
        }
    }

    private Decision tierDecision(Request req, Plan plan, Limits limits, Instant now) {
        Run run = req.run();
        ChangeRequest change = plan.request();
        if (plan.tier() == Tier.T2) {
            return new Decision.Propose(plan, "Tier 2: only the owner approves");
        }
        if (!limits.autonomy().atLeast(AutonomyLevel.L2)) {
            return new Decision.Propose(plan, "autonomy " + limits.autonomy() + " files Tier 1 changes as proposals");
        }
        if (!run.loop().mayApplyDirectly(change.tool())) {
            return new Decision.Propose(plan, "the " + run.loop() + " loop proposes " + change.tool() + " instead of applying it");
        }
        boolean emergency = change instanceof EmergencyPauseCampaign;
        if (!emergency) {
            Instant dayStart = limits.zone().equals(ZoneId.of("UTC")) ? now.truncatedTo(java.time.temporal.ChronoUnit.DAYS)
                    : LocalDate.ofInstant(now, limits.zone()).atStartOfDay(limits.zone()).toInstant();
            long today = ledger.tier1ChangesSince(dayStart);
            if (today >= limits.tier1PerDay()) {
                throw refuse(Rule.TIER1_VOLUME, "Tier 1 volume limit reached: " + today + " changes today, limit "
                        + limits.tier1PerDay() + " per day.");
            }
        }
        return new Decision.Apply(plan);
    }

    // ---- scope ------------------------------------------------------------------------------------

    private EntityState state(EntityRef ref, Limits limits, Instant now) {
        LocalDate today = LocalDate.ofInstant(now, limits.zone());
        LocalDate to = today.minusDays(limits.conversionLagDays());
        LocalDate from = to.minusDays(limits.keywordPauseWindowDays() - 1L);
        return ads.entityState(ref, from, to).orElseThrow(() ->
                refuse(Rule.UNKNOWN_ENTITY, ref + " was not found in the allowlisted account."));
    }

    /** Scope: agent-managed campaign, no no-agent label anywhere. A budget is managed only if all its campaigns are. */
    private List<EntityState> checkScope(ChangeRequest change, EntityState target, Limits limits, Instant now) {
        List<EntityState> extra = new ArrayList<>();
        if (target.ref().kind() == EntityKind.BUDGET) {
            List<String> campaignIds = target.listAttr("campaignIds");
            if (campaignIds.isEmpty()) {
                throw refuse(Rule.NOT_MANAGED, target.ref() + " is not used by any campaign carrying the label '"
                        + limits.managedLabel() + "'.");
            }
            int managed = 0;
            String unmanagedId = null;
            for (String id : campaignIds) {
                EntityState campaign = state(EntityRef.campaign(id), limits, now);
                extra.add(campaign);
                if (campaign.chainLabels().contains(limits.noAgentLabel())) {
                    throw refuse(Rule.NO_AGENT, "Campaign " + id + " uses " + target.ref() + " and carries the label '"
                            + limits.noAgentLabel() + "', which always wins.");
                }
                if (campaign.campaignLabels().contains(limits.managedLabel())) {
                    managed++;
                } else {
                    unmanagedId = id;
                }
            }
            if (managed == 0) {
                throw refuse(Rule.NOT_MANAGED, target.ref() + " is used only by campaigns without the label '"
                        + limits.managedLabel() + "'.");
            }
            if (unmanagedId != null) {
                throw refuse(Rule.SHARED_BUDGET, target.ref() + " is shared with campaign " + unmanagedId
                        + " which does not carry the label '" + limits.managedLabel() + "', so changing it would touch an unmanaged campaign.");
            }
            return extra;
        }
        if (target.chainLabels().contains(limits.noAgentLabel())) {
            throw refuse(Rule.NO_AGENT, target.ref() + " carries the label '" + limits.noAgentLabel()
                    + "', which always wins: Claude does not touch it.");
        }
        if (!target.campaignLabels().contains(limits.managedLabel())) {
            throw refuse(Rule.NOT_MANAGED, target.ref() + " is not in a campaign carrying the label '"
                    + limits.managedLabel() + "'.");
        }
        return extra;
    }

    private void checkCooldown(String entityKey, Limits limits, Instant now, int toolIntervalHours) {
        int hours = limits.cooldownHoursFor(toolIntervalHours);
        ledger.lastChangeAt(entityKey).ifPresent(last -> {
            Duration age = Duration.between(last, now);
            if (age.compareTo(Duration.ofHours(hours)) < 0) {
                throw refuse(Rule.COOLDOWN, entityKey + " changed " + age.toHours() + " h ago; the cooldown is "
                        + hours + " h so each result can be read before the next change.");
            }
        });
    }

    private void checkInterval(String entityKey, String tool, String rule, int hours, String label, Instant now) {
        ledger.lastChangeAt(entityKey, tool).ifPresent(last -> {
            Duration age = Duration.between(last, now);
            if (age.compareTo(Duration.ofHours(hours)) < 0) {
                throw refuse(rule, label + " on " + entityKey + " was last changed " + age.toHours()
                        + " h ago; the limit is once per " + hours + " h.");
            }
        });
    }

    // ---- budgets ----------------------------------------------------------------------------------

    private Diff decreaseBudget(DecreaseBudget c, EntityState budget, Context ctx, Request req, Instant now) {
        Limits l = ctx.limits();
        long old = amount(budget);
        long neu = c.newAmountMicros();
        if (neu <= 0) throw refuse(Rule.BUDGET_FLOOR, "A budget must stay above zero; use a pause for that.");
        if (neu >= old) throw refuse(Rule.NO_CHANGE, "decrease_budget needs a lower amount than the current " + Money.fmt(old) + ".");
        if (!req.rollback() && neu < l.minDailyBudgetMicros()) {
            throw refuse(Rule.BUDGET_BELOW_MINIMUM, "A daily budget of " + Money.fmt(neu) + " is below the minimum of "
                    + Money.fmt(l.minDailyBudgetMicros()) + " per campaign. To stop spending, pause the campaign instead.");
        }
        if (!req.rollback()) {
            if (Pct.exceeds(old, neu, l.budgetDecreasePct())) {
                throw refuse(Rule.BUDGET_DECREASE_PCT, "Budget change of " + Pct.signed(old, neu) + " exceeds the -"
                        + l.budgetDecreasePct() + "% limit.");
            }
            checkInterval(budget.ref().key(), "decrease_budget", Rule.BUDGET_DECREASE_INTERVAL,
                    l.cooldownHoursFor(l.budgetDecreaseIntervalHours()), "Budget decrease", now);
        }
        return new Diff(Map.of("amountMicros", old), Map.of("amountMicros", neu),
                "Decrease budget " + budget.ref().id() + " from " + Money.fmt(old) + " to " + Money.fmt(neu)
                        + " (" + Pct.signed(old, neu) + ")");
    }

    private Diff increaseBudget(IncreaseBudget c, EntityState budget, Context ctx, Request req, Instant now) {
        Limits l = ctx.limits();
        long old = amount(budget);
        long neu = c.newAmountMicros();
        if (neu <= old) throw refuse(Rule.NO_CHANGE, "increase_budget needs a higher amount than the current " + Money.fmt(old) + ".");
        if (!req.rollback()) {
            if (Pct.exceeds(old, neu, l.budgetIncreasePct())) {
                throw refuse(Rule.BUDGET_INCREASE_PCT, "Budget change of " + Pct.signed(old, neu) + " exceeds the +"
                        + l.budgetIncreasePct() + "% limit.");
            }
            checkInterval(budget.ref().key(), "increase_budget", Rule.BUDGET_INCREASE_INTERVAL,
                    l.budgetIncreaseIntervalDays() * 24, "Budget increase", now);
        }
        long sum = sumDailyBudgets() - old + neu;
        requireWithinCap(sum, l);
        return new Diff(Map.of("amountMicros", old), Map.of("amountMicros", neu),
                "Increase budget " + budget.ref().id() + " from " + Money.fmt(old) + " to " + Money.fmt(neu)
                        + " (" + Pct.signed(old, neu) + "); daily budgets then sum to " + Money.fmt(sum));
    }

    private long amount(EntityState budget) {
        Long a = budget.longAttr("amountMicros");
        if (a == null || a <= 0) throw refuse(Rule.INVALID_STATE, budget.ref() + " has no readable amount.");
        return a;
    }

    /** Sum of daily budgets of budgets used by at least one enabled campaign, each counted once. */
    long sumDailyBudgets() {
        return app.autopilot.ads.AccountMath.sumDailyBudgets(ads.campaigns(), ads.budgets());
    }

    private void requireWithinCap(long sumDailyMicros, Limits l) {
        if (!Pct.withinMonthlyCap(sumDailyMicros, l.monthlyCapMicros())) {
            throw refuse(Rule.MONTHLY_CAP, "Daily budgets would sum to " + Money.fmt(sumDailyMicros) + "; x 30.4 = "
                    + Money.fmt(sumDailyMicros * 304 / 10) + " exceeds the monthly cap of " + Money.fmt(l.monthlyCapMicros())
                    + " (largest allowed sum: " + Money.fmt(Pct.maxDailySum(l.monthlyCapMicros())) + ").");
        }
    }

    // ---- keywords and bids -------------------------------------------------------------------------

    private Diff adjustBid(AdjustKeywordBid c, EntityState kw, Context ctx, Request req, Instant now) {
        Limits l = ctx.limits();
        requireKind(kw, EntityKind.KEYWORD);
        if (!BiddingStrategy.Type.MANUAL_CPC.name().equals(kw.stringAttr("biddingStrategy"))) {
            throw refuse(Rule.MANUAL_CPC_ONLY, "Keyword bids can be changed only on Manual CPC campaigns; this one uses "
                    + kw.stringAttr("biddingStrategy") + ".");
        }
        Long old = kw.longAttr("cpcBidMicros");
        if (old == null || old <= 0) throw refuse(Rule.INVALID_STATE, kw.ref() + " has no keyword-level bid.");
        long neu = c.newCpcBidMicros();
        if (neu <= 0) throw refuse(Rule.BID_CHANGE_PCT, "A bid must stay above zero.");
        if (neu == old) throw refuse(Rule.NO_CHANGE, "The bid is already " + Money.fmt(old) + ".");
        if (!req.rollback()) {
            if (Pct.exceeds(old, neu, l.bidChangePct())) {
                throw refuse(Rule.BID_CHANGE_PCT, "Bid change of " + Pct.signed(old, neu) + " exceeds the +-"
                        + l.bidChangePct() + "% limit.");
            }
            checkInterval(kw.ref().key(), "adjust_keyword_bid", Rule.BID_INTERVAL,
                    l.cooldownHoursFor(l.bidIntervalDays() * 24), "Bid change", now);
        }
        return new Diff(Map.of("cpcBidMicros", old), Map.of("cpcBidMicros", neu),
                "Change bid of keyword '" + kw.stringAttr("text") + "' from " + Money.fmt(old) + " to " + Money.fmt(neu)
                        + " (" + Pct.signed(old, neu) + ")");
    }

    private Diff pauseKeyword(PauseKeyword c, EntityState kw, Context ctx) {
        Limits l = ctx.limits();
        requireKind(kw, EntityKind.KEYWORD);
        requireStatus(kw, "ENABLED");
        if (ctx.noRecentSales()) {
            throw refuse(Rule.CONVERSION_DROP, "No sale was recorded in the last 30 days while the account spent, so a keyword with zero "
                    + "conversions proves nothing: if tracking is broken every keyword looks like a loser. Fix the tracking first.");
        }
        Long targetCpa = ctx.goals().targetCpaMicros();
        if (targetCpa == null || targetCpa <= 0) {
            throw refuse(Rule.TARGET_CPA_MISSING, "pause_keyword needs a target CPA in the goals to judge whether the cost so far is meaningful.");
        }
        long clicks = nz(kw.longAttr("clicks"));
        long cost = nz(kw.longAttr("costMicros"));
        double conversions = kw.doubleAttr("conversions") == null ? 0 : kw.doubleAttr("conversions");
        if (clicks < l.keywordPauseMinClicks()) {
            throw refuse(Rule.KEYWORD_THRESHOLD, "Keyword has " + clicks + " clicks in the " + l.keywordPauseWindowDays()
                    + "-day window that ends " + l.conversionLagDays() + " days ago; the minimum is "
                    + l.keywordPauseMinClicks() + ".");
        }
        if (conversions > 0) {
            throw refuse(Rule.KEYWORD_THRESHOLD, "Keyword has " + conversions + " conversions in the window; a pause needs 0.");
        }
        long minCost = Math.round(targetCpa * l.keywordPauseMinCostCpaMultiple());
        if (cost < minCost) {
            throw refuse(Rule.KEYWORD_THRESHOLD, "Keyword cost " + Money.fmt(cost) + " is below " + l.keywordPauseMinCostCpaMultiple()
                    + " x target CPA (" + Money.fmt(minCost) + ").");
        }
        if (nz(kw.longAttr("adGroupActiveKeywords")) <= 1) {
            throw refuse(Rule.LAST_KEYWORD, "The ad group would be left without an active keyword.");
        }
        return new Diff(Map.of("status", "ENABLED"), Map.of("status", "PAUSED"),
                "Pause keyword '" + kw.stringAttr("text") + "' (" + clicks + " clicks, " + Money.fmt(cost) + " cost, 0 conversions)");
    }

    private Diff addNegatives(AddNegativeKeywords c, EntityState scope, Context ctx, Instant now) {
        Limits l = ctx.limits();
        if (scope.ref().kind() != EntityKind.CAMPAIGN && scope.ref().kind() != EntityKind.AD_GROUP) {
            throw refuse(Rule.INVALID_STATE, "Negative keywords attach to a campaign or an ad group.");
        }
        if (c.matchType() != MatchType.EXACT && c.matchType() != MatchType.PHRASE) {
            throw refuse(Rule.NEGATIVE_MATCH_TYPE, "Negative keywords must be EXACT or PHRASE match; BROAD is not allowed.");
        }
        if (c.terms().isEmpty()) throw refuse(Rule.NEGATIVE_INVALID, "No negative keywords given.");
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String raw : c.terms()) {
            String term = NegativeMatcher.normalize(raw);
            if (term.isEmpty() || term.length() > 80 || term.split(" ").length > 10) {
                throw refuse(Rule.NEGATIVE_INVALID, "Negative '" + raw + "' is empty, longer than 80 characters or has more than 10 words.");
            }
            SuspiciousText.reason(raw).ifPresent(why -> {
                throw refuse(Rule.SUSPICIOUS_TEXT, "Negative '" + abbreviate(raw) + "' " + why + " and goes to the review list instead.");
            });
            terms.add(term);
        }
        Instant dayStart = LocalDate.ofInstant(now, l.zone()).atStartOfDay(l.zone()).toInstant();
        long today = ledger.unitsSince("add_negative_keywords", dayStart);
        if (today + terms.size() > l.negativesPerDay()) {
            throw refuse(Rule.NEGATIVES_PER_DAY, "Adding " + terms.size() + " negatives would exceed the limit of "
                    + l.negativesPerDay() + " per day (" + today + " already added today).");
        }

        // conflicts: protected terms, active keywords, converted search terms
        for (String term : terms) {
            for (String protectedTerm : ctx.goals().protectedTerms()) {
                if (NegativeMatcher.blocks(term, c.matchType(), protectedTerm)) {
                    throw refuse(Rule.NEGATIVE_CONFLICT, "Negative '" + term + "' would block the protected term '" + protectedTerm + "'.");
                }
            }
        }
        String campaignId = scope.ref().kind() == EntityKind.CAMPAIGN ? scope.ref().id() : scope.campaignId();
        List<KeywordRow> keywords = ads.keywords(new KeywordQuery(campaignId, true, null, null, 5000));
        for (String term : terms) {
            for (KeywordRow k : keywords) {
                if (scope.ref().kind() == EntityKind.AD_GROUP && !scope.ref().id().equals(k.adGroupId())) continue;
                if (NegativeMatcher.blocks(term, c.matchType(), k.text())) {
                    throw refuse(Rule.NEGATIVE_CONFLICT, "Negative '" + term + "' would block the active keyword '" + k.text() + "'.");
                }
            }
        }
        LocalDate today2 = LocalDate.ofInstant(now, l.zone());
        List<SearchTermRow> history = ads.searchTerms(today2.minusDays(l.negativeConvertedLookbackDays()), today2, 5000);
        for (String term : terms) {
            for (SearchTermRow st : history) {
                if (st.conversions() <= 0) continue;
                if (scope.ref().kind() == EntityKind.CAMPAIGN && !scope.ref().id().equals(st.campaignId())) continue;
                if (scope.ref().kind() == EntityKind.AD_GROUP && !scope.ref().id().equals(st.adGroupId())) continue;
                if (NegativeMatcher.blocks(term, c.matchType(), st.term())) {
                    throw refuse(Rule.NEGATIVE_CONFLICT, "Negative '" + term + "' would block the search term '"
                            + abbreviate(st.term()) + "', which converted in the last " + l.negativeConvertedLookbackDays() + " days.");
                }
            }
        }
        List<NegativeRow> existing = ads.negatives(scope.ref());
        for (String term : terms) {
            for (NegativeRow n : existing) {
                if (n.matchType() == c.matchType() && NegativeMatcher.normalize(n.text()).equals(term)) {
                    throw refuse(Rule.NEGATIVE_EXISTS, "Negative '" + term + "' (" + c.matchType() + ") already exists on " + scope.ref() + ".");
                }
            }
        }
        return new Diff(Map.of(), Map.of("terms", List.copyOf(terms), "matchType", c.matchType().name()),
                "Add " + terms.size() + " " + c.matchType() + " negative keyword(s) to " + scope.ref() + ": " + terms);
    }

    private Diff addKeywords(AddKeywords c, EntityState adGroup) {
        requireKind(adGroup, EntityKind.AD_GROUP);
        if (c.keywords().isEmpty() || c.keywords().size() > 50) {
            throw refuse(Rule.INVALID_STATE, "add_keywords takes between 1 and 50 keywords.");
        }
        for (KeywordSpec k : c.keywords()) {
            String text = NegativeMatcher.normalize(k.text());
            if (text.isEmpty() || text.length() > 80 || text.split(" ").length > 10) {
                throw refuse(Rule.INVALID_STATE, "Keyword '" + abbreviate(k.text()) + "' is empty, longer than 80 characters or has more than 10 words.");
            }
            SuspiciousText.reason(k.text()).ifPresent(why -> {
                throw refuse(Rule.SUSPICIOUS_TEXT, "Keyword '" + abbreviate(k.text()) + "' " + why + "; it cannot become a keyword automatically.");
            });
        }
        return new Diff(Map.of(), Map.of("keywords", c.keywords().stream()
                .map(k -> k.matchType() + " " + k.text()).toList()),
                "Add " + c.keywords().size() + " keyword(s) to ad group " + adGroup.ref().id());
    }

    // ---- ads, campaigns ----------------------------------------------------------------------------

    private Diff pauseAd(PauseAd c, EntityState ad) {
        requireKind(ad, EntityKind.AD);
        requireStatus(ad, "ENABLED");
        if (nz(ad.longAttr("adGroupEnabledAds")) <= 1) {
            throw refuse(Rule.LAST_AD, "The ad group would be left without an enabled ad.");
        }
        return new Diff(Map.of("status", "ENABLED"), Map.of("status", "PAUSED"), "Pause ad " + ad.ref().id());
    }

    private Diff createAd(CreateAd c, EntityState adGroup, Limits limits) {
        requireKind(adGroup, EntityKind.AD_GROUP);
        validateAdAssets(c.headlines(), c.descriptions(), c.finalUrl(), limits);
        return new Diff(Map.of(), c.newValues(), "Create a responsive search ad in ad group " + adGroup.ref().id()
                + " (" + c.headlines().size() + " headlines, " + c.descriptions().size() + " descriptions) -> " + c.finalUrl());
    }

    private void validateAdAssets(List<String> headlines, List<String> descriptions, String finalUrl, Limits limits) {
        if (headlines.size() < 3 || headlines.size() > 15) {
            throw refuse(Rule.AD_ASSETS, "A responsive search ad needs 3 to 15 headlines; got " + headlines.size() + ".");
        }
        if (descriptions.size() < 2 || descriptions.size() > 4) {
            throw refuse(Rule.AD_ASSETS, "A responsive search ad needs 2 to 4 descriptions; got " + descriptions.size() + ".");
        }
        if (new HashSet<>(headlines).size() != headlines.size()) {
            throw refuse(Rule.AD_ASSETS, "Headlines must be unique.");
        }
        for (String h : headlines) {
            if (h == null || h.isBlank() || h.length() > 30) throw refuse(Rule.AD_ASSETS, "Headline '" + abbreviate(h) + "' is empty or longer than 30 characters.");
        }
        for (String d : descriptions) {
            if (d == null || d.isBlank() || d.length() > 90) throw refuse(Rule.AD_ASSETS, "Description '" + abbreviate(d) + "' is empty or longer than 90 characters.");
        }
        requireAllowedDomain(finalUrl, limits);
    }

    private void requireAllowedDomain(String url, Limits limits) {
        String host;
        try {
            URI uri = URI.create(url == null ? "" : url.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null || uri.getHost() == null) {
                throw refuse(Rule.FINAL_URL_DOMAIN, "Final URL must be an https URL without credentials: " + abbreviate(url));
            }
            host = uri.getHost().toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw refuse(Rule.FINAL_URL_DOMAIN, "Final URL is not a valid URL: " + abbreviate(url));
        }
        for (String allowed : limits.allowedDomains()) {
            String a = allowed.toLowerCase(Locale.ROOT);
            if (host.equals(a) || host.endsWith("." + a)) return;
        }
        throw refuse(Rule.FINAL_URL_DOMAIN, "Final URL host '" + host + "' is not on the allowed domain list " + limits.allowedDomains() + ".");
    }

    private Diff createCampaign(CreateCampaign c, Context ctx) {
        Limits l = ctx.limits();
        CampaignSpec s = c.spec();
        if (s.name() == null || s.name().isBlank() || s.name().length() > 255) {
            throw refuse(Rule.CAMPAIGN_INVALID, "Campaign name is required and at most 255 characters.");
        }
        if (s.dailyBudgetMicros() <= 0) throw refuse(Rule.CAMPAIGN_INVALID, "Daily budget must be above zero.");
        if (s.dailyBudgetMicros() < l.minDailyBudgetMicros()) {
            throw refuse(Rule.BUDGET_BELOW_MINIMUM, "A daily budget of " + Money.fmt(s.dailyBudgetMicros()) + " is below the minimum of "
                    + Money.fmt(l.minDailyBudgetMicros()) + " per campaign: too little to learn from.");
        }
        if (s.euPoliticalAds() == null) {
            throw refuse(Rule.CAMPAIGN_INVALID, "The EU political advertising declaration must be given explicitly.");
        }
        if (s.keywords().isEmpty()) throw refuse(Rule.CAMPAIGN_INVALID, "A campaign needs at least one keyword.");
        if (s.adGroupName() == null || s.adGroupName().isBlank()) throw refuse(Rule.CAMPAIGN_INVALID, "An ad group name is required.");
        if (s.bidding() == null) throw refuse(Rule.CAMPAIGN_INVALID, "A bidding strategy is required.");
        for (KeywordSpec k : s.keywords()) {
            SuspiciousText.reason(k.text()).ifPresent(why -> {
                throw refuse(Rule.SUSPICIOUS_TEXT, "Keyword '" + abbreviate(k.text()) + "' " + why + ".");
            });
        }
        if (l.allowedGeoTargets().isEmpty()) {
            throw refuse(Rule.GEO_NOT_ALLOWED, "No allowed locations are configured, so campaigns cannot be created.");
        }
        if (s.geoTargetIds().isEmpty()) throw refuse(Rule.GEO_NOT_ALLOWED, "At least one location is required.");
        for (Long geo : s.geoTargetIds()) {
            if (!l.allowedGeoTargets().contains(geo)) {
                throw refuse(Rule.GEO_NOT_ALLOWED, "Location " + geo + " is not on the allowed list " + l.allowedGeoTargets() + ".");
            }
        }
        validateAdAssets(s.headlines(), s.descriptions(), s.finalUrl(), l);
        requireTargetCpaAffordable(s.bidding(), ctx.goals());
        List<CampaignInfo> campaigns = ads.campaigns();
        requireCampaignRoom(l, campaigns.stream().filter(CampaignInfo::enabled).count(), "creating campaign '" + abbreviate(s.name()) + "'");
        for (CampaignInfo existing : campaigns) {
            if (existing.name().equalsIgnoreCase(s.name())) {
                throw refuse(Rule.CAMPAIGN_INVALID, "A campaign named '" + s.name() + "' already exists.");
            }
        }
        long sum = sumDailyBudgets() + s.dailyBudgetMicros();
        requireWithinCap(sum, l);
        return new Diff(Map.of(), c.newValues(), "Create campaign '" + s.name() + "' PAUSED with daily budget "
                + Money.fmt(s.dailyBudgetMicros()) + " (daily budgets then sum to " + Money.fmt(sum) + ")");
    }

    private Diff addAdGroup(AddAdGroup c, EntityState campaign, Limits limits) {
        requireKind(campaign, EntityKind.CAMPAIGN);
        if (c.name() == null || c.name().isBlank() || c.name().length() > 255) {
            throw refuse(Rule.CAMPAIGN_INVALID, "An ad group needs a name of at most 255 characters.");
        }
        if (c.keywords().isEmpty() || c.keywords().size() > 50) {
            throw refuse(Rule.INVALID_STATE, "An ad group needs between 1 and 50 keywords.");
        }
        for (KeywordSpec k : c.keywords()) {
            String text = NegativeMatcher.normalize(k.text());
            if (text.isEmpty() || text.length() > 80 || text.split(" ").length > 10) {
                throw refuse(Rule.INVALID_STATE, "Keyword '" + abbreviate(k.text()) + "' is empty, longer than 80 characters or has more than 10 words.");
            }
            SuspiciousText.reason(k.text()).ifPresent(why -> {
                throw refuse(Rule.SUSPICIOUS_TEXT, "Keyword '" + abbreviate(k.text()) + "' " + why + "; it cannot become a keyword automatically.");
            });
        }
        if (c.cpcBidMicros() != null && c.cpcBidMicros() <= 0) throw refuse(Rule.INVALID_STATE, "A default bid must be above zero.");
        validateAdAssets(c.headlines(), c.descriptions(), c.finalUrl(), limits);
        return new Diff(Map.of(), c.newValues(), "Add ad group '" + abbreviate(c.name()) + "' with " + c.keywords().size() + " keyword(s) and one ad to campaign "
                + campaign.ref().id() + " -> " + c.finalUrl());
    }

    private Diff changeBidding(ChangeBiddingStrategy c, EntityState campaign, Context ctx) {
        requireKind(campaign, EntityKind.CAMPAIGN);
        requireTargetCpaAffordable(c.strategy(), ctx.goals());
        String old = campaign.stringAttr("biddingStrategy");
        Long oldCpa = campaign.longAttr("targetCpaMicros");
        String oldDesc = old == null ? "unknown" : (oldCpa != null && old.equals("TARGET_CPA") ? "TARGET_CPA(" + oldCpa + " micros)" : old);
        if (c.strategy().describe().equals(oldDesc)) {
            throw refuse(Rule.NO_CHANGE, "The campaign already uses " + oldDesc + ".");
        }
        Map<String, Object> before = new java.util.LinkedHashMap<>();
        before.put("bidding", oldDesc);
        before.put("biddingType", old);
        if (oldCpa != null) before.put("targetCpaMicros", oldCpa);
        if (campaign.doubleAttr("targetRoas") != null) before.put("targetRoas", campaign.doubleAttr("targetRoas"));
        return new Diff(before, Map.of("bidding", c.strategy().describe()),
                "Change bidding of campaign " + campaign.ref().id() + " from " + oldDesc + " to " + c.strategy().describe());
    }

    /** A target cost per sale above lifetime value x margin means every sale loses money. */
    private void requireTargetCpaAffordable(BiddingStrategy strategy, Goals goals) {
        Long breakEven = goals.breakEvenCpaMicros();
        if (breakEven != null && strategy != null && strategy.type() == BiddingStrategy.Type.TARGET_CPA
                && strategy.targetCpaMicros() != null && strategy.targetCpaMicros() > breakEven) {
            throw refuse(Rule.TARGET_CPA_ABOVE_BREAK_EVEN, "A target CPA of " + Money.fmt(strategy.targetCpaMicros()) + " is above the break-even of "
                    + Money.fmt(breakEven) + " (lifetime value x gross margin), so each sale would lose money.");
        }
    }

    private void requireCampaignRoom(Limits l, long enabledCampaigns, String action) {
        if (l.maxActiveCampaigns() > 0 && enabledCampaigns >= l.maxActiveCampaigns()) {
            throw refuse(Rule.CAMPAIGN_LIMIT, "At most " + l.maxActiveCampaigns() + " campaigns may be enabled at once and " + enabledCampaigns
                    + " are; " + action + " would exceed it. A small budget spread over many campaigns starves each one: pause one first.");
        }
    }

    private Diff enableEntity(EnableEntity c, EntityState entity, Context ctx) {
        EntityKind kind = entity.ref().kind();
        if (kind != EntityKind.CAMPAIGN && kind != EntityKind.AD_GROUP && kind != EntityKind.AD && kind != EntityKind.KEYWORD) {
            throw refuse(Rule.INVALID_STATE, "enable_entity applies to campaigns, ad groups, ads and keywords.");
        }
        requireStatus(entity, "PAUSED");
        if (kind == EntityKind.CAMPAIGN) {
            requireCampaignRoom(ctx.limits(), ads.campaigns().stream().filter(CampaignInfo::enabled).count(), "enabling campaign " + entity.ref().id());
            String budgetId = entity.stringAttr("budgetId");
            long sum = sumDailyBudgets();
            if (budgetId != null) {
                boolean alreadyCounted = ads.campaigns().stream().anyMatch(ci -> ci.enabled() && budgetId.equals(ci.budgetId()));
                if (!alreadyCounted) {
                    sum += ads.budgets().stream().filter(b -> b.id().equals(budgetId)).mapToLong(BudgetInfo::amountMicros).sum();
                }
            }
            requireWithinCap(sum, ctx.limits());
        }
        return new Diff(Map.of("status", "PAUSED"), Map.of("status", "ENABLED"),
                "Enable " + entity.ref() + ": this starts delivery");
    }

    private Diff applyRecommendation(ApplyRecommendation c, EntityState rec) {
        requireKind(rec, EntityKind.RECOMMENDATION);
        String type = rec.stringAttr("type");
        if (type == null || BLOCKED_RECOMMENDATIONS.contains(type)) {
            throw refuse(Rule.RECOMMENDATION_TYPE, "Recommendation type " + type + " moves spend, bidding or reach; use the dedicated tool with its limits instead.");
        }
        String summary = rec.stringAttr("changeSummary");
        if (summary == null || summary.isBlank()) {
            throw refuse(Rule.INVALID_STATE, "The recommendation's real change could not be read, so it cannot be shown for approval.");
        }
        return new Diff(Map.of(), Map.of("recommendation", rec.ref().id(), "type", type, "change", summary),
                "Apply recommendation " + type + ": " + summary);
    }

    // ---- stop mechanism: emergency pause, rollback inverses ------------------------------------------

    private Diff emergencyPause(EmergencyPauseCampaign c, EntityState campaign, Context ctx) {
        requireKind(campaign, EntityKind.CAMPAIGN);
        requireStatus(campaign, "ENABLED");
        if (!ctx.spendCapBreached() && !ctx.control().breakerOpen()) {
            throw refuse(Rule.EMERGENCY_ONLY, "emergency_pause_campaign is only allowed while a spend cap is breached; none is.");
        }
        return new Diff(Map.of("status", "ENABLED"), Map.of("status", "PAUSED"),
                "Emergency pause of campaign " + campaign.ref().id() + " while a spend cap is breached");
    }

    private Diff removeNegatives(RemoveNegativeKeywords c, EntityState scope) {
        if (c.criterionIds().isEmpty()) throw refuse(Rule.INVALID_STATE, "No negative keyword criteria to remove.");
        return new Diff(Map.of("criteria", c.criterionIds()), Map.of(),
                "Remove " + c.criterionIds().size() + " negative keyword(s) previously added by this system from " + scope.ref());
    }

    private Diff pauseEntity(PauseEntity c, EntityState entity) {
        requireStatus(entity, "ENABLED");
        return new Diff(Map.of("status", "ENABLED"), Map.of("status", "PAUSED"), "Pause " + entity.ref());
    }

    // ---- small helpers --------------------------------------------------------------------------------

    private static void requireKind(EntityState s, EntityKind kind) {
        if (s.ref().kind() != kind) throw refuse(Rule.INVALID_STATE, s.ref() + " is not a " + kind.name().toLowerCase(Locale.ROOT) + ".");
    }

    private static void requireStatus(EntityState s, String status) {
        if (!status.equals(s.status())) {
            throw refuse(Rule.INVALID_STATE, s.ref() + " is " + s.status() + ", not " + status + ".");
        }
    }

    private static long nz(Long v) {
        return v == null ? 0 : v;
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\p{Cntrl}", " ");
        return t.length() <= 40 ? t : t.substring(0, 37) + "...";
    }
}

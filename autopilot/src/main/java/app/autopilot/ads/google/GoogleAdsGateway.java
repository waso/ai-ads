package app.autopilot.ads.google;

import app.autopilot.ads.AdsException;
import app.autopilot.ads.AdsGateway;
import app.autopilot.domain.ChangeRequest;
import app.autopilot.domain.ChangeRequest.AddAdGroup;
import app.autopilot.domain.ChangeRequest.AddKeywords;
import app.autopilot.domain.ChangeRequest.AddNegativeKeywords;
import app.autopilot.domain.ChangeRequest.AdjustKeywordBid;
import app.autopilot.domain.ChangeRequest.ApplyRecommendation;
import app.autopilot.domain.ChangeRequest.ChangeBiddingStrategy;
import app.autopilot.domain.ChangeRequest.CreateAd;
import app.autopilot.domain.ChangeRequest.CreateCampaign;
import app.autopilot.domain.ChangeRequest.DecreaseBudget;
import app.autopilot.domain.ChangeRequest.EmergencyPauseCampaign;
import app.autopilot.domain.ChangeRequest.EnableEntity;
import app.autopilot.domain.ChangeRequest.IncreaseBudget;
import app.autopilot.domain.ChangeRequest.PauseAd;
import app.autopilot.domain.ChangeRequest.PauseEntity;
import app.autopilot.domain.ChangeRequest.PauseKeyword;
import app.autopilot.domain.ChangeRequest.RemoveNegativeKeywords;
import app.autopilot.domain.EntityKind;
import app.autopilot.domain.EntityRef;
import app.autopilot.domain.EntityState;

import com.google.ads.googleads.lib.GoogleAdsClient;
import com.google.ads.googleads.v26.services.GoogleAdsRow;
import com.google.ads.googleads.v26.services.MutateGoogleAdsRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * {@link AdsGateway} on the Google Ads API (v26), bound to one customer id.
 *
 * <p>Reads are GAQL over SearchStream (one operation per query, see {@link Gaql}); writes are built by
 * {@link GoogleMutations} and sent with GoogleAdsService.mutate, partial failure always off. Entities with
 * status REMOVED are reported as not found.
 *
 * <p>Calls per method (each is one SearchStream operation): campaigns 2 or 3, budgets 2, policyIssues 2,
 * changeHistory 1, entityState 2 to 4 depending on the kind, negatives 1 to 3, every other read 1.
 * The label-name lookup is skipped when no row carries a label.
 */
public class GoogleAdsGateway implements AdsGateway, AutoCloseable {

    /** Google keeps 30 days of change events; ask for 29 so the oldest date can never trip its range check. */
    static final int HISTORY_DAYS = 29;

    private final GoogleAdsTransport transport;
    private final String customerId;
    private final Clock clock;
    private final ZoneId zone;
    private final GoogleMutations mutations;

    public GoogleAdsGateway(GoogleAdsClient client, String customerId, Clock clock, ZoneId accountZone) {
        this(client, customerId, clock, accountZone, GoogleMutations.DEFAULT_MANAGED_LABEL);
    }

    public GoogleAdsGateway(GoogleAdsClient client, String customerId, Clock clock, ZoneId accountZone, String managedLabel) {
        this(new SdkTransport(Objects.requireNonNull(client, "client")), customerId, clock, accountZone, managedLabel);
    }

    /** For tests: any transport. */
    GoogleAdsGateway(GoogleAdsTransport transport, String customerId, Clock clock, ZoneId accountZone, String managedLabel) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.customerId = normalizeCustomerId(customerId);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.zone = Objects.requireNonNull(accountZone, "accountZone");
        this.mutations = new GoogleMutations(this.customerId, managedLabel);
    }

    /** "123-456-7890" becomes "1234567890". */
    static String normalizeCustomerId(String raw) {
        String id = raw == null ? "" : raw.replace("-", "").strip();
        if (!id.matches("\\d{1,15}")) {
            throw new IllegalArgumentException("customer id must be digits (dashes allowed)");
        }
        return id;
    }

    @Override
    public void close() {
        transport.close();
    }

    // ---- plumbing -------------------------------------------------------------------------

    /** Runs a Google call and turns Google failures into {@link AdsException}. Bad arguments stay as they are. */
    private <T> T call(Supplier<T> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw AdsErrors.translate(e);
        }
    }

    private List<GoogleAdsRow> query(String gaql) {
        List<GoogleAdsRow> rows = new ArrayList<>();
        transport.searchStream(customerId, gaql, (row, paths) -> {
            rows.add(row);
            return true;
        });
        return rows;
    }

    /** Label names for the labels on these rows; no query when no row has a label. */
    private Map<String, String> labelNamesFor(List<GoogleAdsRow> rows) {
        if (RowMapping.labelResourceNames(rows).isEmpty()) {
            return Map.of();
        }
        return RowMapping.labelNames(query(Gaql.LABELS));
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }

    // ---- account level reads --------------------------------------------------------------

    @Override
    public List<CampaignInfo> campaigns() {
        return call(() -> {
            LocalDate today = today();
            List<GoogleAdsRow> campaigns = query(Gaql.CAMPAIGNS);
            List<GoogleAdsRow> spend = campaigns.isEmpty() ? List.of() : query(Gaql.campaignSpend(today.withDayOfMonth(1), today));
            return RowMapping.campaigns(campaigns, spend, labelNamesFor(campaigns), today);
        });
    }

    @Override
    public List<BudgetInfo> budgets() {
        return call(() -> RowMapping.budgets(query(Gaql.BUDGETS), query(Gaql.BUDGET_USERS)));
    }

    @Override
    public List<ConversionActionInfo> conversionActions() {
        return call(() -> RowMapping.conversionActions(query(Gaql.CONVERSION_ACTIONS), query(Gaql.conversionResults("LAST_7_DAYS")),
                query(Gaql.conversionResults("LAST_30_DAYS"))));
    }

    @Override
    public List<PolicyIssue> policyIssues() {
        return call(() -> RowMapping.policyIssues(query(Gaql.POLICY_ADS), query(Gaql.POLICY_KEYWORDS)));
    }

    /** Newest first. Looks back at most {@value #HISTORY_DAYS} days and returns at most 10,000 events. */
    @Override
    public List<ChangeEvent> changeHistory(Instant since) {
        return call(() -> {
            Instant now = clock.instant();
            Instant earliest = now.minus(HISTORY_DAYS, ChronoUnit.DAYS);
            Instant from = since == null || since.isBefore(earliest) ? earliest : since;
            if (from.isAfter(now)) {
                from = now;
            }
            LocalDateTime fromLocal = LocalDateTime.ofInstant(from, zone).truncatedTo(ChronoUnit.SECONDS);
            LocalDateTime toLocal = LocalDateTime.ofInstant(now, zone).truncatedTo(ChronoUnit.SECONDS);
            return RowMapping.changeEvents(query(Gaql.changeEvents(fromLocal, toLocal)), zone);
        });
    }

    // ---- optimization reads ---------------------------------------------------------------

    @Override
    public List<PerformanceRow> performance(Level level, LocalDate from, LocalDate to, int limit) {
        return call(() -> query(Gaql.performance(level, from, to, limit)).stream()
                .map(row -> RowMapping.performance(level, row)).toList());
    }

    @Override
    public List<SearchTermRow> searchTerms(LocalDate from, LocalDate to, int limit) {
        return call(() -> query(Gaql.searchTerms(from, to, limit)).stream().map(RowMapping::searchTerm).toList());
    }

    @Override
    public List<KeywordRow> keywords(KeywordQuery query) {
        return call(() -> query(Gaql.keywords(query)).stream().map(RowMapping::keyword).toList());
    }

    @Override
    public List<AdRow> ads(String campaignId, int limit) {
        return call(() -> query(Gaql.ads(campaignId, limit)).stream().map(RowMapping::ad).toList());
    }

    @Override
    public List<RecommendationRow> recommendations(int limit) {
        return call(() -> query(Gaql.recommendations(limit)).stream()
                .map(RowMapping::recommendation).filter(Objects::nonNull).toList());
    }

    /**
     * Negatives attached to the scope itself; for an ad group also those of its campaign. Negative keyword
     * lists shared through shared sets are not included.
     */
    @Override
    public List<NegativeRow> negatives(EntityRef scope) {
        return call(() -> {
            List<NegativeRow> out = new ArrayList<>(ownNegatives(scope));
            if (scope.kind() == EntityKind.AD_GROUP && ResourceNames.isNumeric(scope.id())) {
                List<GoogleAdsRow> owner = query(Gaql.adGroupCampaign(scope.id()));
                if (!owner.isEmpty()) {
                    String campaignId = String.valueOf(owner.get(0).getCampaign().getId());
                    out.addAll(ownNegatives(EntityRef.campaign(campaignId)));
                }
            }
            return out;
        });
    }

    /** Negatives attached directly to the scope. */
    private List<NegativeRow> ownNegatives(EntityRef scope) {
        return switch (scope.kind()) {
            case CAMPAIGN -> query(Gaql.campaignNegatives(scope.id())).stream().map(RowMapping::campaignNegative).toList();
            case AD_GROUP -> query(Gaql.adGroupNegatives(scope.id())).stream().map(RowMapping::adGroupNegative).toList();
            default -> throw new IllegalArgumentException("negatives belong to a campaign or an ad group, not " + scope.kind());
        };
    }

    @Override
    public ReportResult runReport(String gaql, int rowCap) {
        return call(() -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            boolean[] truncated = {false};
            transport.searchStream(customerId, gaql, (row, paths) -> {
                if (rows.size() >= rowCap) {
                    truncated[0] = true; // one more row exists beyond the cap
                    return false;
                }
                rows.add(ReportRows.flatten(row, paths));
                return true;
            });
            return new ReportResult(rows, truncated[0]);
        });
    }

    // ---- state for the guard --------------------------------------------------------------

    @Override
    public Optional<EntityState> entityState(EntityRef ref, LocalDate metricsFrom, LocalDate metricsTo) {
        return call(() -> loadState(ref, metricsFrom, metricsTo, true));
    }

    /**
     * @param full also read what only the guard needs: metrics and the ad group keyword count (for keywords)
     */
    private Optional<EntityState> loadState(EntityRef ref, LocalDate from, LocalDate to, boolean full) {
        if (!wellFormed(ref)) {
            return Optional.empty(); // an id that cannot exist in this account is simply not found
        }
        String id = ref.id();
        switch (ref.kind()) {
            case CAMPAIGN: {
                List<GoogleAdsRow> rows = query(Gaql.campaign(id));
                return rows.isEmpty() ? Optional.empty() : RowMapping.campaignState(rows, labelNamesFor(rows));
            }
            case BUDGET: {
                List<GoogleAdsRow> budget = query(Gaql.budget(id));
                if (budget.isEmpty()) {
                    return Optional.empty();
                }
                List<GoogleAdsRow> users = query(Gaql.budgetUsers(customerId, id));
                return RowMapping.budgetState(id, budget, users, labelNamesFor(users));
            }
            case AD_GROUP: {
                List<GoogleAdsRow> rows = query(Gaql.adGroup(id));
                return rows.isEmpty() ? Optional.empty() : RowMapping.adGroupState(rows, labelNamesFor(rows));
            }
            case AD: {
                String[] parts = ResourceNames.split(id);
                List<GoogleAdsRow> rows = query(Gaql.adsOfAdGroup(parts[0]));
                return RowMapping.adState(parts[1], rows, labelNamesFor(rows));
            }
            case KEYWORD: {
                List<GoogleAdsRow> rows = query(Gaql.keyword(customerId, id));
                if (rows.isEmpty()) {
                    return Optional.empty();
                }
                Map<String, String> labels = labelNamesFor(rows);
                Optional<EntityState> base = RowMapping.keywordState(rows, 0, RowMapping.KeywordMetrics.ZERO, labels);
                if (base.isEmpty() || !full) {
                    return base;
                }
                String adGroupId = ResourceNames.split(id)[0];
                long active = query(Gaql.enabledKeywordIds(adGroupId)).size();
                RowMapping.KeywordMetrics metrics = from == null || to == null
                        ? RowMapping.KeywordMetrics.ZERO
                        : RowMapping.KeywordMetrics.sum(query(Gaql.keywordMetrics(customerId, id, from, to)));
                return RowMapping.keywordState(rows, active, metrics, labels);
            }
            case RECOMMENDATION: {
                List<GoogleAdsRow> rows = query(Gaql.recommendation(customerId, id));
                if (rows.isEmpty()) {
                    return Optional.empty();
                }
                GoogleAdsRow campaignRow = null;
                Map<String, String> labels = Map.of();
                if (rows.get(0).getRecommendation().hasCampaign()) {
                    String campaignId = ResourceNames.lastSegment(rows.get(0).getRecommendation().getCampaign());
                    List<GoogleAdsRow> campaign = query(Gaql.campaignLabels(campaignId));
                    if (!campaign.isEmpty()) {
                        campaignRow = campaign.get(0);
                        labels = labelNamesFor(campaign);
                    }
                }
                return RowMapping.recommendationState(rows, campaignRow, labels);
            }
            default:
                return Optional.empty();
        }
    }

    private static boolean wellFormed(EntityRef ref) {
        try {
            switch (ref.kind()) {
                case CAMPAIGN, AD_GROUP, BUDGET -> ResourceNames.numeric(ref.id());
                case AD, KEYWORD -> ResourceNames.composite(ref.id());
                case RECOMMENDATION -> {
                    if (!ref.id().matches("[A-Za-z0-9_~.=-]{1,256}")) {
                        return false;
                    }
                }
            }
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ---- writes ---------------------------------------------------------------------------

    /**
     * Sends one change. With {@code validateOnly} Google checks the request and changes nothing (the result
     * then has no created ids). Planning problems (a wrong entity kind, 2 headlines instead of 3) come back
     * as an {@link AdsException} with code INVALID_REQUEST before anything is sent.
     *
     * <p>{@code apply_recommendation} has no validate_only in Google's API: a validate-only call only checks
     * that the recommendation still exists and is not dismissed.
     */
    @Override
    public MutationResult mutate(ChangeRequest request, boolean validateOnly) {
        GoogleMutations.Plan plan;
        try {
            String label = mutations.needsManagedLabelLookup(request) ? call(this::findManagedLabel) : null;
            plan = mutations.plan(request, label);
        } catch (IllegalArgumentException e) {
            throw new AdsException("INVALID_REQUEST", e.getMessage());
        }
        try {
            if (plan.isRecommendation()) {
                return applyRecommendation(plan, validateOnly);
            }
            MutateGoogleAdsRequest google = GoogleMutations.buildRequest(customerId, plan.operations(), validateOnly);
            GoogleAdsTransport.MutateOutcome outcome = transport.mutate(google);
            return new MutationResult(
                    outcome.requestId(),
                    validateOnly ? List.of() : GoogleMutations.resourceNames(outcome.response()),
                    validateOnly ? Map.of() : mutations.createdIds(plan, outcome.response()));
        } catch (RuntimeException e) {
            throw AdsErrors.translate(e);
        }
    }

    private MutationResult applyRecommendation(GoogleMutations.Plan plan, boolean validateOnly) {
        String resourceName = plan.recommendation().getResourceName();
        if (validateOnly) {
            EntityRef ref = new EntityRef(EntityKind.RECOMMENDATION, ResourceNames.lastSegment(resourceName));
            Optional<EntityState> state = loadState(ref, null, null, false);
            if (state.isEmpty() || !"ACTIVE".equals(state.get().status())) {
                throw new AdsException("RECOMMENDATION_NOT_FOUND", "recommendation " + ref.id() + " is gone or dismissed");
            }
            return new MutationResult("", List.of(), Map.of());
        }
        var outcome = transport.applyRecommendation(GoogleMutations.buildApplyRecommendationRequest(customerId, plan.recommendation()));
        List<String> names = outcome.response().getResultsList().stream()
                .map(r -> r.getResourceName()).filter(n -> !n.isEmpty()).toList();
        return new MutationResult(outcome.requestId(), names, Map.of());
    }

    private String findManagedLabel() {
        List<GoogleAdsRow> rows = query(Gaql.labelByName(mutations.managedLabel()));
        return rows.isEmpty() ? null : rows.get(0).getLabel().getResourceName();
    }

    // ---- read back ------------------------------------------------------------------------

    /** Reads the changed entity back and compares it with what {@code request.newValues()} promises. */
    @Override
    public ReadBack readBack(ChangeRequest request, MutationResult result) {
        return call(() -> switch (request) {
            case PauseKeyword r -> statusIs(r.keyword(), "PAUSED");
            case EmergencyPauseCampaign r -> statusIs(r.campaign(), "PAUSED");
            case PauseEntity r -> statusIs(r.entity(), "PAUSED");
            case PauseAd r -> statusIs(r.ad(), "PAUSED");
            case EnableEntity r -> statusIs(r.entity(), "ENABLED");
            case AdjustKeywordBid r -> attrIs(r.keyword(), "cpcBidMicros", r.newCpcBidMicros());
            case DecreaseBudget r -> attrIs(r.budget(), "amountMicros", r.newAmountMicros());
            case IncreaseBudget r -> attrIs(r.budget(), "amountMicros", r.newAmountMicros());
            case AddNegativeKeywords r -> readBackNegatives(r);
            case RemoveNegativeKeywords r -> readBackRemovedNegatives(r);
            case AddKeywords r -> readBackKeywords(r);
            case CreateAd r -> readBackAd(r, result);
            case CreateCampaign r -> readBackCampaign(r, result);
            case AddAdGroup r -> readBackAdGroup(r, result);
            case ChangeBiddingStrategy r -> readBackBidding(r);
            case ApplyRecommendation r -> readBackRecommendation(r);
        });
    }

    private ReadBack statusIs(EntityRef ref, String expected) {
        Optional<EntityState> state = loadState(ref, null, null, false);
        if (state.isEmpty()) {
            return new ReadBack(false, ref + " not found");
        }
        String actual = state.get().status();
        return new ReadBack(expected.equals(actual), "status=" + actual);
    }

    private ReadBack attrIs(EntityRef ref, String attr, long expected) {
        Optional<EntityState> state = loadState(ref, null, null, false);
        if (state.isEmpty()) {
            return new ReadBack(false, ref + " not found");
        }
        Long actual = state.get().longAttr(attr);
        return new ReadBack(actual != null && actual == expected, attr + "=" + actual);
    }

    private ReadBack readBackNegatives(AddNegativeKeywords r) {
        List<NegativeRow> present = ownNegatives(r.target());
        List<String> missing = new ArrayList<>();
        for (String term : r.terms()) {
            boolean found = present.stream().anyMatch(n -> sameText(n.text(), term) && n.matchType() == r.matchType());
            if (!found) {
                missing.add(term);
            }
        }
        return new ReadBack(missing.isEmpty(), missing.isEmpty()
                ? "all " + r.terms().size() + " negatives present on " + r.target()
                : "missing on " + r.target() + ": " + missing);
    }

    private ReadBack readBackRemovedNegatives(RemoveNegativeKeywords r) {
        Set<String> removed = new java.util.LinkedHashSet<>();
        for (String id : r.criterionIds()) {
            removed.add(id.substring(id.indexOf('~') + 1));
        }
        List<String> stillThere = ownNegatives(r.target()).stream()
                .map(NegativeRow::criterionId).filter(removed::contains).toList();
        return new ReadBack(stillThere.isEmpty(), stillThere.isEmpty()
                ? "none of " + removed.size() + " criteria remain on " + r.target()
                : "still present on " + r.target() + ": " + stillThere);
    }

    private ReadBack readBackKeywords(AddKeywords r) {
        List<GoogleAdsRow> rows = query(Gaql.adGroupKeywords(r.adGroup().id()));
        List<String> missing = new ArrayList<>();
        for (ChangeRequest.KeywordSpec spec : r.keywords()) {
            boolean found = rows.stream().anyMatch(row -> {
                var c = row.getAdGroupCriterion();
                boolean bidOk = spec.cpcBidMicros() == null
                        || (c.hasCpcBidMicros() && c.getCpcBidMicros() == spec.cpcBidMicros());
                return sameText(c.getKeyword().getText(), spec.text())
                        && RowMapping.matchType(c.getKeyword().getMatchType()) == spec.matchType() && bidOk;
            });
            if (!found) {
                missing.add(spec.text() + " [" + spec.matchType() + "]");
            }
        }
        return new ReadBack(missing.isEmpty(), missing.isEmpty()
                ? "all " + r.keywords().size() + " keywords present in ad group " + r.adGroup().id()
                : "missing in ad group " + r.adGroup().id() + ": " + missing);
    }

    private ReadBack readBackAd(CreateAd r, MutationResult result) {
        String created = result.createdIds().get("ad");
        if (created == null) {
            return new ReadBack(false, "the result carries no ad id");
        }
        String[] parts = ResourceNames.split(created);
        List<GoogleAdsRow> rows = query(Gaql.adContent(parts[0], parts[1]));
        if (rows.isEmpty()) {
            return new ReadBack(false, "ad " + created + " not found");
        }
        AdRow ad = RowMapping.ad(rows.get(0));
        boolean ok = ad.headlines().equals(r.headlines()) && ad.descriptions().equals(r.descriptions())
                && r.finalUrl().equals(ad.finalUrl());
        return new ReadBack(ok, "ad " + created + " status=" + ad.status() + ", headlines=" + ad.headlines().size()
                + ", descriptions=" + ad.descriptions().size() + ", finalUrl=" + ad.finalUrl());
    }

    private ReadBack readBackCampaign(CreateCampaign r, MutationResult result) {
        var spec = r.spec();
        String campaignId = result.createdIds().get("campaign");
        String budgetId = result.createdIds().get("budget");
        if (campaignId == null || budgetId == null) {
            return new ReadBack(false, "the result carries no campaign or budget id");
        }
        Optional<EntityState> campaign = loadState(EntityRef.campaign(campaignId), null, null, false);
        if (campaign.isEmpty()) {
            return new ReadBack(false, "campaign " + campaignId + " not found");
        }
        EntityState c = campaign.get();
        Optional<EntityState> budget = loadState(EntityRef.budget(budgetId), null, null, false);
        List<String> problems = new ArrayList<>();
        Map<String, String> seen = new LinkedHashMap<>();
        check(problems, seen, "status", c.status(), "PAUSED");
        check(problems, seen, "name", c.stringAttr("name"), spec.name());
        check(problems, seen, "budgetId", c.stringAttr("budgetId"), budgetId);
        check(problems, seen, "biddingStrategy", c.stringAttr("biddingStrategy"), spec.bidding().type().name());
        if (spec.bidding().type() == app.autopilot.domain.BiddingStrategy.Type.TARGET_CPA) {
            check(problems, seen, "targetCpaMicros", String.valueOf(c.longAttr("targetCpaMicros")), String.valueOf(spec.bidding().targetCpaMicros()));
        }
        check(problems, seen, "targetContentNetwork", String.valueOf(c.attrs().get("targetContentNetwork")), "false");
        check(problems, seen, "targetSearchNetwork", String.valueOf(c.attrs().get("targetSearchNetwork")), "false");
        check(problems, seen, "positiveGeoTargetType", c.stringAttr("positiveGeoTargetType"), "PRESENCE");
        check(problems, seen, "euPoliticalAds", c.stringAttr("containsEuPoliticalAdvertising"), switch (spec.euPoliticalAds()) {
            case NO_POLITICAL_ADS -> "DOES_NOT_CONTAIN_EU_POLITICAL_ADVERTISING";
            case HAS_POLITICAL_ADS -> "CONTAINS_EU_POLITICAL_ADVERTISING";
        });
        check(problems, seen, "managedLabel", String.valueOf(c.campaignLabels().contains(mutations.managedLabel())), "true");
        if (budget.isEmpty()) {
            problems.add("budget " + budgetId + " not found");
        } else {
            check(problems, seen, "budgetAmountMicros", String.valueOf(budget.get().longAttr("amountMicros")), String.valueOf(spec.dailyBudgetMicros()));
            check(problems, seen, "budgetExplicitlyShared", String.valueOf(budget.get().attrs().get("explicitlyShared")), "false");
        }
        String observed = "campaign " + campaignId + " " + seen;
        return new ReadBack(problems.isEmpty(), problems.isEmpty() ? observed : observed + "; differs: " + problems);
    }

    /**
     * The new ad group must exist, be ENABLED, sit in the requested campaign and hold as many enabled keywords as
     * were asked for. Costs 1 to 2 queries for the ad group (2 when it or its campaign has labels) and 1 for the keywords.
     */
    private ReadBack readBackAdGroup(AddAdGroup r, MutationResult result) {
        String adGroupId = result.createdIds().get("adGroup");
        if (adGroupId == null) {
            return new ReadBack(false, "the result carries no ad group id");
        }
        Optional<EntityState> adGroup = loadState(EntityRef.adGroup(adGroupId), null, null, false);
        if (adGroup.isEmpty()) {
            return new ReadBack(false, "ad group " + adGroupId + " not found");
        }
        EntityState g = adGroup.get();
        int enabledKeywords = query(Gaql.enabledKeywordIds(adGroupId)).size();
        List<String> problems = new ArrayList<>();
        Map<String, String> seen = new LinkedHashMap<>();
        check(problems, seen, "status", g.status(), "ENABLED");
        check(problems, seen, "campaignId", g.campaignId(), r.campaign().id());
        check(problems, seen, "enabledKeywords", String.valueOf(enabledKeywords), String.valueOf(r.keywords().size()));
        String observed = "ad group " + adGroupId + " " + seen;
        return new ReadBack(problems.isEmpty(), problems.isEmpty() ? observed : observed + "; differs: " + problems);
    }

    private ReadBack readBackBidding(ChangeBiddingStrategy r) {
        Optional<EntityState> state = loadState(r.campaign(), null, null, false);
        if (state.isEmpty()) {
            return new ReadBack(false, r.campaign() + " not found");
        }
        EntityState c = state.get();
        boolean ok = r.strategy().type().name().equals(c.stringAttr("biddingStrategy"));
        if (ok && r.strategy().type() == app.autopilot.domain.BiddingStrategy.Type.TARGET_CPA) {
            ok = Objects.equals(c.longAttr("targetCpaMicros"), r.strategy().targetCpaMicros());
        }
        if (ok && r.strategy().type() == app.autopilot.domain.BiddingStrategy.Type.TARGET_ROAS) {
            Double actual = c.doubleAttr("targetRoas");
            ok = actual != null && Math.abs(actual - r.strategy().targetRoas()) < 1e-9;
        }
        return new ReadBack(ok, "biddingStrategy=" + c.stringAttr("biddingStrategy")
                + (c.longAttr("targetCpaMicros") != null ? ", targetCpaMicros=" + c.longAttr("targetCpaMicros") : "")
                + (c.doubleAttr("targetRoas") != null ? ", targetRoas=" + c.doubleAttr("targetRoas") : ""));
    }

    /** An applied recommendation disappears from the pending list. */
    private ReadBack readBackRecommendation(ApplyRecommendation r) {
        Optional<EntityState> state = loadState(r.recommendation(), null, null, false);
        if (state.isEmpty()) {
            return new ReadBack(true, "recommendation " + r.recommendation().id() + " is no longer listed");
        }
        boolean applied = !"ACTIVE".equals(state.get().status());
        return new ReadBack(applied, "recommendation status=" + state.get().status());
    }

    private static void check(List<String> problems, Map<String, String> seen, String name, String actual, String expected) {
        seen.put(name, String.valueOf(actual));
        if (!Objects.equals(actual, expected)) {
            problems.add(name + " is " + actual + ", expected " + expected);
        }
    }

    /** Google lower-cases and trims keyword text, so compare the same way. */
    private static boolean sameText(String a, String b) {
        return normalize(a).equals(normalize(b));
    }

    private static String normalize(String s) {
        return s == null ? "" : s.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}

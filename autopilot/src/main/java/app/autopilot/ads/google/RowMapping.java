package app.autopilot.ads.google;

import app.autopilot.ads.AdsGateway.AdRow;
import app.autopilot.ads.AdsGateway.BudgetInfo;
import app.autopilot.ads.AdsGateway.CampaignInfo;
import app.autopilot.ads.AdsGateway.ChangeEvent;
import app.autopilot.ads.AdsGateway.ConversionActionInfo;
import app.autopilot.ads.AdsGateway.KeywordRow;
import app.autopilot.ads.AdsGateway.Level;
import app.autopilot.ads.AdsGateway.NegativeRow;
import app.autopilot.ads.AdsGateway.PerformanceRow;
import app.autopilot.ads.AdsGateway.PolicyIssue;
import app.autopilot.ads.AdsGateway.RecommendationRow;
import app.autopilot.ads.AdsGateway.SearchTermRow;
import app.autopilot.domain.EntityKind;
import app.autopilot.domain.EntityRef;
import app.autopilot.domain.EntityState;
import app.autopilot.domain.MatchType;

import com.google.ads.googleads.v26.common.AdTextAsset;
import com.google.ads.googleads.v26.enums.KeywordMatchTypeEnum.KeywordMatchType;
import com.google.ads.googleads.v26.resources.Campaign;
import com.google.ads.googleads.v26.services.GoogleAdsRow;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Maps {@link GoogleAdsRow}s to the records of {@link app.autopilot.ads.AdsGateway}. Pure functions: the
 * rows come in, plain values go out, so each query's mapping is tested by building rows by hand.
 */
final class RowMapping {

    private RowMapping() {}

    private static final DateTimeFormatter CHANGE_TIME = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
            .toFormatter();

    // ---- labels ---------------------------------------------------------------------------

    /** Label resource name to label name. */
    static Map<String, String> labelNames(List<GoogleAdsRow> labelRows) {
        Map<String, String> names = new HashMap<>();
        for (GoogleAdsRow row : labelRows) {
            names.put(row.getLabel().getResourceName(), row.getLabel().getName());
        }
        return names;
    }

    /**
     * Label names for a list of label resource names. A resource name with no known label stays as it is,
     * so nothing is silently dropped.
     */
    static Set<String> labels(Collection<String> resourceNames, Map<String, String> names) {
        Set<String> out = new LinkedHashSet<>();
        for (String resourceName : resourceNames) {
            out.add(names.getOrDefault(resourceName, resourceName));
        }
        return out;
    }

    // ---- bidding strategy -----------------------------------------------------------------

    /**
     * The name of the campaign's bidding strategy, mapped from the strategy oneof. For the five strategies in
     * {@code BiddingStrategy.Type} it is that type's name; a portfolio strategy is {@code PORTFOLIO};
     * anything else keeps Google's name (for example {@code TARGET_IMPRESSION_SHARE}). When the oneof is
     * not populated the campaign's {@code bidding_strategy_type} is used.
     */
    static String biddingStrategy(Campaign campaign) {
        return switch (campaign.getCampaignBiddingStrategyCase()) {
            case MANUAL_CPC -> "MANUAL_CPC";
            case MAXIMIZE_CONVERSIONS -> "MAXIMIZE_CONVERSIONS";
            case MAXIMIZE_CONVERSION_VALUE -> "MAXIMIZE_CONVERSION_VALUE";
            case TARGET_CPA -> "TARGET_CPA";
            case TARGET_ROAS -> "TARGET_ROAS";
            case BIDDING_STRATEGY -> "PORTFOLIO";
            case CAMPAIGNBIDDINGSTRATEGY_NOT_SET -> switch (campaign.getBiddingStrategyType()) {
                case ENHANCED_CPC -> "MANUAL_CPC";
                case UNSPECIFIED, UNKNOWN, UNRECOGNIZED -> "UNKNOWN";
                default -> campaign.getBiddingStrategyType().name();
            };
            default -> campaign.getCampaignBiddingStrategyCase().name();
        };
    }

    /** Adds {@code biddingStrategy}, {@code biddingStrategyType}, and target values when present. */
    static void putBidding(Map<String, Object> attrs, Campaign campaign) {
        attrs.put("biddingStrategy", biddingStrategy(campaign));
        attrs.put("biddingStrategyType", campaign.getBiddingStrategyType().name());
        long targetCpa = campaign.getTargetCpa().getTargetCpaMicros();
        if (targetCpa <= 0) {
            targetCpa = campaign.getMaximizeConversions().getTargetCpaMicros();
        }
        if (targetCpa > 0) {
            attrs.put("targetCpaMicros", targetCpa);
        }
        double targetRoas = campaign.getTargetRoas().getTargetRoas();
        if (targetRoas <= 0) {
            targetRoas = campaign.getMaximizeConversionValue().getTargetRoas();
        }
        if (targetRoas > 0) {
            attrs.put("targetRoas", targetRoas);
        }
    }

    // ---- account level reads --------------------------------------------------------------

    /**
     * @param spendRows daily rows from {@link Gaql#campaignSpend}; cost today is the row dated {@code today},
     *        cost for the month is the sum of all rows (the query is limited to the month)
     */
    static List<CampaignInfo> campaigns(List<GoogleAdsRow> campaignRows, List<GoogleAdsRow> spendRows,
                                        Map<String, String> labelNames, LocalDate today) {
        Map<String, long[]> spend = new HashMap<>(); // id -> {costToday, costMonth, impressionsToday}
        String todayText = today.toString();
        for (GoogleAdsRow row : spendRows) {
            long[] s = spend.computeIfAbsent(String.valueOf(row.getCampaign().getId()), k -> new long[3]);
            long cost = row.getMetrics().getCostMicros();
            s[1] += cost;
            if (todayText.equals(row.getSegments().getDate())) {
                s[0] += cost;
                s[2] += row.getMetrics().getImpressions();
            }
        }
        List<CampaignInfo> out = new ArrayList<>();
        for (GoogleAdsRow row : campaignRows) {
            Campaign c = row.getCampaign();
            String id = String.valueOf(c.getId());
            long[] s = spend.getOrDefault(id, new long[3]);
            out.add(new CampaignInfo(
                    id,
                    c.getName(),
                    c.getStatus().name(),
                    c.getPrimaryStatus().name(),
                    ResourceNames.lastSegment(c.getCampaignBudget()),
                    labels(c.getLabelsList(), labelNames),
                    s[0], s[1], s[2],
                    biddingStrategy(c)));
        }
        return out;
    }

    /** Budgets with the non-removed campaigns (any type) that use each. Budgets nobody uses have an empty list. */
    static List<BudgetInfo> budgets(List<GoogleAdsRow> budgetRows, List<GoogleAdsRow> campaignRows) {
        Map<String, List<String>> users = campaignsByBudget(campaignRows);
        List<BudgetInfo> out = new ArrayList<>();
        for (GoogleAdsRow row : budgetRows) {
            var b = row.getCampaignBudget();
            String id = String.valueOf(b.getId());
            out.add(new BudgetInfo(id, b.getName(), b.getAmountMicros(), b.getExplicitlyShared(),
                    users.getOrDefault(id, List.of())));
        }
        return out;
    }

    private static Map<String, List<String>> campaignsByBudget(List<GoogleAdsRow> campaignRows) {
        Map<String, List<String>> users = new LinkedHashMap<>();
        for (GoogleAdsRow row : campaignRows) {
            String budgetId = ResourceNames.lastSegment(row.getCampaign().getCampaignBudget());
            users.computeIfAbsent(budgetId, k -> new ArrayList<>()).add(String.valueOf(row.getCampaign().getId()));
        }
        return users;
    }

    static List<PolicyIssue> policyIssues(List<GoogleAdsRow> adRows, List<GoogleAdsRow> keywordRows) {
        List<PolicyIssue> out = new ArrayList<>();
        for (GoogleAdsRow row : adRows) {
            var ad = row.getAdGroupAd();
            String id = row.getAdGroup().getId() + "~" + ad.getAd().getId();
            List<String> topics = ad.getPolicySummary().getPolicyTopicEntriesList().stream()
                    .map(e -> e.getTopic()).toList();
            String name = ad.getAd().getName().isBlank() ? "Ad " + ad.getAd().getId() : ad.getAd().getName();
            out.add(new PolicyIssue(EntityRef.ad(id), name, ad.getPolicySummary().getApprovalStatus().name(), topics));
        }
        for (GoogleAdsRow row : keywordRows) {
            var c = row.getAdGroupCriterion();
            String id = row.getAdGroup().getId() + "~" + c.getCriterionId();
            out.add(new PolicyIssue(EntityRef.keyword(id), c.getKeyword().getText(),
                    c.getApprovalStatus().name(), List.copyOf(c.getDisapprovalReasonsList())));
        }
        return out;
    }

    /** Parses the change history; Google reports the time in the account time zone. */
    /** Joins each conversion action with its 7 and 30 day results; an action with no result row has none. */
    static List<ConversionActionInfo> conversionActions(List<GoogleAdsRow> actions, List<GoogleAdsRow> last7, List<GoogleAdsRow> last30) {
        Map<Long, double[]> seven = conversionResults(last7);
        Map<Long, double[]> thirty = conversionResults(last30);
        List<ConversionActionInfo> out = new ArrayList<>();
        for (GoogleAdsRow row : actions) {
            var a = row.getConversionAction();
            double[] r7 = seven.getOrDefault(a.getId(), new double[2]);
            double[] r30 = thirty.getOrDefault(a.getId(), new double[2]);
            out.add(new ConversionActionInfo(String.valueOf(a.getId()), a.getName(), a.getType().name(), a.getStatus().name(),
                    a.getCategory().name(), a.getPrimaryForGoal(), a.getCountingType().name(),
                    a.getValueSettings().getDefaultValue(), a.getValueSettings().getAlwaysUseDefaultValue(),
                    r7[0], r7[1], r30[0], r30[1]));
        }
        return out;
    }

    private static Map<Long, double[]> conversionResults(List<GoogleAdsRow> rows) {
        Map<Long, double[]> byAction = new java.util.HashMap<>();
        for (GoogleAdsRow row : rows) {
            double[] sum = byAction.computeIfAbsent(row.getConversionAction().getId(), k -> new double[2]);
            sum[0] += row.getMetrics().getConversions();
            sum[1] += row.getMetrics().getConversionsValue();
        }
        return byAction;
    }

    static List<ChangeEvent> changeEvents(List<GoogleAdsRow> rows, ZoneId accountZone) {
        List<ChangeEvent> out = new ArrayList<>();
        for (GoogleAdsRow row : rows) {
            var e = row.getChangeEvent();
            out.add(new ChangeEvent(
                    parseChangeTime(e.getChangeDateTime(), accountZone),
                    e.getUserEmail(),
                    e.getClientType().name(),
                    e.getChangeResourceType().name(),
                    e.getChangeResourceName(),
                    e.getResourceChangeOperation().name()));
        }
        return out;
    }

    static Instant parseChangeTime(String text, ZoneId zone) {
        try {
            return LocalDateTime.parse(text, CHANGE_TIME).atZone(zone).toInstant();
        } catch (DateTimeException e) {
            throw new IllegalStateException("unexpected change_date_time from Google: " + text, e);
        }
    }

    // ---- optimization reads ---------------------------------------------------------------

    static PerformanceRow performance(Level level, GoogleAdsRow row) {
        var m = row.getMetrics();
        return switch (level) {
            case CAMPAIGN -> new PerformanceRow(level, String.valueOf(row.getCampaign().getId()), row.getCampaign().getName(),
                    null, m.getImpressions(), m.getClicks(), m.getCostMicros(), m.getConversions(), m.getConversionsValue());
            case AD_GROUP -> new PerformanceRow(level, String.valueOf(row.getAdGroup().getId()), row.getAdGroup().getName(),
                    String.valueOf(row.getCampaign().getId()),
                    m.getImpressions(), m.getClicks(), m.getCostMicros(), m.getConversions(), m.getConversionsValue());
            case KEYWORD -> new PerformanceRow(level, keywordId(row), row.getAdGroupCriterion().getKeyword().getText(),
                    String.valueOf(row.getAdGroup().getId()),
                    m.getImpressions(), m.getClicks(), m.getCostMicros(), m.getConversions(), m.getConversionsValue());
            case AD -> new PerformanceRow(level, adId(row), row.getAdGroupAd().getAd().getName(),
                    String.valueOf(row.getAdGroup().getId()),
                    m.getImpressions(), m.getClicks(), m.getCostMicros(), m.getConversions(), m.getConversionsValue());
        };
    }

    static SearchTermRow searchTerm(GoogleAdsRow row) {
        var m = row.getMetrics();
        return new SearchTermRow(row.getSearchTermView().getSearchTerm(),
                String.valueOf(row.getCampaign().getId()), String.valueOf(row.getAdGroup().getId()),
                m.getImpressions(), m.getClicks(), m.getCostMicros(), m.getConversions());
    }

    static KeywordRow keyword(GoogleAdsRow row) {
        var c = row.getAdGroupCriterion();
        var m = row.getMetrics();
        Long bid = c.hasCpcBidMicros() ? c.getCpcBidMicros() : null;
        Integer qs = c.hasQualityInfo() && c.getQualityInfo().hasQualityScore() ? c.getQualityInfo().getQualityScore() : null;
        return new KeywordRow(keywordId(row), String.valueOf(row.getAdGroup().getId()), String.valueOf(row.getCampaign().getId()),
                c.getKeyword().getText(), matchType(c.getKeyword().getMatchType()), c.getStatus().name(), bid, qs,
                m.getImpressions(), m.getClicks(), m.getCostMicros(), m.getConversions());
    }

    static AdRow ad(GoogleAdsRow row) {
        var a = row.getAdGroupAd();
        var rsa = a.getAd().getResponsiveSearchAd();
        List<String> urls = a.getAd().getFinalUrlsList();
        return new AdRow(adId(row), String.valueOf(row.getAdGroup().getId()), String.valueOf(row.getCampaign().getId()),
                a.getStatus().name(), a.getAdStrength().name(), a.getPolicySummary().getApprovalStatus().name(),
                rsa.getHeadlinesList().stream().map(AdTextAsset::getText).toList(),
                rsa.getDescriptionsList().stream().map(AdTextAsset::getText).toList(),
                urls.isEmpty() ? null : urls.get(0));
    }

    /** Null for a dismissed recommendation. */
    static RecommendationRow recommendation(GoogleAdsRow row) {
        var r = row.getRecommendation();
        if (r.getDismissed()) {
            return null;
        }
        String type = r.getType().name();
        return new RecommendationRow(ResourceNames.lastSegment(r.getResourceName()), type,
                r.hasCampaign() ? ResourceNames.lastSegment(r.getCampaign()) : null,
                "Google recommendation: " + type.toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
    }

    static NegativeRow campaignNegative(GoogleAdsRow row) {
        var c = row.getCampaignCriterion();
        return new NegativeRow(String.valueOf(c.getCriterionId()), EntityRef.campaign(String.valueOf(row.getCampaign().getId())),
                c.getKeyword().getText(), matchType(c.getKeyword().getMatchType()));
    }

    static NegativeRow adGroupNegative(GoogleAdsRow row) {
        var c = row.getAdGroupCriterion();
        return new NegativeRow(String.valueOf(c.getCriterionId()), EntityRef.adGroup(String.valueOf(row.getAdGroup().getId())),
                c.getKeyword().getText(), matchType(c.getKeyword().getMatchType()));
    }

    static MatchType matchType(KeywordMatchType type) {
        return switch (type) {
            case EXACT -> MatchType.EXACT;
            case PHRASE -> MatchType.PHRASE;
            case BROAD -> MatchType.BROAD;
            default -> null;
        };
    }

    /** "{adGroupId}~{criterionId}", the keyword entity id. */
    static String keywordId(GoogleAdsRow row) {
        return row.getAdGroup().getId() + "~" + row.getAdGroupCriterion().getCriterionId();
    }

    /** "{adGroupId}~{adId}", the ad entity id. */
    static String adId(GoogleAdsRow row) {
        return row.getAdGroup().getId() + "~" + row.getAdGroupAd().getAd().getId();
    }

    // ---- entity state ---------------------------------------------------------------------

    /** Metrics of one keyword over the window, summed over the returned rows (none means zero). */
    record KeywordMetrics(long clicks, long costMicros, double conversions) {
        static final KeywordMetrics ZERO = new KeywordMetrics(0, 0, 0);

        static KeywordMetrics sum(List<GoogleAdsRow> rows) {
            long clicks = 0;
            long cost = 0;
            double conversions = 0;
            for (GoogleAdsRow row : rows) {
                clicks += row.getMetrics().getClicks();
                cost += row.getMetrics().getCostMicros();
                conversions += row.getMetrics().getConversions();
            }
            return new KeywordMetrics(clicks, cost, conversions);
        }
    }

    /**
     * BUDGET. {@code campaignLabels} is the intersection of the labels of all campaigns using the budget (the
     * scope label must be on every one), {@code chainLabels} the union (a no-agent label on any one blocks).
     * A budget used by exactly one campaign has that campaign as owner; otherwise {@code campaignId} is null.
     */
    static Optional<EntityState> budgetState(String budgetId, List<GoogleAdsRow> budgetRows, List<GoogleAdsRow> userRows,
                                             Map<String, String> labelNames) {
        if (budgetRows.isEmpty()) {
            return Optional.empty();
        }
        var b = budgetRows.get(0).getCampaignBudget();
        List<String> campaignIds = new ArrayList<>();
        Set<String> intersection = null;
        Set<String> union = new LinkedHashSet<>();
        for (GoogleAdsRow row : userRows) {
            campaignIds.add(String.valueOf(row.getCampaign().getId()));
            Set<String> l = labels(row.getCampaign().getLabelsList(), labelNames);
            union.addAll(l);
            if (intersection == null) {
                intersection = new LinkedHashSet<>(l);
            } else {
                intersection.retainAll(l);
            }
        }
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("name", b.getName());
        attrs.put("amountMicros", b.getAmountMicros());
        attrs.put("explicitlyShared", b.getExplicitlyShared());
        attrs.put("campaignIds", List.copyOf(campaignIds));
        attrs.put("status", b.getStatus().name());
        return Optional.of(new EntityState(EntityRef.budget(budgetId), b.getStatus().name(),
                campaignIds.size() == 1 ? campaignIds.get(0) : null, null,
                intersection == null ? Set.of() : intersection, union, attrs));
    }

    static Optional<EntityState> campaignState(List<GoogleAdsRow> rows, Map<String, String> labelNames) {
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Campaign c = rows.get(0).getCampaign();
        String id = String.valueOf(c.getId());
        Set<String> labels = labels(c.getLabelsList(), labelNames);
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("name", c.getName());
        attrs.put("status", c.getStatus().name());
        attrs.put("channelType", c.getAdvertisingChannelType().name());
        attrs.put("budgetId", ResourceNames.lastSegment(c.getCampaignBudget()));
        putBidding(attrs, c);
        var net = c.getNetworkSettings();
        attrs.put("targetGoogleSearch", net.getTargetGoogleSearch());
        attrs.put("targetSearchNetwork", net.getTargetSearchNetwork());
        attrs.put("targetContentNetwork", net.getTargetContentNetwork());
        attrs.put("targetPartnerSearchNetwork", net.getTargetPartnerSearchNetwork());
        attrs.put("positiveGeoTargetType", c.getGeoTargetTypeSetting().getPositiveGeoTargetType().name());
        attrs.put("containsEuPoliticalAdvertising", c.getContainsEuPoliticalAdvertising().name());
        return Optional.of(new EntityState(EntityRef.campaign(id), c.getStatus().name(), id, null, labels, labels, attrs));
    }

    static Optional<EntityState> adGroupState(List<GoogleAdsRow> rows, Map<String, String> labelNames) {
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        GoogleAdsRow row = rows.get(0);
        var g = row.getAdGroup();
        Set<String> campaignLabels = labels(row.getCampaign().getLabelsList(), labelNames);
        Set<String> chain = new LinkedHashSet<>(campaignLabels);
        chain.addAll(labels(g.getLabelsList(), labelNames));
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("name", g.getName());
        attrs.put("status", g.getStatus().name());
        return Optional.of(new EntityState(EntityRef.adGroup(String.valueOf(g.getId())), g.getStatus().name(),
                String.valueOf(row.getCampaign().getId()), String.valueOf(g.getId()), campaignLabels, chain, attrs));
    }

    /**
     * AD, from all non-removed ads of the ad group: the target ad is found by id, and the enabled count comes
     * from the same rows.
     */
    static Optional<EntityState> adState(String adId, List<GoogleAdsRow> adGroupAdRows, Map<String, String> labelNames) {
        long enabled = adGroupAdRows.stream()
                .filter(r -> r.getAdGroupAd().getStatus() == com.google.ads.googleads.v26.enums.AdGroupAdStatusEnum.AdGroupAdStatus.ENABLED)
                .count();
        for (GoogleAdsRow row : adGroupAdRows) {
            if (!adId.equals(String.valueOf(row.getAdGroupAd().getAd().getId()))) {
                continue;
            }
            String adGroupId = String.valueOf(row.getAdGroup().getId());
            Set<String> campaignLabels = labels(row.getCampaign().getLabelsList(), labelNames);
            Set<String> chain = new LinkedHashSet<>(campaignLabels);
            chain.addAll(labels(row.getAdGroup().getLabelsList(), labelNames));
            chain.addAll(labels(row.getAdGroupAd().getLabelsList(), labelNames));
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("status", row.getAdGroupAd().getStatus().name());
            attrs.put("adGroupEnabledAds", enabled);
            return Optional.of(new EntityState(EntityRef.ad(adGroupId + "~" + adId), row.getAdGroupAd().getStatus().name(),
                    String.valueOf(row.getCampaign().getId()), adGroupId, campaignLabels, chain, attrs));
        }
        return Optional.empty();
    }

    /**
     * KEYWORD. A criterion that is a negative or not a keyword is not a KEYWORD entity, so it is reported as
     * missing. {@code cpcBidMicros} is left out of the attrs when the keyword has no bid of its own.
     */
    static Optional<EntityState> keywordState(List<GoogleAdsRow> rows, long adGroupActiveKeywords, KeywordMetrics metrics,
                                              Map<String, String> labelNames) {
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        GoogleAdsRow row = rows.get(0);
        var c = row.getAdGroupCriterion();
        if (c.getNegative() || c.getType() != com.google.ads.googleads.v26.enums.CriterionTypeEnum.CriterionType.KEYWORD) {
            return Optional.empty();
        }
        Set<String> campaignLabels = labels(row.getCampaign().getLabelsList(), labelNames);
        Set<String> chain = new LinkedHashSet<>(campaignLabels);
        chain.addAll(labels(row.getAdGroup().getLabelsList(), labelNames));
        chain.addAll(labels(c.getLabelsList(), labelNames));
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("text", c.getKeyword().getText());
        attrs.put("matchType", c.getKeyword().getMatchType().name());
        if (c.hasCpcBidMicros()) {
            attrs.put("cpcBidMicros", c.getCpcBidMicros());
        }
        attrs.put("adGroupActiveKeywords", adGroupActiveKeywords);
        attrs.put("clicks", metrics.clicks());
        attrs.put("costMicros", metrics.costMicros());
        attrs.put("conversions", metrics.conversions());
        attrs.put("biddingStrategy", biddingStrategy(row.getCampaign()));
        attrs.put("status", c.getStatus().name());
        return Optional.of(new EntityState(EntityRef.keyword(keywordId(row)), c.getStatus().name(),
                String.valueOf(row.getCampaign().getId()), String.valueOf(row.getAdGroup().getId()),
                campaignLabels, chain, attrs));
    }

    /**
     * RECOMMENDATION. {@code campaignRow} is the owning campaign (null for an account-level recommendation,
     * which then has no labels and so is never in scope).
     */
    static Optional<EntityState> recommendationState(List<GoogleAdsRow> rows, GoogleAdsRow campaignRow, Map<String, String> labelNames) {
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        var r = rows.get(0).getRecommendation();
        String status = r.getDismissed() ? "DISMISSED" : "ACTIVE";
        Set<String> labels = campaignRow == null ? Set.of() : labels(campaignRow.getCampaign().getLabelsList(), labelNames);
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("type", r.getType().name());
        attrs.put("status", status);
        return Optional.of(new EntityState(new EntityRef(EntityKind.RECOMMENDATION, ResourceNames.lastSegment(r.getResourceName())), status,
                r.hasCampaign() ? ResourceNames.lastSegment(r.getCampaign()) : null, null, labels, labels, attrs));
    }

    // ---- helpers for the gateway ----------------------------------------------------------

    /** Every label resource name carried by the rows (campaign, ad group, ad, criterion). */
    static Set<String> labelResourceNames(List<GoogleAdsRow> rows) {
        Set<String> out = new LinkedHashSet<>();
        for (GoogleAdsRow row : rows) {
            out.addAll(row.getCampaign().getLabelsList());
            out.addAll(row.getAdGroup().getLabelsList());
            out.addAll(row.getAdGroupAd().getLabelsList());
            out.addAll(row.getAdGroupCriterion().getLabelsList());
        }
        return out;
    }
}

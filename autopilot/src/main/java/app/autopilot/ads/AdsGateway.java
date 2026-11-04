package app.autopilot.ads;

import app.autopilot.domain.ChangeRequest;
import app.autopilot.domain.EntityRef;
import app.autopilot.domain.EntityState;
import app.autopilot.domain.MatchType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The only door to Google Ads. It is bound to one allowlisted account: no method takes a customer id,
 * so an id from outside that account can only come back as "not found".
 *
 * <p>Reads use SearchStream where Google offers it. The server counts every call as Ads operations
 * (see {@link app.autopilot.ads.MeteredAdsGateway}).
 */
public interface AdsGateway {

    // ---- account level reads (watchdog, health) ----------------------------------------------

    /** Every non-removed Search campaign with status, budget link, labels and spend. */
    List<CampaignInfo> campaigns();

    /** Every budget in use, with the campaigns that use it. */
    List<BudgetInfo> budgets();

    /** Ads or keywords with a disapproved or limited policy status. */
    List<PolicyIssue> policyIssues();

    /** Account change history (Google keeps 30 days and may omit some changes). */
    List<ChangeEvent> changeHistory(Instant since);

    // ---- optimization reads -----------------------------------------------------------------

    List<PerformanceRow> performance(Level level, LocalDate from, LocalDate to, int limit);

    List<SearchTermRow> searchTerms(LocalDate from, LocalDate to, int limit);

    List<KeywordRow> keywords(KeywordQuery query);

    List<AdRow> ads(String campaignId, int limit);

    List<RecommendationRow> recommendations(int limit);

    /** Negative keywords currently in force for a campaign or ad group, plus those inherited from the campaign. */
    List<NegativeRow> negatives(EntityRef scope);

    /**
     * The account's conversion actions with their last 7 and 30 days of results. Read-only: the server never changes
     * conversion actions, but it must see whether the signal it optimizes toward is real.
     */
    List<ConversionActionInfo> conversionActions();

    /** A read-only GAQL query. The caller has already validated resource allowlist and row cap. */
    ReportResult runReport(String gaql, int rowCap);

    // ---- state for the guard -----------------------------------------------------------------

    /**
     * Live state of one entity, with metrics over the given window (used for pause thresholds).
     * Empty when the entity is not in the allowlisted account.
     */
    Optional<EntityState> entityState(EntityRef ref, LocalDate metricsFrom, LocalDate metricsTo);

    // ---- writes ------------------------------------------------------------------------------

    /**
     * Sends one change. With {@code validateOnly} Google checks the request and changes nothing.
     * Real calls run with partial failure off; campaign creation is one atomic request.
     */
    MutationResult mutate(ChangeRequest request, boolean validateOnly);

    /** Reads the changed entity back and says whether it now holds the intended values. */
    ReadBack readBack(ChangeRequest request, MutationResult result);

    // ---- read model --------------------------------------------------------------------------

    enum Level { CAMPAIGN, AD_GROUP, KEYWORD, AD }

    record CampaignInfo(
            String id,
            String name,
            String status,
            /* Google's primary status: ELIGIBLE, NOT_ELIGIBLE, LIMITED, PAUSED, ENDED, ... */
            String primaryStatus,
            String budgetId,
            Set<String> labels,
            long costTodayMicros,
            long costMonthMicros,
            long impressionsToday,
            String biddingStrategy) {
        public CampaignInfo {
            labels = labels == null ? Set.of() : Set.copyOf(labels);
        }
        public boolean enabled() { return "ENABLED".equals(status); }
    }

    record BudgetInfo(String id, String name, long amountMicros, boolean explicitlyShared, List<String> campaignIds) {
        public BudgetInfo {
            campaignIds = List.copyOf(campaignIds);
        }
    }

    record PolicyIssue(EntityRef entity, String name, String approvalStatus, List<String> topics) {
        public PolicyIssue {
            topics = List.copyOf(topics);
        }
    }

    record ChangeEvent(
            Instant at,
            String actorEmail,
            /* GOOGLE_ADS_WEB_CLIENT, GOOGLE_ADS_API, GOOGLE_ADS_SCRIPTS, GOOGLE_ADS_AUTOMATED_RULE, ... */
            String clientType,
            String resourceType,
            String resourceName,
            String operation) {}

    record PerformanceRow(
            Level level, String id, String name, String parentId,
            long impressions, long clicks, long costMicros, double conversions, double conversionValue) {}

    record SearchTermRow(
            String term, String campaignId, String adGroupId,
            long impressions, long clicks, long costMicros, double conversions) {}

    record KeywordQuery(String campaignId, boolean enabledOnly, LocalDate from, LocalDate to, int limit) {}

    record KeywordRow(
            String id, String adGroupId, String campaignId, String text, MatchType matchType, String status,
            Long cpcBidMicros, Integer qualityScore,
            long impressions, long clicks, long costMicros, double conversions) {}

    record AdRow(
            String id, String adGroupId, String campaignId, String status, String adStrength,
            String policyStatus, List<String> headlines, List<String> descriptions, String finalUrl) {
        public AdRow {
            headlines = List.copyOf(headlines);
            descriptions = List.copyOf(descriptions);
        }
    }

    record RecommendationRow(String id, String type, String campaignId, String description) {}

    record NegativeRow(String criterionId, EntityRef scope, String text, MatchType matchType) {}

    record ReportResult(List<java.util.Map<String, Object>> rows, boolean truncated) {}

    record MutationResult(String requestId, List<String> resourceNames, java.util.Map<String, String> createdIds) {
        public MutationResult {
            resourceNames = List.copyOf(resourceNames);
            createdIds = java.util.Map.copyOf(createdIds);
        }
    }

    record ReadBack(boolean matches, String observed) {}

    /**
     * @param category         Google's category, for example PURCHASE or SUBSCRIBE_PAID
     * @param primaryForGoal   whether bidding optimizes toward it
     * @param alwaysUseDefaultValue true when every conversion counts as the default value
     */
    record ConversionActionInfo(
            String id, String name, String type, String status, String category, boolean primaryForGoal,
            String countingType, double defaultValue, boolean alwaysUseDefaultValue,
            double conversions7d, double conversionValue7d, double conversions30d, double conversionValue30d) {}
}

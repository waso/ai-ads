package app.autopilot.ads;

import app.autopilot.domain.ChangeRequest;
import app.autopilot.domain.EntityRef;
import app.autopilot.domain.EntityState;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Used when {@code autopilot.ads.mode=none}: the server runs, every Google call says it is not connected. */
public class UnconfiguredAdsGateway implements AdsGateway {

    private static AdsException off() {
        return new AdsException("NOT_CONFIGURED", "Google Ads access is not configured (autopilot.ads.mode=none).");
    }

    @Override public List<CampaignInfo> campaigns() { throw off(); }
    @Override public List<BudgetInfo> budgets() { throw off(); }
    @Override public List<PolicyIssue> policyIssues() { throw off(); }
    @Override public List<ChangeEvent> changeHistory(Instant since) { throw off(); }
    @Override public List<PerformanceRow> performance(Level level, LocalDate from, LocalDate to, int limit) { throw off(); }
    @Override public List<SearchTermRow> searchTerms(LocalDate from, LocalDate to, int limit) { throw off(); }
    @Override public List<KeywordRow> keywords(KeywordQuery query) { throw off(); }
    @Override public List<AdRow> ads(String campaignId, int limit) { throw off(); }
    @Override public List<RecommendationRow> recommendations(int limit) { throw off(); }
    @Override public List<NegativeRow> negatives(EntityRef scope) { throw off(); }
    @Override public List<ConversionActionInfo> conversionActions() { throw off(); }
    @Override public ReportResult runReport(String gaql, int rowCap) { throw off(); }
    @Override public Optional<EntityState> entityState(EntityRef ref, LocalDate from, LocalDate to) { throw off(); }
    @Override public MutationResult mutate(ChangeRequest request, boolean validateOnly) { throw off(); }
    @Override public ReadBack readBack(ChangeRequest request, MutationResult result) { throw off(); }
}

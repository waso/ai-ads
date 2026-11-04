package app.autopilot.ads;

import app.autopilot.domain.ChangeRequest;
import app.autopilot.domain.ChangeRequest.*;
import app.autopilot.domain.EntityRef;
import app.autopilot.domain.EntityState;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** Counts every call as Ads operations, success or failure, then delegates. */
public class MeteredAdsGateway implements AdsGateway {

    private final AdsGateway delegate;
    private final QuotaMeter meter;

    public MeteredAdsGateway(AdsGateway delegate, QuotaMeter meter) {
        this.delegate = delegate;
        this.meter = meter;
    }

    private <T> T metered(long ops, Supplier<T> call) {
        try {
            return call.get();
        } finally {
            meter.count(ops);
        }
    }

    /** One SearchStream is one operation however many batches it returns. */
    @Override public List<CampaignInfo> campaigns() { return metered(1, delegate::campaigns); }
    @Override public List<BudgetInfo> budgets() { return metered(1, delegate::budgets); }
    @Override public List<PolicyIssue> policyIssues() { return metered(1, delegate::policyIssues); }
    @Override public List<ChangeEvent> changeHistory(Instant since) { return metered(1, () -> delegate.changeHistory(since)); }
    @Override public List<PerformanceRow> performance(Level level, LocalDate from, LocalDate to, int limit) {
        return metered(1, () -> delegate.performance(level, from, to, limit));
    }
    @Override public List<SearchTermRow> searchTerms(LocalDate from, LocalDate to, int limit) {
        return metered(1, () -> delegate.searchTerms(from, to, limit));
    }
    @Override public List<KeywordRow> keywords(KeywordQuery query) { return metered(1, () -> delegate.keywords(query)); }
    @Override public List<AdRow> ads(String campaignId, int limit) { return metered(1, () -> delegate.ads(campaignId, limit)); }
    @Override public List<RecommendationRow> recommendations(int limit) { return metered(1, () -> delegate.recommendations(limit)); }
    @Override public List<NegativeRow> negatives(EntityRef scope) { return metered(1, () -> delegate.negatives(scope)); }
    /** Three queries: the actions, then their results for 7 and for 30 days. */
    @Override public List<ConversionActionInfo> conversionActions() { return metered(3, delegate::conversionActions); }
    @Override public ReportResult runReport(String gaql, int rowCap) { return metered(1, () -> delegate.runReport(gaql, rowCap)); }
    @Override public Optional<EntityState> entityState(EntityRef ref, LocalDate from, LocalDate to) {
        return metered(2, () -> delegate.entityState(ref, from, to));
    }
    @Override public MutationResult mutate(ChangeRequest request, boolean validateOnly) {
        return metered(operationsFor(request), () -> delegate.mutate(request, validateOnly));
    }
    @Override public ReadBack readBack(ChangeRequest request, MutationResult result) {
        return metered(1, () -> delegate.readBack(request, result));
    }

    /** Planning estimate of Ads operations in one mutate request. */
    static long operationsFor(ChangeRequest r) {
        return switch (r) {
            case AddNegativeKeywords c -> Math.max(1, c.terms().size());
            case AddKeywords c -> Math.max(1, c.keywords().size());
            case RemoveNegativeKeywords c -> Math.max(1, c.criterionIds().size());
            case CreateCampaign c -> 6 + c.spec().keywords().size() + c.spec().geoTargetIds().size();
            case AddAdGroup c -> 2 + c.keywords().size();
            default -> 1;
        };
    }
}

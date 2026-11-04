package app.autopilot.ads.google;

import app.autopilot.ads.AdsException;
import app.autopilot.ads.AdsGateway.CampaignInfo;
import app.autopilot.ads.AdsGateway.ChangeEvent;
import app.autopilot.ads.AdsGateway.MutationResult;
import app.autopilot.ads.AdsGateway.ReadBack;
import app.autopilot.ads.AdsGateway.ReportResult;
import app.autopilot.domain.BiddingStrategy;
import app.autopilot.domain.ChangeRequest;
import app.autopilot.domain.ChangeRequest.AddAdGroup;
import app.autopilot.domain.ChangeRequest.AddNegativeKeywords;
import app.autopilot.domain.ChangeRequest.AdjustKeywordBid;
import app.autopilot.domain.ChangeRequest.ApplyRecommendation;
import app.autopilot.domain.ChangeRequest.CampaignSpec;
import app.autopilot.domain.ChangeRequest.ChangeBiddingStrategy;
import app.autopilot.domain.ChangeRequest.CreateAd;
import app.autopilot.domain.ChangeRequest.CreateCampaign;
import app.autopilot.domain.ChangeRequest.DecreaseBudget;
import app.autopilot.domain.ChangeRequest.EmergencyPauseCampaign;
import app.autopilot.domain.ChangeRequest.EuPoliticalAds;
import app.autopilot.domain.ChangeRequest.KeywordSpec;
import app.autopilot.domain.ChangeRequest.PauseKeyword;
import app.autopilot.domain.ChangeRequest.RemoveNegativeKeywords;
import app.autopilot.domain.EntityKind;
import app.autopilot.domain.EntityRef;
import app.autopilot.domain.EntityState;
import app.autopilot.domain.MatchType;

import com.google.ads.googleads.v26.common.AdTextAsset;
import com.google.ads.googleads.v26.common.KeywordInfo;
import com.google.ads.googleads.v26.common.Metrics;
import com.google.ads.googleads.v26.common.ResponsiveSearchAdInfo;
import com.google.ads.googleads.v26.common.TargetCpa;
import com.google.ads.googleads.v26.enums.AdGroupAdStatusEnum.AdGroupAdStatus;
import com.google.ads.googleads.v26.enums.AdGroupCriterionStatusEnum.AdGroupCriterionStatus;
import com.google.ads.googleads.v26.enums.AdGroupStatusEnum.AdGroupStatus;
import com.google.ads.googleads.v26.enums.BudgetStatusEnum.BudgetStatus;
import com.google.ads.googleads.v26.enums.CampaignStatusEnum.CampaignStatus;
import com.google.ads.googleads.v26.enums.CriterionTypeEnum.CriterionType;
import com.google.ads.googleads.v26.enums.KeywordMatchTypeEnum.KeywordMatchType;
import com.google.ads.googleads.v26.enums.RecommendationTypeEnum.RecommendationType;
import com.google.ads.googleads.v26.errors.ErrorCode;
import com.google.ads.googleads.v26.errors.GoogleAdsError;
import com.google.ads.googleads.v26.errors.GoogleAdsException;
import com.google.ads.googleads.v26.errors.GoogleAdsFailure;
import com.google.ads.googleads.v26.errors.MutateErrorEnum.MutateError;
import com.google.ads.googleads.v26.errors.QuotaErrorEnum.QuotaError;
import com.google.ads.googleads.v26.resources.Ad;
import com.google.ads.googleads.v26.resources.AdGroup;
import com.google.ads.googleads.v26.resources.AdGroupAd;
import com.google.ads.googleads.v26.resources.AdGroupCriterion;
import com.google.ads.googleads.v26.resources.Campaign;
import com.google.ads.googleads.v26.resources.CampaignBudget;
import com.google.ads.googleads.v26.resources.CampaignCriterion;
import com.google.ads.googleads.v26.resources.Label;
import com.google.ads.googleads.v26.resources.Recommendation;
import com.google.ads.googleads.v26.services.ApplyRecommendationRequest;
import com.google.ads.googleads.v26.services.ApplyRecommendationResponse;
import com.google.ads.googleads.v26.services.ApplyRecommendationResult;
import com.google.ads.googleads.v26.services.GoogleAdsRow;
import com.google.ads.googleads.v26.services.MutateAdGroupAdResult;
import com.google.ads.googleads.v26.services.MutateAdGroupCriterionResult;
import com.google.ads.googleads.v26.services.MutateAdGroupResult;
import com.google.ads.googleads.v26.services.MutateCampaignBudgetResult;
import com.google.ads.googleads.v26.services.MutateCampaignResult;
import com.google.ads.googleads.v26.services.MutateGoogleAdsRequest;
import com.google.ads.googleads.v26.services.MutateGoogleAdsResponse;
import com.google.ads.googleads.v26.services.MutateOperationResponse;
import com.google.api.gax.grpc.GrpcStatusCode;
import com.google.api.gax.rpc.ApiException;
import io.grpc.Metadata;
import io.grpc.Status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The gateway run against a fake transport: no network, no client library channel. */
class GoogleAdsGatewayTest {

    private static final String CUSTOMER = "1234567890";
    private static final String C = "customers/" + CUSTOMER + "/";
    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z"); // 11:00 in Warsaw
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /** Answers each GAQL query by a function of the query text; records everything sent. */
    static final class FakeTransport implements GoogleAdsTransport {
        final List<String> queries = new ArrayList<>();
        final List<MutateGoogleAdsRequest> mutates = new ArrayList<>();
        final List<ApplyRecommendationRequest> applies = new ArrayList<>();
        Function<String, List<GoogleAdsRow>> answers = q -> List.of();
        List<String> fieldPaths = List.of();
        MutateGoogleAdsResponse mutateResponse = MutateGoogleAdsResponse.getDefaultInstance();
        RuntimeException mutateFailure;
        String requestId = "req-42";
        boolean closed;

        @Override
        public void searchStream(String customerId, String query, RowHandler handler) {
            assertThat(customerId).isEqualTo(CUSTOMER);
            queries.add(query);
            for (GoogleAdsRow row : answers.apply(query)) {
                if (!handler.onRow(row, fieldPaths)) {
                    return;
                }
            }
        }

        @Override
        public MutateOutcome mutate(MutateGoogleAdsRequest request) {
            mutates.add(request);
            if (mutateFailure != null) {
                throw mutateFailure;
            }
            return new MutateOutcome(mutateResponse, requestId);
        }

        @Override
        public ApplyOutcome applyRecommendation(ApplyRecommendationRequest request) {
            applies.add(request);
            return new ApplyOutcome(ApplyRecommendationResponse.newBuilder()
                    .addResults(ApplyRecommendationResult.newBuilder().setResourceName(request.getOperations(0).getResourceName())).build(), requestId);
        }

        @Override
        public void close() {
            closed = true;
        }

        long count(String fragment) {
            return queries.stream().filter(q -> q.contains(fragment)).count();
        }
    }

    private final FakeTransport transport = new FakeTransport();
    private final GoogleAdsGateway gateway = new GoogleAdsGateway(transport, "123-456-7890", CLOCK, WARSAW, "agent-managed");

    private static GoogleAdsRow row(Object... parts) {
        GoogleAdsRow.Builder b = GoogleAdsRow.newBuilder();
        for (Object p : parts) {
            if (p instanceof Campaign.Builder x) b.setCampaign(x);
            else if (p instanceof AdGroup.Builder x) b.setAdGroup(x);
            else if (p instanceof AdGroupAd.Builder x) b.setAdGroupAd(x);
            else if (p instanceof AdGroupCriterion.Builder x) b.setAdGroupCriterion(x);
            else if (p instanceof CampaignBudget.Builder x) b.setCampaignBudget(x);
            else if (p instanceof CampaignCriterion.Builder x) b.setCampaignCriterion(x);
            else if (p instanceof Label.Builder x) b.setLabel(x);
            else if (p instanceof Metrics.Builder x) b.setMetrics(x);
            else if (p instanceof Recommendation.Builder x) b.setRecommendation(x);
            else if (p instanceof com.google.ads.googleads.v26.common.Segments.Builder x) b.setSegments(x);
            else throw new IllegalArgumentException(p.getClass().getName());
        }
        return b.build();
    }

    private static GoogleAdsRow label(String id, String name) {
        return row(Label.newBuilder().setResourceName(C + "labels/" + id).setName(name));
    }

    private static Campaign.Builder campaign(long id) {
        return Campaign.newBuilder().setId(id).setName("Campaign " + id).setStatus(CampaignStatus.ENABLED).setCampaignBudget(C + "campaignBudgets/" + (id + 1000));
    }

    // ---- setup ----------------------------------------------------------------------------

    @Test
    @DisplayName("[GATEWAY] the customer id may be written with dashes but must be digits; close closes the transport")
    void customerIdAndClose() {
        assertThat(GoogleAdsGateway.normalizeCustomerId("123-456-7890")).isEqualTo("1234567890");
        assertThatThrownBy(() -> GoogleAdsGateway.normalizeCustomerId("12a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GoogleAdsGateway.normalizeCustomerId(null)).isInstanceOf(IllegalArgumentException.class);
        gateway.close();
        assertThat(transport.closed).isTrue();
    }

    // ---- reads ----------------------------------------------------------------------------

    @Test
    @DisplayName("[GATEWAY] campaigns: spend is read for today and the month in the account time zone")
    void campaignsUseAccountZoneForTodayAndMonth() {
        transport.answers = q -> {
            if (q.contains("FROM campaign") && q.contains("metrics.cost_micros")) {
                return List.of(
                        row(Campaign.newBuilder().setId(1), com.google.ads.googleads.v26.common.Segments.newBuilder().setDate("2026-11-02"),
                                Metrics.newBuilder().setCostMicros(4_000_000).setImpressions(10)),
                        row(Campaign.newBuilder().setId(1), com.google.ads.googleads.v26.common.Segments.newBuilder().setDate("2026-11-03"),
                                Metrics.newBuilder().setCostMicros(1_000_000).setImpressions(5)));
            }
            if (q.contains("FROM campaign")) {
                return List.of(row(campaign(1).addLabels(C + "labels/10")));
            }
            if (q.contains("FROM label")) {
                return List.of(label("10", "agent-managed"));
            }
            return List.of();
        };

        List<CampaignInfo> out = gateway.campaigns();

        assertThat(out).hasSize(1);
        assertThat(out.get(0).costTodayMicros()).isEqualTo(1_000_000);
        assertThat(out.get(0).costMonthMicros()).isEqualTo(5_000_000);
        assertThat(out.get(0).labels()).containsExactly("agent-managed");
        assertThat(transport.queries).anyMatch(q -> q.contains("segments.date BETWEEN '2026-11-01' AND '2026-11-03'"));
        assertThat(transport.queries).hasSize(3);
    }

    @Test
    @DisplayName("[GATEWAY] campaigns: no label query when no campaign has a label, no spend query when there are no campaigns")
    void campaignsSkipUnneededQueries() {
        transport.answers = q -> q.contains("metrics.cost_micros") ? List.of() : List.of(row(campaign(1)));
        assertThat(gateway.campaigns()).hasSize(1);
        assertThat(transport.count("FROM label")).isZero();

        FakeTransport empty = new FakeTransport();
        assertThat(new GoogleAdsGateway(empty, CUSTOMER, CLOCK, WARSAW, "agent-managed").campaigns()).isEmpty();
        assertThat(empty.queries).hasSize(1);
    }

    @Test
    @DisplayName("[GATEWAY] change history asks for at most 29 days and uses the account time zone")
    void changeHistoryWindow() {
        transport.answers = q -> List.of(GoogleAdsRow.newBuilder().setChangeEvent(
                com.google.ads.googleads.v26.resources.ChangeEvent.newBuilder().setChangeDateTime("2026-11-03 10:30:00.000000")
                        .setUserEmail("a@b.c")).build());

        List<ChangeEvent> recent = gateway.changeHistory(Instant.parse("2026-11-03T08:00:00Z"));
        assertThat(recent).hasSize(1);
        assertThat(recent.get(0).at()).isEqualTo(Instant.parse("2026-11-03T09:30:00Z"));
        assertThat(transport.queries.get(0))
                .contains("change_event.change_date_time >= '2026-11-03 09:00:00'")   // 08:00Z = 09:00 Warsaw
                .contains("change_event.change_date_time <= '2026-11-03 11:00:00'")
                .contains("ORDER BY change_event.change_date_time DESC LIMIT 10000");

        gateway.changeHistory(Instant.parse("2020-01-01T00:00:00Z"));       // far too old
        // 29 days before 10:00Z on 3 Nov is 10:00Z on 5 Oct, which is 12:00 in Warsaw (still summer time then)
        assertThat(transport.queries.get(1)).contains("change_event.change_date_time >= '2026-10-05 12:00:00'");

        gateway.changeHistory(Instant.parse("2027-01-01T00:00:00Z"));       // in the future
        assertThat(transport.queries.get(2)).contains(">= '2026-11-03 11:00:00'");
        gateway.changeHistory(null);
        assertThat(transport.queries.get(3)).contains(">= '2026-10-05 12:00:00'");
    }

    @Test
    @DisplayName("[GATEWAY] run_report stops at the row cap and says it truncated")
    void runReportRowCap() {
        transport.fieldPaths = List.of("campaign.id", "campaign.name");
        transport.answers = q -> List.of(row(campaign(1)), row(campaign(2)), row(campaign(3)), row(campaign(4)), row(campaign(5)));

        ReportResult capped = gateway.runReport("SELECT campaign.id, campaign.name FROM campaign", 3);
        assertThat(capped.truncated()).isTrue();
        assertThat(capped.rows()).hasSize(3);
        assertThat(capped.rows().get(0)).containsEntry("campaign.id", 1L).containsEntry("campaign.name", "Campaign 1");
        assertThat(capped.rows().get(2)).containsEntry("campaign.id", 3L);

        ReportResult exact = gateway.runReport("SELECT campaign.id, campaign.name FROM campaign", 5);
        assertThat(exact.truncated()).isFalse();
        assertThat(exact.rows()).hasSize(5);

        ReportResult roomy = gateway.runReport("SELECT campaign.id, campaign.name FROM campaign", 50);
        assertThat(roomy.truncated()).isFalse();
        assertThat(transport.queries.get(0)).isEqualTo("SELECT campaign.id, campaign.name FROM campaign"); // sent as validated by the caller
    }

    @Test
    @DisplayName("[GATEWAY] negatives of an ad group include the campaign's; campaign scope reads one query")
    void negativesInherit() {
        transport.answers = q -> {
            if (q.contains("FROM campaign_criterion")) {
                return List.of(row(campaign(1), CampaignCriterion.newBuilder().setCriterionId(9001)
                        .setKeyword(KeywordInfo.newBuilder().setText("free").setMatchType(KeywordMatchType.PHRASE))));
            }
            if (q.contains("FROM ad_group_criterion")) {
                return List.of(row(AdGroup.newBuilder().setId(22), AdGroupCriterion.newBuilder().setCriterionId(7001)
                        .setKeyword(KeywordInfo.newBuilder().setText("cheap").setMatchType(KeywordMatchType.EXACT))));
            }
            if (q.contains("FROM ad_group")) {
                return List.of(row(AdGroup.newBuilder().setId(22), campaign(1)));
            }
            return List.of();
        };

        var adGroup = gateway.negatives(EntityRef.adGroup("22"));
        assertThat(adGroup).extracting(n -> n.criterionId() + ":" + n.scope()).containsExactly("7001:AD_GROUP:22", "9001:CAMPAIGN:1");

        transport.queries.clear();
        assertThat(gateway.negatives(EntityRef.campaign("1"))).hasSize(1);
        assertThat(transport.queries).hasSize(1);
        assertThatThrownBy(() -> gateway.negatives(EntityRef.keyword("1~2"))).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- entity state ---------------------------------------------------------------------

    private Function<String, List<GoogleAdsRow>> keywordWorld(boolean negative) {
        return q -> {
            if (q.contains("ad_group_criterion.resource_name = 'customers/1234567890/adGroupCriteria/22~33'") && q.contains("ad_group_criterion.labels")) {
                return List.of(row(
                        AdGroupCriterion.newBuilder().setCriterionId(33).setStatus(AdGroupCriterionStatus.ENABLED).setType(CriterionType.KEYWORD)
                                .setNegative(negative)
                                .setKeyword(KeywordInfo.newBuilder().setText("acme shoes").setMatchType(KeywordMatchType.EXACT))
                                .setCpcBidMicros(1_200_000),
                        AdGroup.newBuilder().setId(22).addLabels(C + "labels/11"),
                        campaign(1).addLabels(C + "labels/10").setTargetCpa(TargetCpa.newBuilder().setTargetCpaMicros(5_000_000))));
            }
            if (q.contains("ad_group_criterion.status = 'ENABLED'")) {
                return List.of(row(AdGroupCriterion.newBuilder().setCriterionId(33)), row(AdGroupCriterion.newBuilder().setCriterionId(34)),
                        row(AdGroupCriterion.newBuilder().setCriterionId(35)));
            }
            if (q.contains("metrics.clicks")) {
                return List.of(row(Metrics.newBuilder().setClicks(120).setCostMicros(60_000_000).setConversions(3)));
            }
            if (q.contains("FROM label")) {
                return List.of(label("10", "agent-managed"), label("11", "no-agent"));
            }
            return List.of();
        };
    }

    @Test
    @DisplayName("[GATEWAY] entityState KEYWORD fills labels, attrs, metrics over the window and the enabled keyword count")
    void keywordEntityState() {
        transport.answers = keywordWorld(false);

        EntityState s = gateway.entityState(EntityRef.keyword("22~33"), LocalDate.of(2026, 10, 4), LocalDate.of(2026, 11, 3)).orElseThrow();

        assertThat(s.campaignLabels()).containsExactly("agent-managed");
        assertThat(s.chainLabels()).containsExactlyInAnyOrder("agent-managed", "no-agent");
        assertThat(s.longAttr("clicks")).isEqualTo(120L);
        assertThat(s.longAttr("costMicros")).isEqualTo(60_000_000L);
        assertThat(s.doubleAttr("conversions")).isEqualTo(3.0);
        assertThat(s.longAttr("adGroupActiveKeywords")).isEqualTo(3L);
        assertThat(s.longAttr("cpcBidMicros")).isEqualTo(1_200_000L);
        assertThat(s.stringAttr("biddingStrategy")).isEqualTo("TARGET_CPA");
        assertThat(transport.queries).anyMatch(q -> q.contains("segments.date BETWEEN '2026-10-04' AND '2026-11-03'"));
    }

    @Test
    @DisplayName("[GATEWAY] entityState: unknown, malformed or negative ids are 'not found', and malformed ids send nothing")
    void entityStateNotFound() {
        transport.answers = q -> List.of();
        LocalDate d = LocalDate.of(2026, 11, 1);
        assertThat(gateway.entityState(EntityRef.campaign("999"), d, d)).isEmpty();
        assertThat(gateway.entityState(EntityRef.budget("999"), d, d)).isEmpty();
        assertThat(gateway.entityState(EntityRef.adGroup("999"), d, d)).isEmpty();
        assertThat(gateway.entityState(EntityRef.ad("22~99"), d, d)).isEmpty();
        assertThat(gateway.entityState(EntityRef.keyword("22~99"), d, d)).isEmpty();
        assertThat(gateway.entityState(new EntityRef(EntityKind.RECOMMENDATION, "nope"), d, d)).isEmpty();
        int sent = transport.queries.size();

        // an id that can not exist (not numeric / not composite) is answered without asking Google
        assertThat(gateway.entityState(EntityRef.campaign("12abc"), d, d)).isEmpty();
        assertThat(gateway.entityState(EntityRef.keyword("22"), d, d)).isEmpty();
        assertThat(gateway.entityState(EntityRef.ad("a~b"), d, d)).isEmpty();
        assertThat(transport.queries).hasSize(sent);

        transport.answers = keywordWorld(true);   // a negative keyword criterion is not a KEYWORD entity
        assertThat(gateway.entityState(EntityRef.keyword("22~33"), d, d)).isEmpty();
    }

    @Test
    @DisplayName("[GATEWAY] entityState BUDGET lists all campaigns using it and the shared flag")
    void budgetEntityState() {
        transport.answers = q -> {
            if (q.contains("FROM campaign_budget")) {
                return List.of(row(CampaignBudget.newBuilder().setId(2000).setAmountMicros(40_000_000).setExplicitlyShared(true).setStatus(BudgetStatus.ENABLED)));
            }
            if (q.contains("FROM campaign")) {
                return List.of(row(campaign(5).addLabels(C + "labels/10")), row(campaign(6).addLabels(C + "labels/10")));
            }
            if (q.contains("FROM label")) {
                return List.of(label("10", "agent-managed"));
            }
            return List.of();
        };

        EntityState s = gateway.entityState(EntityRef.budget("2000"), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3)).orElseThrow();

        assertThat(s.longAttr("amountMicros")).isEqualTo(40_000_000L);
        assertThat(s.attrs().get("explicitlyShared")).isEqualTo(true);
        assertThat(s.listAttr("campaignIds")).containsExactly("5", "6");
        assertThat(s.campaignLabels()).containsExactly("agent-managed");
        assertThat(transport.queries).anyMatch(q -> q.contains("campaign.campaign_budget = 'customers/1234567890/campaignBudgets/2000'"));
    }

    @Test
    @DisplayName("[GATEWAY] entityState RECOMMENDATION reads the owning campaign's labels")
    void recommendationEntityState() {
        transport.answers = q -> {
            if (q.contains("FROM recommendation")) {
                return List.of(row(Recommendation.newBuilder().setResourceName(C + "recommendations/abc").setType(RecommendationType.KEYWORD)
                        .setCampaign(C + "campaigns/7")));
            }
            if (q.contains("FROM campaign")) {
                return List.of(row(campaign(7).addLabels(C + "labels/10")));
            }
            if (q.contains("FROM label")) {
                return List.of(label("10", "agent-managed"));
            }
            return List.of();
        };
        EntityState s = gateway.entityState(new EntityRef(EntityKind.RECOMMENDATION, "abc"), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3)).orElseThrow();
        assertThat(s.status()).isEqualTo("ACTIVE");
        assertThat(s.campaignId()).isEqualTo("7");
        assertThat(s.campaignLabels()).containsExactly("agent-managed");
    }

    // ---- mutate ---------------------------------------------------------------------------

    @Test
    @DisplayName("[AC-36] validate_only and the real call differ only in the flag; partial failure is never on")
    void mutateFlagsAndRequestId() {
        PauseKeyword pause = new PauseKeyword(EntityRef.keyword("22~33"));
        transport.mutateResponse = MutateGoogleAdsResponse.newBuilder().addMutateOperationResponses(
                MutateOperationResponse.newBuilder().setAdGroupCriterionResult(
                        MutateAdGroupCriterionResult.newBuilder().setResourceName(C + "adGroupCriteria/22~33"))).build();

        MutationResult validated = gateway.mutate(pause, true);
        MutationResult real = gateway.mutate(pause, false);

        assertThat(transport.mutates).hasSize(2);
        assertThat(transport.mutates.get(0).getValidateOnly()).isTrue();
        assertThat(transport.mutates.get(1).getValidateOnly()).isFalse();
        assertThat(transport.mutates).allSatisfy(r -> {
            assertThat(r.getPartialFailure()).isFalse();
            assertThat(r.getCustomerId()).isEqualTo(CUSTOMER);
            assertThat(r.getMutateOperationsCount()).isEqualTo(1);
        });
        assertThat(validated.requestId()).isEqualTo("req-42");
        assertThat(validated.resourceNames()).isEmpty();
        assertThat(validated.createdIds()).isEmpty();
        assertThat(real.requestId()).isEqualTo("req-42");
        assertThat(real.resourceNames()).containsExactly(C + "adGroupCriteria/22~33");
    }

    @Test
    @DisplayName("[AC-36] a real create_campaign sends one atomic request and returns the new ids")
    void createCampaignEndToEnd() {
        transport.answers = q -> q.contains("FROM label") ? List.of(label("10", "agent-managed")) : List.of();
        transport.mutateResponse = MutateGoogleAdsResponse.newBuilder()
                .addMutateOperationResponses(MutateOperationResponse.newBuilder().setCampaignBudgetResult(
                        MutateCampaignBudgetResult.newBuilder().setResourceName(C + "campaignBudgets/501")))
                .addMutateOperationResponses(MutateOperationResponse.newBuilder().setCampaignResult(
                        MutateCampaignResult.newBuilder().setResourceName(C + "campaigns/502")))
                .build();
        CreateCampaign request = new CreateCampaign(new CampaignSpec("Brand", 25_000_000L, BiddingStrategy.maximizeConversions(),
                List.of(2616L), "Brand", List.of(new KeywordSpec("acme", MatchType.EXACT, null)),
                List.of("a", "b", "c"), List.of("d", "e"), "https://acme.example", EuPoliticalAds.NO_POLITICAL_ADS));

        MutationResult result = gateway.mutate(request, false);

        assertThat(transport.mutates).hasSize(1);                        // one atomic request
        MutateGoogleAdsRequest sent = transport.mutates.get(0);
        assertThat(sent.getValidateOnly()).isFalse();
        assertThat(sent.getPartialFailure()).isFalse();
        assertThat(sent.getMutateOperationsList()).noneMatch(op -> op.hasLabelOperation());      // label existed
        assertThat(sent.getMutateOperationsList().get(sent.getMutateOperationsCount() - 1).getCampaignLabelOperation().getCreate().getLabel())
                .isEqualTo(C + "labels/10");
        assertThat(result.createdIds()).containsEntry("campaign", "502").containsEntry("budget", "501");
        assertThat(transport.count("label.name = 'agent-managed'")).isEqualTo(1);
    }

    @Test
    @DisplayName("[AC-36] create_campaign creates the label in the same request when it does not exist")
    void createCampaignCreatesMissingLabel() {
        CreateCampaign request = new CreateCampaign(new CampaignSpec("Brand", 25_000_000L, BiddingStrategy.manualCpc(),
                List.of(2616L), "Brand", List.of(), List.of("a", "b", "c"), List.of("d", "e"), "https://acme.example", EuPoliticalAds.NO_POLITICAL_ADS));

        gateway.mutate(request, true);

        assertThat(transport.mutates).hasSize(1);
        assertThat(transport.mutates.get(0).getValidateOnly()).isTrue();
        assertThat(transport.mutates.get(0).getMutateOperationsList()).filteredOn(op -> op.hasLabelOperation()).hasSize(1);
    }

    private static AddAdGroup bootsAdGroup() {
        return new AddAdGroup(EntityRef.campaign("11"), "Winter boots",
                List.of(new KeywordSpec("winter boots", MatchType.PHRASE, 900_000L), new KeywordSpec("snow boots", MatchType.EXACT, null)),
                List.of("Winter boots", "Free delivery", "Official store"),
                List.of("Warm boots for the cold season.", "Fast delivery across Poland."),
                "https://acme.example/boots", 750_000L);
    }

    @Test
    @DisplayName("[AC-36] a real add_ad_group sends one atomic request and returns the new ids; validate_only returns none")
    void addAdGroupEndToEnd() {
        transport.mutateResponse = MutateGoogleAdsResponse.newBuilder()
                .addMutateOperationResponses(MutateOperationResponse.newBuilder().setAdGroupResult(
                        MutateAdGroupResult.newBuilder().setResourceName(C + "adGroups/503")))
                .addMutateOperationResponses(MutateOperationResponse.newBuilder().setAdGroupCriterionResult(
                        MutateAdGroupCriterionResult.newBuilder().setResourceName(C + "adGroupCriteria/503~601")))
                .addMutateOperationResponses(MutateOperationResponse.newBuilder().setAdGroupCriterionResult(
                        MutateAdGroupCriterionResult.newBuilder().setResourceName(C + "adGroupCriteria/503~602")))
                .addMutateOperationResponses(MutateOperationResponse.newBuilder().setAdGroupAdResult(
                        MutateAdGroupAdResult.newBuilder().setResourceName(C + "adGroupAds/503~700")))
                .build();

        MutationResult validated = gateway.mutate(bootsAdGroup(), true);
        MutationResult real = gateway.mutate(bootsAdGroup(), false);

        assertThat(transport.mutates).hasSize(2);                         // one atomic request per call
        assertThat(transport.mutates.get(0).getValidateOnly()).isTrue();
        assertThat(transport.mutates.get(1).getValidateOnly()).isFalse();
        assertThat(transport.mutates).allSatisfy(r -> {
            assertThat(r.getPartialFailure()).isFalse();
            assertThat(r.getMutateOperationsCount()).isEqualTo(4);        // ad group, 2 keywords, ad
            assertThat(r.getMutateOperations(0).getAdGroupOperation().getCreate().getCampaign()).isEqualTo(C + "campaigns/11");
        });
        assertThat(transport.queries).isEmpty();                          // no label lookup: the campaign is not touched
        assertThat(validated.resourceNames()).isEmpty();
        assertThat(validated.createdIds()).isEmpty();
        assertThat(real.requestId()).isEqualTo("req-42");
        assertThat(real.resourceNames()).hasSize(4);
        assertThat(real.createdIds()).containsOnly(      // MutationResult keeps the ids as an unordered map
                Map.entry("adGroup", "503"), Map.entry("criterion:0", "503~601"), Map.entry("criterion:1", "503~602"), Map.entry("ad", "503~700"));
    }

    @Test
    @DisplayName("[AC-36] add_ad_group with no keywords becomes INVALID_REQUEST and nothing is sent")
    void addAdGroupPlanningErrors() {
        AddAdGroup noKeywords = new AddAdGroup(EntityRef.campaign("11"), "n", List.of(), List.of("a", "b", "c"), List.of("d", "e"), "https://x.example", null);

        assertThatThrownBy(() -> gateway.mutate(noKeywords, false)).isInstanceOfSatisfying(AdsException.class,
                e -> assertThat(e.code()).isEqualTo("INVALID_REQUEST"));
        assertThat(transport.mutates).isEmpty();
        assertThat(transport.queries).isEmpty();
    }

    @Test
    @DisplayName("[GATEWAY] planning errors become INVALID_REQUEST and nothing is sent")
    void invalidRequestsAreNotSent() {
        assertThatThrownBy(() -> gateway.mutate(new CreateAd(EntityRef.adGroup("22"), List.of("a"), List.of("d", "e"), "https://x.example"), false))
                .isInstanceOfSatisfying(AdsException.class, e -> {
                    assertThat(e.code()).isEqualTo("INVALID_REQUEST");
                    assertThat(e.quotaRelated()).isFalse();
                });
        assertThat(transport.mutates).isEmpty();
        assertThat(transport.queries).isEmpty();
    }

    @Test
    @DisplayName("[GATEWAY] Google failures become AdsException with the first error's code, request id and quota flag")
    void googleFailuresAreTranslated() {
        transport.mutateFailure = googleAdsException(
                ErrorCode.newBuilder().setMutateError(MutateError.RESOURCE_NOT_FOUND).build(), "Requested entity was not found.", "req-777");

        assertThatThrownBy(() -> gateway.mutate(new PauseKeyword(EntityRef.keyword("22~33")), false))
                .isInstanceOfSatisfying(AdsException.class, e -> {
                    assertThat(e.code()).isEqualTo("RESOURCE_NOT_FOUND");
                    assertThat(e.quotaRelated()).isFalse();
                    assertThat(e.getMessage()).contains("MUTATE_ERROR.RESOURCE_NOT_FOUND").contains("Requested entity was not found.")
                            .contains("request_id=req-777");
                    assertThat(e.getCause()).isInstanceOf(GoogleAdsException.class);
                });

        transport.mutateFailure = googleAdsException(
                ErrorCode.newBuilder().setQuotaError(QuotaError.RESOURCE_TEMPORARILY_EXHAUSTED).build(), "Too many requests.", "req-778");
        assertThatThrownBy(() -> gateway.mutate(new PauseKeyword(EntityRef.keyword("22~33")), false))
                .isInstanceOfSatisfying(AdsException.class, e -> {
                    assertThat(e.code()).isEqualTo("RESOURCE_TEMPORARILY_EXHAUSTED");
                    assertThat(e.quotaRelated()).isTrue();
                });
    }

    @Test
    @DisplayName("[GATEWAY] transport and read failures are translated too; bad arguments stay IllegalArgumentException")
    void otherFailuresAreTranslated() {
        transport.mutateFailure = new ApiException(new RuntimeException("rate limited"), GrpcStatusCode.of(Status.Code.RESOURCE_EXHAUSTED), true);
        assertThatThrownBy(() -> gateway.mutate(new PauseKeyword(EntityRef.keyword("22~33")), false))
                .isInstanceOfSatisfying(AdsException.class, e -> {
                    assertThat(e.code()).isEqualTo("RESOURCE_EXHAUSTED");
                    assertThat(e.quotaRelated()).isTrue();
                });

        transport.mutateFailure = new ApiException(new RuntimeException("down"), GrpcStatusCode.of(Status.Code.UNAVAILABLE), true);
        assertThatThrownBy(() -> gateway.mutate(new PauseKeyword(EntityRef.keyword("22~33")), false))
                .isInstanceOfSatisfying(AdsException.class, e -> {
                    assertThat(e.code()).isEqualTo("UNAVAILABLE");
                    assertThat(e.quotaRelated()).isFalse();
                });

        transport.answers = q -> {
            throw new ApiException(new RuntimeException("boom"), GrpcStatusCode.of(Status.Code.DEADLINE_EXCEEDED), true);
        };
        assertThatThrownBy(() -> gateway.budgets()).isInstanceOfSatisfying(AdsException.class, e -> assertThat(e.code()).isEqualTo("DEADLINE_EXCEEDED"));

        assertThatThrownBy(() -> gateway.ads("not-a-number", 5)).isInstanceOf(IllegalArgumentException.class);
    }

    private static GoogleAdsException googleAdsException(ErrorCode code, String message, String requestId) {
        GoogleAdsFailure failure = GoogleAdsFailure.newBuilder()
                .addErrors(GoogleAdsError.newBuilder().setErrorCode(code).setMessage(message)).build();
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("request-id", Metadata.ASCII_STRING_MARSHALLER), requestId);
        ApiException cause = new ApiException(new RuntimeException(message), GrpcStatusCode.of(Status.Code.INVALID_ARGUMENT), false);
        return new GoogleAdsException(cause, failure, headers);
    }

    @Test
    @DisplayName("[GATEWAY] apply_recommendation goes through RecommendationService; validate_only only checks it is still pending")
    void applyRecommendation() {
        ApplyRecommendation request = new ApplyRecommendation(new EntityRef(EntityKind.RECOMMENDATION, "abc"));
        transport.answers = q -> q.contains("FROM recommendation")
                ? List.of(row(Recommendation.newBuilder().setResourceName(C + "recommendations/abc").setType(RecommendationType.KEYWORD)))
                : List.of();

        MutationResult validated = gateway.mutate(request, true);
        assertThat(validated.resourceNames()).isEmpty();
        assertThat(transport.applies).isEmpty();
        assertThat(transport.mutates).isEmpty();

        MutationResult real = gateway.mutate(request, false);
        assertThat(transport.applies).hasSize(1);
        assertThat(transport.applies.get(0).getPartialFailure()).isFalse();
        assertThat(transport.applies.get(0).getOperations(0).getResourceName()).isEqualTo(C + "recommendations/abc");
        assertThat(transport.mutates).isEmpty();
        assertThat(real.requestId()).isEqualTo("req-42");
        assertThat(real.resourceNames()).containsExactly(C + "recommendations/abc");

        transport.answers = q -> List.of();   // gone
        assertThatThrownBy(() -> gateway.mutate(request, true)).isInstanceOfSatisfying(AdsException.class,
                e -> assertThat(e.code()).isEqualTo("RECOMMENDATION_NOT_FOUND"));
    }

    // ---- read back ------------------------------------------------------------------------

    private static MutationResult noIds() {
        return new MutationResult("req", List.of(), Map.of());
    }

    @Test
    @DisplayName("[GATEWAY] readBack of status changes compares the live status")
    void readBackStatus() {
        transport.answers = q -> q.contains("FROM campaign") ? List.of(row(campaign(11).setStatus(CampaignStatus.PAUSED))) : List.of();
        ReadBack ok = gateway.readBack(new EmergencyPauseCampaign(EntityRef.campaign("11")), noIds());
        assertThat(ok.matches()).isTrue();
        assertThat(ok.observed()).isEqualTo("status=PAUSED");

        transport.answers = q -> q.contains("FROM campaign") ? List.of(row(campaign(11))) : List.of();
        ReadBack bad = gateway.readBack(new EmergencyPauseCampaign(EntityRef.campaign("11")), noIds());
        assertThat(bad.matches()).isFalse();
        assertThat(bad.observed()).isEqualTo("status=ENABLED");

        transport.answers = q -> List.of();
        ReadBack missing = gateway.readBack(new EmergencyPauseCampaign(EntityRef.campaign("11")), noIds());
        assertThat(missing.matches()).isFalse();
        assertThat(missing.observed()).contains("not found");
    }

    @Test
    @DisplayName("[GATEWAY] readBack compares budget amounts and keyword bids to newValues")
    void readBackValues() {
        transport.answers = q -> q.contains("FROM campaign_budget")
                ? List.of(row(CampaignBudget.newBuilder().setId(44).setAmountMicros(10_000_000).setStatus(BudgetStatus.ENABLED)))
                : List.of();
        assertThat(gateway.readBack(new DecreaseBudget(EntityRef.budget("44"), 10_000_000L), noIds()).matches()).isTrue();
        ReadBack off = gateway.readBack(new DecreaseBudget(EntityRef.budget("44"), 9_000_000L), noIds());
        assertThat(off.matches()).isFalse();
        assertThat(off.observed()).isEqualTo("amountMicros=10000000");

        transport.answers = keywordWorld(false);
        assertThat(gateway.readBack(new AdjustKeywordBid(EntityRef.keyword("22~33"), 1_200_000L), noIds()).matches()).isTrue();
        assertThat(gateway.readBack(new AdjustKeywordBid(EntityRef.keyword("22~33"), 1_300_000L), noIds()).matches()).isFalse();
        // read back does not need metrics or the keyword count
        assertThat(transport.count("metrics.clicks")).isZero();
    }

    @Test
    @DisplayName("[GATEWAY] readBack of negatives checks text and match type on the scope; removal checks the ids are gone")
    void readBackNegatives() {
        transport.answers = q -> q.contains("FROM campaign_criterion")
                ? List.of(row(campaign(11), CampaignCriterion.newBuilder().setCriterionId(9001)
                        .setKeyword(KeywordInfo.newBuilder().setText("free").setMatchType(KeywordMatchType.PHRASE))))
                : List.of();

        assertThat(gateway.readBack(new AddNegativeKeywords(EntityRef.campaign("11"), List.of("Free"), MatchType.PHRASE), noIds()).matches()).isTrue();
        ReadBack missing = gateway.readBack(new AddNegativeKeywords(EntityRef.campaign("11"), List.of("free", "jobs"), MatchType.PHRASE), noIds());
        assertThat(missing.matches()).isFalse();
        assertThat(missing.observed()).contains("jobs");
        assertThat(gateway.readBack(new AddNegativeKeywords(EntityRef.campaign("11"), List.of("free"), MatchType.EXACT), noIds()).matches()).isFalse();

        assertThat(gateway.readBack(new RemoveNegativeKeywords(EntityRef.campaign("11"), List.of("9002")), noIds()).matches()).isTrue();
        ReadBack still = gateway.readBack(new RemoveNegativeKeywords(EntityRef.campaign("11"), List.of("9001")), noIds());
        assertThat(still.matches()).isFalse();
        assertThat(still.observed()).contains("9001");
    }

    @Test
    @DisplayName("[GATEWAY] readBack of create_campaign checks status, budget, settings and the managed label")
    void readBackCampaign() {
        CampaignSpec spec = new CampaignSpec("Brand", 25_000_000L, BiddingStrategy.maximizeConversions(), List.of(2616L), "Brand", List.of(),
                List.of("a", "b", "c"), List.of("d", "e"), "https://acme.example", EuPoliticalAds.NO_POLITICAL_ADS);
        MutationResult result = new MutationResult("req", List.of(), Map.of("campaign", "502", "budget", "501"));
        Campaign.Builder good = campaign(502).setName("Brand").setStatus(CampaignStatus.PAUSED).setCampaignBudget(C + "campaignBudgets/501")
                .addLabels(C + "labels/10")
                .setMaximizeConversions(com.google.ads.googleads.v26.common.MaximizeConversions.getDefaultInstance())
                .setNetworkSettings(Campaign.NetworkSettings.newBuilder().setTargetGoogleSearch(true).setTargetContentNetwork(false).setTargetSearchNetwork(false))
                .setGeoTargetTypeSetting(Campaign.GeoTargetTypeSetting.newBuilder()
                        .setPositiveGeoTargetType(com.google.ads.googleads.v26.enums.PositiveGeoTargetTypeEnum.PositiveGeoTargetType.PRESENCE))
                .setContainsEuPoliticalAdvertising(
                        com.google.ads.googleads.v26.enums.EuPoliticalAdvertisingStatusEnum.EuPoliticalAdvertisingStatus.DOES_NOT_CONTAIN_EU_POLITICAL_ADVERTISING);
        transport.answers = q -> {
            if (q.contains("FROM campaign_budget")) {
                return List.of(row(CampaignBudget.newBuilder().setId(501).setAmountMicros(25_000_000).setExplicitlyShared(false).setStatus(BudgetStatus.ENABLED)));
            }
            if (q.contains("campaign.campaign_budget =")) {
                return List.of(row(campaign(502)));
            }
            if (q.contains("FROM campaign")) {
                return List.of(row(good));
            }
            if (q.contains("FROM label")) {
                return List.of(label("10", "agent-managed"));
            }
            return List.of();
        };

        ReadBack ok = gateway.readBack(new CreateCampaign(spec), result);
        assertThat(ok.matches()).as(ok.observed()).isTrue();
        assertThat(ok.observed()).contains("status=PAUSED").contains("managedLabel=true");

        // the same campaign without the label is reported as different
        Campaign.Builder noLabel = good.clone().clearLabels();
        transport.answers = q -> {
            if (q.contains("FROM campaign_budget")) {
                return List.of(row(CampaignBudget.newBuilder().setId(501).setAmountMicros(25_000_000).setExplicitlyShared(false).setStatus(BudgetStatus.ENABLED)));
            }
            if (q.contains("campaign.campaign_budget =")) {
                return List.of(row(campaign(502)));
            }
            return q.contains("FROM campaign") ? List.of(row(noLabel)) : List.of();
        };
        ReadBack bad = gateway.readBack(new CreateCampaign(spec), result);
        assertThat(bad.matches()).isFalse();
        assertThat(bad.observed()).contains("managedLabel is false");

        assertThat(gateway.readBack(new CreateCampaign(spec), noIds()).matches()).isFalse();
    }

    /** One ad group 503 in campaign {@code campaignId} with {@code enabledKeywords} enabled keywords; its campaign carries a label. */
    private static Function<String, List<GoogleAdsRow>> adGroupWorld(AdGroupStatus status, long campaignId, int enabledKeywords) {
        return q -> {
            if (q.contains("FROM ad_group WHERE")) {
                return List.of(row(AdGroup.newBuilder().setId(503).setName("Winter boots").setStatus(status), campaign(campaignId).addLabels(C + "labels/10")));
            }
            if (q.contains("ad_group_criterion.status = 'ENABLED'")) {
                return IntStream.range(0, enabledKeywords).mapToObj(i -> row(AdGroupCriterion.newBuilder().setCriterionId(601 + i))).toList();
            }
            if (q.contains("FROM label")) {
                return List.of(label("10", "agent-managed"));
            }
            return List.of();
        };
    }

    private static MutationResult createdAdGroup() {
        return new MutationResult("req", List.of(), Map.of("adGroup", "503", "criterion:0", "503~601", "criterion:1", "503~602", "ad", "503~700"));
    }

    @Test
    @DisplayName("[AC-36] readBack of add_ad_group matches an enabled ad group in the requested campaign with all its keywords")
    void readBackAddAdGroupMatches() {
        transport.answers = adGroupWorld(AdGroupStatus.ENABLED, 11, 2);

        ReadBack ok = gateway.readBack(bootsAdGroup(), createdAdGroup());

        assertThat(ok.matches()).as(ok.observed()).isTrue();
        assertThat(ok.observed()).contains("ad group 503").contains("status=ENABLED").contains("campaignId=11").contains("enabledKeywords=2");
        assertThat(transport.queries).hasSize(3);          // the ad group, the label names, the enabled keywords
        assertThat(transport.queries.get(0)).contains("FROM ad_group WHERE ad_group.id = 503");
        assertThat(transport.queries.get(2)).contains("FROM ad_group_criterion WHERE ad_group.id = 503");
    }

    @Test
    @DisplayName("[AC-36] readBack of add_ad_group reports a paused ad group, another campaign or missing keywords")
    void readBackAddAdGroupMismatch() {
        transport.answers = adGroupWorld(AdGroupStatus.PAUSED, 11, 2);
        ReadBack paused = gateway.readBack(bootsAdGroup(), createdAdGroup());
        assertThat(paused.matches()).isFalse();
        assertThat(paused.observed()).contains("status is PAUSED, expected ENABLED");

        transport.answers = adGroupWorld(AdGroupStatus.ENABLED, 12, 2);
        ReadBack elsewhere = gateway.readBack(bootsAdGroup(), createdAdGroup());
        assertThat(elsewhere.matches()).isFalse();
        assertThat(elsewhere.observed()).contains("campaignId is 12, expected 11");

        transport.answers = adGroupWorld(AdGroupStatus.ENABLED, 11, 1);
        ReadBack fewer = gateway.readBack(bootsAdGroup(), createdAdGroup());
        assertThat(fewer.matches()).isFalse();
        assertThat(fewer.observed()).contains("enabledKeywords is 1, expected 2");

        transport.answers = adGroupWorld(AdGroupStatus.ENABLED, 11, 3);   // more than asked for is not what was promised either
        assertThat(gateway.readBack(bootsAdGroup(), createdAdGroup()).matches()).isFalse();
    }

    @Test
    @DisplayName("[AC-36] readBack of add_ad_group fails clearly when the ad group is gone or the result has no ad group id")
    void readBackAddAdGroupMissing() {
        transport.answers = q -> List.of();
        ReadBack gone = gateway.readBack(bootsAdGroup(), createdAdGroup());
        assertThat(gone.matches()).isFalse();
        assertThat(gone.observed()).contains("ad group 503 not found");
        assertThat(transport.queries).hasSize(1);          // no keyword query for an ad group that is not there

        transport.queries.clear();
        ReadBack noId = gateway.readBack(bootsAdGroup(), noIds());
        assertThat(noId.matches()).isFalse();
        assertThat(noId.observed()).contains("no ad group id");
        assertThat(transport.queries).isEmpty();
    }

    @Test
    @DisplayName("[GATEWAY] readBack of bidding strategy, created ad and applied recommendation")
    void readBackBiddingAdAndRecommendation() {
        transport.answers = q -> q.contains("FROM campaign")
                ? List.of(row(campaign(11).setTargetCpa(TargetCpa.newBuilder().setTargetCpaMicros(5_000_000)))) : List.of();
        assertThat(gateway.readBack(new ChangeBiddingStrategy(EntityRef.campaign("11"), BiddingStrategy.targetCpa(5_000_000L)), noIds()).matches()).isTrue();
        assertThat(gateway.readBack(new ChangeBiddingStrategy(EntityRef.campaign("11"), BiddingStrategy.targetCpa(6_000_000L)), noIds()).matches()).isFalse();
        assertThat(gateway.readBack(new ChangeBiddingStrategy(EntityRef.campaign("11"), BiddingStrategy.manualCpc()), noIds()).matches()).isFalse();

        transport.answers = q -> q.contains("FROM ad_group_ad") ? List.of(row(AdGroup.newBuilder().setId(22), campaign(11),
                AdGroupAd.newBuilder().setStatus(AdGroupAdStatus.ENABLED).setAd(Ad.newBuilder().setId(99).addFinalUrls("https://x.example")
                        .setResponsiveSearchAd(ResponsiveSearchAdInfo.newBuilder()
                                .addHeadlines(AdTextAsset.newBuilder().setText("a")).addHeadlines(AdTextAsset.newBuilder().setText("b"))
                                .addHeadlines(AdTextAsset.newBuilder().setText("c"))
                                .addDescriptions(AdTextAsset.newBuilder().setText("d")).addDescriptions(AdTextAsset.newBuilder().setText("e")))))) : List.of();
        CreateAd ad = new CreateAd(EntityRef.adGroup("22"), List.of("a", "b", "c"), List.of("d", "e"), "https://x.example");
        MutationResult created = new MutationResult("req", List.of(), Map.of("ad", "22~99"));
        assertThat(gateway.readBack(ad, created).matches()).isTrue();
        CreateAd different = new CreateAd(EntityRef.adGroup("22"), List.of("a", "b", "z"), List.of("d", "e"), "https://x.example");
        assertThat(gateway.readBack(different, created).matches()).isFalse();
        assertThat(gateway.readBack(ad, noIds()).matches()).isFalse();

        ApplyRecommendation apply = new ApplyRecommendation(new EntityRef(EntityKind.RECOMMENDATION, "abc"));
        transport.answers = q -> List.of();
        assertThat(gateway.readBack(apply, noIds()).matches()).isTrue();        // no longer listed
        transport.answers = q -> q.contains("FROM recommendation")
                ? List.of(row(Recommendation.newBuilder().setResourceName(C + "recommendations/abc").setType(RecommendationType.KEYWORD))) : List.of();
        assertThat(gateway.readBack(apply, noIds()).matches()).isFalse();       // still pending
    }

    @Test
    @DisplayName("[GATEWAY] readBack of add_keywords finds each keyword with its match type and bid")
    void readBackKeywords() {
        transport.answers = q -> List.of(
                row(AdGroupCriterion.newBuilder().setCriterionId(1).setStatus(AdGroupCriterionStatus.ENABLED)
                        .setKeyword(KeywordInfo.newBuilder().setText("acme").setMatchType(KeywordMatchType.BROAD)).setCpcBidMicros(400_000)),
                row(AdGroupCriterion.newBuilder().setCriterionId(2).setStatus(AdGroupCriterionStatus.ENABLED)
                        .setKeyword(KeywordInfo.newBuilder().setText("acme shoes").setMatchType(KeywordMatchType.EXACT))));

        var ok = new ChangeRequest.AddKeywords(EntityRef.adGroup("22"), List.of(
                new KeywordSpec("Acme", MatchType.BROAD, 400_000L), new KeywordSpec("acme shoes", MatchType.EXACT, null)));
        assertThat(gateway.readBack(ok, noIds()).matches()).isTrue();

        var wrongBid = new ChangeRequest.AddKeywords(EntityRef.adGroup("22"), List.of(new KeywordSpec("acme", MatchType.BROAD, 500_000L)));
        assertThat(gateway.readBack(wrongBid, noIds()).matches()).isFalse();
        var wrongType = new ChangeRequest.AddKeywords(EntityRef.adGroup("22"), List.of(new KeywordSpec("acme", MatchType.EXACT, null)));
        ReadBack r = gateway.readBack(wrongType, noIds());
        assertThat(r.matches()).isFalse();
        assertThat(r.observed()).contains("acme [EXACT]");
    }

    @Test
    @DisplayName("[AC-40] conversion actions take three queries: the actions, then their 7 and 30 day results")
    void conversionActions() {
        transport.answers = q -> {
            if (q.contains("metrics.conversions")) {
                return q.contains("LAST_7_DAYS") ? List.of(conversionResultRow(900, 3, 114)) : List.of(conversionResultRow(900, 9, 342));
            }
            return q.contains("FROM conversion_action") ? List.of(conversionActionRow(900, "Paid subscription", "SUBSCRIBE_PAID", true, 38)) : List.of();
        };

        var out = gateway.conversionActions();

        assertThat(out).singleElement().satisfies(a -> {
            assertThat(a.category()).isEqualTo("SUBSCRIBE_PAID");
            assertThat(a.conversions7d()).isEqualTo(3.0);
            assertThat(a.conversions30d()).isEqualTo(9.0);
            assertThat(a.conversionValue30d()).isEqualTo(342.0);
        });
        assertThat(transport.queries).hasSize(3);
        assertThat(transport.queries.get(0)).startsWith("SELECT").contains("FROM conversion_action").doesNotContain("metrics.");
        assertThat(transport.queries.get(1)).contains("segments.date DURING LAST_7_DAYS");
        assertThat(transport.queries.get(2)).contains("segments.date DURING LAST_30_DAYS");
    }

    // ---- conversion actions ---------------------------------------------------------------------

    private static GoogleAdsRow conversionActionRow(long id, String name, String category, boolean primary, double defaultValue) {
        return GoogleAdsRow.newBuilder().setConversionAction(com.google.ads.googleads.v26.resources.ConversionAction.newBuilder()
                .setId(id).setName(name)
                .setType(com.google.ads.googleads.v26.enums.ConversionActionTypeEnum.ConversionActionType.UPLOAD_CLICKS)
                .setStatus(com.google.ads.googleads.v26.enums.ConversionActionStatusEnum.ConversionActionStatus.ENABLED)
                .setCategory(com.google.ads.googleads.v26.enums.ConversionActionCategoryEnum.ConversionActionCategory.valueOf(category))
                .setPrimaryForGoal(primary)
                .setCountingType(com.google.ads.googleads.v26.enums.ConversionActionCountingTypeEnum.ConversionActionCountingType.ONE_PER_CLICK)
                .setValueSettings(com.google.ads.googleads.v26.resources.ConversionAction.ValueSettings.newBuilder()
                        .setDefaultValue(defaultValue).setAlwaysUseDefaultValue(false))).build();
    }

    private static GoogleAdsRow conversionResultRow(long id, double conversions, double value) {
        return GoogleAdsRow.newBuilder()
                .setConversionAction(com.google.ads.googleads.v26.resources.ConversionAction.newBuilder().setId(id))
                .setMetrics(com.google.ads.googleads.v26.common.Metrics.newBuilder().setConversions(conversions).setConversionsValue(value)).build();
    }
}

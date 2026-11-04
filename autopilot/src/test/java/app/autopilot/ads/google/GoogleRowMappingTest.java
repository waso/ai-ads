package app.autopilot.ads.google;

import app.autopilot.ads.AdsGateway.AdRow;
import app.autopilot.ads.AdsGateway.BudgetInfo;
import app.autopilot.ads.AdsGateway.CampaignInfo;
import app.autopilot.ads.AdsGateway.ChangeEvent;
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
import com.google.ads.googleads.v26.common.KeywordInfo;
import com.google.ads.googleads.v26.common.ManualCpc;
import com.google.ads.googleads.v26.common.MaximizeConversionValue;
import com.google.ads.googleads.v26.common.MaximizeConversions;
import com.google.ads.googleads.v26.common.Metrics;
import com.google.ads.googleads.v26.common.PolicyTopicEntry;
import com.google.ads.googleads.v26.common.ResponsiveSearchAdInfo;
import com.google.ads.googleads.v26.common.Segments;
import com.google.ads.googleads.v26.common.TargetCpa;
import com.google.ads.googleads.v26.common.TargetRoas;
import com.google.ads.googleads.v26.enums.AdGroupAdStatusEnum.AdGroupAdStatus;
import com.google.ads.googleads.v26.enums.AdGroupCriterionApprovalStatusEnum.AdGroupCriterionApprovalStatus;
import com.google.ads.googleads.v26.enums.AdGroupCriterionStatusEnum.AdGroupCriterionStatus;
import com.google.ads.googleads.v26.enums.AdGroupStatusEnum.AdGroupStatus;
import com.google.ads.googleads.v26.enums.AdStrengthEnum.AdStrength;
import com.google.ads.googleads.v26.enums.BiddingStrategyTypeEnum.BiddingStrategyType;
import com.google.ads.googleads.v26.enums.BudgetStatusEnum.BudgetStatus;
import com.google.ads.googleads.v26.enums.CampaignPrimaryStatusEnum.CampaignPrimaryStatus;
import com.google.ads.googleads.v26.enums.CampaignStatusEnum.CampaignStatus;
import com.google.ads.googleads.v26.enums.ChangeClientTypeEnum.ChangeClientType;
import com.google.ads.googleads.v26.enums.ChangeEventResourceTypeEnum.ChangeEventResourceType;
import com.google.ads.googleads.v26.enums.CriterionTypeEnum.CriterionType;
import com.google.ads.googleads.v26.enums.KeywordMatchTypeEnum.KeywordMatchType;
import com.google.ads.googleads.v26.enums.PolicyApprovalStatusEnum.PolicyApprovalStatus;
import com.google.ads.googleads.v26.enums.RecommendationTypeEnum.RecommendationType;
import com.google.ads.googleads.v26.enums.ResourceChangeOperationEnum.ResourceChangeOperation;
import com.google.ads.googleads.v26.resources.Ad;
import com.google.ads.googleads.v26.resources.AdGroup;
import com.google.ads.googleads.v26.resources.AdGroupAd;
import com.google.ads.googleads.v26.resources.AdGroupAdPolicySummary;
import com.google.ads.googleads.v26.resources.AdGroupCriterion;
import com.google.ads.googleads.v26.resources.Campaign;
import com.google.ads.googleads.v26.resources.CampaignBudget;
import com.google.ads.googleads.v26.resources.CampaignCriterion;
import com.google.ads.googleads.v26.resources.Label;
import com.google.ads.googleads.v26.resources.Recommendation;
import com.google.ads.googleads.v26.resources.SearchTermView;
import com.google.ads.googleads.v26.services.GoogleAdsRow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleRowMappingTest {

    private static final String C = "customers/1234567890/";
    private static final String L_MANAGED = C + "labels/10";
    private static final String L_NO_AGENT = C + "labels/11";
    private static final String L_OTHER = C + "labels/12";
    private static final Map<String, String> LABELS = Map.of(L_MANAGED, "agent-managed", L_NO_AGENT, "no-agent", L_OTHER, "seasonal");

    // ---- row builders ---------------------------------------------------------------------

    private static GoogleAdsRow row(Object... parts) {
        GoogleAdsRow.Builder b = GoogleAdsRow.newBuilder();
        for (Object p : parts) {
            if (p instanceof Campaign.Builder x) b.setCampaign(x);
            else if (p instanceof Campaign x) b.setCampaign(x);
            else if (p instanceof AdGroup.Builder x) b.setAdGroup(x);
            else if (p instanceof AdGroupAd.Builder x) b.setAdGroupAd(x);
            else if (p instanceof AdGroupCriterion.Builder x) b.setAdGroupCriterion(x);
            else if (p instanceof CampaignBudget.Builder x) b.setCampaignBudget(x);
            else if (p instanceof CampaignCriterion.Builder x) b.setCampaignCriterion(x);
            else if (p instanceof Label.Builder x) b.setLabel(x);
            else if (p instanceof Metrics.Builder x) b.setMetrics(x);
            else if (p instanceof Segments.Builder x) b.setSegments(x);
            else if (p instanceof Recommendation.Builder x) b.setRecommendation(x);
            else if (p instanceof SearchTermView.Builder x) b.setSearchTermView(x);
            else if (p instanceof com.google.ads.googleads.v26.resources.ChangeEvent.Builder x) b.setChangeEvent(x);
            else throw new IllegalArgumentException(p.getClass().getName());
        }
        return b.build();
    }

    private static Campaign.Builder campaign(long id) {
        return Campaign.newBuilder().setId(id).setName("Campaign " + id).setStatus(CampaignStatus.ENABLED)
                .setPrimaryStatus(CampaignPrimaryStatus.ELIGIBLE).setCampaignBudget(C + "campaignBudgets/" + (id + 1000));
    }

    private static Metrics.Builder metrics(long cost, long impressions) {
        return Metrics.newBuilder().setCostMicros(cost).setImpressions(impressions);
    }

    private static GoogleAdsRow spend(long campaignId, String date, long cost, long impressions) {
        return row(Campaign.newBuilder().setId(campaignId), Segments.newBuilder().setDate(date), metrics(cost, impressions));
    }

    // ---- account level ----------------------------------------------------------------------

    @Test
    @DisplayName("[MAPPING] campaigns: spend today and month-to-date, labels by name, budget id, primary status")
    void campaignsWithSpend() {
        GoogleAdsRow one = row(campaign(1).addLabels(L_MANAGED).addLabels(L_OTHER)
                .setManualCpc(ManualCpc.newBuilder().setEnhancedCpcEnabled(false)));
        GoogleAdsRow two = row(campaign(2).setStatus(CampaignStatus.PAUSED).setPrimaryStatus(CampaignPrimaryStatus.PAUSED)
                .setMaximizeConversions(MaximizeConversions.getDefaultInstance()));
        List<GoogleAdsRow> spend = List.of(
                spend(1, "2026-11-01", 5_000_000, 100),
                spend(1, "2026-11-02", 7_000_000, 120),
                spend(1, "2026-11-03", 2_500_000, 40),
                spend(2, "2026-11-02", 1_000_000, 10));

        List<CampaignInfo> out = RowMapping.campaigns(List.of(one, two), spend, LABELS, LocalDate.of(2026, 11, 3));

        assertThat(out).hasSize(2);
        CampaignInfo a = out.get(0);
        assertThat(a.id()).isEqualTo("1");
        assertThat(a.name()).isEqualTo("Campaign 1");
        assertThat(a.status()).isEqualTo("ENABLED");
        assertThat(a.enabled()).isTrue();
        assertThat(a.primaryStatus()).isEqualTo("ELIGIBLE");
        assertThat(a.budgetId()).isEqualTo("1001");
        assertThat(a.labels()).containsExactlyInAnyOrder("agent-managed", "seasonal");
        assertThat(a.costTodayMicros()).isEqualTo(2_500_000);
        assertThat(a.costMonthMicros()).isEqualTo(14_500_000);
        assertThat(a.impressionsToday()).isEqualTo(40);
        assertThat(a.biddingStrategy()).isEqualTo("MANUAL_CPC");
        CampaignInfo b = out.get(1);
        assertThat(b.status()).isEqualTo("PAUSED");
        assertThat(b.primaryStatus()).isEqualTo("PAUSED");
        assertThat(b.costTodayMicros()).isZero();            // nothing today
        assertThat(b.costMonthMicros()).isEqualTo(1_000_000);
        assertThat(b.biddingStrategy()).isEqualTo("MAXIMIZE_CONVERSIONS");
    }

    @Test
    @DisplayName("[MAPPING] a campaign with no spend rows has zero cost, and an unknown label keeps its resource name")
    void campaignWithoutSpend() {
        List<CampaignInfo> out = RowMapping.campaigns(List.of(row(campaign(9).addLabels(C + "labels/999"))), List.of(), LABELS,
                LocalDate.of(2026, 11, 3));
        assertThat(out.get(0).costTodayMicros()).isZero();
        assertThat(out.get(0).costMonthMicros()).isZero();
        assertThat(out.get(0).impressionsToday()).isZero();
        assertThat(out.get(0).labels()).containsExactly(C + "labels/999");
    }

    @Test
    @DisplayName("[MAPPING] bidding strategy name comes from the campaign's strategy oneof")
    void biddingStrategyNames() {
        assertThat(RowMapping.biddingStrategy(Campaign.newBuilder().setManualCpc(ManualCpc.getDefaultInstance()).build())).isEqualTo("MANUAL_CPC");
        assertThat(RowMapping.biddingStrategy(Campaign.newBuilder().setMaximizeConversions(MaximizeConversions.getDefaultInstance()).build()))
                .isEqualTo("MAXIMIZE_CONVERSIONS");
        assertThat(RowMapping.biddingStrategy(Campaign.newBuilder().setMaximizeConversionValue(MaximizeConversionValue.getDefaultInstance()).build()))
                .isEqualTo("MAXIMIZE_CONVERSION_VALUE");
        assertThat(RowMapping.biddingStrategy(Campaign.newBuilder().setTargetCpa(TargetCpa.getDefaultInstance()).build())).isEqualTo("TARGET_CPA");
        assertThat(RowMapping.biddingStrategy(Campaign.newBuilder().setTargetRoas(TargetRoas.getDefaultInstance()).build())).isEqualTo("TARGET_ROAS");
        assertThat(RowMapping.biddingStrategy(Campaign.newBuilder().setBiddingStrategy(C + "biddingStrategies/5").build())).isEqualTo("PORTFOLIO");
        assertThat(RowMapping.biddingStrategy(Campaign.newBuilder()
                .setTargetImpressionShare(com.google.ads.googleads.v26.common.TargetImpressionShare.getDefaultInstance()).build()))
                .isEqualTo("TARGET_IMPRESSION_SHARE");
        // oneof not populated: fall back to Google's bidding_strategy_type
        assertThat(RowMapping.biddingStrategy(Campaign.newBuilder().setBiddingStrategyType(BiddingStrategyType.ENHANCED_CPC).build())).isEqualTo("MANUAL_CPC");
        assertThat(RowMapping.biddingStrategy(Campaign.newBuilder().setBiddingStrategyType(BiddingStrategyType.TARGET_CPA).build())).isEqualTo("TARGET_CPA");
        assertThat(RowMapping.biddingStrategy(Campaign.getDefaultInstance())).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("[MAPPING] budgets list every campaign that uses them, shared or not")
    void budgets() {
        GoogleAdsRow b1 = row(CampaignBudget.newBuilder().setId(1001).setName("own").setAmountMicros(20_000_000).setExplicitlyShared(false));
        GoogleAdsRow b2 = row(CampaignBudget.newBuilder().setId(2000).setName("shared").setAmountMicros(50_000_000).setExplicitlyShared(true));
        GoogleAdsRow b3 = row(CampaignBudget.newBuilder().setId(3000).setName("unused").setAmountMicros(1_000_000));
        GoogleAdsRow u1 = row(campaign(1));
        GoogleAdsRow u2 = row(campaign(5).setCampaignBudget(C + "campaignBudgets/2000"));
        GoogleAdsRow u3 = row(campaign(6).setCampaignBudget(C + "campaignBudgets/2000"));

        List<BudgetInfo> out = RowMapping.budgets(List.of(b1, b2, b3), List.of(u1, u2, u3));

        assertThat(out).extracting(BudgetInfo::id).containsExactly("1001", "2000", "3000");
        assertThat(out.get(0).amountMicros()).isEqualTo(20_000_000);
        assertThat(out.get(0).explicitlyShared()).isFalse();
        assertThat(out.get(0).campaignIds()).containsExactly("1");
        assertThat(out.get(1).explicitlyShared()).isTrue();
        assertThat(out.get(1).campaignIds()).containsExactly("5", "6");
        assertThat(out.get(2).campaignIds()).isEmpty();
    }

    @Test
    @DisplayName("[MAPPING] policy issues for ads and keywords")
    void policyIssues() {
        GoogleAdsRow ad = row(AdGroup.newBuilder().setId(22),
                AdGroupAd.newBuilder().setAd(Ad.newBuilder().setId(55).setName(""))
                        .setPolicySummary(AdGroupAdPolicySummary.newBuilder()
                                .setApprovalStatus(PolicyApprovalStatus.DISAPPROVED)
                                .addPolicyTopicEntries(PolicyTopicEntry.newBuilder().setTopic("TRADEMARKS"))
                                .addPolicyTopicEntries(PolicyTopicEntry.newBuilder().setTopic("DESTINATION_NOT_WORKING"))));
        GoogleAdsRow limited = row(AdGroup.newBuilder().setId(22),
                AdGroupAd.newBuilder().setAd(Ad.newBuilder().setId(56).setName("Spring sale"))
                        .setPolicySummary(AdGroupAdPolicySummary.newBuilder().setApprovalStatus(PolicyApprovalStatus.APPROVED_LIMITED)));
        GoogleAdsRow keyword = row(AdGroup.newBuilder().setId(22),
                AdGroupCriterion.newBuilder().setCriterionId(33).setKeyword(KeywordInfo.newBuilder().setText("pills"))
                        .setApprovalStatus(AdGroupCriterionApprovalStatus.DISAPPROVED).addDisapprovalReasons("HEALTH"));

        List<PolicyIssue> out = RowMapping.policyIssues(List.of(ad, limited), List.of(keyword));

        assertThat(out).hasSize(3);
        assertThat(out.get(0).entity()).isEqualTo(EntityRef.ad("22~55"));
        assertThat(out.get(0).name()).isEqualTo("Ad 55");
        assertThat(out.get(0).approvalStatus()).isEqualTo("DISAPPROVED");
        assertThat(out.get(0).topics()).containsExactly("TRADEMARKS", "DESTINATION_NOT_WORKING");
        assertThat(out.get(1).name()).isEqualTo("Spring sale");
        assertThat(out.get(1).approvalStatus()).isEqualTo("APPROVED_LIMITED");
        assertThat(out.get(2).entity()).isEqualTo(EntityRef.keyword("22~33"));
        assertThat(out.get(2).name()).isEqualTo("pills");
        assertThat(out.get(2).topics()).containsExactly("HEALTH");
    }

    @Test
    @DisplayName("[MAPPING] change events: actor, client type, resource and operation; time is read in the account zone")
    void changeEvents() {
        ZoneId warsaw = ZoneId.of("Europe/Warsaw");
        GoogleAdsRow e = row(com.google.ads.googleads.v26.resources.ChangeEvent.newBuilder()
                .setChangeDateTime("2026-11-03 10:15:30.123456")
                .setUserEmail("owner@example.com")
                .setClientType(ChangeClientType.GOOGLE_ADS_WEB_CLIENT)
                .setChangeResourceType(ChangeEventResourceType.CAMPAIGN_BUDGET)
                .setChangeResourceName(C + "campaignBudgets/1001")
                .setResourceChangeOperation(ResourceChangeOperation.UPDATE));
        GoogleAdsRow noFraction = row(com.google.ads.googleads.v26.resources.ChangeEvent.newBuilder()
                .setChangeDateTime("2026-07-03 10:15:30").setClientType(ChangeClientType.GOOGLE_ADS_API)
                .setChangeResourceType(ChangeEventResourceType.AD_GROUP_CRITERION).setResourceChangeOperation(ResourceChangeOperation.CREATE));

        List<ChangeEvent> out = RowMapping.changeEvents(List.of(e, noFraction), warsaw);

        assertThat(out.get(0).at()).isEqualTo(Instant.parse("2026-11-03T09:15:30.123456Z")); // CET is UTC+1
        assertThat(out.get(0).actorEmail()).isEqualTo("owner@example.com");
        assertThat(out.get(0).clientType()).isEqualTo("GOOGLE_ADS_WEB_CLIENT");
        assertThat(out.get(0).resourceType()).isEqualTo("CAMPAIGN_BUDGET");
        assertThat(out.get(0).resourceName()).isEqualTo(C + "campaignBudgets/1001");
        assertThat(out.get(0).operation()).isEqualTo("UPDATE");
        assertThat(out.get(1).at()).isEqualTo(Instant.parse("2026-07-03T08:15:30Z"));        // CEST is UTC+2
        assertThat(out.get(1).operation()).isEqualTo("CREATE");
        assertThatThrownBy(() -> RowMapping.parseChangeTime("yesterday", warsaw)).isInstanceOf(IllegalStateException.class);
    }

    // ---- optimization reads ---------------------------------------------------------------

    @Test
    @DisplayName("[MAPPING] performance rows for each level carry ids, names, parents and metrics")
    void performanceRows() {
        Metrics.Builder m = Metrics.newBuilder().setImpressions(1000).setClicks(50).setCostMicros(12_000_000).setConversions(2.5).setConversionsValue(99.5);

        PerformanceRow campaign = RowMapping.performance(Level.CAMPAIGN, row(campaign(1), m));
        assertThat(campaign).isEqualTo(new PerformanceRow(Level.CAMPAIGN, "1", "Campaign 1", null, 1000, 50, 12_000_000, 2.5, 99.5));

        PerformanceRow adGroup = RowMapping.performance(Level.AD_GROUP, row(AdGroup.newBuilder().setId(22).setName("Brand"), campaign(1), m));
        assertThat(adGroup.id()).isEqualTo("22");
        assertThat(adGroup.name()).isEqualTo("Brand");
        assertThat(adGroup.parentId()).isEqualTo("1");

        PerformanceRow keyword = RowMapping.performance(Level.KEYWORD, row(AdGroup.newBuilder().setId(22),
                AdGroupCriterion.newBuilder().setCriterionId(33).setKeyword(KeywordInfo.newBuilder().setText("acme shoes")), m));
        assertThat(keyword.id()).isEqualTo("22~33");
        assertThat(keyword.name()).isEqualTo("acme shoes");
        assertThat(keyword.parentId()).isEqualTo("22");
        assertThat(keyword.costMicros()).isEqualTo(12_000_000);

        PerformanceRow ad = RowMapping.performance(Level.AD, row(AdGroup.newBuilder().setId(22),
                AdGroupAd.newBuilder().setAd(Ad.newBuilder().setId(55).setName("RSA 1")), m));
        assertThat(ad.id()).isEqualTo("22~55");
        assertThat(ad.parentId()).isEqualTo("22");
    }

    @Test
    @DisplayName("[MAPPING] search term, keyword and ad rows")
    void listRows() {
        Metrics.Builder m = Metrics.newBuilder().setImpressions(10).setClicks(3).setCostMicros(900_000).setConversions(1);

        SearchTermRow term = RowMapping.searchTerm(row(SearchTermView.newBuilder().setSearchTerm("cheap acme"), campaign(1),
                AdGroup.newBuilder().setId(22), m));
        assertThat(term).isEqualTo(new SearchTermRow("cheap acme", "1", "22", 10, 3, 900_000, 1));

        GoogleAdsRow kwRow = row(AdGroup.newBuilder().setId(22), campaign(1),
                AdGroupCriterion.newBuilder().setCriterionId(33).setStatus(AdGroupCriterionStatus.ENABLED)
                        .setKeyword(KeywordInfo.newBuilder().setText("acme").setMatchType(KeywordMatchType.PHRASE))
                        .setCpcBidMicros(750_000)
                        .setQualityInfo(AdGroupCriterion.QualityInfo.newBuilder().setQualityScore(7)), m);
        KeywordRow kw = RowMapping.keyword(kwRow);
        assertThat(kw.id()).isEqualTo("22~33");
        assertThat(kw.adGroupId()).isEqualTo("22");
        assertThat(kw.campaignId()).isEqualTo("1");
        assertThat(kw.matchType()).isEqualTo(MatchType.PHRASE);
        assertThat(kw.status()).isEqualTo("ENABLED");
        assertThat(kw.cpcBidMicros()).isEqualTo(750_000L);
        assertThat(kw.qualityScore()).isEqualTo(7);

        KeywordRow noBid = RowMapping.keyword(row(AdGroup.newBuilder().setId(22), campaign(1),
                AdGroupCriterion.newBuilder().setCriterionId(34).setKeyword(KeywordInfo.newBuilder().setText("x").setMatchType(KeywordMatchType.EXACT)), m));
        assertThat(noBid.cpcBidMicros()).isNull();
        assertThat(noBid.qualityScore()).isNull();

        AdRow ad = RowMapping.ad(row(AdGroup.newBuilder().setId(22), campaign(1), AdGroupAd.newBuilder()
                .setStatus(AdGroupAdStatus.ENABLED).setAdStrength(AdStrength.GOOD)
                .setPolicySummary(AdGroupAdPolicySummary.newBuilder().setApprovalStatus(PolicyApprovalStatus.APPROVED))
                .setAd(Ad.newBuilder().setId(55).addFinalUrls("https://acme.example/a")
                        .setResponsiveSearchAd(ResponsiveSearchAdInfo.newBuilder()
                                .addHeadlines(AdTextAsset.newBuilder().setText("H1")).addHeadlines(AdTextAsset.newBuilder().setText("H2"))
                                .addDescriptions(AdTextAsset.newBuilder().setText("D1"))))));
        assertThat(ad.id()).isEqualTo("22~55");
        assertThat(ad.adStrength()).isEqualTo("GOOD");
        assertThat(ad.policyStatus()).isEqualTo("APPROVED");
        assertThat(ad.headlines()).containsExactly("H1", "H2");
        assertThat(ad.descriptions()).containsExactly("D1");
        assertThat(ad.finalUrl()).isEqualTo("https://acme.example/a");
    }

    @Test
    @DisplayName("[MAPPING] recommendations: id is the last segment of the resource name, dismissed ones are skipped")
    void recommendationRows() {
        RecommendationRow r = RowMapping.recommendation(row(Recommendation.newBuilder()
                .setResourceName(C + "recommendations/abc123").setType(RecommendationType.CAMPAIGN_BUDGET).setCampaign(C + "campaigns/7")));
        assertThat(r.id()).isEqualTo("abc123");
        assertThat(r.type()).isEqualTo("CAMPAIGN_BUDGET");
        assertThat(r.campaignId()).isEqualTo("7");
        assertThat(r.description()).contains("campaign budget");

        assertThat(RowMapping.recommendation(row(Recommendation.newBuilder()
                .setResourceName(C + "recommendations/x").setType(RecommendationType.KEYWORD).setDismissed(true)))).isNull();
        assertThat(RowMapping.recommendation(row(Recommendation.newBuilder()
                .setResourceName(C + "recommendations/y").setType(RecommendationType.KEYWORD))).campaignId()).isNull();
    }

    @Test
    @DisplayName("[MAPPING] negative keyword rows carry the plain criterion id and where they are attached")
    void negativeRows() {
        NegativeRow c = RowMapping.campaignNegative(row(campaign(1), CampaignCriterion.newBuilder().setCriterionId(9001)
                .setNegative(true).setKeyword(KeywordInfo.newBuilder().setText("free").setMatchType(KeywordMatchType.PHRASE))));
        assertThat(c).isEqualTo(new NegativeRow("9001", EntityRef.campaign("1"), "free", MatchType.PHRASE));

        NegativeRow g = RowMapping.adGroupNegative(row(AdGroup.newBuilder().setId(22), AdGroupCriterion.newBuilder().setCriterionId(7001)
                .setNegative(true).setKeyword(KeywordInfo.newBuilder().setText("cheap").setMatchType(KeywordMatchType.EXACT))));
        assertThat(g).isEqualTo(new NegativeRow("7001", EntityRef.adGroup("22"), "cheap", MatchType.EXACT));
    }

    // ---- entity state ---------------------------------------------------------------------

    @Test
    @DisplayName("[MAPPING] BUDGET state: amount, shared flag, all campaigns using it, labels of owners")
    void budgetState() {
        GoogleAdsRow budget = row(CampaignBudget.newBuilder().setId(1001).setName("own").setAmountMicros(20_000_000)
                .setExplicitlyShared(false).setStatus(BudgetStatus.ENABLED));
        GoogleAdsRow user = row(campaign(1).addLabels(L_MANAGED).addLabels(L_OTHER));

        EntityState s = RowMapping.budgetState("1001", List.of(budget), List.of(user), LABELS).orElseThrow();

        assertThat(s.ref()).isEqualTo(EntityRef.budget("1001"));
        assertThat(s.status()).isEqualTo("ENABLED");
        assertThat(s.campaignId()).isEqualTo("1");
        assertThat(s.longAttr("amountMicros")).isEqualTo(20_000_000L);
        assertThat(s.attrs().get("explicitlyShared")).isEqualTo(false);
        assertThat(s.listAttr("campaignIds")).containsExactly("1");
        assertThat(s.stringAttr("status")).isEqualTo("ENABLED");
        assertThat(s.campaignLabels()).containsExactlyInAnyOrder("agent-managed", "seasonal");
        assertThat(s.chainLabels()).containsExactlyInAnyOrder("agent-managed", "seasonal");

        assertThat(RowMapping.budgetState("1", List.of(), List.of(), LABELS)).isEmpty();
    }

    @Test
    @DisplayName("[MAPPING] a budget shared by campaigns: scope label must be on all, a no-agent label on any one blocks")
    void sharedBudgetLabels() {
        GoogleAdsRow budget = row(CampaignBudget.newBuilder().setId(2000).setAmountMicros(5).setExplicitlyShared(true).setStatus(BudgetStatus.ENABLED));
        GoogleAdsRow a = row(campaign(5).addLabels(L_MANAGED));
        GoogleAdsRow b = row(campaign(6).addLabels(L_MANAGED).addLabels(L_NO_AGENT));
        GoogleAdsRow c = row(campaign(7));

        EntityState two = RowMapping.budgetState("2000", List.of(budget), List.of(a, b), LABELS).orElseThrow();
        assertThat(two.campaignId()).isNull();
        assertThat(two.listAttr("campaignIds")).containsExactly("5", "6");
        assertThat(two.campaignLabels()).containsExactly("agent-managed");
        assertThat(two.chainLabels()).containsExactlyInAnyOrder("agent-managed", "no-agent");

        EntityState withUnlabelled = RowMapping.budgetState("2000", List.of(budget), List.of(a, c), LABELS).orElseThrow();
        assertThat(withUnlabelled.campaignLabels()).isEmpty();   // not on every campaign, so not in scope

        EntityState unused = RowMapping.budgetState("2000", List.of(budget), List.of(), LABELS).orElseThrow();
        assertThat(unused.campaignLabels()).isEmpty();
        assertThat(unused.listAttr("campaignIds")).isEmpty();
    }

    @Test
    @DisplayName("[MAPPING] CAMPAIGN state: budget id, bidding strategy and target CPA, settings, labels")
    void campaignState() {
        GoogleAdsRow r = row(campaign(1).addLabels(L_MANAGED)
                .setAdvertisingChannelType(com.google.ads.googleads.v26.enums.AdvertisingChannelTypeEnum.AdvertisingChannelType.SEARCH)
                .setBiddingStrategyType(BiddingStrategyType.TARGET_CPA)
                .setTargetCpa(TargetCpa.newBuilder().setTargetCpaMicros(8_000_000))
                .setNetworkSettings(Campaign.NetworkSettings.newBuilder().setTargetGoogleSearch(true).setTargetContentNetwork(false)
                        .setTargetSearchNetwork(false).setTargetPartnerSearchNetwork(false))
                .setGeoTargetTypeSetting(Campaign.GeoTargetTypeSetting.newBuilder()
                        .setPositiveGeoTargetType(com.google.ads.googleads.v26.enums.PositiveGeoTargetTypeEnum.PositiveGeoTargetType.PRESENCE)));

        EntityState s = RowMapping.campaignState(List.of(r), LABELS).orElseThrow();

        assertThat(s.ref()).isEqualTo(EntityRef.campaign("1"));
        assertThat(s.status()).isEqualTo("ENABLED");
        assertThat(s.campaignId()).isEqualTo("1");
        assertThat(s.adGroupId()).isNull();
        assertThat(s.stringAttr("budgetId")).isEqualTo("1001");
        assertThat(s.stringAttr("biddingStrategy")).isEqualTo("TARGET_CPA");
        assertThat(s.stringAttr("biddingStrategyType")).isEqualTo("TARGET_CPA");
        assertThat(s.longAttr("targetCpaMicros")).isEqualTo(8_000_000L);
        assertThat(s.stringAttr("status")).isEqualTo("ENABLED");
        assertThat(s.stringAttr("channelType")).isEqualTo("SEARCH");
        assertThat(s.attrs().get("targetContentNetwork")).isEqualTo(false);
        assertThat(s.stringAttr("positiveGeoTargetType")).isEqualTo("PRESENCE");
        assertThat(s.campaignLabels()).containsExactly("agent-managed");
        assertThat(s.chainLabels()).containsExactly("agent-managed");

        EntityState manual = RowMapping.campaignState(List.of(row(campaign(2).setManualCpc(ManualCpc.getDefaultInstance()))), LABELS).orElseThrow();
        assertThat(manual.stringAttr("biddingStrategy")).isEqualTo("MANUAL_CPC");
        assertThat(manual.attrs()).doesNotContainKey("targetCpaMicros");

        EntityState viaMaxConversions = RowMapping.campaignState(List.of(row(campaign(3).setMaximizeConversions(
                MaximizeConversions.newBuilder().setTargetCpaMicros(3_000_000)))), LABELS).orElseThrow();
        assertThat(viaMaxConversions.stringAttr("biddingStrategy")).isEqualTo("MAXIMIZE_CONVERSIONS");
        assertThat(viaMaxConversions.longAttr("targetCpaMicros")).isEqualTo(3_000_000L);

        assertThat(RowMapping.campaignState(List.of(), LABELS)).isEmpty();
    }

    @Test
    @DisplayName("[MAPPING] AD_GROUP state: chain labels cover campaign and ad group")
    void adGroupState() {
        GoogleAdsRow r = row(AdGroup.newBuilder().setId(22).setName("Brand").setStatus(AdGroupStatus.PAUSED).addLabels(L_NO_AGENT),
                campaign(1).addLabels(L_MANAGED));

        EntityState s = RowMapping.adGroupState(List.of(r), LABELS).orElseThrow();

        assertThat(s.ref()).isEqualTo(EntityRef.adGroup("22"));
        assertThat(s.status()).isEqualTo("PAUSED");
        assertThat(s.campaignId()).isEqualTo("1");
        assertThat(s.adGroupId()).isEqualTo("22");
        assertThat(s.campaignLabels()).containsExactly("agent-managed");
        assertThat(s.chainLabels()).containsExactlyInAnyOrder("agent-managed", "no-agent");
        assertThat(RowMapping.adGroupState(List.of(), LABELS)).isEmpty();
    }

    @Test
    @DisplayName("[MAPPING] AD state: enabled ad count of the ad group, ad id is adGroupId~adId, ad labels in the chain")
    void adState() {
        GoogleAdsRow target = row(AdGroup.newBuilder().setId(22), campaign(1).addLabels(L_MANAGED),
                AdGroupAd.newBuilder().setStatus(AdGroupAdStatus.ENABLED).setAd(Ad.newBuilder().setId(55)).addLabels(L_NO_AGENT));
        GoogleAdsRow paused = row(AdGroup.newBuilder().setId(22), campaign(1),
                AdGroupAd.newBuilder().setStatus(AdGroupAdStatus.PAUSED).setAd(Ad.newBuilder().setId(56)));
        GoogleAdsRow other = row(AdGroup.newBuilder().setId(22), campaign(1),
                AdGroupAd.newBuilder().setStatus(AdGroupAdStatus.ENABLED).setAd(Ad.newBuilder().setId(57)));

        EntityState s = RowMapping.adState("55", List.of(target, paused, other), LABELS).orElseThrow();

        assertThat(s.ref()).isEqualTo(EntityRef.ad("22~55"));
        assertThat(s.status()).isEqualTo("ENABLED");
        assertThat(s.longAttr("adGroupEnabledAds")).isEqualTo(2L);
        assertThat(s.campaignId()).isEqualTo("1");
        assertThat(s.adGroupId()).isEqualTo("22");
        assertThat(s.campaignLabels()).containsExactly("agent-managed");
        assertThat(s.chainLabels()).containsExactlyInAnyOrder("agent-managed", "no-agent");

        EntityState pausedState = RowMapping.adState("56", List.of(target, paused, other), LABELS).orElseThrow();
        assertThat(pausedState.status()).isEqualTo("PAUSED");
        assertThat(pausedState.longAttr("adGroupEnabledAds")).isEqualTo(2L);

        assertThat(RowMapping.adState("999", List.of(target), LABELS)).isEmpty();
    }

    @Test
    @DisplayName("[MAPPING] KEYWORD state: text, match type, bid, metrics, enabled keyword count, campaign bidding, labels")
    void keywordState() {
        GoogleAdsRow r = row(
                AdGroupCriterion.newBuilder().setCriterionId(33).setStatus(AdGroupCriterionStatus.ENABLED).setType(CriterionType.KEYWORD)
                        .setKeyword(KeywordInfo.newBuilder().setText("acme shoes").setMatchType(KeywordMatchType.EXACT))
                        .setCpcBidMicros(1_200_000).addLabels(L_OTHER),
                AdGroup.newBuilder().setId(22).addLabels(L_NO_AGENT),
                campaign(1).addLabels(L_MANAGED).setTargetCpa(TargetCpa.newBuilder().setTargetCpaMicros(5_000_000)));
        RowMapping.KeywordMetrics metrics = RowMapping.KeywordMetrics.sum(List.of(
                row(Metrics.newBuilder().setClicks(60).setCostMicros(30_000_000).setConversions(1.5)),
                row(Metrics.newBuilder().setClicks(41).setCostMicros(11_000_000).setConversions(0.5))));

        EntityState s = RowMapping.keywordState(List.of(r), 12, metrics, LABELS).orElseThrow();

        assertThat(s.ref()).isEqualTo(EntityRef.keyword("22~33"));
        assertThat(s.status()).isEqualTo("ENABLED");
        assertThat(s.campaignId()).isEqualTo("1");
        assertThat(s.adGroupId()).isEqualTo("22");
        assertThat(s.stringAttr("text")).isEqualTo("acme shoes");
        assertThat(s.stringAttr("matchType")).isEqualTo("EXACT");
        assertThat(s.longAttr("cpcBidMicros")).isEqualTo(1_200_000L);
        assertThat(s.longAttr("adGroupActiveKeywords")).isEqualTo(12L);
        assertThat(s.longAttr("clicks")).isEqualTo(101L);
        assertThat(s.longAttr("costMicros")).isEqualTo(41_000_000L);
        assertThat(s.doubleAttr("conversions")).isEqualTo(2.0);
        assertThat(s.stringAttr("biddingStrategy")).isEqualTo("TARGET_CPA");
        assertThat(s.stringAttr("status")).isEqualTo("ENABLED");
        assertThat(s.campaignLabels()).containsExactly("agent-managed");
        assertThat(s.chainLabels()).containsExactlyInAnyOrder("agent-managed", "no-agent", "seasonal");
    }

    @Test
    @DisplayName("[MAPPING] KEYWORD state: no own bid means no cpcBidMicros attr, no metric rows mean zero, negatives are not keywords")
    void keywordStateEdges() {
        GoogleAdsRow noBid = row(
                AdGroupCriterion.newBuilder().setCriterionId(34).setStatus(AdGroupCriterionStatus.PAUSED).setType(CriterionType.KEYWORD)
                        .setKeyword(KeywordInfo.newBuilder().setText("x").setMatchType(KeywordMatchType.BROAD)),
                AdGroup.newBuilder().setId(22), campaign(1));

        EntityState s = RowMapping.keywordState(List.of(noBid), 0, RowMapping.KeywordMetrics.sum(List.of()), LABELS).orElseThrow();
        assertThat(s.attrs()).doesNotContainKey("cpcBidMicros");
        assertThat(s.longAttr("clicks")).isZero();
        assertThat(s.doubleAttr("conversions")).isZero();
        assertThat(s.status()).isEqualTo("PAUSED");

        GoogleAdsRow negative = row(
                AdGroupCriterion.newBuilder().setCriterionId(35).setType(CriterionType.KEYWORD).setNegative(true)
                        .setKeyword(KeywordInfo.newBuilder().setText("free").setMatchType(KeywordMatchType.PHRASE)),
                AdGroup.newBuilder().setId(22), campaign(1));
        assertThat(RowMapping.keywordState(List.of(negative), 0, RowMapping.KeywordMetrics.ZERO, LABELS)).isEmpty();
        assertThat(RowMapping.keywordState(List.of(), 0, RowMapping.KeywordMetrics.ZERO, LABELS)).isEmpty();
    }

    @Test
    @DisplayName("[MAPPING] RECOMMENDATION state: type, status and the owning campaign's labels")
    void recommendationState() {
        GoogleAdsRow r = row(Recommendation.newBuilder().setResourceName(C + "recommendations/abc123")
                .setType(RecommendationType.KEYWORD).setCampaign(C + "campaigns/7"));
        GoogleAdsRow owner = row(campaign(7).addLabels(L_MANAGED));

        EntityState s = RowMapping.recommendationState(List.of(r), owner, LABELS).orElseThrow();

        assertThat(s.ref()).isEqualTo(new EntityRef(EntityKind.RECOMMENDATION, "abc123"));
        assertThat(s.status()).isEqualTo("ACTIVE");
        assertThat(s.stringAttr("type")).isEqualTo("KEYWORD");
        assertThat(s.campaignId()).isEqualTo("7");
        assertThat(s.campaignLabels()).containsExactly("agent-managed");

        EntityState accountLevel = RowMapping.recommendationState(List.of(row(Recommendation.newBuilder()
                .setResourceName(C + "recommendations/zzz").setType(RecommendationType.KEYWORD).setDismissed(true))), null, LABELS).orElseThrow();
        assertThat(accountLevel.status()).isEqualTo("DISMISSED");
        assertThat(accountLevel.campaignId()).isNull();
        assertThat(accountLevel.campaignLabels()).isEmpty();
    }

    @Test
    @DisplayName("[MAPPING] label names are read from label rows and label resource names are collected from every level")
    void labelHelpers() {
        Map<String, String> names = RowMapping.labelNames(List.of(
                row(Label.newBuilder().setResourceName(L_MANAGED).setName("agent-managed")),
                row(Label.newBuilder().setResourceName(L_NO_AGENT).setName("no-agent"))));
        assertThat(names).containsEntry(L_MANAGED, "agent-managed").containsEntry(L_NO_AGENT, "no-agent");

        GoogleAdsRow r = row(campaign(1).addLabels(L_MANAGED), AdGroup.newBuilder().setId(2).addLabels(L_NO_AGENT),
                AdGroupAd.newBuilder().addLabels(L_OTHER), AdGroupCriterion.newBuilder().addLabels(C + "labels/13"));
        assertThat(RowMapping.labelResourceNames(List.of(r))).containsExactlyInAnyOrder(L_MANAGED, L_NO_AGENT, L_OTHER, C + "labels/13");
        assertThat(RowMapping.labelResourceNames(List.of(row(campaign(1))))).isEmpty();
    }

    // ---- run_report flattening ------------------------------------------------------------

    @Test
    @DisplayName("[MAPPING] run_report rows are flattened by selected field path: numbers, enum names, lists, unset as null")
    void reportFlattening() {
        GoogleAdsRow r = row(campaign(5).setStatus(CampaignStatus.PAUSED).addLabels(L_MANAGED).addLabels(L_OTHER),
                metrics(12_345_000, 99).setConversions(1.5), Segments.newBuilder().setDate("2026-11-03"));
        List<String> paths = List.of("campaign.id", "campaign.name", "campaign.status", "campaign.labels", "metrics.cost_micros",
                "metrics.conversions", "segments.date", "campaign.tracking_url_template", "ad_group.name");

        Map<String, Object> flat = ReportRows.flatten(r, paths);

        assertThat(flat.keySet()).containsExactlyElementsOf(paths);
        assertThat(flat.get("campaign.id")).isEqualTo(5L);
        assertThat(flat.get("campaign.name")).isEqualTo("Campaign 5");
        assertThat(flat.get("campaign.status")).isEqualTo("PAUSED");
        assertThat(flat.get("campaign.labels")).isEqualTo(List.of(L_MANAGED, L_OTHER));
        assertThat(flat.get("metrics.cost_micros")).isEqualTo(12_345_000L);
        assertThat(flat.get("metrics.conversions")).isEqualTo(1.5);
        assertThat(flat.get("segments.date")).isEqualTo("2026-11-03");
        assertThat(flat.get("campaign.tracking_url_template")).isNull(); // optional field that is not set
        assertThat(flat.get("ad_group.name")).isNull();                  // whole message not set
    }

    @Test
    @DisplayName("[MAPPING] run_report flattens repeated message fields to lists of values")
    void reportFlatteningRepeated() {
        GoogleAdsRow r = row(AdGroupAd.newBuilder().setAd(Ad.newBuilder().setId(55)
                .setResponsiveSearchAd(ResponsiveSearchAdInfo.newBuilder()
                        .addHeadlines(AdTextAsset.newBuilder().setText("H1")).addHeadlines(AdTextAsset.newBuilder().setText("H2")))));

        Map<String, Object> flat = ReportRows.flatten(r, List.of("ad_group_ad.ad.responsive_search_ad.headlines",
                "ad_group_ad.ad.responsive_search_ad.headlines.text"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> headlines = (List<Map<String, Object>>) flat.get("ad_group_ad.ad.responsive_search_ad.headlines");
        assertThat(headlines).extracting(h -> h.get("text")).containsExactly("H1", "H2");
        assertThat(flat.get("ad_group_ad.ad.responsive_search_ad.headlines.text")).isEqualTo(List.of("H1", "H2"));
    }

    @Test
    @DisplayName("[AC-40] conversion actions join their definition with the 7 and 30 day results; an action with no result row has none")
    void conversionActionsJoinResults() {
        List<app.autopilot.ads.AdsGateway.ConversionActionInfo> out = RowMapping.conversionActions(
                List.of(conversionActionRow(900, "Paid subscription", "SUBSCRIBE_PAID", true, 38), conversionActionRow(901, "Sign-up", "SIGNUP", false, 0)),
                List.of(conversionResultRow(900, 2, 76), conversionResultRow(900, 1, 38)),
                List.of(conversionResultRow(900, 9, 342)));

        assertThat(out).hasSize(2);
        var paid = out.get(0);
        assertThat(paid.id()).isEqualTo("900");
        assertThat(paid.name()).isEqualTo("Paid subscription");
        assertThat(paid.type()).isEqualTo("UPLOAD_CLICKS");
        assertThat(paid.status()).isEqualTo("ENABLED");
        assertThat(paid.category()).isEqualTo("SUBSCRIBE_PAID");
        assertThat(paid.primaryForGoal()).isTrue();
        assertThat(paid.countingType()).isEqualTo("ONE_PER_CLICK");
        assertThat(paid.defaultValue()).isEqualTo(38.0);
        assertThat(paid.alwaysUseDefaultValue()).isFalse();
        assertThat(paid.conversions7d()).as("rows of the same action are summed").isEqualTo(3.0);
        assertThat(paid.conversionValue7d()).isEqualTo(114.0);
        assertThat(paid.conversions30d()).isEqualTo(9.0);
        assertThat(paid.conversionValue30d()).isEqualTo(342.0);
        var signUp = out.get(1);
        assertThat(signUp.primaryForGoal()).isFalse();
        assertThat(signUp.conversions30d()).isZero();
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

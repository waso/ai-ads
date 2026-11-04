package app.autopilot.ads.google;

import app.autopilot.domain.BiddingStrategy;
import app.autopilot.domain.ChangeRequest;
import app.autopilot.domain.ChangeRequest.AddAdGroup;
import app.autopilot.domain.ChangeRequest.AddKeywords;
import app.autopilot.domain.ChangeRequest.AddNegativeKeywords;
import app.autopilot.domain.ChangeRequest.AdjustKeywordBid;
import app.autopilot.domain.ChangeRequest.ApplyRecommendation;
import app.autopilot.domain.ChangeRequest.CampaignSpec;
import app.autopilot.domain.ChangeRequest.ChangeBiddingStrategy;
import app.autopilot.domain.ChangeRequest.CreateAd;
import app.autopilot.domain.ChangeRequest.CreateCampaign;
import app.autopilot.domain.ChangeRequest.DecreaseBudget;
import app.autopilot.domain.ChangeRequest.EmergencyPauseCampaign;
import app.autopilot.domain.ChangeRequest.EnableEntity;
import app.autopilot.domain.ChangeRequest.EuPoliticalAds;
import app.autopilot.domain.ChangeRequest.IncreaseBudget;
import app.autopilot.domain.ChangeRequest.KeywordSpec;
import app.autopilot.domain.ChangeRequest.PauseAd;
import app.autopilot.domain.ChangeRequest.PauseEntity;
import app.autopilot.domain.ChangeRequest.PauseKeyword;
import app.autopilot.domain.ChangeRequest.RemoveNegativeKeywords;
import app.autopilot.domain.EntityRef;
import app.autopilot.domain.MatchType;

import com.google.ads.googleads.v26.enums.AdGroupAdStatusEnum.AdGroupAdStatus;
import com.google.ads.googleads.v26.enums.AdGroupCriterionStatusEnum.AdGroupCriterionStatus;
import com.google.ads.googleads.v26.enums.AdGroupStatusEnum.AdGroupStatus;
import com.google.ads.googleads.v26.enums.AdGroupTypeEnum.AdGroupType;
import com.google.ads.googleads.v26.enums.AdvertisingChannelTypeEnum.AdvertisingChannelType;
import com.google.ads.googleads.v26.enums.AssetAutomationStatusEnum.AssetAutomationStatus;
import com.google.ads.googleads.v26.enums.AssetAutomationTypeEnum.AssetAutomationType;
import com.google.ads.googleads.v26.enums.CampaignStatusEnum.CampaignStatus;
import com.google.ads.googleads.v26.enums.CriterionTypeEnum.CriterionType;
import com.google.ads.googleads.v26.enums.EuPoliticalAdvertisingStatusEnum.EuPoliticalAdvertisingStatus;
import com.google.ads.googleads.v26.enums.KeywordMatchTypeEnum.KeywordMatchType;
import com.google.ads.googleads.v26.enums.PositiveGeoTargetTypeEnum.PositiveGeoTargetType;
import com.google.ads.googleads.v26.resources.Campaign;
import com.google.ads.googleads.v26.services.AdGroupAdOperation;
import com.google.ads.googleads.v26.services.AdGroupCriterionOperation;
import com.google.ads.googleads.v26.services.ApplyRecommendationRequest;
import com.google.ads.googleads.v26.services.CampaignCriterionOperation;
import com.google.ads.googleads.v26.services.MutateGoogleAdsRequest;
import com.google.ads.googleads.v26.services.MutateGoogleAdsResponse;
import com.google.ads.googleads.v26.services.MutateOperation;
import com.google.ads.googleads.v26.services.MutateOperation.OperationCase;
import com.google.ads.googleads.v26.services.MutateOperationResponse;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.FieldMask;
import com.google.protobuf.Message;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleMutationsTest {

    private static final String CUSTOMER = "1234567890";
    private final GoogleMutations mutations = new GoogleMutations(CUSTOMER);

    // ---- helpers --------------------------------------------------------------------------

    private static CampaignSpec spec(BiddingStrategy bidding, EuPoliticalAds eu) {
        return new CampaignSpec(
                "Brand search PL", 25_000_000L, bidding, List.of(2616L, 1011419L), "Brand",
                List.of(new KeywordSpec("acme shoes", MatchType.EXACT, 1_200_000L),
                        new KeywordSpec("acme boots", MatchType.PHRASE, null)),
                List.of("Acme shoes", "Free delivery", "Official store"),
                List.of("Buy Acme shoes online.", "Fast delivery across Poland."),
                "https://acme.example/shoes", eu);
    }

    private static CreateCampaign createCampaign() {
        return new CreateCampaign(spec(BiddingStrategy.maximizeConversions(), EuPoliticalAds.NO_POLITICAL_ADS));
    }

    private static final List<KeywordSpec> BOOT_KEYWORDS = List.of(
            new KeywordSpec("winter boots", MatchType.PHRASE, 900_000L),
            new KeywordSpec("snow boots", MatchType.EXACT, null));
    private static final List<String> BOOT_HEADLINES = List.of("Winter boots", "Free delivery", "Official store");
    private static final List<String> BOOT_DESCRIPTIONS = List.of("Warm boots for the cold season.", "Fast delivery across Poland.");
    private static final String BOOT_URL = "https://acme.example/boots";

    private static AddAdGroup addAdGroup(Long cpcBidMicros) {
        return new AddAdGroup(EntityRef.campaign("11"), "Winter boots", BOOT_KEYWORDS, BOOT_HEADLINES, BOOT_DESCRIPTIONS, BOOT_URL, cpcBidMicros);
    }

    private List<MutateOperation> ops(ChangeRequest request) {
        return mutations.plan(request).operations();
    }

    private static Message sub(MutateOperation op) {
        return (Message) op.getAllFields().values().iterator().next();
    }

    private static boolean isRemove(MutateOperation op) {
        return hasOperationField(op, "remove");
    }

    private static boolean isUpdate(MutateOperation op) {
        return hasOperationField(op, "update");
    }

    /** Some operation types (labels) have no update field at all. */
    private static boolean hasOperationField(MutateOperation op, String name) {
        Message s = sub(op);
        FieldDescriptor field = s.getDescriptorForType().findFieldByName(name);
        return field != null && s.hasField(field);
    }

    private static Message resource(MutateOperation op, String field) {
        Message s = sub(op);
        return (Message) s.getField(s.getDescriptorForType().findFieldByName(field));
    }

    private static FieldMask maskOf(MutateOperation op) {
        Message s = sub(op);
        return (FieldMask) s.getField(s.getDescriptorForType().findFieldByName("update_mask"));
    }

    private static Set<String> setFieldNames(Message resource) {
        Set<String> names = new TreeSet<>();
        for (FieldDescriptor f : resource.getAllFields().keySet()) {
            names.add(f.getName());
        }
        names.remove("resource_name");
        return names;
    }

    /** One request of every kind, to check rules that must hold for all of them. */
    private List<ChangeRequest> oneOfEach() {
        return List.of(
                new AddNegativeKeywords(EntityRef.campaign("11"), List.of("free", "jobs"), MatchType.PHRASE),
                new AddNegativeKeywords(EntityRef.adGroup("22"), List.of("cheap"), MatchType.EXACT),
                new PauseKeyword(EntityRef.keyword("22~33")),
                new AdjustKeywordBid(EntityRef.keyword("22~33"), 900_000L),
                new DecreaseBudget(EntityRef.budget("44"), 10_000_000L),
                new IncreaseBudget(EntityRef.budget("44"), 12_000_000L),
                new EmergencyPauseCampaign(EntityRef.campaign("11")),
                new PauseEntity(EntityRef.adGroup("22")),
                new PauseAd(EntityRef.ad("22~55")),
                new EnableEntity(EntityRef.campaign("11")),
                new AddKeywords(EntityRef.adGroup("22"), List.of(new KeywordSpec("acme", MatchType.BROAD, null))),
                new CreateAd(EntityRef.adGroup("22"), List.of("a1", "a2", "a3"), List.of("d1", "d2"), "https://acme.example"),
                createCampaign(),
                addAdGroup(750_000L),
                new ChangeBiddingStrategy(EntityRef.campaign("11"), BiddingStrategy.targetCpa(5_000_000L)),
                new ApplyRecommendation(new EntityRef(app.autopilot.domain.EntityKind.RECOMMENDATION, "abc123")),
                new RemoveNegativeKeywords(EntityRef.campaign("11"), List.of("901", "902")));
    }

    // ---- AC-36: create_campaign -----------------------------------------------------------

    @Test
    @DisplayName("[AC-36] create_campaign is one request with every operation in dependency order")
    void createCampaignIsOneRequestInDependencyOrder() {
        GoogleMutations.Plan plan = mutations.plan(createCampaign());
        MutateGoogleAdsRequest request = GoogleMutations.buildRequest(CUSTOMER, plan.operations(), false);

        List<OperationCase> order = request.getMutateOperationsList().stream().map(MutateOperation::getOperationCase).toList();
        assertThat(order).containsExactly(
                OperationCase.CAMPAIGN_BUDGET_OPERATION,
                OperationCase.CAMPAIGN_OPERATION,
                OperationCase.CAMPAIGN_CRITERION_OPERATION,   // 2 locations
                OperationCase.CAMPAIGN_CRITERION_OPERATION,
                OperationCase.AD_GROUP_OPERATION,
                OperationCase.AD_GROUP_CRITERION_OPERATION,   // 2 keywords
                OperationCase.AD_GROUP_CRITERION_OPERATION,
                OperationCase.AD_GROUP_AD_OPERATION,
                OperationCase.LABEL_OPERATION,                // label did not exist
                OperationCase.CAMPAIGN_LABEL_OPERATION);
        assertThat(request.getCustomerId()).isEqualTo(CUSTOMER);
        assertThat(plan.isRecommendation()).isFalse();
    }

    @Test
    @DisplayName("[AC-36] create_campaign makes a PAUSED Search campaign that targets Google Search only")
    void createCampaignIsPausedAndSearchOnly() {
        Campaign campaign = campaignOf(createCampaign());

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.PAUSED);
        assertThat(campaign.getAdvertisingChannelType()).isEqualTo(AdvertisingChannelType.SEARCH);
        var net = campaign.getNetworkSettings();
        assertThat(net.getTargetGoogleSearch()).isTrue();
        assertThat(net.hasTargetContentNetwork()).isTrue();      // set explicitly, not left to a default
        assertThat(net.getTargetContentNetwork()).isFalse();
        assertThat(net.hasTargetSearchNetwork()).isTrue();
        assertThat(net.getTargetSearchNetwork()).isFalse();
        assertThat(net.hasTargetPartnerSearchNetwork()).isTrue();
        assertThat(net.getTargetPartnerSearchNetwork()).isFalse();
    }

    @Test
    @DisplayName("[AC-36] create_campaign targets by location presence, not interest")
    void createCampaignUsesPresenceTargeting() {
        Campaign campaign = campaignOf(createCampaign());
        assertThat(campaign.getGeoTargetTypeSetting().getPositiveGeoTargetType()).isEqualTo(PositiveGeoTargetType.PRESENCE);
    }

    @Test
    @DisplayName("[AC-36] create_campaign sends location criteria and never a language criterion")
    void createCampaignHasNoLanguageCriteria() {
        List<MutateOperation> all = ops(createCampaign());

        List<CampaignCriterionOperation> campaignCriteria = all.stream()
                .filter(MutateOperation::hasCampaignCriterionOperation).map(MutateOperation::getCampaignCriterionOperation).toList();
        assertThat(campaignCriteria).hasSize(2)
                .allSatisfy(op -> {
                    var c = op.getCreate();
                    assertThat(c.hasLocation()).isTrue();
                    assertThat(c.hasLanguage()).isFalse();
                    assertThat(c.getType()).isNotEqualTo(CriterionType.LANGUAGE);
                    assertThat(c.getNegative()).isFalse();
                });
        assertThat(campaignCriteria.stream().map(o -> o.getCreate().getLocation().getGeoTargetConstant()))
                .containsExactly("geoTargetConstants/2616", "geoTargetConstants/1011419");
        // nothing anywhere in the request mentions a language
        assertThat(all.stream().map(Object::toString)).noneMatch(s -> s.contains("language"));
    }

    @Test
    @DisplayName("[AC-36] create_campaign sets the EU political advertising declaration from the spec")
    void createCampaignSetsEuPoliticalDeclaration() {
        Campaign none = campaignOf(new CreateCampaign(spec(BiddingStrategy.manualCpc(), EuPoliticalAds.NO_POLITICAL_ADS)));
        Campaign has = campaignOf(new CreateCampaign(spec(BiddingStrategy.manualCpc(), EuPoliticalAds.HAS_POLITICAL_ADS)));

        assertThat(none.getContainsEuPoliticalAdvertising())
                .isEqualTo(EuPoliticalAdvertisingStatus.DOES_NOT_CONTAIN_EU_POLITICAL_ADVERTISING);
        assertThat(has.getContainsEuPoliticalAdvertising())
                .isEqualTo(EuPoliticalAdvertisingStatus.CONTAINS_EU_POLITICAL_ADVERTISING);
    }

    @Test
    @DisplayName("[AC-36] create_campaign budget is its own (not shared) and carries the daily amount")
    void createCampaignBudgetIsNotShared() {
        var budget = ops(createCampaign()).get(0).getCampaignBudgetOperation().getCreate();

        assertThat(budget.hasExplicitlyShared()).isTrue();
        assertThat(budget.getExplicitlyShared()).isFalse();
        assertThat(budget.getAmountMicros()).isEqualTo(25_000_000L);
    }

    @Test
    @DisplayName("[AC-36] create_campaign temporary resource names are negative and consistent")
    void createCampaignTemporaryNamesAreConsistent() {
        List<MutateOperation> all = ops(createCampaign());
        var budget = all.get(0).getCampaignBudgetOperation().getCreate();
        var campaign = all.get(1).getCampaignOperation().getCreate();
        var adGroup = all.stream().filter(MutateOperation::hasAdGroupOperation).findFirst().orElseThrow()
                .getAdGroupOperation().getCreate();

        assertThat(budget.getResourceName()).isEqualTo("customers/1234567890/campaignBudgets/-1");
        assertThat(campaign.getResourceName()).isEqualTo("customers/1234567890/campaigns/-2");
        assertThat(adGroup.getResourceName()).isEqualTo("customers/1234567890/adGroups/-3");
        assertThat(campaign.getCampaignBudget()).isEqualTo(budget.getResourceName());
        assertThat(adGroup.getCampaign()).isEqualTo(campaign.getResourceName());

        for (MutateOperation op : all) {
            if (op.hasCampaignCriterionOperation()) {
                assertThat(op.getCampaignCriterionOperation().getCreate().getCampaign()).isEqualTo(campaign.getResourceName());
            }
            if (op.hasAdGroupCriterionOperation()) {
                assertThat(op.getAdGroupCriterionOperation().getCreate().getAdGroup()).isEqualTo(adGroup.getResourceName());
            }
            if (op.hasAdGroupAdOperation()) {
                assertThat(op.getAdGroupAdOperation().getCreate().getAdGroup()).isEqualTo(adGroup.getResourceName());
            }
            if (op.hasCampaignLabelOperation()) {
                assertThat(op.getCampaignLabelOperation().getCreate().getCampaign()).isEqualTo(campaign.getResourceName());
            }
        }
        // every temporary id in the request is negative and each distinct resource has its own
        Matcher m = Pattern.compile("customers/1234567890/[A-Za-z]+/(-?\\d+)").matcher(
                all.stream().map(Object::toString).reduce("", String::concat));
        Set<String> ids = new TreeSet<>();
        while (m.find()) {
            assertThat(Long.parseLong(m.group(1))).isNegative();
            ids.add(m.group(1));
        }
        assertThat(ids).containsExactlyInAnyOrder("-1", "-2", "-3", "-4");
    }

    @Test
    @DisplayName("[AC-36] create_campaign includes the ad group, its keywords and a responsive search ad")
    void createCampaignIncludesAdGroupKeywordsAndAd() {
        List<MutateOperation> all = ops(createCampaign());

        var adGroup = all.stream().filter(MutateOperation::hasAdGroupOperation).findFirst().orElseThrow()
                .getAdGroupOperation().getCreate();
        assertThat(adGroup.getName()).isEqualTo("Brand");
        assertThat(adGroup.getStatus()).isEqualTo(AdGroupStatus.ENABLED);

        List<AdGroupCriterionOperation> keywords = all.stream().filter(MutateOperation::hasAdGroupCriterionOperation)
                .map(MutateOperation::getAdGroupCriterionOperation).toList();
        assertThat(keywords).hasSize(2);
        var first = keywords.get(0).getCreate();
        assertThat(first.getKeyword().getText()).isEqualTo("acme shoes");
        assertThat(first.getKeyword().getMatchType()).isEqualTo(KeywordMatchType.EXACT);
        assertThat(first.getCpcBidMicros()).isEqualTo(1_200_000L);
        assertThat(first.getNegative()).isFalse();
        var second = keywords.get(1).getCreate();
        assertThat(second.getKeyword().getMatchType()).isEqualTo(KeywordMatchType.PHRASE);
        assertThat(second.hasCpcBidMicros()).isFalse();

        AdGroupAdOperation adOp = all.stream().filter(MutateOperation::hasAdGroupAdOperation).findFirst().orElseThrow()
                .getAdGroupAdOperation();
        var ad = adOp.getCreate();
        assertThat(ad.getStatus()).isEqualTo(AdGroupAdStatus.ENABLED);
        assertThat(ad.getAd().getFinalUrlsList()).containsExactly("https://acme.example/shoes");
        assertThat(ad.getAd().getResponsiveSearchAd().getHeadlinesList().stream().map(h -> h.getText()))
                .containsExactly("Acme shoes", "Free delivery", "Official store");
        assertThat(ad.getAd().getResponsiveSearchAd().getDescriptionsList().stream().map(d -> d.getText()))
                .containsExactly("Buy Acme shoes online.", "Fast delivery across Poland.");
    }

    @Test
    @DisplayName("[AC-36] create_campaign opts out of AI Max, text asset automation and final URL expansion")
    void createCampaignOptsOutOfAutomation() {
        Campaign campaign = campaignOf(createCampaign());

        assertThat(campaign.getAiMaxSetting().hasEnableAiMax()).isTrue();
        assertThat(campaign.getAiMaxSetting().getEnableAiMax()).isFalse();
        assertThat(campaign.getAssetAutomationSettingsList())
                .extracting(s -> s.getAssetAutomationType(), s -> s.getAssetAutomationStatus())
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(AssetAutomationType.TEXT_ASSET_AUTOMATION, AssetAutomationStatus.OPTED_OUT),
                        org.assertj.core.groups.Tuple.tuple(AssetAutomationType.FINAL_URL_EXPANSION_TEXT_ASSET_AUTOMATION, AssetAutomationStatus.OPTED_OUT));
    }

    @Test
    @DisplayName("[AC-36] the automation opt-outs can be switched off if Google rejects them")
    void automationOptOutsCanBeSwitchedOff() {
        GoogleMutations plain = new GoogleMutations(CUSTOMER, "agent-managed", false);
        Campaign campaign = plain.plan(createCampaign()).operations().get(1).getCampaignOperation().getCreate();

        assertThat(campaign.hasAiMaxSetting()).isFalse();
        assertThat(campaign.getAssetAutomationSettingsCount()).isZero();
        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.PAUSED); // the hard rules stay
    }

    @Test
    @DisplayName("[AC-36] create_campaign sets the bidding strategy from the spec (manual CPC with enhanced CPC off)")
    void createCampaignBidding() {
        Campaign manual = campaignOf(new CreateCampaign(spec(BiddingStrategy.manualCpc(), EuPoliticalAds.NO_POLITICAL_ADS)));
        assertThat(manual.getCampaignBiddingStrategyCase()).isEqualTo(Campaign.CampaignBiddingStrategyCase.MANUAL_CPC);
        assertThat(manual.getManualCpc().hasEnhancedCpcEnabled()).isTrue();
        assertThat(manual.getManualCpc().getEnhancedCpcEnabled()).isFalse();

        Campaign maxConv = campaignOf(createCampaign());
        assertThat(maxConv.getCampaignBiddingStrategyCase()).isEqualTo(Campaign.CampaignBiddingStrategyCase.MAXIMIZE_CONVERSIONS);

        Campaign cpa = campaignOf(new CreateCampaign(spec(BiddingStrategy.targetCpa(7_000_000L), EuPoliticalAds.NO_POLITICAL_ADS)));
        assertThat(cpa.getTargetCpa().getTargetCpaMicros()).isEqualTo(7_000_000L);

        Campaign roas = campaignOf(new CreateCampaign(spec(
                new BiddingStrategy(BiddingStrategy.Type.TARGET_ROAS, null, 3.5), EuPoliticalAds.NO_POLITICAL_ADS)));
        assertThat(roas.getTargetRoas().getTargetRoas()).isEqualTo(3.5);

        Campaign value = campaignOf(new CreateCampaign(spec(
                new BiddingStrategy(BiddingStrategy.Type.MAXIMIZE_CONVERSION_VALUE, null, null), EuPoliticalAds.NO_POLITICAL_ADS)));
        assertThat(value.getCampaignBiddingStrategyCase()).isEqualTo(Campaign.CampaignBiddingStrategyCase.MAXIMIZE_CONVERSION_VALUE);
    }

    @Test
    @DisplayName("[AC-36] manual CPC campaigns get an ad group default bid from the highest keyword bid")
    void manualCpcAdGroupDefaultBid() {
        var adGroup = ops(new CreateCampaign(spec(BiddingStrategy.manualCpc(), EuPoliticalAds.NO_POLITICAL_ADS))).stream()
                .filter(MutateOperation::hasAdGroupOperation).findFirst().orElseThrow().getAdGroupOperation().getCreate();
        assertThat(adGroup.getCpcBidMicros()).isEqualTo(1_200_000L);

        var automated = ops(createCampaign()).stream()
                .filter(MutateOperation::hasAdGroupOperation).findFirst().orElseThrow().getAdGroupOperation().getCreate();
        assertThat(automated.hasCpcBidMicros()).isFalse();
    }

    @Test
    @DisplayName("[AC-36] new campaigns get the agent-managed label inside the same request, creating the label if needed")
    void createCampaignLabelWhenMissing() {
        List<MutateOperation> all = ops(createCampaign());
        MutateOperation labelOp = all.get(all.size() - 2);
        MutateOperation attach = all.get(all.size() - 1);

        assertThat(labelOp.hasLabelOperation()).isTrue();
        var label = labelOp.getLabelOperation().getCreate();
        assertThat(label.getName()).isEqualTo("agent-managed");
        assertThat(label.getResourceName()).isEqualTo("customers/1234567890/labels/-4");
        assertThat(attach.getCampaignLabelOperation().getCreate().getLabel()).isEqualTo(label.getResourceName());
        assertThat(attach.getCampaignLabelOperation().getCreate().getCampaign()).isEqualTo("customers/1234567890/campaigns/-2");
    }

    @Test
    @DisplayName("[AC-36] an existing agent-managed label is attached, not created again")
    void createCampaignLabelWhenPresent() {
        List<MutateOperation> all = mutations.plan(createCampaign(), "customers/1234567890/labels/777").operations();

        assertThat(all).noneMatch(MutateOperation::hasLabelOperation);
        MutateOperation attach = all.get(all.size() - 1);
        assertThat(attach.getCampaignLabelOperation().getCreate().getLabel()).isEqualTo("customers/1234567890/labels/777");
        assertThat(all.get(all.size() - 2).hasAdGroupAdOperation()).isTrue();
    }

    @Test
    @DisplayName("[AC-36] the label name is configurable and only campaign creation needs the label lookup")
    void labelNameIsConfigurable() {
        GoogleMutations custom = new GoogleMutations(CUSTOMER, "robot-owned");
        List<MutateOperation> all = custom.plan(createCampaign()).operations();
        assertThat(all.get(all.size() - 2).getLabelOperation().getCreate().getName()).isEqualTo("robot-owned");

        assertThat(mutations.managedLabel()).isEqualTo("agent-managed");
        assertThat(mutations.needsManagedLabelLookup(createCampaign())).isTrue();
        assertThat(mutations.needsManagedLabelLookup(new PauseKeyword(EntityRef.keyword("1~2")))).isFalse();

        List<MutateOperation> labelOps = mutations.ensureLabelOps("customers/1234567890/campaigns/55", null);
        assertThat(labelOps).hasSize(2);
        assertThat(labelOps.get(1).getCampaignLabelOperation().getCreate().getCampaign()).isEqualTo("customers/1234567890/campaigns/55");
        assertThat(mutations.ensureLabelOps("customers/1234567890/campaigns/55", "customers/1234567890/labels/9")).hasSize(1);
    }

    @Test
    @DisplayName("[AC-36] validate_only and partial_failure are set by the request builder")
    void requestBuilderFlags() {
        List<MutateOperation> ops = ops(createCampaign());

        MutateGoogleAdsRequest validate = GoogleMutations.buildRequest(CUSTOMER, ops, true);
        MutateGoogleAdsRequest real = GoogleMutations.buildRequest(CUSTOMER, ops, false);

        assertThat(validate.getValidateOnly()).isTrue();
        assertThat(real.getValidateOnly()).isFalse();
        assertThat(validate.getPartialFailure()).isFalse();
        assertThat(real.getPartialFailure()).isFalse();
        assertThat(real.getMutateOperationsList()).isEqualTo(ops);
        assertThatThrownBy(() -> GoogleMutations.buildRequest(CUSTOMER, List.of(), false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("[AC-36] only rollback_remove_negative_keywords ever emits a REMOVE operation")
    void onlyRollbackRemoves() {
        for (ChangeRequest request : oneOfEach()) {
            boolean anyRemove = ops(request).stream().anyMatch(GoogleMutationsTest::isRemove);
            assertThat(anyRemove)
                    .as(request.tool())
                    .isEqualTo(request instanceof RemoveNegativeKeywords);
        }
    }

    @Test
    @DisplayName("[AC-36] mutation builders never touch billing, users, conversion actions or other accounts' resources")
    void neverTouchesForbiddenResources() {
        Set<OperationCase> allowed = Set.of(
                OperationCase.CAMPAIGN_BUDGET_OPERATION, OperationCase.CAMPAIGN_OPERATION,
                OperationCase.CAMPAIGN_CRITERION_OPERATION, OperationCase.AD_GROUP_OPERATION,
                OperationCase.AD_GROUP_CRITERION_OPERATION, OperationCase.AD_GROUP_AD_OPERATION,
                OperationCase.LABEL_OPERATION, OperationCase.CAMPAIGN_LABEL_OPERATION);
        for (ChangeRequest request : oneOfEach()) {
            for (MutateOperation op : ops(request)) {
                assertThat(op.getOperationCase()).as(request.tool()).isIn(allowed);
            }
            // every resource name in every operation belongs to the bound customer
            String text = ops(request).stream().map(Object::toString).reduce("", String::concat);
            Matcher m = Pattern.compile("customers/(\\d+)/").matcher(text);
            while (m.find()) {
                assertThat(m.group(1)).as(request.tool()).isEqualTo(CUSTOMER);
            }
        }
    }

    // ---- AC-36: add_ad_group --------------------------------------------------------------

    @Test
    @DisplayName("[AC-36] add_ad_group is one atomic request: ad group, keywords, then the ad, all creates, no REMOVE")
    void addAdGroupIsOneRequestInDependencyOrder() {
        GoogleMutations.Plan plan = mutations.plan(addAdGroup(null));
        MutateGoogleAdsRequest request = GoogleMutations.buildRequest(CUSTOMER, plan.operations(), false);

        List<OperationCase> order = request.getMutateOperationsList().stream().map(MutateOperation::getOperationCase).toList();
        assertThat(order).containsExactly(
                OperationCase.AD_GROUP_OPERATION,
                OperationCase.AD_GROUP_CRITERION_OPERATION,   // 2 keywords
                OperationCase.AD_GROUP_CRITERION_OPERATION,
                OperationCase.AD_GROUP_AD_OPERATION);
        assertThat(request.getCustomerId()).isEqualTo(CUSTOMER);
        assertThat(plan.isRecommendation()).isFalse();
        assertThat(plan.tags()).containsExactly(
                GoogleMutations.TAG_AD_GROUP, GoogleMutations.TAG_KEYWORD_CRITERION, GoogleMutations.TAG_KEYWORD_CRITERION, GoogleMutations.TAG_AD);
        assertThat(request.getMutateOperationsList())
                .allSatisfy(op -> assertThat(hasOperationField(op, "create")).isTrue())
                .noneMatch(GoogleMutationsTest::isRemove)
                .noneMatch(GoogleMutationsTest::isUpdate);
    }

    @Test
    @DisplayName("[AC-36] add_ad_group takes validate_only and partial_failure from the request builder")
    void addAdGroupRequestFlags() {
        List<MutateOperation> ops = ops(addAdGroup(null));

        MutateGoogleAdsRequest validate = GoogleMutations.buildRequest(CUSTOMER, ops, true);
        MutateGoogleAdsRequest real = GoogleMutations.buildRequest(CUSTOMER, ops, false);

        assertThat(validate.getValidateOnly()).isTrue();
        assertThat(real.getValidateOnly()).isFalse();
        assertThat(validate.getPartialFailure()).isFalse();
        assertThat(real.getPartialFailure()).isFalse();
        assertThat(real.getMutateOperationsList()).isEqualTo(ops);
    }

    @Test
    @DisplayName("[AC-36] add_ad_group temporary resource names are negative and consistent; the campaign is only referenced")
    void addAdGroupTemporaryNamesAreConsistent() {
        List<MutateOperation> all = ops(addAdGroup(null));
        var adGroup = all.get(0).getAdGroupOperation().getCreate();

        assertThat(adGroup.getResourceName()).isEqualTo("customers/1234567890/adGroups/-3");
        assertThat(adGroup.getCampaign()).isEqualTo("customers/1234567890/campaigns/11");
        for (MutateOperation op : all.subList(1, all.size())) {
            if (op.hasAdGroupCriterionOperation()) {
                assertThat(op.getAdGroupCriterionOperation().getCreate().getAdGroup()).isEqualTo(adGroup.getResourceName());
            } else {
                assertThat(op.getAdGroupAdOperation().getCreate().getAdGroup()).isEqualTo(adGroup.getResourceName());
            }
        }
        // the only resources named anywhere: the new ad group under a temporary (negative) id, the real campaign
        Matcher m = Pattern.compile("customers/1234567890/([A-Za-z]+)/(-?\\d+)").matcher(
                all.stream().map(Object::toString).reduce("", String::concat));
        Set<String> named = new TreeSet<>();
        while (m.find()) {
            named.add(m.group(1) + "/" + m.group(2));
            if (!m.group(1).equals("campaigns")) {
                assertThat(Long.parseLong(m.group(2))).isNegative();
            }
        }
        assertThat(named).containsExactlyInAnyOrder("adGroups/-3", "campaigns/11");
        // no language criteria, no label, nothing but ad group level resources
        assertThat(all.stream().map(Object::toString)).noneMatch(t -> t.contains("language"));
    }

    @Test
    @DisplayName("[AC-36] add_ad_group creates an ENABLED Search ad group in the given campaign, the bid only when asked for")
    void addAdGroupAdGroupFields() {
        var withBid = ops(addAdGroup(750_000L)).get(0).getAdGroupOperation().getCreate();
        assertThat(withBid.getName()).isEqualTo("Winter boots");
        assertThat(withBid.getStatus()).isEqualTo(AdGroupStatus.ENABLED);
        assertThat(withBid.getType()).isEqualTo(AdGroupType.SEARCH_STANDARD);
        assertThat(withBid.getCampaign()).isEqualTo("customers/1234567890/campaigns/11");
        assertThat(withBid.hasCpcBidMicros()).isTrue();
        assertThat(withBid.getCpcBidMicros()).isEqualTo(750_000L);

        var withoutBid = ops(addAdGroup(null)).get(0).getAdGroupOperation().getCreate();
        assertThat(withoutBid.hasCpcBidMicros()).isFalse();
        assertThat(withoutBid.getStatus()).isEqualTo(AdGroupStatus.ENABLED);

        var padded = ops(new AddAdGroup(EntityRef.campaign("11"), "  Winter boots ", BOOT_KEYWORDS, BOOT_HEADLINES, BOOT_DESCRIPTIONS, BOOT_URL, null))
                .get(0).getAdGroupOperation().getCreate();
        assertThat(padded.getName()).isEqualTo("Winter boots");
    }

    @Test
    @DisplayName("[AC-36] add_ad_group includes every keyword in order and one enabled responsive search ad")
    void addAdGroupKeywordsAndAd() {
        List<MutateOperation> all = ops(addAdGroup(null));

        var first = all.get(1).getAdGroupCriterionOperation().getCreate();
        assertThat(first.getKeyword().getText()).isEqualTo("winter boots");
        assertThat(first.getKeyword().getMatchType()).isEqualTo(KeywordMatchType.PHRASE);
        assertThat(first.getCpcBidMicros()).isEqualTo(900_000L);
        assertThat(first.getStatus()).isEqualTo(AdGroupCriterionStatus.ENABLED);
        assertThat(first.getNegative()).isFalse();
        var second = all.get(2).getAdGroupCriterionOperation().getCreate();
        assertThat(second.getKeyword().getText()).isEqualTo("snow boots");
        assertThat(second.getKeyword().getMatchType()).isEqualTo(KeywordMatchType.EXACT);
        assertThat(second.hasCpcBidMicros()).isFalse();
        assertThat(second.getStatus()).isEqualTo(AdGroupCriterionStatus.ENABLED);

        var ad = all.get(3).getAdGroupAdOperation().getCreate();
        assertThat(ad.getStatus()).isEqualTo(AdGroupAdStatus.ENABLED);
        assertThat(ad.getAd().getFinalUrlsList()).containsExactly(BOOT_URL);
        assertThat(ad.getAd().getResponsiveSearchAd().getHeadlinesList().stream().map(h -> h.getText()))
                .containsExactlyElementsOf(BOOT_HEADLINES);
        assertThat(ad.getAd().getResponsiveSearchAd().getDescriptionsList().stream().map(d -> d.getText()))
                .containsExactlyElementsOf(BOOT_DESCRIPTIONS);
    }

    @Test
    @DisplayName("[AC-36] add_ad_group refuses bad arguments before building anything")
    void addAdGroupValidation() {
        EntityRef campaign = EntityRef.campaign("11");
        List<String> h16 = Stream.iterate(1, i -> i + 1).limit(16).map(i -> "h" + i).toList();

        assertThatThrownBy(() -> ops(new AddAdGroup(campaign, "n", List.of(), BOOT_HEADLINES, BOOT_DESCRIPTIONS, BOOT_URL, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("keyword");
        assertThatThrownBy(() -> ops(new AddAdGroup(EntityRef.adGroup("11"), "n", BOOT_KEYWORDS, BOOT_HEADLINES, BOOT_DESCRIPTIONS, BOOT_URL, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("CAMPAIGN");
        assertThatThrownBy(() -> ops(new AddAdGroup(EntityRef.budget("11"), "n", BOOT_KEYWORDS, BOOT_HEADLINES, BOOT_DESCRIPTIONS, BOOT_URL, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ops(new AddAdGroup(null, "n", BOOT_KEYWORDS, BOOT_HEADLINES, BOOT_DESCRIPTIONS, BOOT_URL, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ops(new AddAdGroup(EntityRef.campaign("1.5"), "n", BOOT_KEYWORDS, BOOT_HEADLINES, BOOT_DESCRIPTIONS, BOOT_URL, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ops(new AddAdGroup(campaign, "n", BOOT_KEYWORDS, List.of("h1", "h2"), BOOT_DESCRIPTIONS, BOOT_URL, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("headlines");
        assertThatThrownBy(() -> ops(new AddAdGroup(campaign, "n", BOOT_KEYWORDS, h16, BOOT_DESCRIPTIONS, BOOT_URL, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("headlines");
        assertThatThrownBy(() -> ops(new AddAdGroup(campaign, "n", BOOT_KEYWORDS, BOOT_HEADLINES, List.of("d1"), BOOT_URL, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("descriptions");
        assertThatThrownBy(() -> ops(new AddAdGroup(campaign, "n", BOOT_KEYWORDS, BOOT_HEADLINES, BOOT_DESCRIPTIONS, " ", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("final url");
        assertThatThrownBy(() -> ops(new AddAdGroup(campaign, " ", BOOT_KEYWORDS, BOOT_HEADLINES, BOOT_DESCRIPTIONS, BOOT_URL, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ad group name");
        assertThatThrownBy(() -> ops(new AddAdGroup(campaign, null, BOOT_KEYWORDS, BOOT_HEADLINES, BOOT_DESCRIPTIONS, BOOT_URL, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ops(addAdGroup(0L))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cpc bid");
        assertThatThrownBy(() -> ops(addAdGroup(-5L))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cpc bid");
        assertThatThrownBy(() -> ops(new AddAdGroup(campaign, "n", List.of(new KeywordSpec("a", MatchType.BROAD, 0L)),
                BOOT_HEADLINES, BOOT_DESCRIPTIONS, BOOT_URL, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cpc bid");
        // the same arguments with everything right are accepted, so each refusal above is about the one thing it changed
        assertThat(ops(new AddAdGroup(campaign, "n", BOOT_KEYWORDS, BOOT_HEADLINES, BOOT_DESCRIPTIONS, BOOT_URL, null))).hasSize(4);
    }

    // ---- id extraction --------------------------------------------------------------------

    private static MutateOperationResponse campaignResult(String name) {
        return MutateOperationResponse.newBuilder().setCampaignResult(
                com.google.ads.googleads.v26.services.MutateCampaignResult.newBuilder().setResourceName(name)).build();
    }

    @Test
    @DisplayName("[AC-36] created ids come back by key: campaign, budget, adGroup, ad and criterion:N")
    void createdIdsOfCreateCampaign() {
        GoogleMutations.Plan plan = mutations.plan(createCampaign());
        MutateGoogleAdsResponse response = MutateGoogleAdsResponse.newBuilder()
                .addMutateOperationResponses(MutateOperationResponse.newBuilder().setCampaignBudgetResult(
                        com.google.ads.googleads.v26.services.MutateCampaignBudgetResult.newBuilder()
                                .setResourceName("customers/1234567890/campaignBudgets/501")))
                .addMutateOperationResponses(campaignResult("customers/1234567890/campaigns/502"))
                .addMutateOperationResponses(criterionResult("customers/1234567890/campaignCriteria/502~2616"))
                .addMutateOperationResponses(criterionResult("customers/1234567890/campaignCriteria/502~1011419"))
                .addMutateOperationResponses(MutateOperationResponse.newBuilder().setAdGroupResult(
                        com.google.ads.googleads.v26.services.MutateAdGroupResult.newBuilder()
                                .setResourceName("customers/1234567890/adGroups/503")))
                .addMutateOperationResponses(adGroupCriterionResult("customers/1234567890/adGroupCriteria/503~601"))
                .addMutateOperationResponses(adGroupCriterionResult("customers/1234567890/adGroupCriteria/503~602"))
                .addMutateOperationResponses(MutateOperationResponse.newBuilder().setAdGroupAdResult(
                        com.google.ads.googleads.v26.services.MutateAdGroupAdResult.newBuilder()
                                .setResourceName("customers/1234567890/adGroupAds/503~700")))
                .addMutateOperationResponses(MutateOperationResponse.newBuilder().setLabelResult(
                        com.google.ads.googleads.v26.services.MutateLabelResult.newBuilder()
                                .setResourceName("customers/1234567890/labels/800")))
                .addMutateOperationResponses(MutateOperationResponse.newBuilder().setCampaignLabelResult(
                        com.google.ads.googleads.v26.services.MutateCampaignLabelResult.newBuilder()
                                .setResourceName("customers/1234567890/campaignLabels/502~800")))
                .build();

        Map<String, String> ids = mutations.createdIds(plan, response);

        assertThat(ids).containsEntry("budget", "501")
                .containsEntry("campaign", "502")
                .containsEntry("adGroup", "503")
                .containsEntry("ad", "503~700")
                .containsEntry("criterion:0", "503~601")
                .containsEntry("criterion:1", "503~602")
                .containsEntry("label", "800")
                .doesNotContainKey("criterion:2"); // location criteria are not listed
        assertThat(GoogleMutations.resourceNames(response)).hasSize(10).first().isEqualTo("customers/1234567890/campaignBudgets/501");
    }

    private static MutateOperationResponse criterionResult(String name) {
        return MutateOperationResponse.newBuilder().setCampaignCriterionResult(
                com.google.ads.googleads.v26.services.MutateCampaignCriterionResult.newBuilder().setResourceName(name)).build();
    }

    private static MutateOperationResponse adGroupCriterionResult(String name) {
        return MutateOperationResponse.newBuilder().setAdGroupCriterionResult(
                com.google.ads.googleads.v26.services.MutateAdGroupCriterionResult.newBuilder().setResourceName(name)).build();
    }

    @Test
    @DisplayName("[AC-36] the i-th negative keyword term is criterion:i with the plain criterion id")
    void createdIdsOfNegatives() {
        GoogleMutations.Plan campaignPlan = mutations.plan(
                new AddNegativeKeywords(EntityRef.campaign("11"), List.of("free", "jobs", "diy"), MatchType.PHRASE));
        MutateGoogleAdsResponse campaignResponse = MutateGoogleAdsResponse.newBuilder()
                .addMutateOperationResponses(criterionResult("customers/1234567890/campaignCriteria/11~9001"))
                .addMutateOperationResponses(criterionResult("customers/1234567890/campaignCriteria/11~9002"))
                .addMutateOperationResponses(criterionResult("customers/1234567890/campaignCriteria/11~9003"))
                .build();
        assertThat(mutations.createdIds(campaignPlan, campaignResponse))
                .containsExactly(Map.entry("criterion:0", "9001"), Map.entry("criterion:1", "9002"), Map.entry("criterion:2", "9003"));

        GoogleMutations.Plan adGroupPlan = mutations.plan(
                new AddNegativeKeywords(EntityRef.adGroup("22"), List.of("cheap", "used"), MatchType.EXACT));
        MutateGoogleAdsResponse adGroupResponse = MutateGoogleAdsResponse.newBuilder()
                .addMutateOperationResponses(adGroupCriterionResult("customers/1234567890/adGroupCriteria/22~7001"))
                .addMutateOperationResponses(adGroupCriterionResult("customers/1234567890/adGroupCriteria/22~7002"))
                .build();
        assertThat(mutations.createdIds(adGroupPlan, adGroupResponse))
                .containsExactly(Map.entry("criterion:0", "7001"), Map.entry("criterion:1", "7002"));
    }

    @Test
    @DisplayName("[AC-36] added keywords and a created ad report composite ids; validate_only answers give none")
    void createdIdsOfKeywordsAndAd() {
        GoogleMutations.Plan keywords = mutations.plan(new AddKeywords(EntityRef.adGroup("22"), List.of(
                new KeywordSpec("a", MatchType.EXACT, null), new KeywordSpec("b", MatchType.PHRASE, 500_000L))));
        MutateGoogleAdsResponse response = MutateGoogleAdsResponse.newBuilder()
                .addMutateOperationResponses(adGroupCriterionResult("customers/1234567890/adGroupCriteria/22~31"))
                .addMutateOperationResponses(adGroupCriterionResult("customers/1234567890/adGroupCriteria/22~32"))
                .build();
        assertThat(mutations.createdIds(keywords, response))
                .containsExactly(Map.entry("criterion:0", "22~31"), Map.entry("criterion:1", "22~32"));

        GoogleMutations.Plan ad = mutations.plan(new CreateAd(EntityRef.adGroup("22"), List.of("a", "b", "c"), List.of("d", "e"), "https://x.example"));
        MutateGoogleAdsResponse adResponse = MutateGoogleAdsResponse.newBuilder()
                .addMutateOperationResponses(MutateOperationResponse.newBuilder().setAdGroupAdResult(
                        com.google.ads.googleads.v26.services.MutateAdGroupAdResult.newBuilder()
                                .setResourceName("customers/1234567890/adGroupAds/22~99")))
                .build();
        assertThat(mutations.createdIds(ad, adResponse)).containsExactly(Map.entry("ad", "22~99"));

        // validate_only: Google answers with no results
        assertThat(mutations.createdIds(keywords, MutateGoogleAdsResponse.getDefaultInstance())).isEmpty();
    }

    private static MutateOperationResponse adGroupResult(String name) {
        return MutateOperationResponse.newBuilder().setAdGroupResult(
                com.google.ads.googleads.v26.services.MutateAdGroupResult.newBuilder().setResourceName(name)).build();
    }

    private static MutateOperationResponse adGroupAdResult(String name) {
        return MutateOperationResponse.newBuilder().setAdGroupAdResult(
                com.google.ads.googleads.v26.services.MutateAdGroupAdResult.newBuilder().setResourceName(name)).build();
    }

    @Test
    @DisplayName("[AC-36] add_ad_group created ids: adGroup, criterion:i as adGroupId~criterionId in keyword order, ad as adGroupId~adId")
    void createdIdsOfAddAdGroup() {
        GoogleMutations.Plan plan = mutations.plan(addAdGroup(null));
        MutateGoogleAdsResponse response = MutateGoogleAdsResponse.newBuilder()
                .addMutateOperationResponses(adGroupResult("customers/1234567890/adGroups/503"))
                .addMutateOperationResponses(adGroupCriterionResult("customers/1234567890/adGroupCriteria/503~601"))
                .addMutateOperationResponses(adGroupCriterionResult("customers/1234567890/adGroupCriteria/503~602"))
                .addMutateOperationResponses(adGroupAdResult("customers/1234567890/adGroupAds/503~700"))
                .build();

        Map<String, String> ids = mutations.createdIds(plan, response);

        assertThat(ids).containsExactly(
                Map.entry("adGroup", "503"),
                Map.entry("criterion:0", "503~601"),
                Map.entry("criterion:1", "503~602"),
                Map.entry("ad", "503~700"));
        assertThat(GoogleMutations.resourceNames(response)).containsExactly(
                "customers/1234567890/adGroups/503",
                "customers/1234567890/adGroupCriteria/503~601",
                "customers/1234567890/adGroupCriteria/503~602",
                "customers/1234567890/adGroupAds/503~700");

        // validate_only: Google answers with no results, so there is nothing to report
        assertThat(mutations.createdIds(plan, MutateGoogleAdsResponse.getDefaultInstance())).isEmpty();
    }

    // ---- field masks and resource types ---------------------------------------------------

    @Test
    @DisplayName("[MUTATE] every update carries a field mask that names exactly the fields it sets")
    void everyUpdateHasACorrectMask() {
        int updates = 0;
        for (ChangeRequest request : oneOfEach()) {
            for (MutateOperation op : ops(request)) {
                if (!isUpdate(op)) {
                    continue;
                }
                updates++;
                Set<String> roots = new TreeSet<>();
                for (String path : maskOf(op).getPathsList()) {
                    roots.add(path.contains(".") ? path.substring(0, path.indexOf('.')) : path);
                }
                assertThat(roots).as(request.tool()).isEqualTo(setFieldNames(resource(op, "update")));
                assertThat(maskOf(op).getPathsCount()).as(request.tool()).isPositive();
            }
        }
        assertThat(updates).isEqualTo(9); // pause kw, bid, 2 budgets, emergency pause, pause entity, pause ad, enable, bidding
    }

    @Test
    @DisplayName("[MUTATE] field masks of the single-field updates")
    void fieldMasks() {
        assertThat(maskOf(ops(new PauseKeyword(EntityRef.keyword("22~33"))).get(0)).getPathsList()).containsExactly("status");
        assertThat(maskOf(ops(new AdjustKeywordBid(EntityRef.keyword("22~33"), 900_000L)).get(0)).getPathsList())
                .containsExactly("cpc_bid_micros");
        assertThat(maskOf(ops(new DecreaseBudget(EntityRef.budget("44"), 5L)).get(0)).getPathsList()).containsExactly("amount_micros");
        assertThat(maskOf(ops(new IncreaseBudget(EntityRef.budget("44"), 5L)).get(0)).getPathsList()).containsExactly("amount_micros");
        assertThat(maskOf(ops(new EmergencyPauseCampaign(EntityRef.campaign("11"))).get(0)).getPathsList()).containsExactly("status");
        assertThat(maskOf(ops(new PauseAd(EntityRef.ad("22~55"))).get(0)).getPathsList()).containsExactly("status");
        assertThat(maskOf(ops(new EnableEntity(EntityRef.adGroup("22"))).get(0)).getPathsList()).containsExactly("status");
    }

    @Test
    @DisplayName("[MUTATE] change_bidding_strategy names the new strategy member in the mask for every type")
    void biddingMasks() {
        EntityRef campaign = EntityRef.campaign("11");
        Map<BiddingStrategy, String> expected = Map.of(
                BiddingStrategy.manualCpc(), "manual_cpc.enhanced_cpc_enabled",
                BiddingStrategy.maximizeConversions(), "maximize_conversions",
                new BiddingStrategy(BiddingStrategy.Type.MAXIMIZE_CONVERSION_VALUE, null, null), "maximize_conversion_value",
                BiddingStrategy.targetCpa(4_000_000L), "target_cpa.target_cpa_micros",
                new BiddingStrategy(BiddingStrategy.Type.TARGET_ROAS, null, 2.5), "target_roas.target_roas");
        assertThat(expected).hasSize(BiddingStrategy.Type.values().length);
        expected.forEach((strategy, path) -> {
            MutateOperation op = ops(new ChangeBiddingStrategy(campaign, strategy)).get(0);
            assertThat(op.getCampaignOperation().getUpdateMask().getPathsList()).as(strategy.describe()).containsExactly(path);
            assertThat(op.getCampaignOperation().getUpdate().getResourceName()).isEqualTo("customers/1234567890/campaigns/11");
        });
        var cpa = ops(new ChangeBiddingStrategy(campaign, BiddingStrategy.targetCpa(4_000_000L))).get(0).getCampaignOperation().getUpdate();
        assertThat(cpa.getTargetCpa().getTargetCpaMicros()).isEqualTo(4_000_000L);
    }

    @Test
    @DisplayName("[MUTATE] each change type produces operations on the right resource type")
    void operationsTargetTheRightResource() {
        assertThat(kinds(new AddNegativeKeywords(EntityRef.campaign("11"), List.of("a", "b"), MatchType.EXACT)))
                .containsExactly(OperationCase.CAMPAIGN_CRITERION_OPERATION, OperationCase.CAMPAIGN_CRITERION_OPERATION);
        assertThat(kinds(new AddNegativeKeywords(EntityRef.adGroup("22"), List.of("a"), MatchType.PHRASE)))
                .containsExactly(OperationCase.AD_GROUP_CRITERION_OPERATION);
        assertThat(kinds(new PauseKeyword(EntityRef.keyword("22~33")))).containsExactly(OperationCase.AD_GROUP_CRITERION_OPERATION);
        assertThat(kinds(new AdjustKeywordBid(EntityRef.keyword("22~33"), 1L))).containsExactly(OperationCase.AD_GROUP_CRITERION_OPERATION);
        assertThat(kinds(new DecreaseBudget(EntityRef.budget("44"), 1L))).containsExactly(OperationCase.CAMPAIGN_BUDGET_OPERATION);
        assertThat(kinds(new IncreaseBudget(EntityRef.budget("44"), 1L))).containsExactly(OperationCase.CAMPAIGN_BUDGET_OPERATION);
        assertThat(kinds(new EmergencyPauseCampaign(EntityRef.campaign("11")))).containsExactly(OperationCase.CAMPAIGN_OPERATION);
        assertThat(kinds(new PauseAd(EntityRef.ad("22~55")))).containsExactly(OperationCase.AD_GROUP_AD_OPERATION);
        assertThat(kinds(new AddKeywords(EntityRef.adGroup("22"), List.of(new KeywordSpec("a", MatchType.BROAD, null)))))
                .containsExactly(OperationCase.AD_GROUP_CRITERION_OPERATION);
        assertThat(kinds(new CreateAd(EntityRef.adGroup("22"), List.of("a", "b", "c"), List.of("d", "e"), "https://x.example")))
                .containsExactly(OperationCase.AD_GROUP_AD_OPERATION);
        assertThat(kinds(addAdGroup(null))).containsExactly(OperationCase.AD_GROUP_OPERATION,
                OperationCase.AD_GROUP_CRITERION_OPERATION, OperationCase.AD_GROUP_CRITERION_OPERATION, OperationCase.AD_GROUP_AD_OPERATION);
        assertThat(kinds(new ChangeBiddingStrategy(EntityRef.campaign("11"), BiddingStrategy.manualCpc())))
                .containsExactly(OperationCase.CAMPAIGN_OPERATION);
        assertThat(kinds(new RemoveNegativeKeywords(EntityRef.campaign("11"), List.of("1")))).containsExactly(OperationCase.CAMPAIGN_CRITERION_OPERATION);
        assertThat(kinds(new RemoveNegativeKeywords(EntityRef.adGroup("22"), List.of("1")))).containsExactly(OperationCase.AD_GROUP_CRITERION_OPERATION);
    }

    private List<OperationCase> kinds(ChangeRequest request) {
        return ops(request).stream().map(MutateOperation::getOperationCase).toList();
    }

    @Test
    @DisplayName("[MUTATE] pause and enable reach campaign, ad group, ad and keyword, with the right status")
    void pauseAndEnableEveryKind() {
        MutateOperation campaign = ops(new PauseEntity(EntityRef.campaign("11"))).get(0);
        assertThat(campaign.getCampaignOperation().getUpdate().getStatus()).isEqualTo(CampaignStatus.PAUSED);
        MutateOperation adGroup = ops(new PauseEntity(EntityRef.adGroup("22"))).get(0);
        assertThat(adGroup.getAdGroupOperation().getUpdate().getStatus()).isEqualTo(AdGroupStatus.PAUSED);
        MutateOperation ad = ops(new PauseEntity(EntityRef.ad("22~55"))).get(0);
        assertThat(ad.getAdGroupAdOperation().getUpdate().getStatus()).isEqualTo(AdGroupAdStatus.PAUSED);
        assertThat(ad.getAdGroupAdOperation().getUpdate().getResourceName()).isEqualTo("customers/1234567890/adGroupAds/22~55");
        MutateOperation keyword = ops(new PauseEntity(EntityRef.keyword("22~33"))).get(0);
        assertThat(keyword.getAdGroupCriterionOperation().getUpdate().getStatus()).isEqualTo(AdGroupCriterionStatus.PAUSED);
        assertThat(keyword.getAdGroupCriterionOperation().getUpdate().getResourceName()).isEqualTo("customers/1234567890/adGroupCriteria/22~33");

        assertThat(ops(new EnableEntity(EntityRef.campaign("11"))).get(0).getCampaignOperation().getUpdate().getStatus())
                .isEqualTo(CampaignStatus.ENABLED);
        assertThat(ops(new EnableEntity(EntityRef.ad("22~55"))).get(0).getAdGroupAdOperation().getUpdate().getStatus())
                .isEqualTo(AdGroupAdStatus.ENABLED);
        assertThat(ops(new EnableEntity(EntityRef.keyword("22~33"))).get(0).getAdGroupCriterionOperation().getUpdate().getStatus())
                .isEqualTo(AdGroupCriterionStatus.ENABLED);
        assertThat(ops(new EnableEntity(EntityRef.adGroup("22"))).get(0).getAdGroupOperation().getUpdate().getStatus())
                .isEqualTo(AdGroupStatus.ENABLED);
    }

    @Test
    @DisplayName("[MUTATE] negatives are created as negative EXACT or PHRASE keyword criteria, one per term in order")
    void negativeKeywordOperations() {
        var campaignOps = ops(new AddNegativeKeywords(EntityRef.campaign("11"), List.of(" free ", "jobs"), MatchType.PHRASE));
        var first = campaignOps.get(0).getCampaignCriterionOperation().getCreate();
        assertThat(first.getNegative()).isTrue();
        assertThat(first.getCampaign()).isEqualTo("customers/1234567890/campaigns/11");
        assertThat(first.getKeyword().getText()).isEqualTo("free");
        assertThat(first.getKeyword().getMatchType()).isEqualTo(KeywordMatchType.PHRASE);
        assertThat(campaignOps.get(1).getCampaignCriterionOperation().getCreate().getKeyword().getText()).isEqualTo("jobs");

        var adGroupOp = ops(new AddNegativeKeywords(EntityRef.adGroup("22"), List.of("cheap"), MatchType.EXACT)).get(0)
                .getAdGroupCriterionOperation().getCreate();
        assertThat(adGroupOp.getNegative()).isTrue();
        assertThat(adGroupOp.getAdGroup()).isEqualTo("customers/1234567890/adGroups/22");
        assertThat(adGroupOp.getKeyword().getMatchType()).isEqualTo(KeywordMatchType.EXACT);
    }

    @Test
    @DisplayName("[MUTATE] rollback removal builds campaign or ad group criterion names from plain ids and scope")
    void removeNegativesNames() {
        var campaignOps = ops(new RemoveNegativeKeywords(EntityRef.campaign("11"), List.of("9001", "9002")));
        assertThat(campaignOps.stream().map(o -> o.getCampaignCriterionOperation().getRemove()))
                .containsExactly("customers/1234567890/campaignCriteria/11~9001", "customers/1234567890/campaignCriteria/11~9002");

        var adGroupOps = ops(new RemoveNegativeKeywords(EntityRef.adGroup("22"), List.of("7001", "22~7002")));
        assertThat(adGroupOps.stream().map(o -> o.getAdGroupCriterionOperation().getRemove()))
                .containsExactly("customers/1234567890/adGroupCriteria/22~7001", "customers/1234567890/adGroupCriteria/22~7002");

        assertThatThrownBy(() -> ops(new RemoveNegativeKeywords(EntityRef.adGroup("22"), List.of("99~7002"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not belong");
    }

    @Test
    @DisplayName("[MUTATE] apply_recommendation is a RecommendationService operation, not a MutateOperation")
    void applyRecommendationIsSeparate() {
        var request = new ApplyRecommendation(new EntityRef(app.autopilot.domain.EntityKind.RECOMMENDATION, "abc-123_X"));
        GoogleMutations.Plan plan = mutations.plan(request);

        assertThat(plan.isRecommendation()).isTrue();
        assertThat(plan.operations()).isEmpty();
        assertThat(plan.recommendation().getResourceName()).isEqualTo("customers/1234567890/recommendations/abc-123_X");

        ApplyRecommendationRequest google = GoogleMutations.buildApplyRecommendationRequest(CUSTOMER, plan.recommendation());
        assertThat(google.getCustomerId()).isEqualTo(CUSTOMER);
        assertThat(google.getPartialFailure()).isFalse();
        assertThat(google.getOperationsList()).containsExactly(plan.recommendation());
    }

    @Test
    @DisplayName("[MUTATE] create_ad builds an enabled responsive search ad and enforces 3-15 headlines and 2-4 descriptions")
    void createAdRules() {
        List<String> h3 = List.of("h1", "h2", "h3");
        List<String> d2 = List.of("d1", "d2");
        var ad = ops(new CreateAd(EntityRef.adGroup("22"), h3, d2, "https://x.example/a")).get(0).getAdGroupAdOperation().getCreate();
        assertThat(ad.getAdGroup()).isEqualTo("customers/1234567890/adGroups/22");
        assertThat(ad.getStatus()).isEqualTo(AdGroupAdStatus.ENABLED);
        assertThat(ad.getAd().hasResponsiveSearchAd()).isTrue();
        assertThat(ad.getAd().getFinalUrlsList()).containsExactly("https://x.example/a");

        List<String> h15 = Stream.iterate(1, i -> i + 1).limit(15).map(i -> "h" + i).toList();
        List<String> d4 = List.of("d1", "d2", "d3", "d4");
        assertThat(ops(new CreateAd(EntityRef.adGroup("22"), h15, d4, "https://x.example"))).hasSize(1);

        assertThatThrownBy(() -> ops(new CreateAd(EntityRef.adGroup("22"), List.of("h1", "h2"), d2, "https://x.example")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("headlines");
        List<String> h16 = new ArrayList<>(h15);
        h16.add("h16");
        assertThatThrownBy(() -> ops(new CreateAd(EntityRef.adGroup("22"), h16, d2, "https://x.example")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ops(new CreateAd(EntityRef.adGroup("22"), h3, List.of("d1"), "https://x.example")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("descriptions");
        assertThatThrownBy(() -> ops(new CreateAd(EntityRef.adGroup("22"), h3, List.of("1", "2", "3", "4", "5"), "https://x.example")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ops(new CreateAd(EntityRef.adGroup("22"), h3, d2, " ")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("final url");
    }

    @Test
    @DisplayName("[MUTATE] add_keywords sets text, match type, optional bid and ENABLED status")
    void addKeywordsOperations() {
        var ops = ops(new AddKeywords(EntityRef.adGroup("22"), List.of(
                new KeywordSpec("acme", MatchType.BROAD, 400_000L), new KeywordSpec("acme shoes", MatchType.EXACT, null))));
        var first = ops.get(0).getAdGroupCriterionOperation().getCreate();
        assertThat(first.getAdGroup()).isEqualTo("customers/1234567890/adGroups/22");
        assertThat(first.getStatus()).isEqualTo(AdGroupCriterionStatus.ENABLED);
        assertThat(first.getKeyword().getMatchType()).isEqualTo(KeywordMatchType.BROAD);
        assertThat(first.getCpcBidMicros()).isEqualTo(400_000L);
        assertThat(ops.get(1).getAdGroupCriterionOperation().getCreate().hasCpcBidMicros()).isFalse();
    }

    @Test
    @DisplayName("[MUTATE] bad requests are refused before anything is built")
    void badRequestsAreRefused() {
        assertThatThrownBy(() -> ops(new AddNegativeKeywords(EntityRef.campaign("11"), List.of("x"), MatchType.BROAD)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("EXACT or PHRASE");
        assertThatThrownBy(() -> ops(new AddNegativeKeywords(EntityRef.keyword("1~2"), List.of("x"), MatchType.EXACT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ops(new AddNegativeKeywords(EntityRef.campaign("11"), List.of(), MatchType.EXACT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ops(new PauseEntity(EntityRef.budget("44")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ops(new DecreaseBudget(EntityRef.campaign("44"), 5L))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ops(new DecreaseBudget(EntityRef.budget("44"), 0L))).isInstanceOf(IllegalArgumentException.class);
        // an id that is not numeric can never become part of a resource name
        assertThatThrownBy(() -> ops(new PauseEntity(EntityRef.campaign("1.5")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ops(new PauseKeyword(EntityRef.keyword("22~x")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ops(new PauseKeyword(EntityRef.keyword("22")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GoogleMutations("12-34")).isInstanceOf(IllegalArgumentException.class);
        // campaigns need a location, a budget and a political advertising declaration
        CampaignSpec noGeo = new CampaignSpec("n", 1_000_000L, BiddingStrategy.manualCpc(), List.of(), "g", List.of(),
                List.of("a", "b", "c"), List.of("d", "e"), "https://x.example", EuPoliticalAds.NO_POLITICAL_ADS);
        assertThatThrownBy(() -> ops(new CreateCampaign(noGeo))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("geo");
        CampaignSpec noBudget = new CampaignSpec("n", 0L, BiddingStrategy.manualCpc(), List.of(1L), "g", List.of(),
                List.of("a", "b", "c"), List.of("d", "e"), "https://x.example", EuPoliticalAds.NO_POLITICAL_ADS);
        assertThatThrownBy(() -> ops(new CreateCampaign(noBudget))).isInstanceOf(IllegalArgumentException.class);
        CampaignSpec noEu = new CampaignSpec("n", 1L, BiddingStrategy.manualCpc(), List.of(1L), "g", List.of(),
                List.of("a", "b", "c"), List.of("d", "e"), "https://x.example", null);
        assertThatThrownBy(() -> ops(new CreateCampaign(noEu))).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- helpers --------------------------------------------------------------------------

    private Campaign campaignOf(CreateCampaign request) {
        return ops(request).get(1).getCampaignOperation().getCreate();
    }
}

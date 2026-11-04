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
import app.autopilot.domain.ChangeRequest.IncreaseBudget;
import app.autopilot.domain.ChangeRequest.KeywordSpec;
import app.autopilot.domain.ChangeRequest.PauseAd;
import app.autopilot.domain.ChangeRequest.PauseEntity;
import app.autopilot.domain.ChangeRequest.PauseKeyword;
import app.autopilot.domain.ChangeRequest.RemoveNegativeKeywords;
import app.autopilot.domain.EntityKind;
import app.autopilot.domain.EntityRef;
import app.autopilot.domain.MatchType;

import com.google.ads.googleads.v26.common.AdTextAsset;
import com.google.ads.googleads.v26.common.KeywordInfo;
import com.google.ads.googleads.v26.common.LocationInfo;
import com.google.ads.googleads.v26.common.ManualCpc;
import com.google.ads.googleads.v26.common.MaximizeConversionValue;
import com.google.ads.googleads.v26.common.MaximizeConversions;
import com.google.ads.googleads.v26.common.ResponsiveSearchAdInfo;
import com.google.ads.googleads.v26.common.TargetCpa;
import com.google.ads.googleads.v26.common.TargetRoas;
import com.google.ads.googleads.v26.common.TextLabel;
import com.google.ads.googleads.v26.enums.AdGroupAdStatusEnum.AdGroupAdStatus;
import com.google.ads.googleads.v26.enums.AdGroupCriterionStatusEnum.AdGroupCriterionStatus;
import com.google.ads.googleads.v26.enums.AdGroupStatusEnum.AdGroupStatus;
import com.google.ads.googleads.v26.enums.AdGroupTypeEnum.AdGroupType;
import com.google.ads.googleads.v26.enums.AdvertisingChannelTypeEnum.AdvertisingChannelType;
import com.google.ads.googleads.v26.enums.AssetAutomationStatusEnum.AssetAutomationStatus;
import com.google.ads.googleads.v26.enums.AssetAutomationTypeEnum.AssetAutomationType;
import com.google.ads.googleads.v26.enums.BudgetDeliveryMethodEnum.BudgetDeliveryMethod;
import com.google.ads.googleads.v26.enums.CampaignStatusEnum.CampaignStatus;
import com.google.ads.googleads.v26.enums.EuPoliticalAdvertisingStatusEnum.EuPoliticalAdvertisingStatus;
import com.google.ads.googleads.v26.enums.KeywordMatchTypeEnum.KeywordMatchType;
import com.google.ads.googleads.v26.enums.PositiveGeoTargetTypeEnum.PositiveGeoTargetType;
import com.google.ads.googleads.v26.enums.ResponseContentTypeEnum.ResponseContentType;
import com.google.ads.googleads.v26.resources.Ad;
import com.google.ads.googleads.v26.resources.AdGroup;
import com.google.ads.googleads.v26.resources.AdGroupAd;
import com.google.ads.googleads.v26.resources.AdGroupCriterion;
import com.google.ads.googleads.v26.resources.Campaign;
import com.google.ads.googleads.v26.resources.CampaignBudget;
import com.google.ads.googleads.v26.resources.CampaignCriterion;
import com.google.ads.googleads.v26.resources.CampaignLabel;
import com.google.ads.googleads.v26.resources.Label;
import com.google.ads.googleads.v26.services.AdGroupAdOperation;
import com.google.ads.googleads.v26.services.AdGroupCriterionOperation;
import com.google.ads.googleads.v26.services.AdGroupOperation;
import com.google.ads.googleads.v26.services.ApplyRecommendationOperation;
import com.google.ads.googleads.v26.services.ApplyRecommendationRequest;
import com.google.ads.googleads.v26.services.CampaignBudgetOperation;
import com.google.ads.googleads.v26.services.CampaignCriterionOperation;
import com.google.ads.googleads.v26.services.CampaignLabelOperation;
import com.google.ads.googleads.v26.services.CampaignOperation;
import com.google.ads.googleads.v26.services.LabelOperation;
import com.google.ads.googleads.v26.services.MutateGoogleAdsRequest;
import com.google.ads.googleads.v26.services.MutateGoogleAdsResponse;
import com.google.ads.googleads.v26.services.MutateOperation;
import com.google.ads.googleads.v26.services.MutateOperationResponse;
import com.google.protobuf.FieldMask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns each {@link ChangeRequest} into the Google Ads operations that carry it out. Nothing here
 * talks to the network, so every rule can be tested by looking at the protobuf messages.
 *
 * <p>Rules that hold for every builder:
 * <ul>
 *   <li>Updates carry an explicit {@link FieldMask} naming exactly the fields they change.</li>
 *   <li>The only REMOVE operations are the ones {@link RemoveNegativeKeywords} produces.</li>
 *   <li>Only campaign, budget, ad group, ad, criterion and label resources are touched; never billing,
 *       users or conversion actions.</li>
 * </ul>
 *
 * <p><b>Managed label.</b> A campaign created by {@link CreateCampaign} gets the label named by
 * {@code managedLabel} (default {@code agent-managed}) so the guard can manage it later. The same atomic
 * request does it: when the label already exists the request carries one {@code CampaignLabel} that
 * points to it; when it does not, the request first creates the {@code Label} under a temporary id and
 * attaches that. The caller finds out whether the label exists ({@link #needsManagedLabelLookup}) and
 * passes its resource name to {@link #plan(ChangeRequest, String)}. If someone creates the label between
 * that lookup and the mutate, Google answers DUPLICATE_NAME and nothing is created; a retry succeeds.
 */
public final class GoogleMutations {

    public static final String DEFAULT_MANAGED_LABEL = "agent-managed";

    // Tags say which created entity an operation's result is, see createdIds().
    static final String TAG_CAMPAIGN = "campaign";
    static final String TAG_BUDGET = "budget";
    static final String TAG_AD_GROUP = "adGroup";
    static final String TAG_AD = "ad";
    static final String TAG_LABEL = "label";
    static final String TAG_KEYWORD_CRITERION = "criterion.keyword";
    static final String TAG_NEGATIVE_CRITERION = "criterion.negative";

    private static final int MIN_HEADLINES = 3;
    private static final int MAX_HEADLINES = 15;
    private static final int MIN_DESCRIPTIONS = 2;
    private static final int MAX_DESCRIPTIONS = 4;

    // Temporary ids for resources created in the same request. Any negative number works.
    private static final long TEMP_BUDGET = -1;
    private static final long TEMP_CAMPAIGN = -2;
    private static final long TEMP_AD_GROUP = -3;
    private static final long TEMP_LABEL = -4;

    /**
     * The operations of one change. {@code tags} has one entry per operation (null when the result of
     * that operation is not an id the caller wants back). {@code recommendation} is set instead of
     * operations for {@code apply_recommendation}, which is not a MutateOperation.
     */
    public record Plan(List<MutateOperation> operations, List<String> tags, ApplyRecommendationOperation recommendation) {
        public Plan {
            operations = List.copyOf(operations);
            tags = java.util.Collections.unmodifiableList(new ArrayList<>(tags));
            if (operations.size() != tags.size()) {
                throw new IllegalArgumentException("one tag per operation");
            }
        }

        public boolean isRecommendation() {
            return recommendation != null;
        }
    }

    private final ResourceNames names;
    private final String managedLabel;
    private final boolean explicitAutomationOptOuts;

    public GoogleMutations(String customerId) {
        this(customerId, DEFAULT_MANAGED_LABEL);
    }

    public GoogleMutations(String customerId, String managedLabel) {
        this(customerId, managedLabel, true);
    }

    /**
     * @param explicitAutomationOptOuts when true (the default), new campaigns send AI Max off and opt out of
     *        automatically created text assets and final URL expansion. Turn it off only if Google rejects
     *        those fields for the account (a validate_only call shows that).
     */
    public GoogleMutations(String customerId, String managedLabel, boolean explicitAutomationOptOuts) {
        if (customerId == null || !customerId.matches("\\d{1,15}")) {
            throw new IllegalArgumentException("customer id must be digits only");
        }
        if (managedLabel == null || managedLabel.isBlank()) {
            throw new IllegalArgumentException("managed label name is required");
        }
        this.names = new ResourceNames(customerId);
        this.managedLabel = managedLabel;
        this.explicitAutomationOptOuts = explicitAutomationOptOuts;
    }

    public String managedLabel() {
        return managedLabel;
    }

    // ---- request wrappers -----------------------------------------------------------------

    /**
     * The one place a mutate request is assembled: validate_only as asked, partial failure always off so
     * a request is all-or-nothing, and only resource names back.
     */
    public static MutateGoogleAdsRequest buildRequest(String customerId, List<MutateOperation> operations, boolean validateOnly) {
        if (operations == null || operations.isEmpty()) {
            throw new IllegalArgumentException("a mutate request needs at least one operation");
        }
        return MutateGoogleAdsRequest.newBuilder()
                .setCustomerId(customerId)
                .addAllMutateOperations(operations)
                .setValidateOnly(validateOnly)
                .setPartialFailure(false)
                .setResponseContentType(ResponseContentType.RESOURCE_NAME_ONLY)
                .build();
    }

    /** The RecommendationService request for {@code apply_recommendation}. It has no validate_only. */
    public static ApplyRecommendationRequest buildApplyRecommendationRequest(String customerId, ApplyRecommendationOperation operation) {
        return ApplyRecommendationRequest.newBuilder()
                .setCustomerId(customerId)
                .addOperations(operation)
                .setPartialFailure(false)
                .build();
    }

    // ---- planning -------------------------------------------------------------------------

    /** True when planning needs to know if the managed label already exists (only campaign creation does). */
    public boolean needsManagedLabelLookup(ChangeRequest request) {
        return request instanceof CreateCampaign;
    }

    public Plan plan(ChangeRequest request) {
        return plan(request, null);
    }

    /**
     * @param existingManagedLabelResourceName resource name of the managed label when it already exists in the
     *        account, otherwise null (then campaign creation also creates the label)
     */
    public Plan plan(ChangeRequest request, String existingManagedLabelResourceName) {
        return switch (request) {
            case AddNegativeKeywords r -> addNegatives(r);
            case PauseKeyword r -> single(statusUpdate(r.keyword(), true));
            case AdjustKeywordBid r -> single(adjustBid(r));
            case DecreaseBudget r -> single(budgetUpdate(r.budget(), r.newAmountMicros()));
            case IncreaseBudget r -> single(budgetUpdate(r.budget(), r.newAmountMicros()));
            case EmergencyPauseCampaign r -> single(statusUpdate(r.campaign(), true));
            case PauseEntity r -> single(statusUpdate(r.entity(), true));
            case PauseAd r -> single(statusUpdate(r.ad(), true));
            case EnableEntity r -> single(statusUpdate(r.entity(), false));
            case AddKeywords r -> addKeywords(r);
            case CreateAd r -> createAd(r);
            case CreateCampaign r -> createCampaign(r.spec(), existingManagedLabelResourceName);
            case AddAdGroup r -> addAdGroup(r);
            case ChangeBiddingStrategy r -> single(changeBidding(r));
            case ApplyRecommendation r -> applyRecommendation(r);
            case RemoveNegativeKeywords r -> removeNegatives(r);
        };
    }

    private static Plan single(MutateOperation op) {
        return new Plan(List.of(op), java.util.Collections.singletonList(null), null);
    }

    // ---- tier 1 ---------------------------------------------------------------------------

    private Plan addNegatives(AddNegativeKeywords r) {
        EntityRef scope = r.scope();
        if (r.terms().isEmpty()) {
            throw new IllegalArgumentException("add_negative_keywords needs at least one term");
        }
        if (r.matchType() != MatchType.EXACT && r.matchType() != MatchType.PHRASE) {
            throw new IllegalArgumentException("negative keywords may only be EXACT or PHRASE");
        }
        List<MutateOperation> ops = new ArrayList<>();
        List<String> tags = new ArrayList<>();
        // One operation per term, in the order given: the i-th term is the i-th criterion in createdIds.
        for (String term : r.terms()) {
            KeywordInfo keyword = keyword(term, r.matchType());
            if (scope.kind() == EntityKind.CAMPAIGN) {
                CampaignCriterion criterion = CampaignCriterion.newBuilder()
                        .setCampaign(names.campaign(scope.id()))
                        .setNegative(true)
                        .setKeyword(keyword)
                        .build();
                ops.add(MutateOperation.newBuilder()
                        .setCampaignCriterionOperation(CampaignCriterionOperation.newBuilder().setCreate(criterion))
                        .build());
            } else if (scope.kind() == EntityKind.AD_GROUP) {
                AdGroupCriterion criterion = AdGroupCriterion.newBuilder()
                        .setAdGroup(names.adGroup(scope.id()))
                        .setNegative(true)
                        .setKeyword(keyword)
                        .build();
                ops.add(MutateOperation.newBuilder()
                        .setAdGroupCriterionOperation(AdGroupCriterionOperation.newBuilder().setCreate(criterion))
                        .build());
            } else {
                throw new IllegalArgumentException("negative keywords attach to a campaign or an ad group, not " + scope.kind());
            }
            tags.add(TAG_NEGATIVE_CRITERION);
        }
        return new Plan(ops, tags, null);
    }

    private MutateOperation adjustBid(AdjustKeywordBid r) {
        requireKind(r.keyword(), EntityKind.KEYWORD);
        if (r.newCpcBidMicros() <= 0) {
            throw new IllegalArgumentException("cpc bid must be positive");
        }
        AdGroupCriterion criterion = AdGroupCriterion.newBuilder()
                .setResourceName(names.adGroupCriterion(r.keyword().id()))
                .setCpcBidMicros(r.newCpcBidMicros())
                .build();
        return MutateOperation.newBuilder()
                .setAdGroupCriterionOperation(AdGroupCriterionOperation.newBuilder()
                        .setUpdate(criterion)
                        .setUpdateMask(mask("cpc_bid_micros")))
                .build();
    }

    private MutateOperation budgetUpdate(EntityRef budget, long amountMicros) {
        requireKind(budget, EntityKind.BUDGET);
        if (amountMicros <= 0) {
            throw new IllegalArgumentException("budget amount must be positive");
        }
        CampaignBudget update = CampaignBudget.newBuilder()
                .setResourceName(names.campaignBudget(budget.id()))
                .setAmountMicros(amountMicros)
                .build();
        return MutateOperation.newBuilder()
                .setCampaignBudgetOperation(CampaignBudgetOperation.newBuilder()
                        .setUpdate(update)
                        .setUpdateMask(mask("amount_micros")))
                .build();
    }

    /** Pause (true) or enable (false) a campaign, ad group, ad or keyword. Only the status field changes. */
    private MutateOperation statusUpdate(EntityRef entity, boolean pause) {
        FieldMask statusMask = mask("status");
        return switch (entity.kind()) {
            case CAMPAIGN -> MutateOperation.newBuilder()
                    .setCampaignOperation(CampaignOperation.newBuilder()
                            .setUpdate(Campaign.newBuilder()
                                    .setResourceName(names.campaign(entity.id()))
                                    .setStatus(pause ? CampaignStatus.PAUSED : CampaignStatus.ENABLED))
                            .setUpdateMask(statusMask))
                    .build();
            case AD_GROUP -> MutateOperation.newBuilder()
                    .setAdGroupOperation(AdGroupOperation.newBuilder()
                            .setUpdate(AdGroup.newBuilder()
                                    .setResourceName(names.adGroup(entity.id()))
                                    .setStatus(pause ? AdGroupStatus.PAUSED : AdGroupStatus.ENABLED))
                            .setUpdateMask(statusMask))
                    .build();
            case AD -> MutateOperation.newBuilder()
                    .setAdGroupAdOperation(AdGroupAdOperation.newBuilder()
                            .setUpdate(AdGroupAd.newBuilder()
                                    .setResourceName(names.adGroupAd(entity.id()))
                                    .setStatus(pause ? AdGroupAdStatus.PAUSED : AdGroupAdStatus.ENABLED))
                            .setUpdateMask(statusMask))
                    .build();
            case KEYWORD -> MutateOperation.newBuilder()
                    .setAdGroupCriterionOperation(AdGroupCriterionOperation.newBuilder()
                            .setUpdate(AdGroupCriterion.newBuilder()
                                    .setResourceName(names.adGroupCriterion(entity.id()))
                                    .setStatus(pause ? AdGroupCriterionStatus.PAUSED : AdGroupCriterionStatus.ENABLED))
                            .setUpdateMask(statusMask))
                    .build();
            default -> throw new IllegalArgumentException(
                    (pause ? "pause" : "enable") + " does not apply to " + entity.kind());
        };
    }

    // ---- tier 2 ---------------------------------------------------------------------------

    private Plan addKeywords(AddKeywords r) {
        requireKind(r.adGroup(), EntityKind.AD_GROUP);
        if (r.keywords().isEmpty()) {
            throw new IllegalArgumentException("add_keywords needs at least one keyword");
        }
        String adGroup = names.adGroup(r.adGroup().id());
        List<MutateOperation> ops = new ArrayList<>();
        List<String> tags = new ArrayList<>();
        for (KeywordSpec k : r.keywords()) {
            ops.add(keywordOp(adGroup, k));
            tags.add(TAG_KEYWORD_CRITERION);
        }
        return new Plan(ops, tags, null);
    }

    private static MutateOperation keywordOp(String adGroupResource, KeywordSpec k) {
        AdGroupCriterion.Builder criterion = AdGroupCriterion.newBuilder()
                .setAdGroup(adGroupResource)
                .setStatus(AdGroupCriterionStatus.ENABLED)
                .setKeyword(keyword(k.text(), k.matchType()));
        if (k.cpcBidMicros() != null) {
            if (k.cpcBidMicros() <= 0) {
                throw new IllegalArgumentException("cpc bid must be positive");
            }
            criterion.setCpcBidMicros(k.cpcBidMicros());
        }
        return MutateOperation.newBuilder()
                .setAdGroupCriterionOperation(AdGroupCriterionOperation.newBuilder().setCreate(criterion))
                .build();
    }

    private Plan createAd(CreateAd r) {
        requireKind(r.adGroup(), EntityKind.AD_GROUP);
        MutateOperation op = adOp(names.adGroup(r.adGroup().id()), r.headlines(), r.descriptions(), r.finalUrl());
        return new Plan(List.of(op), List.of(TAG_AD), null);
    }

    /** A responsive search ad, created ENABLED (it only serves when its ad group and campaign do). */
    private static MutateOperation adOp(String adGroupResource, List<String> headlines, List<String> descriptions, String finalUrl) {
        if (headlines.size() < MIN_HEADLINES || headlines.size() > MAX_HEADLINES) {
            throw new IllegalArgumentException("a responsive search ad needs " + MIN_HEADLINES + " to " + MAX_HEADLINES + " headlines");
        }
        if (descriptions.size() < MIN_DESCRIPTIONS || descriptions.size() > MAX_DESCRIPTIONS) {
            throw new IllegalArgumentException("a responsive search ad needs " + MIN_DESCRIPTIONS + " to " + MAX_DESCRIPTIONS + " descriptions");
        }
        if (finalUrl == null || finalUrl.isBlank()) {
            throw new IllegalArgumentException("final url is required");
        }
        ResponsiveSearchAdInfo.Builder rsa = ResponsiveSearchAdInfo.newBuilder();
        for (String h : headlines) {
            rsa.addHeadlines(AdTextAsset.newBuilder().setText(requireText(h, "headline")));
        }
        for (String d : descriptions) {
            rsa.addDescriptions(AdTextAsset.newBuilder().setText(requireText(d, "description")));
        }
        AdGroupAd.Builder adGroupAd = AdGroupAd.newBuilder()
                .setAdGroup(adGroupResource)
                .setStatus(AdGroupAdStatus.ENABLED)
                .setAd(Ad.newBuilder().addFinalUrls(finalUrl).setResponsiveSearchAd(rsa));
        return MutateOperation.newBuilder()
                .setAdGroupAdOperation(AdGroupAdOperation.newBuilder().setCreate(adGroupAd))
                .build();
    }

    private MutateOperation changeBidding(ChangeBiddingStrategy r) {
        requireKind(r.campaign(), EntityKind.CAMPAIGN);
        Campaign.Builder campaign = Campaign.newBuilder().setResourceName(names.campaign(r.campaign().id()));
        // Switching the oneof needs the new member named in the mask; the old one is cleared by Google.
        String maskPath = applyBidding(campaign, r.strategy());
        return MutateOperation.newBuilder()
                .setCampaignOperation(CampaignOperation.newBuilder()
                        .setUpdate(campaign)
                        .setUpdateMask(mask(maskPath)))
                .build();
    }

    /** Sets the bidding strategy oneof on {@code campaign} and returns the field mask path that names it. */
    private static String applyBidding(Campaign.Builder campaign, BiddingStrategy strategy) {
        return switch (strategy.type()) {
            case MANUAL_CPC -> {
                campaign.setManualCpc(ManualCpc.newBuilder().setEnhancedCpcEnabled(false));
                yield "manual_cpc.enhanced_cpc_enabled";
            }
            case MAXIMIZE_CONVERSIONS -> {
                campaign.setMaximizeConversions(MaximizeConversions.getDefaultInstance());
                yield "maximize_conversions";
            }
            case MAXIMIZE_CONVERSION_VALUE -> {
                campaign.setMaximizeConversionValue(MaximizeConversionValue.getDefaultInstance());
                yield "maximize_conversion_value";
            }
            case TARGET_CPA -> {
                campaign.setTargetCpa(TargetCpa.newBuilder().setTargetCpaMicros(strategy.targetCpaMicros()));
                yield "target_cpa.target_cpa_micros";
            }
            case TARGET_ROAS -> {
                campaign.setTargetRoas(TargetRoas.newBuilder().setTargetRoas(strategy.targetRoas()));
                yield "target_roas.target_roas";
            }
        };
    }

    private Plan applyRecommendation(ApplyRecommendation r) {
        requireKind(r.recommendation(), EntityKind.RECOMMENDATION);
        ApplyRecommendationOperation op = ApplyRecommendationOperation.newBuilder()
                .setResourceName(names.recommendation(r.recommendation().id()))
                .build();
        return new Plan(List.of(), List.of(), op);
    }

    // ---- rollback only --------------------------------------------------------------------

    /**
     * Removes the listed negative keyword criteria. Ids are plain criterion ids; an id already written
     * as "{ownerId}~{criterionId}" is accepted if the owner matches the scope.
     */
    private Plan removeNegatives(RemoveNegativeKeywords r) {
        EntityRef scope = r.scope();
        if (r.criterionIds().isEmpty()) {
            throw new IllegalArgumentException("nothing to remove");
        }
        List<MutateOperation> ops = new ArrayList<>();
        for (String raw : r.criterionIds()) {
            String criterionId = raw;
            if (raw.contains("~")) {
                String[] parts = ResourceNames.split(raw);
                if (!parts[0].equals(scope.id())) {
                    throw new IllegalArgumentException("criterion " + raw + " does not belong to " + scope);
                }
                criterionId = parts[1];
            }
            ResourceNames.numeric(criterionId);
            if (scope.kind() == EntityKind.CAMPAIGN) {
                ops.add(MutateOperation.newBuilder()
                        .setCampaignCriterionOperation(CampaignCriterionOperation.newBuilder()
                                .setRemove(names.campaignCriterion(scope.id(), criterionId)))
                        .build());
            } else if (scope.kind() == EntityKind.AD_GROUP) {
                ops.add(MutateOperation.newBuilder()
                        .setAdGroupCriterionOperation(AdGroupCriterionOperation.newBuilder()
                                .setRemove(names.adGroupCriterion(ResourceNames.numeric(scope.id()) + "~" + criterionId)))
                        .build());
            } else {
                throw new IllegalArgumentException("negative keywords live on a campaign or an ad group, not " + scope.kind());
            }
        }
        return new Plan(ops, java.util.Collections.nCopies(ops.size(), null), null);
    }

    // ---- create_campaign ------------------------------------------------------------------

    /**
     * One atomic request, in dependency order, with temporary negative ids:
     * <pre>
     *   CampaignBudget(-1)            not shared, daily amount
     *   Campaign(-2)                  PAUSED, Search only, Google Search only, PRESENCE targeting
     *   CampaignCriterion x N         locations (never languages)
     *   AdGroup(-3)
     *   AdGroupCriterion x N          keywords
     *   AdGroupAd                     responsive search ad
     *   [Label(-4)]                   only when the managed label does not exist yet
     *   CampaignLabel                 managed label on the campaign
     * </pre>
     */
    private Plan createCampaign(CampaignSpec spec, String existingLabelResourceName) {
        validate(spec);
        String budget = names.temp("campaignBudgets", TEMP_BUDGET);
        String campaign = names.temp("campaigns", TEMP_CAMPAIGN);
        String adGroup = names.temp("adGroups", TEMP_AD_GROUP);

        List<MutateOperation> ops = new ArrayList<>();
        List<String> tags = new ArrayList<>();

        // Budget: explicitly not shared. A name is only required for shared budgets.
        ops.add(MutateOperation.newBuilder()
                .setCampaignBudgetOperation(CampaignBudgetOperation.newBuilder().setCreate(CampaignBudget.newBuilder()
                        .setResourceName(budget)
                        .setAmountMicros(spec.dailyBudgetMicros())
                        .setDeliveryMethod(BudgetDeliveryMethod.STANDARD)
                        .setExplicitlyShared(false)))
                .build());
        tags.add(TAG_BUDGET);

        ops.add(MutateOperation.newBuilder()
                .setCampaignOperation(CampaignOperation.newBuilder().setCreate(campaignMessage(spec, campaign, budget)))
                .build());
        tags.add(TAG_CAMPAIGN);

        // Locations only. Language criteria are deliberately never sent: Google rejects them on Search.
        for (long geoTargetId : spec.geoTargetIds()) {
            ops.add(MutateOperation.newBuilder()
                    .setCampaignCriterionOperation(CampaignCriterionOperation.newBuilder().setCreate(CampaignCriterion.newBuilder()
                            .setCampaign(campaign)
                            .setLocation(LocationInfo.newBuilder().setGeoTargetConstant(ResourceNames.geoTarget(geoTargetId)))))
                    .build());
            tags.add(null);
        }

        AdGroup.Builder adGroupMessage = searchAdGroup(adGroup, spec.adGroupName(), campaign);
        if (spec.bidding().type() == BiddingStrategy.Type.MANUAL_CPC) {
            // Manual CPC needs a default bid at ad group level; use the highest keyword bid the spec gave.
            spec.keywords().stream()
                    .map(KeywordSpec::cpcBidMicros)
                    .filter(java.util.Objects::nonNull)
                    .max(Long::compare)
                    .ifPresent(adGroupMessage::setCpcBidMicros);
        }
        ops.add(MutateOperation.newBuilder()
                .setAdGroupOperation(AdGroupOperation.newBuilder().setCreate(adGroupMessage))
                .build());
        tags.add(TAG_AD_GROUP);

        for (KeywordSpec k : spec.keywords()) {
            ops.add(keywordOp(adGroup, k));
            tags.add(TAG_KEYWORD_CRITERION);
        }

        ops.add(adOp(adGroup, spec.headlines(), spec.descriptions(), spec.finalUrl()));
        tags.add(TAG_AD);

        for (TaggedOp t : labelOps(campaign, existingLabelResourceName, TEMP_LABEL)) {
            ops.add(t.op());
            tags.add(t.tag());
        }
        return new Plan(ops, tags, null);
    }

    /** An ENABLED Search ad group in the campaign with the given resource name (existing or temporary). */
    private static AdGroup.Builder searchAdGroup(String adGroupResource, String name, String campaignResource) {
        return AdGroup.newBuilder()
                .setResourceName(adGroupResource)
                .setName(requireText(name, "ad group name"))
                .setCampaign(campaignResource)
                .setStatus(AdGroupStatus.ENABLED)
                .setType(AdGroupType.SEARCH_STANDARD);
    }

    private Campaign campaignMessage(CampaignSpec spec, String campaignResource, String budgetResource) {
        Campaign.Builder c = Campaign.newBuilder()
                .setResourceName(campaignResource)
                .setName(requireText(spec.name(), "campaign name"))
                .setStatus(CampaignStatus.PAUSED)
                .setAdvertisingChannelType(AdvertisingChannelType.SEARCH)
                .setCampaignBudget(budgetResource)
                .setNetworkSettings(Campaign.NetworkSettings.newBuilder()
                        .setTargetGoogleSearch(true)
                        .setTargetSearchNetwork(false)
                        .setTargetContentNetwork(false)
                        .setTargetPartnerSearchNetwork(false))
                .setGeoTargetTypeSetting(Campaign.GeoTargetTypeSetting.newBuilder()
                        .setPositiveGeoTargetType(PositiveGeoTargetType.PRESENCE))
                .setContainsEuPoliticalAdvertising(switch (spec.euPoliticalAds()) {
                    case NO_POLITICAL_ADS -> EuPoliticalAdvertisingStatus.DOES_NOT_CONTAIN_EU_POLITICAL_ADVERTISING;
                    case HAS_POLITICAL_ADS -> EuPoliticalAdvertisingStatus.CONTAINS_EU_POLITICAL_ADVERTISING;
                });
        applyBidding(c, spec.bidding());
        if (explicitAutomationOptOuts) {
            // Google turns some automation on by default; a new campaign must not start with any of it.
            c.setAiMaxSetting(Campaign.AiMaxSetting.newBuilder().setEnableAiMax(false));
            c.addAssetAutomationSettings(optedOut(AssetAutomationType.TEXT_ASSET_AUTOMATION));
            c.addAssetAutomationSettings(optedOut(AssetAutomationType.FINAL_URL_EXPANSION_TEXT_ASSET_AUTOMATION));
        }
        return c.build();
    }

    private static Campaign.AssetAutomationSetting optedOut(AssetAutomationType type) {
        return Campaign.AssetAutomationSetting.newBuilder()
                .setAssetAutomationType(type)
                .setAssetAutomationStatus(AssetAutomationStatus.OPTED_OUT)
                .build();
    }

    private static void validate(CampaignSpec spec) {
        if (spec.dailyBudgetMicros() <= 0) {
            throw new IllegalArgumentException("daily budget must be positive");
        }
        if (spec.bidding() == null) {
            throw new IllegalArgumentException("bidding strategy is required");
        }
        if (spec.geoTargetIds().isEmpty()) {
            throw new IllegalArgumentException("at least one geo target is required");
        }
        if (spec.euPoliticalAds() == null) {
            throw new IllegalArgumentException("the EU political advertising declaration is required");
        }
    }

    // ---- add_ad_group ---------------------------------------------------------------------

    /**
     * One atomic request, in dependency order, with a temporary negative id for the new ad group:
     * <pre>
     *   AdGroup(-3)                   ENABLED, Search standard, in the existing campaign
     *   AdGroupCriterion x N          keywords, in the order given
     *   AdGroupAd                     responsive search ad
     * </pre>
     * The campaign is only referenced, never changed. No language criteria and no REMOVE operations.
     * Result tags: {@code adGroup}, then one {@code criterion.keyword} per keyword, then {@code ad}.
     */
    private Plan addAdGroup(AddAdGroup r) {
        requireKind(r.campaign(), EntityKind.CAMPAIGN);
        if (r.keywords().isEmpty()) {
            throw new IllegalArgumentException("add_ad_group needs at least one keyword");
        }
        if (r.cpcBidMicros() != null && r.cpcBidMicros() <= 0) {
            throw new IllegalArgumentException("cpc bid must be positive");
        }
        String adGroup = names.temp("adGroups", TEMP_AD_GROUP);

        List<MutateOperation> ops = new ArrayList<>();
        List<String> tags = new ArrayList<>();

        AdGroup.Builder adGroupMessage = searchAdGroup(adGroup, r.name(), names.campaign(r.campaign().id()));
        if (r.cpcBidMicros() != null) {
            adGroupMessage.setCpcBidMicros(r.cpcBidMicros());
        }
        ops.add(MutateOperation.newBuilder()
                .setAdGroupOperation(AdGroupOperation.newBuilder().setCreate(adGroupMessage))
                .build());
        tags.add(TAG_AD_GROUP);

        for (KeywordSpec k : r.keywords()) {
            ops.add(keywordOp(adGroup, k));
            tags.add(TAG_KEYWORD_CRITERION);
        }

        ops.add(adOp(adGroup, r.headlines(), r.descriptions(), r.finalUrl()));
        tags.add(TAG_AD);
        return new Plan(ops, tags, null);
    }

    // ---- labels ---------------------------------------------------------------------------

    private record TaggedOp(MutateOperation op, String tag) {}

    /**
     * Operations that put the managed label on a campaign inside the same atomic request.
     *
     * @param campaignResourceName the campaign's resource name (a temporary one for a campaign created in the same request)
     * @param existingLabelResourceName resource name of the label when it already exists, else null
     * @return a CampaignLabel create; preceded by a Label create (temporary id) when the label does not exist yet
     */
    public List<MutateOperation> ensureLabelOps(String campaignResourceName, String existingLabelResourceName) {
        return labelOps(campaignResourceName, existingLabelResourceName, TEMP_LABEL).stream().map(TaggedOp::op).toList();
    }

    private List<TaggedOp> labelOps(String campaignResourceName, String existingLabelResourceName, long tempLabelId) {
        List<TaggedOp> out = new ArrayList<>();
        String labelResource = existingLabelResourceName;
        if (labelResource == null || labelResource.isBlank()) {
            labelResource = names.temp("labels", tempLabelId);
            out.add(new TaggedOp(MutateOperation.newBuilder()
                    .setLabelOperation(LabelOperation.newBuilder().setCreate(Label.newBuilder()
                            .setResourceName(labelResource)
                            .setName(managedLabel)
                            .setTextLabel(TextLabel.newBuilder().setDescription("Campaigns the Ads Autopilot server may manage"))))
                    .build(), TAG_LABEL));
        }
        out.add(new TaggedOp(MutateOperation.newBuilder()
                .setCampaignLabelOperation(CampaignLabelOperation.newBuilder().setCreate(CampaignLabel.newBuilder()
                        .setCampaign(campaignResourceName)
                        .setLabel(labelResource)))
                .build(), null));
        return out;
    }

    // ---- ids from the response ------------------------------------------------------------

    /**
     * Reads the ids of what a real (not validate_only) mutate created.
     * <ul>
     *   <li>{@code campaign}, {@code budget}, {@code adGroup}: the new id</li>
     *   <li>{@code ad}: "{adGroupId}~{adId}"</li>
     *   <li>{@code label}: id of a label created by the request</li>
     *   <li>{@code criterion:0}, {@code criterion:1}, ...: created keyword criteria in operation order;
     *       negatives give the plain criterion id, positive keywords "{adGroupId}~{criterionId}".
     *       Location criteria of a new campaign are not listed.</li>
     *   <li>{@code add_ad_group} gives {@code adGroup}, {@code criterion:i} for the i-th keyword and {@code ad}.</li>
     * </ul>
     */
    public Map<String, String> createdIds(Plan plan, MutateGoogleAdsResponse response) {
        Map<String, String> ids = new LinkedHashMap<>();
        List<MutateOperationResponse> results = response.getMutateOperationResponsesList();
        int criterion = 0;
        for (int i = 0; i < Math.min(results.size(), plan.tags().size()); i++) {
            String tag = plan.tags().get(i);
            if (tag == null) {
                continue;
            }
            String last = ResourceNames.lastSegment(resourceNameOf(results.get(i)));
            if (last.isEmpty()) {
                continue;
            }
            switch (tag) {
                case TAG_CAMPAIGN, TAG_BUDGET, TAG_AD_GROUP, TAG_AD, TAG_LABEL -> ids.put(tag, last);
                case TAG_KEYWORD_CRITERION -> ids.put("criterion:" + criterion++, last);
                case TAG_NEGATIVE_CRITERION -> ids.put("criterion:" + criterion++, last.substring(last.indexOf('~') + 1));
                default -> { }
            }
        }
        return ids;
    }

    /** Every resource name in the response, in operation order. */
    public static List<String> resourceNames(MutateGoogleAdsResponse response) {
        List<String> out = new ArrayList<>();
        for (MutateOperationResponse r : response.getMutateOperationResponsesList()) {
            String name = resourceNameOf(r);
            if (!name.isEmpty()) {
                out.add(name);
            }
        }
        return out;
    }

    private static String resourceNameOf(MutateOperationResponse r) {
        return switch (r.getResponseCase()) {
            case CAMPAIGN_BUDGET_RESULT -> r.getCampaignBudgetResult().getResourceName();
            case CAMPAIGN_RESULT -> r.getCampaignResult().getResourceName();
            case CAMPAIGN_CRITERION_RESULT -> r.getCampaignCriterionResult().getResourceName();
            case AD_GROUP_RESULT -> r.getAdGroupResult().getResourceName();
            case AD_GROUP_CRITERION_RESULT -> r.getAdGroupCriterionResult().getResourceName();
            case AD_GROUP_AD_RESULT -> r.getAdGroupAdResult().getResourceName();
            case LABEL_RESULT -> r.getLabelResult().getResourceName();
            case CAMPAIGN_LABEL_RESULT -> r.getCampaignLabelResult().getResourceName();
            default -> "";
        };
    }

    // ---- small helpers --------------------------------------------------------------------

    private static KeywordInfo keyword(String text, MatchType matchType) {
        if (matchType == null) {
            throw new IllegalArgumentException("match type is required");
        }
        return KeywordInfo.newBuilder()
                .setText(requireText(text, "keyword text"))
                .setMatchType(switch (matchType) {
                    case EXACT -> KeywordMatchType.EXACT;
                    case PHRASE -> KeywordMatchType.PHRASE;
                    case BROAD -> KeywordMatchType.BROAD;
                })
                .build();
    }

    private static String requireText(String s, String what) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException(what + " is required");
        }
        return s.strip();
    }

    private static void requireKind(EntityRef ref, EntityKind expected) {
        if (ref == null || ref.kind() != expected) {
            throw new IllegalArgumentException("expected a " + expected + " but got " + (ref == null ? "nothing" : ref.kind()));
        }
    }

    private static FieldMask mask(String... paths) {
        return FieldMask.newBuilder().addAllPaths(List.of(paths)).build();
    }
}

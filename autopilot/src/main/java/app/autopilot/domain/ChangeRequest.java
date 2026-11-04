package app.autopilot.domain;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every change the server can send to Google Ads. There is deliberately no generic mutate: each type
 * has its own rules, so every write can be checked against a known rule.
 *
 * <p>The tier is a property of the type, decided here, never supplied by the caller.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "tool")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ChangeRequest.AddNegativeKeywords.class, name = "add_negative_keywords"),
        @JsonSubTypes.Type(value = ChangeRequest.PauseKeyword.class, name = "pause_keyword"),
        @JsonSubTypes.Type(value = ChangeRequest.AdjustKeywordBid.class, name = "adjust_keyword_bid"),
        @JsonSubTypes.Type(value = ChangeRequest.DecreaseBudget.class, name = "decrease_budget"),
        @JsonSubTypes.Type(value = ChangeRequest.EmergencyPauseCampaign.class, name = "emergency_pause_campaign"),
        @JsonSubTypes.Type(value = ChangeRequest.PauseAd.class, name = "pause_ad"),
        @JsonSubTypes.Type(value = ChangeRequest.AddKeywords.class, name = "add_keywords"),
        @JsonSubTypes.Type(value = ChangeRequest.CreateAd.class, name = "create_ad"),
        @JsonSubTypes.Type(value = ChangeRequest.CreateCampaign.class, name = "create_campaign"),
        @JsonSubTypes.Type(value = ChangeRequest.AddAdGroup.class, name = "add_ad_group"),
        @JsonSubTypes.Type(value = ChangeRequest.IncreaseBudget.class, name = "increase_budget"),
        @JsonSubTypes.Type(value = ChangeRequest.ChangeBiddingStrategy.class, name = "change_bidding_strategy"),
        @JsonSubTypes.Type(value = ChangeRequest.EnableEntity.class, name = "enable_entity"),
        @JsonSubTypes.Type(value = ChangeRequest.ApplyRecommendation.class, name = "apply_recommendation"),
        @JsonSubTypes.Type(value = ChangeRequest.RemoveNegativeKeywords.class, name = "rollback_remove_negative_keywords"),
        @JsonSubTypes.Type(value = ChangeRequest.PauseEntity.class, name = "rollback_pause_entity"),
})
public sealed interface ChangeRequest {

    /** Tool name as shown to Claude and written to the audit log. */
    String tool();

    Tier tier();

    /** The entity this change acts on, or the parent that will own a new entity. */
    EntityRef target();

    /** The values after the change, for diff display and audit. Money in account-currency micros. */
    Map<String, Object> newValues();

    /** Identifies "same tool, same entity, same target value" for duplicate detection. */
    default String fingerprint() {
        return tool() + "|" + (target() == null ? "-" : target().key()) + "|" + newValues();
    }

    /**
     * Additive changes create new entities (negatives, keywords, ads), so they are governed by volume limits
     * and duplicate checks instead of a per-entity cooldown, and the ledger records them under their own key so
     * they do not start a cooldown on the campaign or ad group they were added to.
     */
    default boolean cooldownApplies() {
        return true;
    }

    /** The key the ledger records this change under; cooldowns and intervals look entities up by it. */
    default String ledgerKey() {
        return target() == null ? null : target().key();
    }

    /** True for changes that exist only as the inverse of an earlier change (reachable only via rollback). */
    default boolean rollbackOnly() {
        return false;
    }

    // ---- Tier 1 -------------------------------------------------------------------------------

    record AddNegativeKeywords(EntityRef scope, List<String> terms, MatchType matchType) implements ChangeRequest {
        public AddNegativeKeywords {
            terms = List.copyOf(terms);
        }
        public String tool() { return "add_negative_keywords"; }
        @Override public boolean cooldownApplies() { return false; }
        @Override public String ledgerKey() { return target().key() + "#" + tool(); }
        public Tier tier() { return Tier.T1; }
        public EntityRef target() { return scope; }
        public Map<String, Object> newValues() { return Map.of("terms", terms, "matchType", matchType.name()); }
    }

    record PauseKeyword(EntityRef keyword) implements ChangeRequest {
        public String tool() { return "pause_keyword"; }
        public Tier tier() { return Tier.T1; }
        public EntityRef target() { return keyword; }
        public Map<String, Object> newValues() { return Map.of("status", "PAUSED"); }
    }

    record AdjustKeywordBid(EntityRef keyword, long newCpcBidMicros) implements ChangeRequest {
        public String tool() { return "adjust_keyword_bid"; }
        public Tier tier() { return Tier.T1; }
        public EntityRef target() { return keyword; }
        public Map<String, Object> newValues() { return Map.of("cpcBidMicros", newCpcBidMicros); }
    }

    record DecreaseBudget(EntityRef budget, long newAmountMicros) implements ChangeRequest {
        public String tool() { return "decrease_budget"; }
        public Tier tier() { return Tier.T1; }
        public EntityRef target() { return budget; }
        public Map<String, Object> newValues() { return Map.of("amountMicros", newAmountMicros); }
    }

    /** Only while a spend cap is breached. */
    record EmergencyPauseCampaign(EntityRef campaign) implements ChangeRequest {
        public String tool() { return "emergency_pause_campaign"; }
        public Tier tier() { return Tier.T1; }
        public EntityRef target() { return campaign; }
        public Map<String, Object> newValues() { return Map.of("status", "PAUSED"); }
    }

    // ---- Tier 2 -------------------------------------------------------------------------------

    record PauseAd(EntityRef ad) implements ChangeRequest {
        public String tool() { return "pause_ad"; }
        public Tier tier() { return Tier.T2; }
        public EntityRef target() { return ad; }
        public Map<String, Object> newValues() { return Map.of("status", "PAUSED"); }
    }

    record KeywordSpec(String text, MatchType matchType, Long cpcBidMicros) {
        public KeywordSpec {
            if (text == null || text.isBlank()) throw new IllegalArgumentException("keyword text is required");
            if (matchType == null) throw new IllegalArgumentException("keyword match type is required");
        }
    }

    record AddKeywords(EntityRef adGroup, List<KeywordSpec> keywords) implements ChangeRequest {
        public AddKeywords {
            keywords = List.copyOf(keywords);
        }
        public String tool() { return "add_keywords"; }
        @Override public boolean cooldownApplies() { return false; }
        @Override public String ledgerKey() { return target().key() + "#" + tool(); }
        public Tier tier() { return Tier.T2; }
        public EntityRef target() { return adGroup; }
        public Map<String, Object> newValues() { return Map.of("keywords", keywords); }
    }

    record CreateAd(EntityRef adGroup, List<String> headlines, List<String> descriptions, String finalUrl)
            implements ChangeRequest {
        public CreateAd {
            headlines = List.copyOf(headlines);
            descriptions = List.copyOf(descriptions);
        }
        public String tool() { return "create_ad"; }
        @Override public boolean cooldownApplies() { return false; }
        @Override public String ledgerKey() { return target().key() + "#" + tool(); }
        public Tier tier() { return Tier.T2; }
        public EntityRef target() { return adGroup; }
        public Map<String, Object> newValues() {
            return Map.of("headlines", headlines, "descriptions", descriptions, "finalUrl", finalUrl);
        }
    }

    /** Declared explicitly by the owner in the approval diff; there is no default. */
    enum EuPoliticalAds { NO_POLITICAL_ADS, HAS_POLITICAL_ADS }

    record CampaignSpec(
            String name,
            long dailyBudgetMicros,
            BiddingStrategy bidding,
            List<Long> geoTargetIds,
            String adGroupName,
            List<KeywordSpec> keywords,
            List<String> headlines,
            List<String> descriptions,
            String finalUrl,
            EuPoliticalAds euPoliticalAds) {
        public CampaignSpec {
            geoTargetIds = List.copyOf(geoTargetIds);
            keywords = List.copyOf(keywords);
            headlines = List.copyOf(headlines);
            descriptions = List.copyOf(descriptions);
        }
    }

    /** Always created PAUSED, with budget, targeting and ad group in one atomic request. */
    record CreateCampaign(CampaignSpec spec) implements ChangeRequest {
        public String tool() { return "create_campaign"; }
        public Tier tier() { return Tier.T2; }
        public EntityRef target() { return null; }
        public Map<String, Object> newValues() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", spec.name());
            m.put("status", "PAUSED");
            m.put("dailyBudgetMicros", spec.dailyBudgetMicros());
            m.put("bidding", spec.bidding() == null ? "UNSET" : spec.bidding().describe());
            m.put("geoTargetIds", spec.geoTargetIds());
            m.put("adGroup", spec.adGroupName());
            m.put("keywords", spec.keywords());
            m.put("headlines", spec.headlines());
            m.put("descriptions", spec.descriptions());
            m.put("finalUrl", spec.finalUrl());
            m.put("euPoliticalAds", spec.euPoliticalAds() == null ? "UNSET" : spec.euPoliticalAds().name());
            m.put("targetContentNetwork", false);
            m.put("targetSearchNetwork", false);
            m.put("geoTargetType", "PRESENCE");
            return m;
        }
        @Override public String fingerprint() { return tool() + "|new|" + spec.name(); }
    }

    /**
     * A new ad group in an existing campaign: its keywords and one responsive search ad, in one atomic request. Created
     * ENABLED inside a campaign the owner already runs, so it is Tier 2 and the owner sees the keywords and the ad copy.
     */
    record AddAdGroup(EntityRef campaign, String name, List<KeywordSpec> keywords, List<String> headlines,
                      List<String> descriptions, String finalUrl, Long cpcBidMicros) implements ChangeRequest {
        public AddAdGroup {
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
            headlines = headlines == null ? List.of() : List.copyOf(headlines);
            descriptions = descriptions == null ? List.of() : List.copyOf(descriptions);
        }
        public String tool() { return "add_ad_group"; }
        public Tier tier() { return Tier.T2; }
        public EntityRef target() { return campaign; }
        public Map<String, Object> newValues() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("keywords", keywords);
            m.put("headlines", headlines);
            m.put("descriptions", descriptions);
            m.put("finalUrl", finalUrl);
            if (cpcBidMicros != null) m.put("cpcBidMicros", cpcBidMicros);
            return m;
        }
        @Override public boolean cooldownApplies() { return false; }
        @Override public String ledgerKey() { return campaign.key() + "#" + tool(); }
        @Override public String fingerprint() { return tool() + "|" + campaign.key() + "|" + name; }
    }

    record IncreaseBudget(EntityRef budget, long newAmountMicros) implements ChangeRequest {
        public String tool() { return "increase_budget"; }
        public Tier tier() { return Tier.T2; }
        public EntityRef target() { return budget; }
        public Map<String, Object> newValues() { return Map.of("amountMicros", newAmountMicros); }
    }

    record ChangeBiddingStrategy(EntityRef campaign, BiddingStrategy strategy) implements ChangeRequest {
        public String tool() { return "change_bidding_strategy"; }
        public Tier tier() { return Tier.T2; }
        public EntityRef target() { return campaign; }
        public Map<String, Object> newValues() { return Map.of("bidding", strategy.describe()); }
    }

    /** Starts delivery, so it is always approved separately. */
    record EnableEntity(EntityRef entity) implements ChangeRequest {
        public String tool() { return "enable_entity"; }
        public Tier tier() { return Tier.T2; }
        public EntityRef target() { return entity; }
        public Map<String, Object> newValues() { return Map.of("status", "ENABLED"); }
    }

    record ApplyRecommendation(EntityRef recommendation) implements ChangeRequest {
        public String tool() { return "apply_recommendation"; }
        public Tier tier() { return Tier.T2; }
        public EntityRef target() { return recommendation; }
        public Map<String, Object> newValues() { return Map.of("apply", recommendation.id()); }
    }

    // ---- Rollback only (never exposed as an MCP tool) --------------------------------------

    /** Inverse of add_negative_keywords: removes only criteria this system created. */
    record RemoveNegativeKeywords(EntityRef scope, List<String> criterionIds) implements ChangeRequest {
        public RemoveNegativeKeywords {
            criterionIds = List.copyOf(criterionIds);
        }
        public String tool() { return "rollback_remove_negative_keywords"; }
        public Tier tier() { return Tier.T2; }
        public EntityRef target() { return scope; }
        public Map<String, Object> newValues() { return Map.of("removeCriteria", criterionIds); }
        @Override public boolean rollbackOnly() { return true; }
    }

    /** Inverse of enable_entity and of create_campaign. */
    record PauseEntity(EntityRef entity) implements ChangeRequest {
        public String tool() { return "rollback_pause_entity"; }
        public Tier tier() { return Tier.T2; }
        public EntityRef target() { return entity; }
        public Map<String, Object> newValues() { return Map.of("status", "PAUSED"); }
        @Override public boolean rollbackOnly() { return true; }
    }
}

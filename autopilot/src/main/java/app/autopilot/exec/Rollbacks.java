package app.autopilot.exec;

import app.autopilot.domain.BiddingStrategy;
import app.autopilot.domain.ChangeRequest;
import app.autopilot.domain.ChangeRequest.*;
import app.autopilot.domain.EntityRef;
import app.autopilot.store.Json;
import app.autopilot.store.LedgerStore.ChangeRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The inverse of a recorded change, built only from what was recorded. A change without a safe inverse has
 * none here, so propose_rollback refuses it instead of guessing:
 * removing keywords, ads or recommendations is Tier 3 (done in the Google Ads interface).
 */
final class Rollbacks {
    private Rollbacks() {}

    static Optional<ChangeRequest> inverse(ChangeRecord rec, Json json) {
        if (!"APPLIED".equals(rec.result())) return Optional.empty();
        ChangeRequest original;
        try {
            original = json.read(rec.requestJson(), ChangeRequest.class);
        } catch (RuntimeException e) {
            return Optional.empty(); // an unreadable record is not guessed at
        }
        Map<String, Object> old = rec.oldValues();
        return switch (original) {
            case DecreaseBudget c -> number(old, "amountMicros").map(v -> new IncreaseBudget(c.budget(), v));
            case IncreaseBudget c -> number(old, "amountMicros").map(v -> new DecreaseBudget(c.budget(), v));
            case AdjustKeywordBid c -> number(old, "cpcBidMicros").map(v -> new AdjustKeywordBid(c.keyword(), v));
            case PauseKeyword c -> Optional.of(new EnableEntity(c.keyword()));
            case PauseAd c -> Optional.of(new EnableEntity(c.ad()));
            case EmergencyPauseCampaign c -> Optional.of(new EnableEntity(c.campaign()));
            case EnableEntity c -> Optional.of(new PauseEntity(c.entity()));
            case PauseEntity c -> Optional.of(new EnableEntity(c.entity()));
            case CreateCampaign c -> createdId(rec, "campaign").map(id -> new PauseEntity(EntityRef.campaign(id)));
            case AddAdGroup c -> createdId(rec, "adGroup").map(id -> new PauseEntity(EntityRef.adGroup(id)));
            case ChangeBiddingStrategy c -> oldStrategy(old).map(s -> new ChangeBiddingStrategy(c.campaign(), s));
            case AddNegativeKeywords c -> {
                List<String> ids = new ArrayList<>();
                for (int i = 0; i < c.terms().size(); i++) {
                    Optional<String> id = createdId(rec, "criterion:" + i);
                    if (id.isEmpty()) yield Optional.empty();
                    ids.add(id.get());
                }
                yield Optional.of(new RemoveNegativeKeywords(c.scope(), ids));
            }
            case AddKeywords c -> Optional.empty();
            case CreateAd c -> Optional.empty();
            case ApplyRecommendation c -> Optional.empty();
            case RemoveNegativeKeywords c -> Optional.empty();
        };
    }

    /** Why a change has no inverse, for the refusal message. */
    static String noInverseReason(ChangeRecord rec) {
        return switch (rec.tool()) {
            case "add_keywords", "create_ad" -> "removing keywords or ads is Tier 3; remove them in the Google Ads interface";
            case "apply_recommendation" -> "an applied recommendation has no recorded inverse";
            case "rollback_remove_negative_keywords" -> "removed negatives are not restored automatically";
            default -> "the record does not hold the values needed to restore it";
        };
    }

    private static Optional<Long> number(Map<String, Object> m, String key) {
        return m.get(key) instanceof Number n ? Optional.of(n.longValue()) : Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static Optional<String> createdId(ChangeRecord rec, String key) {
        Object ids = rec.newValues().get("createdIds");
        if (ids instanceof Map<?, ?> m && m.get(key) != null) return Optional.of(m.get(key).toString());
        return Optional.empty();
    }

    private static Optional<BiddingStrategy> oldStrategy(Map<String, Object> old) {
        Object type = old.get("biddingType");
        if (type == null) return Optional.empty();
        try {
            BiddingStrategy.Type t = BiddingStrategy.Type.valueOf(type.toString());
            Long cpa = number(old, "targetCpaMicros").orElse(null);
            Double roas = old.get("targetRoas") instanceof Number n ? n.doubleValue() : null;
            return Optional.of(new BiddingStrategy(t, cpa, roas));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}

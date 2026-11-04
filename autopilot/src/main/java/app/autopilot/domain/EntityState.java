package app.autopilot.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the guard needs to know about one entity, read live from Google Ads.
 *
 * @param campaignLabels labels on the owning campaign (the scope label {@code agent-managed} lives here)
 * @param chainLabels    labels on the entity and every ancestor (a {@code no-agent} anywhere blocks)
 * @param attrs          kind-specific values, for example {@code amountMicros} or {@code cpcBidMicros}
 */
public record EntityState(
        EntityRef ref,
        String status,
        String campaignId,
        String adGroupId,
        Set<String> campaignLabels,
        Set<String> chainLabels,
        Map<String, Object> attrs) {

    public EntityState {
        campaignLabels = campaignLabels == null ? Set.of() : Set.copyOf(campaignLabels);
        chainLabels = chainLabels == null ? Set.of() : Set.copyOf(chainLabels);
        attrs = attrs == null ? Map.of() : Map.copyOf(attrs);
    }

    public Long longAttr(String name) {
        Object v = attrs.get(name);
        return v instanceof Number n ? n.longValue() : null;
    }

    public Double doubleAttr(String name) {
        Object v = attrs.get(name);
        return v instanceof Number n ? n.doubleValue() : null;
    }

    public String stringAttr(String name) {
        Object v = attrs.get(name);
        return v == null ? null : v.toString();
    }

    @SuppressWarnings("unchecked")
    public List<String> listAttr(String name) {
        Object v = attrs.get(name);
        return v instanceof List<?> l ? (List<String>) l : List.of();
    }
}

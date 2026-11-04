package app.autopilot.guard;

import app.autopilot.domain.EntityState;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Hash of the parts of an entity's state that a proposal depends on. Metrics are left out on purpose:
 * they change every day and would make every proposal stale overnight. Limits are re-checked separately.
 */
public final class StateHasher {
    private StateHasher() {}

    private static final Set<String> STRUCTURAL = Set.of(
            "amountMicros", "explicitlyShared", "campaignIds", "budgetId", "biddingStrategy", "targetCpaMicros",
            "cpcBidMicros", "text", "matchType", "adGroupActiveKeywords", "adGroupEnabledAds", "type");

    public static String hash(List<EntityState> states) {
        StringBuilder sb = new StringBuilder();
        states.stream().sorted(Comparator.comparing(s -> s.ref().key())).forEach(s -> {
            sb.append(s.ref().key()).append('|').append(s.status()).append('|')
                    .append(new TreeSet<>(s.campaignLabels())).append('|')
                    .append(new TreeSet<>(s.chainLabels())).append('|');
            TreeMap<String, Object> attrs = new TreeMap<>();
            s.attrs().forEach((k, v) -> {
                if (STRUCTURAL.contains(k)) attrs.put(k, v);
            });
            sb.append(attrs).append('\n');
        });
        return sha256(sb.toString());
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

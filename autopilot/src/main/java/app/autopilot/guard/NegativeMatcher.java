package app.autopilot.guard;

import app.autopilot.domain.MatchType;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Decides whether a negative keyword would block a piece of text. Used to refuse negatives that would
 * block an active keyword, a protected term or a term that converted.
 */
public final class NegativeMatcher {
    private NegativeMatcher() {}

    /** Lowercase tokens with match-type symbols and punctuation removed. */
    public static List<String> tokens(String text) {
        String t = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        t = t.replaceAll("[\\[\\]\"+]", " ").replaceAll("[^\\p{L}\\p{N}' -]", " ");
        List<String> out = new ArrayList<>();
        for (String s : t.trim().split("\\s+")) {
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    public static String normalize(String text) {
        return String.join(" ", tokens(text));
    }

    /**
     * EXACT blocks the query only when all tokens are equal; PHRASE blocks any query that contains the
     * tokens contiguously and in order.
     */
    public static boolean blocks(String negative, MatchType matchType, String query) {
        List<String> neg = tokens(negative);
        List<String> q = tokens(query);
        if (neg.isEmpty() || q.isEmpty()) return false;
        if (matchType == MatchType.EXACT) {
            return neg.equals(q);
        }
        for (int i = 0; i + neg.size() <= q.size(); i++) {
            if (q.subList(i, i + neg.size()).equals(neg)) return true;
        }
        return false;
    }
}

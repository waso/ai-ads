package app.autopilot.guard;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Heuristic for free text that looks like an instruction rather than a search query. Search terms are
 * typed by strangers and reach Claude as data. Terms that match go to a review list and can never become
 * keywords automatically. This is a second layer: the hard limits hold whatever Claude reads.
 */
public final class SuspiciousText {
    private SuspiciousText() {}

    private static final int MAX_CHARS = 80;
    private static final int MAX_WORDS = 10;

    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("\\b(ignore|disregard|forget|override)\\b.*\\b(previous|above|prior|earlier|all|instructions?|rules?|limits?)\\b"),
            Pattern.compile("\\b(system|assistant|developer|admin)\\s*(prompt|message|override|note)\\b"),
            Pattern.compile("\\byou (must|should|are|will|need to|have to)\\b"),
            Pattern.compile("\\bplease\\s+(raise|increase|set|pause|enable|delete|remove|call|use|add|approve|change)\\b"),
            Pattern.compile("\\b(raise|increase|double|triple|set|change|lift|remove|disable|turn|switch|clear|reset|bypass|skip|unlock|override)\\b.*\\b(budget|bid|cap|limit|limits|autonomy|guard|breaker|kill switch)\\b"),
            Pattern.compile("\\b(approve|approval)\\b.*\\b(proposal|change|request)\\b"),
            Pattern.compile("\\b(call|invoke|run|execute|use)\\b.*\\b(tool|function|mcp|api|command)\\b"),
            Pattern.compile("</?[a-z][^>]*>|\\[/?inst\\]|<\\|"),
            Pattern.compile("https?://|www\\.|\\bcurl\\b|\\bwget\\b"),
            Pattern.compile("\\b(api key|password|secret|token|credentials?)\\b"));

    /** Why the text looks like an instruction, or empty when it looks like a normal query. */
    public static Optional<String> reason(String text) {
        if (text == null) return Optional.empty();
        String t = text.toLowerCase(java.util.Locale.ROOT);
        if (t.length() > MAX_CHARS) return Optional.of("longer than " + MAX_CHARS + " characters");
        if (t.trim().split("\\s+").length > MAX_WORDS) return Optional.of("more than " + MAX_WORDS + " words");
        return instructionReason(text);
    }

    /**
     * Like {@link #reason} but for running prose, where length and word count mean nothing: only control characters and
     * instruction-like phrasing count.
     */
    public static Optional<String> instructionReason(String text) {
        if (text == null) return Optional.empty();
        String t = text.toLowerCase(java.util.Locale.ROOT);
        for (char c : t.toCharArray()) {
            if (Character.isISOControl(c)) return Optional.of("contains control characters");
        }
        for (Pattern p : PATTERNS) {
            if (p.matcher(t).find()) return Optional.of("reads like an instruction");
        }
        return Optional.empty();
    }

    public static boolean looksLikeInstruction(String text) {
        return reason(text).isPresent();
    }
}

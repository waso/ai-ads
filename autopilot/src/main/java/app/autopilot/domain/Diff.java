package app.autopilot.domain;

import java.util.Map;

/** Old and new values of a change, shown to the owner and written to the audit record. */
public record Diff(Map<String, Object> before, Map<String, Object> after, String summary) {
    public Diff {
        before = before == null ? Map.of() : Map.copyOf(before);
        after = after == null ? Map.of() : Map.copyOf(after);
    }
}

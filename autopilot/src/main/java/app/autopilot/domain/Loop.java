package app.autopilot.domain;

import java.util.Set;

/**
 * The loops a Claude run can belong to, and what each may do. A run is bound to one loop when it
 * starts, so a chat session cannot use the daily loop's write rights.
 */
public enum Loop {
    /** Hourly: diagnose open alerts. May emergency-pause while a cap is breached; otherwise proposals. */
    TRIAGE(Set.of("emergency_pause_campaign"), true),
    /** Daily: Tier 1 changes at L2 and above. */
    DAILY(Set.of("add_negative_keywords", "pause_keyword", "adjust_keyword_bid", "decrease_budget"), true),
    /** Weekly: report and propose only. */
    WEEKLY(Set.of(), true),
    /** Owner-driven chat, including mobile: proposals only. */
    CHAT(Set.of(), false);

    private final Set<String> directApplyTools;
    private final boolean scheduled;

    Loop(Set<String> directApplyTools, boolean scheduled) {
        this.directApplyTools = directApplyTools;
        this.scheduled = scheduled;
    }

    /** Tools that may be applied directly (rather than filed as proposals) from this loop. */
    public boolean mayApplyDirectly(String tool) {
        return directApplyTools.contains(tool);
    }

    /** Scheduled loops get one run slot per window; chat has no slots. */
    public boolean scheduled() {
        return scheduled;
    }
}

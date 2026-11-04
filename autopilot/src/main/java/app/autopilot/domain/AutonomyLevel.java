package app.autopilot.domain;

/** Set by the owner in server configuration. Claude can read it and has no tool to change it. */
public enum AutonomyLevel {
    /** Observe: read and report. */
    L0,
    /** Propose: file proposals for the owner to approve. */
    L1,
    /** Limited autonomy: apply Tier 1 changes within limits. */
    L2,
    /** Extended autonomy: Tier 1 limits widened by the owner. */
    L3;

    public boolean atLeast(AutonomyLevel other) {
        return compareTo(other) >= 0;
    }
}

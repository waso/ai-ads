package app.autopilot.domain;

/** The guard's verdict on one change request. */
public sealed interface Decision {

    /** Not allowed. The message names the rule and the limit so Claude can adjust or file a proposal. */
    record Refused(String rule, String message) implements Decision {}

    /** Allowed and may be applied now (Tier 1 at L2 and above, from a loop that may apply it). */
    record Apply(Plan plan) implements Decision {}

    /** Allowed only after the owner approves. */
    record Propose(Plan plan, String why) implements Decision {}
}

package app.autopilot.domain;

import java.time.Instant;

/** A change the guard has accepted, ready to be applied or filed as a proposal. */
public record Plan(
        ChangeRequest request,
        ChangeMeta meta,
        Tier tier,
        Diff diff,
        String stateHash,
        Instant evaluatedAt) {
}

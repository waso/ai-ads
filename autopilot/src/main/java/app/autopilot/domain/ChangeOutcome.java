package app.autopilot.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/** What a change tool returns to Claude. Never contains approval links or tokens. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChangeOutcome(Status status, String rule, String message, String proposalId, String changeId) {

    public enum Status { APPLIED, PROPOSED, REFUSED, FAILED }

    public static ChangeOutcome refused(String rule, String message) {
        return new ChangeOutcome(Status.REFUSED, rule, message, null, null);
    }

    public static ChangeOutcome applied(String changeId, String message) {
        return new ChangeOutcome(Status.APPLIED, null, message, null, changeId);
    }

    public static ChangeOutcome proposed(String proposalId, String message) {
        return new ChangeOutcome(Status.PROPOSED, null, message, proposalId, null);
    }

    public static ChangeOutcome failed(String rule, String message, String changeId) {
        return new ChangeOutcome(Status.FAILED, rule, message, null, changeId);
    }
}

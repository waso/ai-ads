package app.autopilot.domain;

/** What every change tool requires besides its own arguments. */
public record ChangeMeta(String reason, String expectedEffect, String runId, String idempotencyKey) {
    public ChangeMeta {
        require(reason, "reason", 1000);
        require(expectedEffect, "expected_effect", 1000);
        require(runId, "run_id", 64);
        require(idempotencyKey, "idempotency_key", 128);
    }

    private static void require(String v, String name, int max) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        if (v.length() > max) {
            throw new IllegalArgumentException(name + " is longer than " + max + " characters");
        }
    }
}

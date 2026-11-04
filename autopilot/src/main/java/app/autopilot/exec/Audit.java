package app.autopilot.exec;

import app.autopilot.store.LedgerStore;
import app.autopilot.store.LedgerStore.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writes the audit trail. {@link #record} throws when the audit log is unavailable, which makes the caller
 * refuse the write. {@link #recordOrLog} is for protective pauses: the pause goes ahead and the record goes
 * to the log instead, so the breaker works even when the database is down.
 */
@Component
public class Audit {

    private static final Logger audit = LoggerFactory.getLogger("audit");

    private final LedgerStore ledger;
    private final JsonMapper mapper;

    public Audit(LedgerStore ledger, JsonMapper mapper) {
        this.ledger = ledger;
        this.mapper = mapper;
    }

    public void record(AuditEvent e) {
        ledger.audit(e);
    }

    public void recordOrLog(AuditEvent e) {
        try {
            ledger.audit(e);
        } catch (RuntimeException ex) {
            audit.error("AUDIT (database unavailable) {}", mapper.writeValueAsString(e));
        }
    }

    public static Builder event(String event, Instant at) {
        return new Builder(event, at);
    }

    /** Fluent construction; most fields are optional. */
    public static final class Builder {
        private final String event;
        private final Instant at;
        private UUID runId;
        private String loop, tool, tier, entityKey, rule, message, reason, expectedEffect, approver, apiRequestId, idempotencyKey;
        private Map<String, Object> oldValues, newValues;
        private List<String> resources;
        private UUID proposalId, changeId;

        private Builder(String event, Instant at) {
            this.event = event;
            this.at = at;
        }

        public Builder run(UUID v) { runId = v; return this; }
        public Builder loop(String v) { loop = v; return this; }
        public Builder tool(String v) { tool = v; return this; }
        public Builder tier(String v) { tier = v; return this; }
        public Builder entity(String v) { entityKey = v; return this; }
        public Builder rule(String v) { rule = v; return this; }
        public Builder message(String v) { message = v; return this; }
        public Builder reason(String v) { reason = v; return this; }
        public Builder expectedEffect(String v) { expectedEffect = v; return this; }
        public Builder approver(String v) { approver = v; return this; }
        public Builder apiRequestId(String v) { apiRequestId = v; return this; }
        public Builder idempotencyKey(String v) { idempotencyKey = v; return this; }
        public Builder oldValues(Map<String, Object> v) { oldValues = v; return this; }
        public Builder newValues(Map<String, Object> v) { newValues = v; return this; }
        public Builder resources(List<String> v) { resources = v; return this; }
        public Builder proposal(UUID v) { proposalId = v; return this; }
        public Builder change(UUID v) { changeId = v; return this; }

        public AuditEvent build() {
            return new AuditEvent(at, event, runId, loop, tool, tier, entityKey, rule, message, reason, expectedEffect,
                    oldValues, newValues, approver, apiRequestId, resources, proposalId, changeId, idempotencyKey);
        }
    }
}

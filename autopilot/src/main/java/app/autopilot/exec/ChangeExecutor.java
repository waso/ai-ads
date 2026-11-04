package app.autopilot.exec;

import app.autopilot.ads.AdsException;
import app.autopilot.ads.AdsGateway;
import app.autopilot.ads.AdsGateway.MutationResult;
import app.autopilot.ads.AdsGateway.ReadBack;
import app.autopilot.domain.ChangeRequest;
import app.autopilot.domain.ChangeRequest.AddNegativeKeywords;
import app.autopilot.domain.Plan;
import app.autopilot.notify.AlertService;
import app.autopilot.notify.Notification.Severity;
import app.autopilot.store.Json;
import app.autopilot.store.LedgerStore;
import app.autopilot.store.LedgerStore.ChangeRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sends one accepted change to Google and records it. The order is fixed:
 * audit attempt, validate_only, real mutate (partial failure off), read back, record.
 * The caller holds the write lock.
 */
@Component
public class ChangeExecutor {

    private static final Logger log = LoggerFactory.getLogger(ChangeExecutor.class);

    /** How long after a change its outcome is reviewed. */
    static final Duration REVIEW_WINDOW = Duration.ofDays(7);

    public record Actor(String name, String approver, UUID proposalId, UUID runId, String loop, String idempotencyKey) {}

    public record Result(boolean applied, UUID changeId, String message) {}

    private final AdsGateway ads;
    private final Audit audit;
    private final LedgerStore ledger;
    private final AlertService alerts;
    private final Json json;
    private final Clock clock;

    public ChangeExecutor(AdsGateway ads, Audit audit, LedgerStore ledger, AlertService alerts, Json json, Clock clock) {
        this.ads = ads;
        this.audit = audit;
        this.ledger = ledger;
        this.alerts = alerts;
        this.json = json;
        this.clock = clock;
    }

    /**
     * @param strictAudit true for normal writes: an audit record that cannot be written stops the change
     *                    before Google is called. False for a protective pause, which goes ahead and logs.
     */
    public Result execute(Plan plan, Actor actor, boolean strictAudit) {
        ChangeRequest req = plan.request();
        UUID changeId = UUID.randomUUID();
        String entityKey = req.ledgerKey();

        var attempt = Audit.event("ATTEMPT", clock.instant()).run(actor.runId()).loop(actor.loop()).tool(req.tool())
                .tier(plan.tier().name()).entity(entityKey).reason(plan.meta().reason())
                .expectedEffect(plan.meta().expectedEffect()).oldValues(plan.diff().before())
                .newValues(plan.diff().after()).approver(actor.approver()).proposal(actor.proposalId())
                .change(changeId).idempotencyKey(actor.idempotencyKey()).build();
        if (strictAudit) {
            audit.record(attempt); // if this throws, Google is never called
        } else {
            audit.recordOrLog(attempt);
        }

        MutationResult result;
        try {
            ads.mutate(req, true);
            result = ads.mutate(req, false);
        } catch (AdsException e) {
            return failed(plan, actor, changeId, entityKey, e, strictAudit);
        }

        ReadBack readBack = readBackSafely(req, result);
        Map<String, Object> newValues = new LinkedHashMap<>(plan.diff().after());
        if (!result.createdIds().isEmpty()) newValues.put("createdIds", result.createdIds());
        if (entityKey == null && result.createdIds().containsKey("campaign")) {
            entityKey = "CAMPAIGN:" + result.createdIds().get("campaign");
        }
        Instant now = clock.instant();
        ChangeRecord record = new ChangeRecord(changeId, actor.proposalId(), actor.runId(), req.tool(), plan.tier().name(),
                entityKey, req.fingerprint(), units(req), actor.name(), actor.approver(), json.write(req),
                plan.diff().before(), newValues, result.requestId(), result.resourceNames(), "APPLIED", null, now);
        persist(() -> ledger.insertChange(record), strictAudit, "change " + changeId);
        persist(() -> audit.record(Audit.event("APPLIED", now).run(actor.runId()).loop(actor.loop()).tool(req.tool())
                .tier(plan.tier().name()).entity(record.entityKey()).reason(plan.meta().reason())
                .expectedEffect(plan.meta().expectedEffect()).oldValues(plan.diff().before()).newValues(newValues)
                .approver(actor.approver()).apiRequestId(result.requestId()).resources(result.resourceNames())
                .proposal(actor.proposalId()).change(changeId).idempotencyKey(actor.idempotencyKey()).build()),
                strictAudit, "applied audit " + changeId);
        String finalKey = record.entityKey();
        persist(() -> ledger.scheduleOutcome(changeId, now.plus(REVIEW_WINDOW), Map.of("tool", req.tool(),
                "entity", String.valueOf(finalKey), "before", plan.diff().before())), strictAudit, "outcome " + changeId);

        if (!readBack.matches()) {
            audit.recordOrLog(Audit.event("VERIFY_MISMATCH", clock.instant()).run(actor.runId()).tool(req.tool())
                    .entity(entityKey).message(readBack.observed()).change(changeId).build());
            alerts.raise("VERIFY_MISMATCH", Severity.WARNING, String.valueOf(entityKey),
                    "After " + req.tool() + " on " + entityKey + " Google reports: " + readBack.observed(),
                    Map.of("changeId", changeId.toString()));
        }
        return new Result(true, changeId, plan.diff().summary());
    }

    private Result failed(Plan plan, Actor actor, UUID changeId, String entityKey, AdsException e, boolean strict) {
        ChangeRequest req = plan.request();
        Instant now = clock.instant();
        log.warn("Google rejected {} on {}: {} {}", req.tool(), entityKey, e.code(), e.getMessage());
        ChangeRecord record = new ChangeRecord(changeId, actor.proposalId(), actor.runId(), req.tool(), plan.tier().name(),
                entityKey, req.fingerprint(), 0, actor.name(), actor.approver(), json.write(req), plan.diff().before(),
                plan.diff().after(), null, List.of(), "FAILED", e.code() + ": " + e.getMessage(), now);
        persist(() -> ledger.insertChange(record), strict, "failed change " + changeId);
        persist(() -> audit.record(Audit.event("FAILED", now).run(actor.runId()).loop(actor.loop()).tool(req.tool())
                .tier(plan.tier().name()).entity(entityKey).rule("google_rejected").message(e.code() + ": " + e.getMessage())
                .reason(plan.meta().reason()).approver(actor.approver()).proposal(actor.proposalId()).change(changeId)
                .idempotencyKey(actor.idempotencyKey()).build()), strict, "failed audit " + changeId);
        return new Result(false, changeId, "Google rejected the change (" + e.code() + "): " + e.getMessage());
    }

    private ReadBack readBackSafely(ChangeRequest req, MutationResult result) {
        try {
            return ads.readBack(req, result);
        } catch (RuntimeException e) {
            return new ReadBack(false, "read-back failed: " + e.getMessage());
        }
    }

    /** After Google accepted the change the record is best effort: a failure is logged and caught by reconciliation. */
    private void persist(Runnable write, boolean strict, String what) {
        try {
            write.run();
        } catch (RuntimeException e) {
            log.error("Could not record {} after the change reached Google; reconciliation will flag it: {}", what, e.toString());
            if (!strict) return;
            // The change is already applied. Surface it loudly instead of failing the caller.
            try {
                alerts.raise("UNRECORDED_CHANGE", Severity.CRITICAL, what,
                        "A change reached Google but its record could not be written (" + what + ").", Map.of());
            } catch (RuntimeException ignored) {
                // database is down: the log line above is the record
            }
        }
    }

    private int units(ChangeRequest req) {
        return req instanceof AddNegativeKeywords n ? Math.max(1, n.terms().size()) : 1;
    }

    /** Candidate resource names for display only. */
    static List<String> names(MutationResult r) {
        return new ArrayList<>(r.resourceNames());
    }
}

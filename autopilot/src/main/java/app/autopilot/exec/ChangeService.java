package app.autopilot.exec;

import app.autopilot.ads.AdsException;
import app.autopilot.domain.ChangeMeta;
import app.autopilot.domain.ChangeOutcome;
import app.autopilot.domain.ChangeRequest;
import app.autopilot.domain.ControlState;
import app.autopilot.domain.Decision;
import app.autopilot.domain.Limits;
import app.autopilot.config.RuntimeConfig;
import app.autopilot.guard.Guard;
import app.autopilot.guard.StateHasher;
import app.autopilot.store.AlertStore;
import app.autopilot.store.Json;
import app.autopilot.store.LedgerStore;
import app.autopilot.store.LedgerStore.ChangeRecord;
import app.autopilot.store.LedgerStore.IdempotentOutcome;
import app.autopilot.store.ProposalStore;
import app.autopilot.store.ProposalStore.Proposal;
import app.autopilot.store.RunStore.Run;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The entry point for every change that Claude asks for. In one place and under the write lock it replays
 * idempotent calls, runs the guard, and then applies the change, files a proposal, or refuses. Approval of a
 * proposal is deliberately elsewhere (the {@code approval} package), out of reach of the MCP tools.
 */
@Service
public class ChangeService {

    private static final Logger log = LoggerFactory.getLogger(ChangeService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    public static final String DATABASE_UNAVAILABLE = "database_unavailable";
    public static final String IDEMPOTENCY_MISMATCH = "idempotency_key_reused";
    public static final String NO_SAFE_INVERSE = "no_safe_inverse";
    public static final String GOOGLE_UNAVAILABLE = "google_unavailable";

    private final Guard guard;
    private final ChangeExecutor executor;
    private final RuntimeConfig config;
    private final RunService runs;
    private final LedgerStore ledger;
    private final ProposalStore proposals;
    private final AlertStore alertStore;
    private final Audit audit;
    private final WriteLock lock;
    private final Json json;
    private final Clock clock;
    private final List<ProposalListener> listeners;

    public ChangeService(Guard guard, ChangeExecutor executor, RuntimeConfig config, RunService runService,
                         LedgerStore ledger, ProposalStore proposals, AlertStore alertStore, Audit audit,
                         WriteLock lock, Json json, Clock clock, List<ProposalListener> listeners) {
        this.guard = guard;
        this.executor = executor;
        this.config = config;
        this.runs = runService;
        this.ledger = ledger;
        this.proposals = proposals;
        this.alertStore = alertStore;
        this.audit = audit;
        this.lock = lock;
        this.json = json;
        this.clock = clock;
        this.listeners = listeners;
    }

    public ChangeOutcome submit(ChangeRequest change, ChangeMeta meta) {
        return submit(change, meta, false);
    }

    /** propose_rollback: restores recorded values through a new Tier 2 proposal that follows the same checks. */
    public ChangeOutcome proposeRollback(String changeId, ChangeMeta meta) {
        try {
            Optional<ChangeRecord> rec = ledger.findChange(UUID.fromString(changeId));
            if (rec.isEmpty()) {
                return refusedAndAudited(null, meta, "unknown_change", "No recorded change with id " + changeId + ".", null);
            }
            Optional<ChangeRequest> inverse = Rollbacks.inverse(rec.get(), json);
            if (inverse.isEmpty()) {
                return refusedAndAudited(null, meta, NO_SAFE_INVERSE, "Change " + changeId + " (" + rec.get().tool()
                        + ") has no safe inverse: " + Rollbacks.noInverseReason(rec.get()) + ".", rec.get().tool());
            }
            return submit(inverse.get(), meta, true);
        } catch (IllegalArgumentException e) {
            return ChangeOutcome.refused("unknown_change", "change_id is not a valid id.");
        } catch (DataAccessException e) {
            return databaseUnavailable(e);
        }
    }

    private ChangeOutcome submit(ChangeRequest change, ChangeMeta meta, boolean rollback) {
        try {
            return lock.withLock(() -> locked(change, meta, rollback));
        } catch (DataAccessException e) {
            return databaseUnavailable(e);
        }
    }

    private ChangeOutcome databaseUnavailable(DataAccessException e) {
        log.error("Write refused, database unavailable: {}", e.toString());
        return ChangeOutcome.refused(DATABASE_UNAVAILABLE,
                "Writes are refused while the database, limits or audit log are unavailable. Reads continue.");
    }

    private ChangeOutcome locked(ChangeRequest change, ChangeMeta meta, boolean rollback) {
        Instant now = clock.instant();
        String requestHash = StateHasher.sha256(json.write(change));

        // 1. A replay returns the earlier result instead of changing the account twice.
        Optional<IdempotentOutcome> earlier = ledger.findIdempotent(meta.idempotencyKey());
        if (earlier.isPresent()) {
            if (earlier.get().requestHash().equals(requestHash)) return earlier.get().outcome();
            return refusedAndAudited(change, meta, IDEMPOTENCY_MISMATCH,
                    "idempotency_key was already used for a different change; use a new key.", null);
        }

        // 2. Limits, goals and control state come from the database: if it is down, this throws and we refuse.
        Limits limits = config.limits();
        ControlState control = config.control();
        boolean breached = alertStore.hasOpenWithPrefix("SPEND_");
        Run run = runs.find(meta.runId()).orElse(null);

        Decision decision;
        try {
            decision = guard.evaluate(new Guard.Request(change, meta, Guard.Origin.CLAUDE, run, rollback, null),
                    new Guard.Context(limits, config.goals(), control, breached, alertStore.conversionSignalSuspect(), alertStore.hasOpen("CONVERSION_TRACKING_NO_RECENT_SALES")));
        } catch (AdsException e) {
            // The guard reads live account state. If Google cannot be reached, the change is refused, not guessed.
            return refusedAndAudited(change, meta, GOOGLE_UNAVAILABLE,
                    "Google Ads could not be read (" + e.code() + "), so the change cannot be checked and was not made.",
                    run == null ? null : run.loop().name(), run);
        }

        return switch (decision) {
            case Decision.Refused r -> refusedAndAudited(change, meta, r.rule(), r.message(), run == null ? null : run.loop().name(), run);
            case Decision.Propose p -> propose(p, run, limits, now, meta, rollback);
            case Decision.Apply a -> apply(a, run, meta);
        };
    }

    private ChangeOutcome propose(Decision.Propose p, Run run, Limits limits, Instant now, ChangeMeta meta, boolean rollback) {
        ChangeRequest change = p.plan().request();
        Optional<Proposal> existing = proposals.pendingWithFingerprint(change.fingerprint(), now);
        if (existing.isPresent()) {
            return ChangeOutcome.proposed(existing.get().id().toString(),
                    "This change is already proposed and waiting for the owner (" + existing.get().id() + ").");
        }
        Proposal proposal = new Proposal(UUID.randomUUID(), run.id(), change.tool(), p.plan().tier().name(),
                ProposalStore.Status.PENDING, change, meta, p.plan().diff(), p.plan().stateHash(), p.why(),
                rollback, change.fingerprint(), now, now.plus(Duration.ofHours(limits.proposalLifetimeHours())), null, null, null,
                null, newNonce(), null);
        proposals.insert(proposal);
        audit.record(Audit.event("PROPOSED", now).run(run.id()).loop(run.loop().name()).tool(change.tool())
                .tier(proposal.tier()).entity(target(change)).reason(meta.reason()).expectedEffect(meta.expectedEffect())
                .oldValues(p.plan().diff().before()).newValues(p.plan().diff().after()).message(p.why())
                .proposal(proposal.id()).idempotencyKey(meta.idempotencyKey()).build());
        ChangeOutcome out = ChangeOutcome.proposed(proposal.id().toString(),
                "Filed for the owner's approval (" + p.why() + "). Proposals expire after " + limits.proposalLifetimeHours() + " h.");
        ledger.saveIdempotent(meta.idempotencyKey(), StateHasher.sha256(json.write(change)), out, now);
        for (ProposalListener l : listeners) {
            try {
                l.proposalFiled(proposal);
            } catch (RuntimeException e) {
                log.error("Could not notify the owner about proposal {}: {}", proposal.id(), e.toString());
            }
        }
        return out;
    }

    private ChangeOutcome apply(Decision.Apply a, Run run, ChangeMeta meta) {
        ChangeRequest change = a.plan().request();
        ChangeExecutor.Result r = executor.execute(a.plan(),
                new ChangeExecutor.Actor("claude", null, null, run.id(), run.loop().name(), meta.idempotencyKey()), true);
        ChangeOutcome out = r.applied()
                ? ChangeOutcome.applied(r.changeId().toString(), r.message())
                : ChangeOutcome.failed("google_rejected", r.message(), r.changeId().toString());
        ledger.saveIdempotent(meta.idempotencyKey(), StateHasher.sha256(json.write(change)), out, clock.instant());
        return out;
    }

    private ChangeOutcome refusedAndAudited(ChangeRequest change, ChangeMeta meta, String rule, String message, String tool) {
        return refusedAndAudited(change, meta, rule, message, null, null);
    }

    private ChangeOutcome refusedAndAudited(ChangeRequest change, ChangeMeta meta, String rule, String message,
                                            String loop, Run run) {
        try {
            audit.record(Audit.event("REFUSED", clock.instant()).run(run == null ? null : run.id()).loop(loop)
                    .tool(change == null ? null : change.tool()).tier(change == null ? null : change.tier().name())
                    .entity(change == null ? null : target(change)).rule(rule).message(message).reason(meta.reason())
                    .expectedEffect(meta.expectedEffect()).newValues(safeNewValues(change))
                    .idempotencyKey(meta.idempotencyKey()).build());
        } catch (RuntimeException e) {
            // A refusal that cannot be audited is still a refusal; the database error is logged by the caller path.
            log.error("Could not audit refusal {}: {}", rule, e.toString());
        }
        return ChangeOutcome.refused(rule, message);
    }

    /** A malformed request must still be audited, so reading its values can never throw. */
    private static java.util.Map<String, Object> safeNewValues(ChangeRequest change) {
        if (change == null) return null;
        try {
            return change.newValues();
        } catch (RuntimeException e) {
            return java.util.Map.of("unreadable", e.getClass().getSimpleName());
        }
    }

    private static String target(ChangeRequest c) {
        return c.target() == null ? null : c.target().key();
    }

    private static String newNonce() {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}

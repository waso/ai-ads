package app.autopilot.approval;

import app.autopilot.config.AutopilotProperties;
import app.autopilot.config.RuntimeConfig;
import app.autopilot.domain.Decision;
import app.autopilot.exec.Audit;
import app.autopilot.exec.ChangeExecutor;
import app.autopilot.exec.ProposalExpiry;
import app.autopilot.exec.WriteLock;
import app.autopilot.guard.Guard;
import app.autopilot.store.AlertStore;
import app.autopilot.store.ProposalStore;
import app.autopilot.store.ProposalStore.Proposal;
import app.autopilot.store.ProposalStore.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The only place a proposal is approved or rejected. It is called from the owner's signed-in browser session
 * and from nowhere else: nothing in the {@code mcp} package depends on this package, which an architecture
 * test enforces.
 *
 * <p>On approval the server re-checks everything, because the account may have changed since Claude filed the
 * proposal: kill switch and limits, the entity's state hash, scope, cooldown and caps.
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    public enum Outcome { APPLIED, REJECTED, REFUSED, FAILED, INVALID_LINK, ALREADY_DECIDED, EXPIRED, NOT_OWNER, UNAVAILABLE }

    public record Result(Outcome outcome, String message, UUID changeId) {
        public boolean ok() { return outcome == Outcome.APPLIED || outcome == Outcome.REJECTED; }
    }

    private final ProposalStore proposals;
    private final ApprovalLinks links;
    private final Guard guard;
    private final ChangeExecutor executor;
    private final RuntimeConfig config;
    private final AlertStore alertStore;
    private final Audit audit;
    private final ProposalExpiry expiry;
    private final WriteLock lock;
    private final AutopilotProperties props;
    private final Clock clock;

    public ApprovalService(ProposalStore proposals, ApprovalLinks links, Guard guard, ChangeExecutor executor,
                           RuntimeConfig config, AlertStore alertStore, Audit audit, ProposalExpiry expiry,
                           WriteLock lock, AutopilotProperties props, Clock clock) {
        this.proposals = proposals;
        this.links = links;
        this.guard = guard;
        this.executor = executor;
        this.config = config;
        this.alertStore = alertStore;
        this.audit = audit;
        this.expiry = expiry;
        this.lock = lock;
        this.props = props;
        this.clock = clock;
    }

    /** Read-only: the proposal a valid link points to. Changes nothing, so link scanners and previews are harmless. */
    public Optional<Proposal> preview(UUID id, long exp, String nonce, String sig) {
        if (!links.valid(id, exp, nonce, sig, clock.instant())) return Optional.empty();
        try {
            return proposals.find(id);
        } catch (DataAccessException e) {
            return Optional.empty();
        }
    }

    public Result approve(UUID id, long exp, String nonce, String sig, String approver) {
        if (!isOwner(approver)) return new Result(Outcome.NOT_OWNER, "Only the owner can approve.", null);
        if (!links.valid(id, exp, nonce, sig, clock.instant())) {
            return new Result(Outcome.INVALID_LINK, "This approval link is invalid or has expired.", null);
        }
        try {
            return lock.withLock(() -> approveLocked(id, nonce, approver));
        } catch (DataAccessException e) {
            log.error("Approval could not run: {}", e.toString());
            return new Result(Outcome.UNAVAILABLE, "The database is unavailable; nothing was changed. Try again shortly.", null);
        }
    }

    private Result approveLocked(UUID id, String nonce, String approver) {
        Instant now = clock.instant();
        Optional<Proposal> found = proposals.find(id);
        if (found.isEmpty()) return new Result(Outcome.INVALID_LINK, "This approval link is invalid or has expired.", null);
        Proposal p = found.get();
        if (p.status() != Status.PENDING) {
            return new Result(Outcome.ALREADY_DECIDED, "This proposal was already decided: " + p.status() + ".", p.changeId());
        }
        if (!now.isBefore(p.expiresAt())) {
            expiry.expire(p);
            return new Result(Outcome.EXPIRED, "This proposal expired after " + java.time.Duration.between(p.createdAt(), p.expiresAt()).toHours() + " h.", null);
        }
        // Re-check first. If Google cannot be read the proposal stays pending and the link stays usable: retry later.
        Decision d;
        try {
            d = guard.evaluate(new Guard.Request(p.request(), p.meta(), Guard.Origin.APPROVAL, null, p.rollback(), p.stateHash()),
                    new Guard.Context(config.limits(), config.goals(), config.control(), alertStore.hasOpenWithPrefix("SPEND_"), alertStore.conversionSignalSuspect(), alertStore.hasOpen("CONVERSION_TRACKING_NO_RECENT_SALES")));
        } catch (app.autopilot.ads.AdsException e) {
            return new Result(Outcome.UNAVAILABLE, "Google Ads could not be read (" + e.code()
                    + "), so the change could not be re-checked. Nothing was changed; the link still works, try again shortly.", null);
        }
        if (!proposals.consumeNonce(id, nonce, now)) {
            return new Result(Outcome.ALREADY_DECIDED, "This link was already used.", null);
        }
        audit.record(Audit.event("APPROVED", now).run(p.runId()).tool(p.tool()).tier(p.tier()).entity(target(p))
                .approver(approver).proposal(p.id()).reason(p.meta().reason()).build());
        if (d instanceof Decision.Refused r) {
            Status status = Guard.Rule.STATE_CHANGED.equals(r.rule()) ? Status.STALE : Status.FAILED;
            proposals.decide(id, status, approver, r.rule() + ": " + r.message(), null, now);
            audit.record(Audit.event("REFUSED", now).run(p.runId()).tool(p.tool()).tier(p.tier()).entity(target(p))
                    .rule(r.rule()).message(r.message()).approver(approver).proposal(p.id()).build());
            return new Result(Outcome.REFUSED, "Not applied: the server's re-check refused it (" + r.rule() + "). " + r.message(), null);
        }
        Decision.Apply apply = (Decision.Apply) d;
        ChangeExecutor.Result res = executor.execute(apply.plan(),
                new ChangeExecutor.Actor("owner-approved", approver, p.id(), p.runId(), "APPROVAL", p.meta().idempotencyKey()), true);
        if (res.applied()) {
            proposals.decide(id, Status.APPLIED, approver, "applied", res.changeId(), now);
            return new Result(Outcome.APPLIED, res.message(), res.changeId());
        }
        proposals.decide(id, Status.FAILED, approver, res.message(), res.changeId(), now);
        return new Result(Outcome.FAILED, res.message(), res.changeId());
    }

    public Result reject(UUID id, long exp, String nonce, String sig, String approver, String note) {
        if (!isOwner(approver)) return new Result(Outcome.NOT_OWNER, "Only the owner can reject.", null);
        if (!links.valid(id, exp, nonce, sig, clock.instant())) {
            return new Result(Outcome.INVALID_LINK, "This approval link is invalid or has expired.", null);
        }
        try {
            return lock.withLock(() -> {
                Instant now = clock.instant();
                Optional<Proposal> found = proposals.find(id);
                if (found.isEmpty() || found.get().status() != Status.PENDING || !proposals.consumeNonce(id, nonce, now)) {
                    return new Result(Outcome.ALREADY_DECIDED, "This proposal was already decided or the link was used.", null);
                }
                Proposal p = found.get();
                String reason = note == null || note.isBlank() ? "rejected by the owner" : note.strip();
                proposals.decide(id, Status.REJECTED, approver, reason, null, now);
                audit.record(Audit.event("REJECTED", now).run(p.runId()).tool(p.tool()).tier(p.tier()).entity(target(p))
                        .approver(approver).proposal(p.id()).message(reason).build());
                return new Result(Outcome.REJECTED, "Rejected. Nothing was applied.", null);
            });
        } catch (DataAccessException e) {
            return new Result(Outcome.UNAVAILABLE, "The database is unavailable; nothing was changed. Try again shortly.", null);
        }
    }

    /** Marks proposals past their lifetime as expired. Delegates to the class that can only expire. */
    public List<Proposal> expireDue() {
        return expiry.expireDue();
    }

    private boolean isOwner(String email) {
        return email != null && !email.isBlank() && props.ownerEmail() != null && !props.ownerEmail().isBlank()
                && props.ownerEmail().equalsIgnoreCase(email);
    }

    private static String target(Proposal p) {
        return p.request().target() == null ? null : p.request().target().key();
    }
}

package app.autopilot.exec;

import app.autopilot.store.ProposalStore;
import app.autopilot.store.ProposalStore.Proposal;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Marks proposals past their lifetime as expired and records it. It can only expire: it has no way to approve or
 * apply anything, which is why the watchdog uses this and not the approval service.
 */
@Component
public class ProposalExpiry {

    private final ProposalStore proposals;
    private final Audit audit;
    private final Clock clock;

    public ProposalExpiry(ProposalStore proposals, Audit audit, Clock clock) {
        this.proposals = proposals;
        this.audit = audit;
        this.clock = clock;
    }

    public List<Proposal> expireDue() {
        Instant now = clock.instant();
        List<Proposal> due = proposals.expireDue(now);
        due.forEach(p -> record(p, now));
        return due;
    }

    /** Expires one proposal that was found past its lifetime. Returns true when it was still pending. */
    public boolean expire(Proposal p) {
        Instant now = clock.instant();
        boolean changed = proposals.decide(p.id(), ProposalStore.Status.EXPIRED, "system", "expired after lifetime", null, now);
        if (changed) record(p, now);
        return changed;
    }

    private void record(Proposal p, Instant now) {
        audit.recordOrLog(Audit.event("EXPIRED", now).run(p.runId()).tool(p.tool()).tier(p.tier())
                .entity(p.request().target() == null ? null : p.request().target().key()).proposal(p.id())
                .message("expired after lifetime, nothing applied").build());
    }
}

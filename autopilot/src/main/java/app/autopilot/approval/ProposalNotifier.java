package app.autopilot.approval;

import app.autopilot.exec.ProposalListener;
import app.autopilot.notify.Notification;
import app.autopilot.notify.Notifier;
import app.autopilot.store.ProposalStore.Proposal;
import org.springframework.stereotype.Component;

/**
 * Tells the owner a proposal is waiting and attaches the signed approval link. The link goes straight to the
 * owner's email and push channel and is never returned to Claude.
 */
@Component
class ProposalNotifier implements ProposalListener {

    private final Notifier notifier;
    private final ApprovalLinks links;

    ProposalNotifier(Notifier notifier, ApprovalLinks links) {
        this.notifier = notifier;
        this.links = links;
    }

    @Override
    public void proposalFiled(Proposal p) {
        String link = links.configured() ? links.url(p) : null;
        String body = p.diff().summary() + "\n\nWhy: " + p.meta().reason() + "\nExpected: " + p.meta().expectedEffect()
                + "\nTier " + p.tier().substring(1) + ", expires " + p.expiresAt();
        notifier.send(new Notification(Notification.Severity.INFO, "Proposal: " + p.tool(), body, link));
    }
}

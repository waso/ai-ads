package app.autopilot.exec;

import app.autopilot.store.ProposalStore.Proposal;

/** Told when a proposal is filed. The approval side builds the signed link and notifies the owner. */
public interface ProposalListener {
    void proposalFiled(Proposal proposal);
}

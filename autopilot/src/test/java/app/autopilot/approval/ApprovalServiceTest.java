package app.autopilot.approval;

import app.autopilot.ads.sandbox.SandboxAdsGateway;
import app.autopilot.approval.ApprovalService.Outcome;
import app.autopilot.domain.ChangeOutcome;
import app.autopilot.domain.ChangeRequest.AddNegativeKeywords;
import app.autopilot.domain.ChangeRequest.DecreaseBudget;
import app.autopilot.domain.ChangeRequest.IncreaseBudget;
import app.autopilot.domain.ChangeRequest.PauseAd;
import app.autopilot.domain.EntityRef;
import app.autopilot.domain.MatchType;
import app.autopilot.guard.Guard;
import app.autopilot.store.ProposalStore.Proposal;
import app.autopilot.store.ProposalStore.Status;
import app.autopilot.testkit.TestLimits;
import app.autopilot.testkit.WorldTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovalServiceTest extends WorldTest {

    private static final EntityRef B1 = EntityRef.budget("B1");
    private static final EntityRef AD1 = EntityRef.ad("G1~A1");

    private Proposal propose(app.autopilot.domain.ChangeRequest r) {
        ChangeOutcome o = submit(r);
        assertProposed(o);
        return w.proposal(o.proposalId());
    }

    private ApprovalService.Result approveWith(Proposal p, long exp, String nonce, String sig, String who) {
        return w.approvals.approve(p.id(), exp, nonce, sig, who);
    }

    private List<Map<String, Object>> audit() {
        return JdbcClient.create(w.db.owner()).sql("SELECT * FROM audit_events ORDER BY id").query().listOfRows();
    }

    // ---- links -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("[AC-13] opening a link changes nothing; only a decision does")
    void previewIsReadOnly() {
        Proposal p = propose(new PauseAd(AD1));
        var t = w.links.token(p);
        long auditBefore = w.ledger.auditCount();
        assertThat(w.approvals.preview(p.id(), t.expiresAt(), t.nonce(), t.signature())).isPresent();
        assertThat(w.approvals.preview(p.id(), t.expiresAt(), t.nonce(), t.signature())).isPresent(); // a scanner opening it twice
        assertThat(w.proposal(p.id().toString()).status()).isEqualTo(Status.PENDING);
        assertThat(w.proposal(p.id().toString()).nonceUsedAt()).isNull();
        assertThat(w.ledger.auditCount()).isEqualTo(auditBefore);
        assertThat(w.fake.sent).isEmpty();
    }

    @Test
    @DisplayName("[AC-13] a tampered, foreign or expired link is rejected and nothing is applied")
    void tamperedLinksRejected() {
        Proposal p = propose(new PauseAd(AD1));
        Proposal other = propose(new IncreaseBudget(B1, 52_000_000));
        var t = w.links.token(p);
        var otherToken = w.links.token(other);

        assertThat(approveWith(p, t.expiresAt(), t.nonce(), "forged", "owner@example.com").outcome()).isEqualTo(Outcome.INVALID_LINK);
        assertThat(approveWith(p, t.expiresAt(), t.nonce() + "x", t.signature(), "owner@example.com").outcome()).isEqualTo(Outcome.INVALID_LINK);
        assertThat(approveWith(p, t.expiresAt() + 3600, t.nonce(), t.signature(), "owner@example.com").outcome()).isEqualTo(Outcome.INVALID_LINK);
        // the signature of another proposal does not work for this one
        assertThat(approveWith(p, otherToken.expiresAt(), otherToken.nonce(), otherToken.signature(), "owner@example.com").outcome())
                .isEqualTo(Outcome.INVALID_LINK);
        assertThat(approveWith(p, t.expiresAt(), null, t.signature(), "owner@example.com").outcome()).isEqualTo(Outcome.INVALID_LINK);

        w.clock.advance(Duration.ofHours(48));
        assertThat(approveWith(p, t.expiresAt(), t.nonce(), t.signature(), "owner@example.com").outcome()).isEqualTo(Outcome.INVALID_LINK);
        assertThat(w.fake.realMutations()).isZero();
    }

    @Test
    @DisplayName("[AC-13] a link is single-use: the second approval applies nothing")
    void linkIsSingleUse() {
        Proposal p = propose(new PauseAd(AD1));
        var first = w.approve(p);
        assertThat(first.outcome()).isEqualTo(Outcome.APPLIED);
        var second = w.approve(p);
        assertThat(second.outcome()).isEqualTo(Outcome.ALREADY_DECIDED);
        assertThat(w.fake.realMutations()).isEqualTo(1);
        assertThat(w.fake.ads.get("G1~A1").status).isEqualTo("PAUSED");
    }

    @Test
    @DisplayName("[AC-12] only the owner's account can approve")
    void onlyOwnerApproves() {
        Proposal p = propose(new PauseAd(AD1));
        var t = w.links.token(p);
        assertThat(approveWith(p, t.expiresAt(), t.nonce(), t.signature(), "someone.else@example.com").outcome()).isEqualTo(Outcome.NOT_OWNER);
        assertThat(approveWith(p, t.expiresAt(), t.nonce(), t.signature(), null).outcome()).isEqualTo(Outcome.NOT_OWNER);
        assertThat(w.fake.realMutations()).isZero();
        assertThat(w.proposal(p.id().toString()).nonceUsedAt()).as("a refused attempt does not burn the link").isNull();
        assertThatThrownBy(() -> w.admin.killSwitch(true, "x", "someone.else@example.com")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> w.admin.clearBreaker("someone.else@example.com")).isInstanceOf(SecurityException.class);
    }

    // ---- applying on approval --------------------------------------------------------------------------

    @Test
    @DisplayName("[AC-17] an approved change is applied once and audited with the approver, request id and proposal")
    void approvalIsAudited() {
        Proposal p = propose(new PauseAd(AD1));
        var r = w.approve(p);
        assertThat(r.outcome()).isEqualTo(Outcome.APPLIED);

        Proposal done = w.proposal(p.id().toString());
        assertThat(done.status()).isEqualTo(Status.APPLIED);
        assertThat(done.decidedBy()).isEqualTo("owner@example.com");
        assertThat(done.changeId()).isEqualTo(r.changeId());

        assertThat(audit()).extracting(row -> row.get("event")).containsExactly("PROPOSED", "APPROVED", "ATTEMPT", "APPLIED");
        Map<String, Object> applied = audit().get(3);
        assertThat(applied.get("approver")).isEqualTo("owner@example.com");
        assertThat(applied.get("proposal_id")).isEqualTo(p.id());
        assertThat(applied.get("api_request_id").toString()).startsWith("req-");
        var change = w.ledger.findChange(r.changeId()).orElseThrow();
        assertThat(change.actor()).isEqualTo("owner-approved");
        assertThat(change.approver()).isEqualTo("owner@example.com");
        assertThat(w.fake.sent).extracting(s -> s.validateOnly()).containsExactly(true, false);
    }

    // ---- re-check on approval --------------------------------------------------------------------------

    @Test
    @DisplayName("[AC-14] if the entity changed after the proposal was made, approval is refused as stale")
    void staleStateRejected() {
        Proposal p = propose(new IncreaseBudget(B1, 55_000_000));
        w.fake.budgets.get("B1").amountMicros = 52_000_000; // someone changed the budget meanwhile
        var r = w.approve(p);
        assertThat(r.outcome()).isEqualTo(Outcome.REFUSED);
        assertThat(r.message()).contains(Guard.Rule.STATE_CHANGED);
        assertThat(w.proposal(p.id().toString()).status()).isEqualTo(Status.STALE);
        assertThat(w.fake.realMutations()).isZero();
    }

    @Test
    @DisplayName("[AC-14] limits are re-checked on approval: a lowered cap, the kill switch and a new cooldown all stop it")
    void limitsRecheckedOnApproval() {
        Proposal capped = propose(new IncreaseBudget(B1, 60_000_000));
        w.useLimits(TestLimits.defaults().cap(2_000_000_000L).build()); // 90 x 30.4 = 2736 > 2000
        var r = w.approve(capped);
        assertThat(r.outcome()).isEqualTo(Outcome.REFUSED);
        assertThat(r.message()).contains(Guard.Rule.MONTHLY_CAP);
        assertThat(w.proposal(capped.id().toString()).status()).isEqualTo(Status.FAILED);

        w.useLimits(TestLimits.defaults().build());
        Proposal ad = propose(new PauseAd(AD1));
        w.configStore.setKillSwitch(true, "test", "owner@example.com");
        assertThat(w.approve(ad).message()).contains(Guard.Rule.KILL_SWITCH);
        w.configStore.setKillSwitch(false, null, "owner@example.com");

        Proposal ad2 = propose(new PauseAd(EntityRef.ad("G1~A2")));
        w.recordApplied("pause_ad", "T2", "AD:G1~A2", 1, w.clock.instant(), "elsewhere"); // changed since
        assertThat(w.approve(ad2).message()).contains(Guard.Rule.COOLDOWN);
        assertThat(w.fake.realMutations()).isZero();
    }

    @Test
    @DisplayName("[AC-14] a proposal expires after 48 hours; expiring records it and applies nothing")
    void proposalsExpire() {
        Proposal p = propose(new PauseAd(AD1));
        assertThat(p.expiresAt()).isEqualTo(w.clock.instant().plus(Duration.ofHours(48)));

        w.clock.advance(Duration.ofHours(47).plusMinutes(59));
        assertThat(w.proposalStore.open(w.clock.instant())).hasSize(1);
        w.clock.advance(Duration.ofMinutes(1));
        assertThat(w.proposalStore.open(w.clock.instant())).isEmpty();

        assertThat(w.approvals.expireDue()).hasSize(1);
        assertThat(w.proposal(p.id().toString()).status()).isEqualTo(Status.EXPIRED);
        assertThat(audit()).extracting(row -> row.get("event")).contains("EXPIRED");
        assertThat(w.approvals.expireDue()).as("only once").isEmpty();
        assertThat(w.fake.realMutations()).isZero();
        assertThat(w.proposalStore.consumeNonce(p.id(), p.nonce(), w.clock.instant())).isFalse();
    }

    @Test
    @DisplayName("[AC-16] rejecting records the reason, applies nothing and burns the link")
    void rejection() {
        Proposal p = propose(new PauseAd(AD1));
        var t = w.links.token(p);
        var r = w.approvals.reject(p.id(), t.expiresAt(), t.nonce(), t.signature(), "owner@example.com", "not now");
        assertThat(r.outcome()).isEqualTo(Outcome.REJECTED);
        Proposal done = w.proposal(p.id().toString());
        assertThat(done.status()).isEqualTo(Status.REJECTED);
        assertThat(done.decisionNote()).isEqualTo("not now");
        assertThat(audit()).anySatisfy(row -> {
            assertThat(row.get("event")).isEqualTo("REJECTED");
            assertThat(row.get("approver")).isEqualTo("owner@example.com");
            assertThat(row.get("message")).isEqualTo("not now");
        });
        assertThat(w.approve(p).outcome()).isEqualTo(Outcome.ALREADY_DECIDED);
        assertThat(w.fake.realMutations()).isZero();
        var notOwner = w.approvals.reject(propose(new IncreaseBudget(B1, 52_000_000)).id(), t.expiresAt(), t.nonce(), t.signature(), "x@y.z", null);
        assertThat(notOwner.outcome()).isEqualTo(Outcome.NOT_OWNER);
    }

    // ---- rollback, end to end --------------------------------------------------------------------------

    @Test
    @DisplayName("[AC-21] an approved rollback restores the recorded values and adds its own audit record")
    void rollbackRestoresValues() {
        ChangeOutcome applied = submit(new DecreaseBudget(B1, 40_000_000));
        assertApplied(applied);
        Proposal rollback = w.proposal(w.changes.proposeRollback(applied.changeId(), w.meta(daily)).proposalId());

        var r = w.approve(rollback);
        assertThat(r.outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(w.fake.budgetAmount("B1")).isEqualTo(50_000_000);
        var change = w.ledger.findChange(r.changeId()).orElseThrow();
        assertThat(change.tool()).isEqualTo("increase_budget");
        assertThat(change.oldValues()).containsEntry("amountMicros", 40_000_000);
        assertThat(change.actor()).isEqualTo("owner-approved");
        assertThat(audit()).filteredOn(row -> "APPLIED".equals(row.get("event"))).hasSize(2); // the change and its rollback
    }

    @Test
    @DisplayName("[AC-21] an approved rollback of negative keywords removes exactly the ones this system added")
    void rollbackRemovesNegatives() {
        ChangeOutcome added = submit(new AddNegativeKeywords(EntityRef.campaign("C1"), List.of("zzterm1", "zzterm2"), MatchType.PHRASE));
        assertApplied(added);
        w.fake.negatives.add(otherNegative());
        assertThat(w.fake.negatives).hasSize(3);

        Proposal rollback = w.proposal(w.changes.proposeRollback(added.changeId(), w.meta(daily)).proposalId());
        assertThat(w.approve(rollback).outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(w.fake.negatives).extracting(n -> n.text).containsExactly("owner-added");
    }

    private app.autopilot.ads.sandbox.SandboxAdsGateway.Negative otherNegative() {
        var n = new app.autopilot.ads.sandbox.SandboxAdsGateway.Negative();
        n.id = "owner-1"; n.scope = EntityRef.campaign("C1"); n.text = "owner-added"; n.matchType = MatchType.EXACT;
        return n;
    }

    @Test
    @DisplayName("[AC-13] approval links are signed with a secret of at least 32 characters")
    void linksNeedASecret() {
        Proposal p = propose(new PauseAd(AD1));
        String url = w.links.url(p);
        assertThat(url).startsWith("https://autopilot.test/approve/" + p.id() + "?e=");
        assertThat(url).contains("&n=" + p.nonce()).contains("&s=");
        var short_ = new app.autopilot.config.AutopilotProperties("o", false, w.props.runs(),
                new app.autopilot.config.AutopilotProperties.Security("too-short", "https://x", null, null, null, null, java.util.Map.of(), false, null),
                w.props.alerting(), w.props.watchdog(), w.props.ads(), w.props.retention());
        assertThat(new ApprovalLinks(short_).configured()).isFalse();
        assertThatThrownBy(() -> new ApprovalLinks(short_).url(p)).isInstanceOf(IllegalStateException.class);
    }
}

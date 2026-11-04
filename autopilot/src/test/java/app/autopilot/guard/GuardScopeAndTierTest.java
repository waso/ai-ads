package app.autopilot.guard;

import app.autopilot.domain.AutonomyLevel;
import app.autopilot.domain.ChangeMeta;
import app.autopilot.domain.ChangeOutcome;
import app.autopilot.domain.ChangeRequest.AddNegativeKeywords;
import app.autopilot.domain.ChangeRequest.AdjustKeywordBid;
import app.autopilot.domain.ChangeRequest.DecreaseBudget;
import app.autopilot.domain.ChangeRequest.EmergencyPauseCampaign;
import app.autopilot.domain.ChangeRequest.EnableEntity;
import app.autopilot.domain.ChangeRequest.PauseKeyword;
import app.autopilot.domain.ChangeRequest.RemoveNegativeKeywords;
import app.autopilot.domain.EntityKind;
import app.autopilot.domain.EntityRef;
import app.autopilot.domain.Loop;
import app.autopilot.domain.MatchType;
import app.autopilot.exec.RunService;
import app.autopilot.store.RunStore.Run;
import app.autopilot.testkit.TestLimits;
import app.autopilot.testkit.TestWorld;
import app.autopilot.testkit.WorldTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuardScopeAndTierTest extends WorldTest {

    private static final EntityRef B1 = EntityRef.budget("B1");
    private static final EntityRef K1 = EntityRef.keyword("G1~K1");

    // ---- scope --------------------------------------------------------------------------------------

    @Test
    @DisplayName("[AC-04] a keyword in a campaign without the agent-managed label is refused")
    void unmanagedCampaignRefused() {
        ChangeOutcome o = submit(new PauseKeyword(EntityRef.keyword("G9~K9")));
        assertRefused(o, Guard.Rule.NOT_MANAGED);
        assertThat(o.message()).contains("agent-managed");
        assertThat(w.fake.realMutations()).isZero();
    }

    @Test
    @DisplayName("[AC-04] no-agent always wins over agent-managed, on the campaign, ad group or keyword")
    void noAgentWins() {
        assertRefused(submit(new PauseKeyword(EntityRef.keyword("G8~K8"))), Guard.Rule.NO_AGENT); // campaign carries both labels

        w.fake.adGroups.get("G1").labels.add("no-agent");
        assertRefused(submit(new PauseKeyword(K1)), Guard.Rule.NO_AGENT);
        w.fake.adGroups.get("G1").labels.remove("no-agent");

        w.fake.keywords.get("G1~K1").labels.add("no-agent");
        assertRefused(submit(new PauseKeyword(K1)), Guard.Rule.NO_AGENT);
    }

    @Test
    @DisplayName("[AC-04] a budget shared with an unmanaged campaign counts as unmanaged")
    void sharedBudgetScope() {
        w.fake.campaigns.get("C9").budgetId = "B1"; // B1 now serves C1 (managed) and C9 (not)
        ChangeOutcome o = submit(new DecreaseBudget(B1, 45_000_000));
        assertRefused(o, Guard.Rule.SHARED_BUDGET);
        assertThat(o.message()).contains("campaign C9");

        w.fake.campaigns.get("C9").budgetId = "B9";
        w.fake.campaigns.get("C8").budgetId = "B1"; // a no-agent campaign on the same budget
        assertRefused(submit(new DecreaseBudget(B1, 45_000_000)), Guard.Rule.NO_AGENT);
    }

    @Test
    @DisplayName("[AC-04] a budget used only by unmanaged campaigns is refused")
    void budgetOfUnmanagedCampaign() {
        assertRefused(submit(new DecreaseBudget(EntityRef.budget("B9"), 15_000_000)), Guard.Rule.NOT_MANAGED);
    }

    @Test
    @DisplayName("[AC-04] ids outside the allowlisted account come back as unknown, never as a Google error")
    void foreignIdsAreUnknown() {
        assertRefused(submit(new PauseKeyword(EntityRef.keyword("G404~K404"))), Guard.Rule.UNKNOWN_ENTITY);
        assertRefused(submit(new DecreaseBudget(EntityRef.budget("999"), 1)), Guard.Rule.UNKNOWN_ENTITY);
        assertThat(w.fake.realMutations()).isZero();
        assertThat(new EntityRef(EntityKind.RECOMMENDATION, "dGVzdA==").id()).as("recommendation ids may end in =").isEqualTo("dGVzdA==");
        assertThatThrownBy(() -> new EntityRef(EntityKind.CAMPAIGN, "1; DROP"))
                .as("ids with unexpected characters never get that far")
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- autonomy and tier ---------------------------------------------------------------------------

    @Test
    @DisplayName("[AC-03] at L0 every change and proposal is refused")
    void l0RefusesEverything() {
        w.useLimits(TestLimits.defaults().autonomy(AutonomyLevel.L0).build());
        assertRefused(submit(new DecreaseBudget(B1, 45_000_000)), Guard.Rule.AUTONOMY_L0);
        assertRefused(submit(new EnableEntity(EntityRef.campaign("C1"))), Guard.Rule.AUTONOMY_L0);
    }

    @Test
    @DisplayName("[AC-03] at L1 Tier 1 changes become proposals")
    void l1ProposesTier1() {
        w.useLimits(TestLimits.defaults().autonomy(AutonomyLevel.L1).build());
        ChangeOutcome o = submit(new DecreaseBudget(B1, 45_000_000));
        assertProposed(o);
        assertThat(o.message()).contains("autonomy L1");
        assertThat(w.fake.realMutations()).isZero();
        var p = w.proposalStore.find(UUID.fromString(o.proposalId())).orElseThrow();
        assertThat(p.status().name()).isEqualTo("PENDING");
        assertThat(p.expiresAt()).isEqualTo(w.clock.instant().plus(Duration.ofHours(48)));
        assertThat(p.nonce()).hasSizeGreaterThan(20);
    }

    @Test
    @DisplayName("[AC-03] at L2 and L3 the daily loop applies Tier 1; Tier 2 is still a proposal")
    void l2AppliesTier1FromDailyOnly() {
        for (AutonomyLevel level : List.of(AutonomyLevel.L2, AutonomyLevel.L3)) {
            TestWorld world = new TestWorld(TestLimits.defaults().autonomy(level).build(), TestWorld.WEDNESDAY_MORNING);
            Run run = world.startRun(Loop.DAILY);
            ChangeOutcome tier1 = world.changes.submit(new DecreaseBudget(B1, 45_000_000), world.meta(run));
            assertThat(tier1.status()).as("%s Tier 1 from the daily loop", level).isEqualTo(ChangeOutcome.Status.APPLIED);

            world.fake.campaigns.get("C1").status = "PAUSED";
            ChangeOutcome tier2 = world.changes.submit(new EnableEntity(EntityRef.campaign("C1")), world.meta(run));
            assertThat(tier2.status()).as("%s Tier 2", level).isEqualTo(ChangeOutcome.Status.PROPOSED);
            assertThat(world.fake.realMutations()).as("only the Tier 1 change reached Google").isEqualTo(1);
            world.close();
        }
    }

    @Test
    @DisplayName("[AC-03] a Tier 2 change is a proposal at every level, even L3")
    void tier2NeverApplies() {
        w.useLimits(TestLimits.defaults().autonomy(AutonomyLevel.L3).build());
        w.fake.campaigns.get("C1").status = "PAUSED";
        ChangeOutcome o = submit(new EnableEntity(EntityRef.campaign("C1")));
        assertProposed(o);
        assertThat(w.fake.realMutations()).isZero();
    }

    // ---- run binding ---------------------------------------------------------------------------------

    @Test
    @DisplayName("[AC-08] chat and weekly runs can only file proposals, even for Tier 1 changes")
    void chatAndWeeklyPropose() {
        Run chat = w.startRun(Loop.CHAT);
        assertProposed(submitAs(chat, new DecreaseBudget(B1, 45_000_000)));

        w.clock.set(TestWorld.MONDAY_MORNING);
        Run weekly = w.startRun(Loop.WEEKLY);
        assertProposed(submitAs(weekly, new AdjustKeywordBid(K1, 1_050_000)));
        assertThat(w.fake.realMutations()).as("neither loop may write directly").isZero();
    }

    @Test
    @DisplayName("[AC-08] a triage run proposes ordinary Tier 1 changes instead of applying them")
    void triageProposesOrdinaryChanges() {
        Run triage = w.startRun(Loop.TRIAGE);
        assertProposed(submitAs(triage, new DecreaseBudget(B1, 45_000_000)));
        assertThat(w.fake.realMutations()).isZero();
    }

    @Test
    @DisplayName("[AC-08] emergency pause: triage only, only while a spend cap is breached, exempt from cooldown")
    void emergencyPause() {
        Run triage = w.startRun(Loop.TRIAGE);
        EmergencyPauseCampaign pause = new EmergencyPauseCampaign(EntityRef.campaign("C1"));

        assertRefused(submitAs(triage, pause), Guard.Rule.EMERGENCY_ONLY);   // no cap breached
        w.alertStore.open("SPEND_DAILY_WARNING", "WARNING", "", "spend is 1.6x daily budgets", Map.of(), w.clock.instant());
        assertRefused(submitAs(daily, pause), Guard.Rule.LOOP_NOT_ALLOWED);  // the daily loop may not

        w.recordApplied("decrease_budget", "T1", "CAMPAIGN:C1", 1, w.clock.instant(), "recent");
        assertApplied(submitAs(triage, pause));                               // cooldown does not apply
        assertThat(w.fake.campaignStatus("C1")).isEqualTo("PAUSED");
    }

    @Test
    @DisplayName("[AC-08] emergency pause still works under the kill switch and an open breaker, and is exempt from volume limits")
    void emergencyPauseThroughStopMechanisms() {
        Run triage = w.startRun(Loop.TRIAGE);
        w.alertStore.open("SPEND_DAILY_BREAKER", "CRITICAL", "", "breaker", Map.of(), w.clock.instant());
        w.configStore.setKillSwitch(true, "test", "owner");
        w.useLimits(TestLimits.defaults().tier1PerDay(1).build());
        w.recordApplied("pause_keyword", "T1", "KEYWORD:G9~K9", 1, w.clock.instant(), "x");
        assertApplied(submitAs(triage, new EmergencyPauseCampaign(EntityRef.campaign("C1"))));
    }

    @Test
    @DisplayName("[AC-08] a run that has ended, expired or never existed cannot change anything")
    void invalidRunsRefused() {
        DecreaseBudget change = new DecreaseBudget(B1, 45_000_000);

        ChangeMeta unknown = new ChangeMeta("r", "e", UUID.randomUUID().toString(), "k-unknown");
        assertRefused(w.changes.submit(change, unknown), Guard.Rule.RUN_INVALID);
        ChangeMeta garbage = new ChangeMeta("r", "e", "not-a-uuid", "k-garbage");
        assertRefused(w.changes.submit(change, garbage), Guard.Rule.RUN_INVALID);

        Run chat = w.startRun(Loop.CHAT);
        w.runs.end(chat.id(), "a", "b", "c");
        assertRefused(submitAs(chat, change), Guard.Rule.RUN_INVALID);

        w.clock.advance(Duration.ofHours(3)); // the run lasts 2 hours
        assertRefused(submit(change), Guard.Rule.RUN_INVALID);
    }

    @Test
    @DisplayName("[AC-08] a scheduled loop gets one run slot per window, and reuse raises an alert")
    void slotsAreOneShot() {
        assertThatThrownBy(() -> w.startRun(Loop.DAILY))
                .isInstanceOf(RunService.RunRefused.class)
                .hasMessageContaining("already started a run in this window");
        assertThat(w.alertStore.hasOpen("RUN_SLOT_REUSED")).isTrue();
        assertThat(w.channel.byTitle("RUN_SLOT_REUSED")).as("the owner is told").hasSize(1);

        w.startRun(Loop.TRIAGE);
        assertThatThrownBy(() -> w.startRun(Loop.TRIAGE)).isInstanceOf(RunService.RunRefused.class);
        w.clock.advance(Duration.ofHours(1));
        w.startRun(Loop.TRIAGE); // next hour, new slot
        w.startRun(Loop.CHAT);
        w.startRun(Loop.CHAT); // chat has no slots
    }

    @Test
    @DisplayName("[AC-08] a scheduled loop cannot start outside its window")
    void windowsAreEnforced() {
        w.clock.set(Instant.parse("2026-11-04T11:00:00Z")); // 12:00 Warsaw; the daily window is 04:00-10:00
        assertThatThrownBy(() -> w.startRun(Loop.DAILY))
                .isInstanceOf(RunService.RunRefused.class).hasMessageContaining("between 04:00 and 10:00");
        assertThatThrownBy(() -> w.startRun(Loop.WEEKLY))
                .isInstanceOf(RunService.RunRefused.class).hasMessageContaining("MONDAY");
        w.clock.set(TestWorld.MONDAY_MORNING);
        w.startRun(Loop.WEEKLY);
    }

    @Test
    @DisplayName("[AC-08] a rollback-only change cannot be submitted directly")
    void rollbackOnlyNotDirectlySubmittable() {
        ChangeOutcome o = submit(new RemoveNegativeKeywords(EntityRef.campaign("C1"), List.of("1")));
        assertRefused(o, Guard.Rule.ROLLBACK_ONLY);
        assertThat(w.fake.realMutations()).isZero();
        assertThat(new AddNegativeKeywords(EntityRef.campaign("C1"), List.of("x"), MatchType.EXACT).rollbackOnly()).isFalse();
    }
}

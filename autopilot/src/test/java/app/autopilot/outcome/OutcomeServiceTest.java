package app.autopilot.outcome;

import app.autopilot.domain.ChangeOutcome;
import app.autopilot.domain.ChangeRequest.DecreaseBudget;
import app.autopilot.domain.EntityRef;
import app.autopilot.outcome.OutcomeMath.Confidence;
import app.autopilot.outcome.OutcomeService.Recorded;
import app.autopilot.outcome.OutcomeService.Review;
import app.autopilot.store.MetricStore.Snapshot;
import app.autopilot.testkit.WorldTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OutcomeServiceTest extends WorldTest {

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

    private LocalDate today() {
        return LocalDate.ofInstant(w.clock.instant(), WARSAW);
    }

    private void snap(String campaign, LocalDate day, long cost, long clicks, double conv) {
        w.metricStore.upsertSnapshot(new Snapshot(day, "CAMPAIGN:" + campaign, "DAILY", cost, clicks, clicks * 10, conv, 0, w.clock.instant()), w.clock.instant());
    }

    /** Applies a budget decrease on B1 (campaign C1) and returns the change id. */
    private UUID applyChange() {
        ChangeOutcome o = submit(new DecreaseBudget(EntityRef.budget("B1"), 45_000_000));
        assertApplied(o);
        return UUID.fromString(o.changeId());
    }

    @Test
    @DisplayName("[AC-47] the watchdog's snapshot also keeps one daily row per campaign, from the same read")
    void snapshotsPerCampaign() {
        w.fake.proceduralMetrics = true;
        int before = w.fake.callCount("performance");
        w.snapshots.refresh(w.config.limits());
        List<Snapshot> c1 = w.metricStore.snapshots("CAMPAIGN:C1", "DAILY", today().minusDays(14), today().minusDays(1));
        assertThat(c1).hasSize(14);
        assertThat(w.metricStore.snapshots(app.autopilot.watchdog.SnapshotService.ACCOUNT, "DAILY", today().minusDays(14), today().minusDays(1))).hasSize(14);
        assertThat(w.fake.callCount("performance") - before).as("one read per day, nothing extra for the campaigns").isEqualTo(14);
    }

    @Test
    @DisplayName("[AC-47] a change is measured on the campaigns it touched: seven days before against the matured days after")
    void measuresBeforeAndAfter() {
        LocalDate changeDay = today();
        UUID change = applyChange();
        // before: 7 days, 14 sales for 140 PLN; other campaigns must not count
        for (int i = 1; i <= 7; i++) {
            snap("C1", changeDay.minusDays(i), 20_000_000, 10, 2);
            snap("C2", changeDay.minusDays(i), 99_000_000, 99, 9);
        }
        w.clock.advance(Duration.ofDays(8));
        // after: change day + 1 .. today - 1 - 3 lag days = 4 days; the unmatured days are ignored even if present
        for (int i = 1; i <= 4; i++) snap("C1", changeDay.plusDays(i), 20_000_000, 10, 2.5);
        snap("C1", changeDay.plusDays(6), 500_000_000, 999, 0);
        snap("C1", changeDay.plusDays(7), 500_000_000, 999, 0);

        List<Review> due = w.outcomes.due(10);
        assertThat(due).hasSize(1);
        Review r = due.get(0);
        assertThat(r.changeId()).isEqualTo(change);
        assertThat(r.tool()).isEqualTo("decrease_budget");
        assertThat(r.campaignIds()).containsExactly("C1");
        assertThat(r.due()).isTrue();
        assertThat(r.measurement().before().days()).isEqualTo(7);
        assertThat(r.measurement().after().days()).isEqualTo(4);
        assertThat(r.measurement().before().costMicros()).isEqualTo(140_000_000L);
        assertThat(r.measurement().confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(r.measurement().suggestedVerdict()).isEqualTo("BETTER");
        assertThat(r.intent()).containsEntry("reason", "test reason").containsEntry("expectedEffect", "test expected effect");
    }

    @Test
    @DisplayName("[AC-47] a change that is not due yet is not listed, and a change with no history is UNKNOWN with confidence NONE")
    void notDueAndNoHistory() {
        UUID change = applyChange();
        assertThat(w.outcomes.due(10)).isEmpty();
        assertThat(w.outcomes.review(change)).hasValueSatisfying(r -> assertThat(r.due()).isFalse());

        w.clock.advance(Duration.ofDays(8));
        List<Review> due = w.outcomes.due(10);
        assertThat(due).hasSize(1);
        assertThat(due.get(0).measurement().confidence()).isEqualTo(Confidence.NONE);
        assertThat(due.get(0).measurement().suggestedVerdict()).isEqualTo("UNKNOWN");
        assertThat(w.outcomes.review(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("[AC-47] every kind of entity key resolves to its campaign; unknown keys resolve to none")
    void campaignResolution() {
        assertThat(w.outcomes.campaignIdsFor("CAMPAIGN:C1")).containsExactly("C1");
        assertThat(w.outcomes.campaignIdsFor("BUDGET:B1")).containsExactly("C1");
        assertThat(w.outcomes.campaignIdsFor("AD_GROUP:G1")).containsExactly("C1");
        assertThat(w.outcomes.campaignIdsFor("AD_GROUP:G1#add_keywords")).containsExactly("C1");
        assertThat(w.outcomes.campaignIdsFor("KEYWORD:G1~K1")).containsExactly("C1");
        assertThat(w.outcomes.campaignIdsFor("AD:G1~A1")).containsExactly("C1");
        assertThat(w.outcomes.campaignIdsFor("KEYWORD:NOPE~NOPE")).isEmpty();
        assertThat(w.outcomes.campaignIdsFor("GARBAGE")).isEmpty();
        assertThat(w.outcomes.campaignIdsFor("WIDGET:1")).isEmpty();
        assertThat(w.outcomes.campaignIdsFor(null)).isEmpty();
    }

    @Test
    @DisplayName("[AC-48] a verdict and a learning are recorded once, audited, and then offered to later runs")
    void recordsOnce() {
        UUID change = applyChange();
        assertThat(w.outcomes.record(change, "NEUTRAL", "too early", daily.id(), "DAILY")).as("before the review time").isEqualTo(Recorded.NOT_DUE);
        assertThat(w.ledger.recentLearnings(10)).isEmpty();

        w.clock.advance(Duration.ofDays(8));
        assertThat(w.outcomes.record(UUID.randomUUID(), "BETTER", "x", daily.id(), "DAILY")).isEqualTo(Recorded.UNKNOWN_CHANGE);
        assertThat(w.outcomes.record(change, "UNKNOWN", "Budget trim on the core campaign; no sales data yet, check again with more history.", daily.id(), "DAILY"))
                .isEqualTo(Recorded.SAVED);
        assertThat(w.outcomes.record(change, "WORSE", "second opinion", daily.id(), "DAILY")).as("a review is final").isEqualTo(Recorded.ALREADY_REVIEWED);

        assertThat(w.outcomes.due(10)).as("reviewed changes are no longer due").isEmpty();
        var learnings = w.ledger.recentLearnings(10);
        assertThat(learnings).hasSize(1);
        assertThat(learnings.get(0).verdict()).isEqualTo("UNKNOWN");
        assertThat(learnings.get(0).learning()).startsWith("Budget trim on the core campaign");
        assertThat(learnings.get(0).tool()).isEqualTo("decrease_budget");
        assertThat(learnings.get(0).confidence()).isEqualTo("NONE");
        assertThat(w.outcomes.review(change)).hasValueSatisfying(r -> assertThat(r.recordedVerdict()).isEqualTo("UNKNOWN"));
        assertThat(w.ledger.auditFor("OUTCOME_REVIEWED")).hasSize(1).first().satisfies(row -> {
            assertThat(row.get("tool")).isEqualTo("record_outcome_review");
            assertThat(row.get("change_id")).hasToString(change.toString());
            assertThat(String.valueOf(row.get("message"))).startsWith("UNKNOWN: Budget trim");
        });
    }

    @Test
    @DisplayName("[AC-48] reviews the watchdog closed as unreviewed are not presented as learnings")
    void unreviewedIsNotALearning() {
        applyChange();
        w.clock.advance(Duration.ofDays(15));
        w.watchdog.runOnce();
        assertThat(w.ledger.pendingOutcomes()).isZero();
        assertThat(w.ledger.recentLearnings(10)).isEmpty();
    }
}

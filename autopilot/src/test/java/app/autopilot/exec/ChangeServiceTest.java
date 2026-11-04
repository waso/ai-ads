package app.autopilot.exec;

import app.autopilot.ads.AdsException;
import app.autopilot.domain.ChangeMeta;
import app.autopilot.domain.ChangeOutcome;
import app.autopilot.domain.ChangeRequest;
import app.autopilot.domain.ChangeRequest.AddKeywords;
import app.autopilot.domain.ChangeRequest.AddNegativeKeywords;
import app.autopilot.domain.ChangeRequest.AdjustKeywordBid;
import app.autopilot.domain.ChangeRequest.DecreaseBudget;
import app.autopilot.domain.ChangeRequest.EnableEntity;
import app.autopilot.domain.ChangeRequest.IncreaseBudget;
import app.autopilot.domain.ChangeRequest.KeywordSpec;
import app.autopilot.domain.ChangeRequest.PauseKeyword;
import app.autopilot.domain.ChangeRequest.RemoveNegativeKeywords;
import app.autopilot.domain.Diff;
import app.autopilot.domain.EntityRef;
import app.autopilot.domain.MatchType;
import app.autopilot.domain.Plan;
import app.autopilot.domain.Tier;
import app.autopilot.guard.Guard;
import app.autopilot.store.ProposalStore;
import app.autopilot.store.RunStore.Run;
import app.autopilot.testkit.TestLimits;
import app.autopilot.testkit.WorldTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChangeServiceTest extends WorldTest {

    private static final EntityRef B1 = EntityRef.budget("B1");
    private static final EntityRef K1 = EntityRef.keyword("G1~K1");

    private DecreaseBudget decrease(long to) { return new DecreaseBudget(B1, to); }

    private List<Map<String, Object>> auditRows() {
        return JdbcClient.create(w.db.owner()).sql("SELECT * FROM audit_events ORDER BY id").query().listOfRows();
    }

    // ---- idempotency -----------------------------------------------------------------------------------

    @Test
    @DisplayName("[AC-05] replaying a call with the same idempotency key returns the earlier result and sends one mutation")
    void replayReturnsEarlierResult() {
        ChangeOutcome first = w.changes.submit(decrease(45_000_000), w.meta(daily, "key-1"));
        ChangeOutcome replay = w.changes.submit(decrease(45_000_000), w.meta(daily, "key-1"));
        assertApplied(first);
        assertThat(replay).isEqualTo(first);
        assertThat(w.fake.realMutations()).isEqualTo(1);
        assertThat(w.fake.budgetAmount("B1")).isEqualTo(45_000_000);

        ChangeOutcome proposal1 = w.changes.submit(new app.autopilot.domain.ChangeRequest.PauseAd(EntityRef.ad("G1~A1")), w.meta(daily, "key-2"));
        ChangeOutcome proposal2 = w.changes.submit(new app.autopilot.domain.ChangeRequest.PauseAd(EntityRef.ad("G1~A1")), w.meta(daily, "key-2"));
        assertProposed(proposal1);
        assertThat(proposal2).isEqualTo(proposal1);
        assertThat(w.filed).hasSize(1);
    }

    @Test
    @DisplayName("[AC-05] the same key used for a different change is refused, not replayed")
    void keyReuseWithDifferentChange() {
        assertApplied(w.changes.submit(decrease(45_000_000), w.meta(daily, "key-1")));
        ChangeOutcome o = w.changes.submit(new AdjustKeywordBid(K1, 1_050_000), w.meta(daily, "key-1"));
        assertRefused(o, ChangeService.IDEMPOTENCY_MISMATCH);
        assertThat(w.fake.realMutations()).isEqualTo(1);
    }

    @Test
    @DisplayName("[AC-05] the same tool, entity and target value under a new key is rejected as a duplicate")
    void duplicateUnderNewKey() {
        TestLimits open = TestLimits.defaults().cooldownHours(0);
        open.budgetDecreaseIntervalHours = 0;
        w.useLimits(open.build());
        assertApplied(w.changes.submit(decrease(40_000_000), w.meta(daily, "key-1")));
        w.fake.budgets.get("B1").amountMicros = 50_000_000; // Google still shows the old value (reporting lag)
        assertRefused(w.changes.submit(decrease(40_000_000), w.meta(daily, "key-2")), Guard.Rule.DUPLICATE);
        assertThat(w.fake.realMutations()).isEqualTo(1);
    }

    @Test
    @DisplayName("[AC-06] cooldown: a second change to the same entity is refused for 72 hours")
    void cooldownBetweenChanges() {
        assertApplied(submit(new AdjustKeywordBid(K1, 1_050_000)));
        ChangeOutcome o = submit(new PauseKeyword(K1));
        assertRefused(o, Guard.Rule.COOLDOWN);
        w.clock.advance(java.time.Duration.ofHours(72));
        Run next = w.startRun(app.autopilot.domain.Loop.DAILY);
        assertApplied(submitAs(next, new PauseKeyword(K1)));
    }

    // ---- audit -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("[AC-17] every attempt is audited with run, reason, old and new values, tier and request id")
    void everyAttemptIsAudited() {
        assertRefused(submit(decrease(25_000_000)), Guard.Rule.BUDGET_DECREASE_PCT);
        assertProposed(submit(new IncreaseBudget(B1, 55_000_000)));
        ChangeOutcome applied = submit(decrease(45_000_000));
        assertApplied(applied);

        List<Map<String, Object>> rows = auditRows();
        assertThat(rows).extracting(r -> r.get("event")).containsExactly("REFUSED", "PROPOSED", "ATTEMPT", "APPLIED");

        Map<String, Object> refused = rows.get(0);
        assertThat(refused.get("rule")).isEqualTo("budget_decrease_pct");
        assertThat(refused.get("message").toString()).contains("-20%");
        assertThat(refused.get("run_id")).isEqualTo(daily.id());
        assertThat(refused.get("reason")).isEqualTo("test reason");

        Map<String, Object> proposed = rows.get(1);
        assertThat(proposed.get("proposal_id")).isNotNull();
        assertThat(proposed.get("tier")).isEqualTo("T2");

        Map<String, Object> done = rows.get(3);
        assertThat(done.get("run_id")).isEqualTo(daily.id());
        assertThat(done.get("loop")).isEqualTo("DAILY");
        assertThat(done.get("tool")).isEqualTo("decrease_budget");
        assertThat(done.get("tier")).isEqualTo("T1");
        assertThat(done.get("entity_key")).isEqualTo("BUDGET:B1");
        assertThat(done.get("reason")).isEqualTo("test reason");
        assertThat(done.get("expected_effect")).isEqualTo("test expected effect");
        assertThat(done.get("old_values").toString()).contains("50000000");
        assertThat(done.get("new_values").toString()).contains("45000000");
        assertThat(done.get("api_request_id").toString()).startsWith("req-");
        assertThat(done.get("change_id").toString()).isEqualTo(applied.changeId());
        assertThat(done.get("idempotency_key")).isNotNull();
    }

    @Test
    @DisplayName("[AC-18] the audit record is written before Google is called")
    void auditComesBeforeGoogle() {
        List<Long> attemptsSeenByGoogle = new ArrayList<>();
        w.fake.beforeMutate = () -> attemptsSeenByGoogle.add(
                JdbcClient.create(w.db.owner()).sql("SELECT count(*) FROM audit_events WHERE event = 'ATTEMPT'")
                        .query(Long.class).single());
        assertApplied(submit(decrease(45_000_000)));
        assertThat(attemptsSeenByGoogle).as("validate_only and the real call both saw the ATTEMPT row").containsExactly(1L, 1L);
    }

    /** An audit log that cannot be written. */
    private static final class BrokenAudit extends Audit {
        BrokenAudit(app.autopilot.store.LedgerStore ledger, tools.jackson.databind.json.JsonMapper mapper) {
            super(ledger, mapper);
        }
        @Override public void record(app.autopilot.store.LedgerStore.AuditEvent e) {
            throw new DataAccessResourceFailureException("audit log unavailable (injected)");
        }
    }

    @Test
    @DisplayName("[AC-18] if the audit record cannot be written, Google is not called")
    void noAuditNoWrite() {
        Audit broken = new BrokenAudit(w.ledger, w.json.mapper());
        ChangeExecutor executor = new ChangeExecutor(w.ads, broken, w.ledger, w.alerts, w.json, w.clock);
        Plan plan = new Plan(decrease(45_000_000), w.meta(daily), Tier.T1, new Diff(Map.of(), Map.of(), "x"), "h", w.clock.instant());
        ChangeExecutor.Actor actor = new ChangeExecutor.Actor("claude", null, null, daily.id(), "DAILY", "k");
        assertThatThrownBy(() -> executor.execute(plan, actor, true)).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(w.fake.sent).as("nothing was sent to Google, not even validate_only").isEmpty();

        ChangeService service = new ChangeService(w.guard, executor, w.config, w.runs, w.ledger, w.proposalStore, w.alertStore,
                broken, new PgWriteLock(w.dataSource), w.json, w.clock, List.of());
        ChangeOutcome o = service.submit(decrease(45_000_000), w.meta(daily));
        assertRefused(o, ChangeService.DATABASE_UNAVAILABLE);
        assertThat(w.fake.sent).isEmpty();
    }

    // ---- applying --------------------------------------------------------------------------------------

    @Test
    @DisplayName("[AC-20] every mutation runs validate_only first, then for real, then is read back")
    void validateThenApplyThenReadBack() {
        assertApplied(submit(decrease(45_000_000)));
        assertThat(w.fake.sent).extracting(s -> s.validateOnly()).containsExactly(true, false);
        assertThat(w.fake.callCount("readBack")).isEqualTo(1);
        assertThat(w.ledger.recentChanges(5)).singleElement().satisfies(c -> {
            assertThat(c.result()).isEqualTo("APPLIED");
            assertThat(c.apiRequestId()).startsWith("req-");
            assertThat(c.oldValues()).containsEntry("amountMicros", 50_000_000);
            assertThat(c.actor()).isEqualTo("claude");
        });
        assertThat(w.ledger.pendingOutcomes()).as("an outcome review is scheduled").isEqualTo(1);
    }

    @Test
    @DisplayName("[AC-20] when validate_only fails the real call is never sent and the failure is recorded")
    void validationFailureStopsTheWrite() {
        w.fake.failNextValidation = new AdsException("MUTATE_ERROR", "Budget too low (injected)");
        ChangeOutcome o = submit(decrease(45_000_000));
        assertThat(o.status()).isEqualTo(ChangeOutcome.Status.FAILED);
        assertThat(o.message()).contains("MUTATE_ERROR");
        assertThat(w.fake.sent).extracting(s -> s.validateOnly()).containsExactly(true);
        assertThat(w.fake.budgetAmount("B1")).isEqualTo(50_000_000);
        assertThat(w.ledger.recentChanges(5)).singleElement().satisfies(c -> assertThat(c.result()).isEqualTo("FAILED"));
        assertThat(auditRows()).extracting(r -> r.get("event")).contains("FAILED");
    }

    @Test
    @DisplayName("[AC-20] a read-back that does not match raises an alert and tells the owner")
    void readBackMismatchAlerts() {
        w.fake.readBackMismatch = true;
        assertApplied(submit(decrease(45_000_000)));
        assertThat(w.alertStore.hasOpen("VERIFY_MISMATCH")).isTrue();
        assertThat(w.channel.byTitle("VERIFY_MISMATCH")).hasSize(1);
        assertThat(auditRows()).extracting(r -> r.get("event")).contains("VERIFY_MISMATCH");
    }

    // ---- rollback --------------------------------------------------------------------------------------

    @Test
    @DisplayName("[AC-21] rollback restores the recorded old value through a new Tier 2 proposal and audits it")
    void rollbackBudget() {
        ChangeOutcome applied = submit(decrease(40_000_000)); // -20%; the way back is +25%, beyond the +20% step limit
        assertApplied(applied);

        ChangeOutcome rollback = w.changes.proposeRollback(applied.changeId(), w.meta(daily));
        assertProposed(rollback);
        ProposalStore.Proposal p = w.proposalStore.find(UUID.fromString(rollback.proposalId())).orElseThrow();
        assertThat(p.request()).isEqualTo(new IncreaseBudget(B1, 50_000_000));
        assertThat(p.tier()).isEqualTo("T2");
        assertThat(p.diff().before()).containsEntry("amountMicros", 40_000_000);
        assertThat(p.diff().after()).containsEntry("amountMicros", 50_000_000);
        assertThat(w.fake.budgetAmount("B1")).as("nothing is restored until the owner approves").isEqualTo(40_000_000);
        assertThat(auditRows()).anySatisfy(r -> {
            assertThat(r.get("event")).isEqualTo("PROPOSED");
            assertThat(r.get("tool")).isEqualTo("increase_budget");
            assertThat(r.get("proposal_id")).isEqualTo(p.id());
        });
    }

    @Test
    @DisplayName("[AC-21] rollback of negatives removes exactly the criteria this system created; other inverses are built from the record")
    void rollbackOtherTypes() {
        ChangeOutcome neg = submit(new AddNegativeKeywords(EntityRef.campaign("C1"), List.of("zzterm1", "zzterm2"), MatchType.PHRASE));
        assertApplied(neg);
        ChangeOutcome rb = w.changes.proposeRollback(neg.changeId(), w.meta(daily));
        assertProposed(rb);
        var removal = (RemoveNegativeKeywords) w.proposalStore.find(UUID.fromString(rb.proposalId())).orElseThrow().request();
        assertThat(removal.scope()).isEqualTo(EntityRef.campaign("C1"));
        assertThat(removal.criterionIds()).containsExactlyElementsOf(w.fake.negatives.stream().map(n -> n.id).toList());

        ChangeOutcome pause = submit(new PauseKeyword(K1));
        assertApplied(pause);
        var enable = w.proposalStore.find(UUID.fromString(w.changes.proposeRollback(pause.changeId(), w.meta(daily)).proposalId()))
                .orElseThrow().request();
        assertThat(enable).isEqualTo(new EnableEntity(K1));
    }

    @Test
    @DisplayName("[AC-21] a change without a safe inverse is refused instead of guessed")
    void rollbackRefusedWithoutInverse() {
        w.fake.adGroup("G2", "C1");
        // keywords and ads can only be removed in the Google Ads interface (Tier 3), so there is no inverse
        w.recordApplied("add_keywords", "T2", "AD_GROUP:G1#add_keywords", 1, w.clock.instant(), "x",
                w.json.write(new AddKeywords(EntityRef.adGroup("G1"), List.of(new KeywordSpec("running shoes", MatchType.PHRASE, null)))));
        UUID id = w.ledger.recentChanges(1).get(0).id();
        ChangeOutcome o = w.changes.proposeRollback(id.toString(), w.meta(daily));
        assertRefused(o, ChangeService.NO_SAFE_INVERSE);
        assertThat(o.message()).contains("Tier 3");

        assertRefused(w.changes.proposeRollback(UUID.randomUUID().toString(), w.meta(daily)), "unknown_change");
        assertRefused(w.changes.proposeRollback("nonsense", w.meta(daily)), "unknown_change");

        // an unreadable stored request is refused too, not guessed at
        w.recordApplied("decrease_budget", "T1", "BUDGET:B1", 1, w.clock.instant(), "y", "{}");
        assertRefused(w.changes.proposeRollback(w.ledger.recentChanges(1).get(0).id().toString(), w.meta(daily)),
                ChangeService.NO_SAFE_INVERSE);

        w.fake.failNextValidation = new AdsException("X", "no");
        ChangeOutcome failed = submit(new AdjustKeywordBid(EntityRef.keyword("G1~K2"), 2_100_000));
        assertThat(failed.status()).isEqualTo(ChangeOutcome.Status.FAILED);
        assertRefused(w.changes.proposeRollback(failed.changeId(), w.meta(daily)), ChangeService.NO_SAFE_INVERSE);
    }

    // ---- one write at a time ---------------------------------------------------------------------------

    @Test
    @DisplayName("[AC-22] two concurrent changes cannot both pass the daily volume check")
    void oneWriteAtATime() throws Exception {
        w.useLimits(TestLimits.defaults().tier1PerDay(1).build());
        w.fake.beforeMutate = () -> {
            try {
                Thread.sleep(150); // widen the window in which an unlocked second check would pass
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        List<ChangeRequest> requests = List.of(new AdjustKeywordBid(EntityRef.keyword("G1~K1"), 1_050_000),
                new AdjustKeywordBid(EntityRef.keyword("G1~K2"), 2_100_000),
                new AdjustKeywordBid(EntityRef.keyword("G1~K3"), 3_150_000),
                new DecreaseBudget(B1, 45_000_000));
        ExecutorService pool = Executors.newFixedThreadPool(requests.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ChangeOutcome>> results = new ArrayList<>();
        for (ChangeRequest r : requests) {
            results.add(pool.submit(() -> {
                go.await();
                return w.changes.submit(r, w.meta(daily));
            }));
        }
        go.countDown();
        List<ChangeOutcome> outcomes = new ArrayList<>();
        for (Future<ChangeOutcome> f : results) outcomes.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();

        assertThat(outcomes.stream().filter(o -> o.status() == ChangeOutcome.Status.APPLIED)).hasSize(1);
        assertThat(outcomes.stream().filter(o -> Guard.Rule.TIER1_VOLUME.equals(o.rule()))).hasSize(3);
        assertThat(w.fake.realMutations()).isEqualTo(1);
    }
}

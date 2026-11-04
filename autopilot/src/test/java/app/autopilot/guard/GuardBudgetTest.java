package app.autopilot.guard;

import app.autopilot.domain.ChangeOutcome;
import app.autopilot.domain.ChangeRequest.DecreaseBudget;
import app.autopilot.domain.ChangeRequest.IncreaseBudget;
import app.autopilot.domain.EntityRef;
import app.autopilot.testkit.TestLimits;
import app.autopilot.testkit.WorldTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class GuardBudgetTest extends WorldTest {

    private static final EntityRef B1 = EntityRef.budget("B1"); // 50.00 per day

    private DecreaseBudget decrease(long to) { return new DecreaseBudget(B1, to); }
    private IncreaseBudget increase(long to) { return new IncreaseBudget(B1, to); }

    @Test
    @DisplayName("[AC-01] budget decrease: exactly -20% is applied, one micro over is refused")
    void decreaseBoundary() {
        ChangeOutcome over = submit(decrease(39_999_999));
        assertRefused(over, Guard.Rule.BUDGET_DECREASE_PCT);
        assertThat(over.message()).contains("exceeds the -20% limit");
        assertThat(w.fake.realMutations()).isZero();

        assertApplied(submit(decrease(40_000_000)));
        assertThat(w.fake.budgetAmount("B1")).isEqualTo(40_000_000);
    }

    @Test
    @DisplayName("[AC-02] a refusal names the rule and the limit, with the real change in percent")
    void refusalNamesRuleAndLimit() {
        ChangeOutcome o = submit(decrease(25_000_000));
        assertRefused(o, Guard.Rule.BUDGET_DECREASE_PCT);
        assertThat(o.message()).isEqualTo("Budget change of -50% exceeds the -20% limit.");
    }

    @Test
    @DisplayName("[AC-01] budget increase: +20% is proposed, one micro over is refused")
    void increaseBoundary() {
        assertRefused(submit(increase(60_000_001)), Guard.Rule.BUDGET_INCREASE_PCT);
        assertProposed(submit(increase(60_000_000)));
        assertThat(w.fake.realMutations()).as("budget increases are Tier 2: never applied by Claude").isZero();
    }

    @Test
    @DisplayName("[AC-01] budget increase is allowed once per 7 days, decrease once per 24 hours")
    void budgetIntervals() {
        Instant now = w.clock.instant();
        w.recordApplied("increase_budget", "T2", "BUDGET:B1", 1, now.minus(Duration.ofDays(7)).plusSeconds(1), "other");
        assertRefused(submit(increase(55_000_000)), Guard.Rule.BUDGET_INCREASE_INTERVAL);

        // the 72 h cooldown is longer than the 24 h decrease interval, so with a short cooldown the 24 h applies
        w.useLimits(TestLimits.defaults().cooldownHours(0).build());
        w.recordApplied("decrease_budget", "T1", "BUDGET:B1", 1, now.minus(Duration.ofHours(24)).plusSeconds(1), "other2");
        assertRefused(submit(decrease(45_000_000)), Guard.Rule.BUDGET_DECREASE_INTERVAL);
    }

    @Test
    @DisplayName("[AC-01] cooldown: 72 hours between changes to an entity, exactly 72 hours is allowed")
    void cooldownBoundary() {
        Instant now = w.clock.instant();
        w.recordApplied("adjust_keyword_bid", "T1", "BUDGET:B1", 1, now.minus(Duration.ofHours(72)).plusSeconds(1), "f1");
        ChangeOutcome o = submit(decrease(45_000_000));
        assertRefused(o, Guard.Rule.COOLDOWN);
        assertThat(o.message()).contains("cooldown is 72 h");

        w.clock.advance(Duration.ofSeconds(1));
        assertApplied(submit(decrease(45_000_000)));
    }

    @Test
    @DisplayName("[AC-06] a change made by the owner in the Google Ads interface starts the cooldown and is never reverted")
    void ownerChangeStartsCooldownAndIsNotReverted() {
        w.metricStore.recordExternalChange("customers/1/campaignBudgets/B1", "BUDGET:B1", w.clock.instant().minus(Duration.ofHours(1)),
                "owner@example.com", "GOOGLE_ADS_WEB_CLIENT", "UPDATE");
        assertRefused(submit(decrease(45_000_000)), Guard.Rule.COOLDOWN);
        assertThat(w.fake.realMutations()).as("nothing was sent to Google: the owner's change stays").isZero();
    }

    @Test
    @DisplayName("[AC-07] no write is accepted until the monthly cap is set")
    void writesDisabledWithoutCap() {
        w.useLimits(TestLimits.defaults().cap(null).build());
        assertRefused(submit(decrease(45_000_000)), Guard.Rule.CAPS_NOT_SET);
        assertRefused(submit(increase(55_000_000)), Guard.Rule.CAPS_NOT_SET);
        assertThat(w.fake.realMutations()).isZero();
    }

    @Test
    @DisplayName("[AC-07] sum of daily budgets x 30.4 must stay within the monthly cap")
    void monthlyCapBoundary() {
        // enabled budgets: 50 + 20 + 10 = 80; raising B1 to 60 makes 90; 90 x 30.4 = 2736.00
        w.useLimits(TestLimits.defaults().cap(2_735_999_999L).build());
        ChangeOutcome over = submit(increase(60_000_000));
        assertRefused(over, Guard.Rule.MONTHLY_CAP);
        assertThat(over.message()).contains("monthly cap");

        w.useLimits(TestLimits.defaults().cap(2_736_000_000L).build());
        assertProposed(submit(increase(60_000_000)));
    }

    @Test
    @DisplayName("[AC-03] Tier 2 never applies directly; the same proposal is not filed twice")
    void tier2ProposedOnceOnly() {
        ChangeOutcome first = submit(increase(55_000_000));
        ChangeOutcome second = submit(increase(55_000_000));
        assertProposed(first);
        assertProposed(second);
        assertThat(second.proposalId()).isEqualTo(first.proposalId());
        assertThat(w.filed).hasSize(1);
    }

    @Test
    @DisplayName("[AC-01] a decrease must lower the budget and keep it above zero")
    void decreaseMustDecrease() {
        assertRefused(submit(decrease(50_000_000)), Guard.Rule.NO_CHANGE);
        assertRefused(submit(decrease(60_000_000)), Guard.Rule.NO_CHANGE);
        assertRefused(submit(decrease(0)), Guard.Rule.BUDGET_FLOOR);
    }
}

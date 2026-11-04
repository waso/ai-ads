package app.autopilot.testkit;

import app.autopilot.domain.AutonomyLevel;
import app.autopilot.domain.Limits;

import java.util.List;

/** Builds {@link Limits} for tests: the PRD defaults, with the fields tests change. */
public final class TestLimits {
    public String accountId = "1234567890";
    public Long monthlyCapMicros = 3_000_000_000L;
    public AutonomyLevel autonomy = AutonomyLevel.L2;
    public int budgetIncreasePct = 20, budgetIncreaseIntervalDays = 7, budgetDecreasePct = 20, budgetDecreaseIntervalHours = 24;
    public int bidChangePct = 10, bidIntervalDays = 7, negativesPerDay = 25, keywordPauseMinClicks = 100;
    public double keywordPauseMinCostCpaMultiple = 2.0;
    public int keywordPauseWindowDays = 30, tier1PerDay = 50, cooldownHours = 72, conversionLagDays = 3;
    public int proposalLifetimeHours = 48, negativeConvertedLookbackDays = 90;
    public List<String> allowedDomains = List.of("example.com");
    public List<Long> allowedGeoTargets = List.of(2616L);
    public double dailyAlertMultiple = 1.5, dailyBreakerMultiple = 1.8;
    public int quotaDailyOps = 2880, quotaPauseReadsPct = 80;
    public int maxActiveCampaigns = 0;
    public long minDailyBudgetMicros = 0;

    public static TestLimits defaults() {
        return new TestLimits();
    }

    public TestLimits autonomy(AutonomyLevel l) { autonomy = l; return this; }
    public TestLimits cap(Long micros) { monthlyCapMicros = micros; return this; }
    public TestLimits cooldownHours(int h) { cooldownHours = h; return this; }
    public TestLimits tier1PerDay(int n) { tier1PerDay = n; return this; }
    public TestLimits negativesPerDay(int n) { negativesPerDay = n; return this; }
    public TestLimits quotaDailyOps(int n) { quotaDailyOps = n; return this; }
    public TestLimits proposalLifetimeHours(int h) { proposalLifetimeHours = h; return this; }
    public TestLimits maxActiveCampaigns(int n) { maxActiveCampaigns = n; return this; }
    public TestLimits minDailyBudget(long micros) { minDailyBudgetMicros = micros; return this; }

    public Limits build() {
        return new Limits(accountId, "Europe/Warsaw", monthlyCapMicros, autonomy, budgetIncreasePct,
                budgetIncreaseIntervalDays, budgetDecreasePct, budgetDecreaseIntervalHours, bidChangePct, bidIntervalDays,
                negativesPerDay, keywordPauseMinClicks, keywordPauseMinCostCpaMultiple, keywordPauseWindowDays, tier1PerDay,
                cooldownHours, conversionLagDays, proposalLifetimeHours, negativeConvertedLookbackDays, allowedDomains,
                allowedGeoTargets, dailyAlertMultiple, dailyBreakerMultiple, quotaDailyOps, quotaPauseReadsPct,
                "agent-managed", "no-agent", maxActiveCampaigns, minDailyBudgetMicros);
    }
}

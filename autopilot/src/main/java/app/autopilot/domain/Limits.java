package app.autopilot.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.ZoneId;
import java.util.List;

/**
 * Hard limits. They live in the server, outside Claude's reach: bound from server configuration at
 * startup, versioned in {@code config_limits}, and read by the guard on every call. Defaults are the
 * PRD's proposed values for the owner to confirm; writes stay disabled until {@code monthlyCapMicros}
 * is set.
 */
@ConfigurationProperties("autopilot.limits")
public record Limits(
        /* Google Ads customer id (digits) of the single allowlisted account. */
        String accountId,
        @DefaultValue("Europe/Warsaw") String accountTimeZone,
        /* Monthly account cap in account-currency micros. Required before any write. */
        Long monthlyCapMicros,
        @DefaultValue("L0") AutonomyLevel autonomy,

        @DefaultValue("20") int budgetIncreasePct,
        @DefaultValue("7") int budgetIncreaseIntervalDays,
        @DefaultValue("20") int budgetDecreasePct,
        @DefaultValue("24") int budgetDecreaseIntervalHours,
        @DefaultValue("10") int bidChangePct,
        @DefaultValue("7") int bidIntervalDays,
        @DefaultValue("25") int negativesPerDay,
        @DefaultValue("100") int keywordPauseMinClicks,
        /* Minimum cost before a keyword pause, as a multiple of target CPA. */
        @DefaultValue("2.0") double keywordPauseMinCostCpaMultiple,
        @DefaultValue("30") int keywordPauseWindowDays,
        @DefaultValue("50") int tier1PerDay,
        @DefaultValue("72") int cooldownHours,
        /* Recent conversion data is incomplete: performance decisions exclude these latest days. */
        @DefaultValue("3") int conversionLagDays,
        @DefaultValue("48") int proposalLifetimeHours,
        @DefaultValue("90") int negativeConvertedLookbackDays,

        List<String> allowedDomains,
        List<Long> allowedGeoTargets,

        @DefaultValue("1.5") double dailyAlertMultiple,
        @DefaultValue("1.8") double dailyBreakerMultiple,
        @DefaultValue("2880") int quotaDailyOps,
        @DefaultValue("80") int quotaPauseReadsPct,

        @DefaultValue("agent-managed") String managedLabel,
        @DefaultValue("no-agent") String noAgentLabel,

        /* At most this many campaigns may be enabled at once (0 = no limit). A small budget spread over many campaigns starves each. */
        @DefaultValue("0") int maxActiveCampaigns,
        /* No campaign may be created, or its budget lowered, below this daily amount (0 = no minimum). */
        @DefaultValue("0") long minDailyBudgetMicros) {

    public Limits {
        allowedDomains = allowedDomains == null ? List.of() : List.copyOf(allowedDomains);
        allowedGeoTargets = allowedGeoTargets == null ? List.of() : List.copyOf(allowedGeoTargets);
        if (accountTimeZone == null) accountTimeZone = "Europe/Warsaw";
        ZoneId.of(accountTimeZone); // fail at startup on a bad zone
        if (autonomy == null) autonomy = AutonomyLevel.L0;
        if (managedLabel == null) managedLabel = "agent-managed";
        if (noAgentLabel == null) noAgentLabel = "no-agent";
    }

    public ZoneId zone() {
        return ZoneId.of(accountTimeZone);
    }

    public boolean capsConfigured() {
        return monthlyCapMicros != null && monthlyCapMicros > 0 && accountId != null && !accountId.isBlank();
    }

    /** Effective minimum gap between two changes of the same entity, in hours. */
    public int cooldownHoursFor(int toolIntervalHours) {
        return Math.max(cooldownHours, toolIntervalHours);
    }

    public Limits withAutonomy(AutonomyLevel level) {
        return new Limits(accountId, accountTimeZone, monthlyCapMicros, level, budgetIncreasePct,
                budgetIncreaseIntervalDays, budgetDecreasePct, budgetDecreaseIntervalHours, bidChangePct,
                bidIntervalDays, negativesPerDay, keywordPauseMinClicks, keywordPauseMinCostCpaMultiple,
                keywordPauseWindowDays, tier1PerDay, cooldownHours, conversionLagDays, proposalLifetimeHours,
                negativeConvertedLookbackDays, allowedDomains, allowedGeoTargets, dailyAlertMultiple,
                dailyBreakerMultiple, quotaDailyOps, quotaPauseReadsPct, managedLabel, noAgentLabel, maxActiveCampaigns,
                minDailyBudgetMicros);
    }
}

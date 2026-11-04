package app.autopilot.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * What Claude optimizes for. Owner-set in configuration, versioned in the database. Money is in account-currency
 * micros (the account's own currency, never converted).
 *
 * @param targetCpaMicros      the cost per sale the owner aims for
 * @param valuePerSaleMicros   revenue from one new subscriber in the first period (informational)
 * @param lifetimeValueMicros  expected revenue per subscriber over their whole subscription
 * @param grossMarginPct       share of that revenue left after cost of service, 0 to 100
 */
@ConfigurationProperties("autopilot.goals")
public record Goals(
        String primaryGoal,
        List<String> conversionActions,
        Long targetCpaMicros,
        Double targetRoas,
        List<String> protectedTerms,
        Long valuePerSaleMicros,
        Long lifetimeValueMicros,
        Integer grossMarginPct) {

    public Goals {
        conversionActions = conversionActions == null ? List.of() : List.copyOf(conversionActions);
        protectedTerms = protectedTerms == null ? List.of() : List.copyOf(protectedTerms);
    }

    public static Goals empty() {
        return new Goals(null, List.of(), null, null, List.of(), null, null, null);
    }

    /** Convenience for the common case with no unit-economics fields. */
    public static Goals of(String primaryGoal, List<String> conversionActions, Long targetCpaMicros, Double targetRoas,
                           List<String> protectedTerms) {
        return new Goals(primaryGoal, conversionActions, targetCpaMicros, targetRoas, protectedTerms, null, null, null);
    }

    /**
     * The most one sale can cost before it loses money: lifetime value x gross margin. Null when either number is
     * not set, in which case nothing is checked against it.
     */
    public Long breakEvenCpaMicros() {
        if (lifetimeValueMicros == null || grossMarginPct == null || lifetimeValueMicros <= 0) return null;
        return lifetimeValueMicros * grossMarginPct / 100;
    }
}

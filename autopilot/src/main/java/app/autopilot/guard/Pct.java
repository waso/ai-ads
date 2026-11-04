package app.autopilot.guard;

/** Exact integer arithmetic for limits: at the limit passes, one micro over is refused. */
public final class Pct {
    private Pct() {}

    /** True when {@code |newValue - oldValue|} is more than {@code limitPct} percent of {@code oldValue}. */
    public static boolean exceeds(long oldValue, long newValue, int limitPct) {
        return Math.abs(newValue - oldValue) * 100 > oldValue * limitPct;
    }

    /**
     * Signed change as text, for example "+35%" or "-12.5%". A change that is not a whole percent is rounded
     * away from zero to one decimal, so a change just over a limit never reads as exactly the limit.
     */
    public static String signed(long oldValue, long newValue) {
        if (oldValue == 0) return "n/a";
        java.math.BigDecimal pct = java.math.BigDecimal.valueOf(newValue - oldValue)
                .multiply(java.math.BigDecimal.valueOf(100))
                .divide(java.math.BigDecimal.valueOf(oldValue), 12, java.math.RoundingMode.HALF_UP);
        java.math.BigDecimal shown = pct.stripTrailingZeros().scale() <= 0
                ? pct.setScale(0, java.math.RoundingMode.UNNECESSARY)
                : pct.setScale(1, java.math.RoundingMode.UP);
        String body = shown.abs().stripTrailingZeros().toPlainString();
        return (pct.signum() >= 0 ? "+" : "-") + body + "%";
    }

    /** Is {@code sumDailyMicros} x 30.4 within {@code monthlyCapMicros}? Integer arithmetic only. */
    public static boolean withinMonthlyCap(long sumDailyMicros, long monthlyCapMicros) {
        return sumDailyMicros * 304 <= monthlyCapMicros * 10;
    }

    /** Largest sum of daily budgets that stays within the monthly cap. */
    public static long maxDailySum(long monthlyCapMicros) {
        return monthlyCapMicros * 10 / 304;
    }
}

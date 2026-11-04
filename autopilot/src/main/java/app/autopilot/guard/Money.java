package app.autopilot.guard;

import java.math.BigDecimal;

/** Account-currency micros for display. The server never sees a currency code, only the amounts. */
public final class Money {
    private Money() {}

    /**
     * Two decimals normally. A value with finer precision keeps it (up to six), so an amount just under a limit never
     * rounds up to read as exactly the limit.
     */
    public static String fmt(long micros) {
        BigDecimal v = BigDecimal.valueOf(micros, 6).stripTrailingZeros();
        return v.setScale(Math.max(2, v.scale()), java.math.RoundingMode.UNNECESSARY).toPlainString();
    }
}

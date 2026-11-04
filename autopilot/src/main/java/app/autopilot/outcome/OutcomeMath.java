package app.autopilot.outcome;

import app.autopilot.store.MetricStore.Snapshot;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What happened after a change, in numbers: the days before against the days after, with an honest statement of how much
 * the numbers can say. With a handful of sales a month most comparisons cannot be judged on sales at all; then the answer is
 * UNKNOWN and the cost and click movement is reported instead of a verdict nobody should trust.
 */
public final class OutcomeMath {

    /** Fewest days on each side before anything is compared. */
    public static final int MIN_DAYS = 3;
    /** Fewest sales across both windows before a sales verdict is suggested. */
    public static final double MIN_CONVERSIONS = 10;
    public static final double HIGH_CONVERSIONS = 30;
    /** Cost per sale must move by at least this much to count as better or worse. */
    static final double BETTER_RATIO = 0.8;
    static final double WORSE_RATIO = 1.25;

    private OutcomeMath() {}

    /** Totals over the days that have data. */
    public record Window(int days, long costMicros, long clicks, long impressions, double conversions) {
        public double costPerDayMicros() { return days == 0 ? 0 : (double) costMicros / days; }
        public double clicksPerDay() { return days == 0 ? 0 : (double) clicks / days; }
        public double conversionsPerDay() { return days == 0 ? 0 : conversions / days; }
        public Long costPerConversionMicros() { return conversions <= 0 ? null : Math.round(costMicros / conversions); }
    }

    public enum Confidence { NONE, LOW, MEDIUM, HIGH }

    public record Measurement(
            Confidence confidence,
            /* BETTER, NEUTRAL, WORSE or UNKNOWN: a suggestion for the reviewer, never a decision. */
            String suggestedVerdict,
            Window before,
            Window after,
            Double costPerDayChangePct,
            Double clicksPerDayChangePct,
            Double conversionsPerDayChangePct,
            Long costPerConversionBeforeMicros,
            Long costPerConversionAfterMicros,
            String note) {}

    /** Sums rows that may come from several campaigns: the days are the distinct dates that have a row. */
    public static Window sum(List<Snapshot> rows) {
        Map<java.time.LocalDate, long[]> byDay = new TreeMap<>();
        double conversions = 0;
        for (Snapshot s : rows) {
            long[] d = byDay.computeIfAbsent(s.date(), k -> new long[3]);
            d[0] += s.costMicros();
            d[1] += s.clicks();
            d[2] += s.impressions();
            conversions += s.conversions();
        }
        long cost = 0, clicks = 0, impressions = 0;
        for (long[] d : byDay.values()) {
            cost += d[0];
            clicks += d[1];
            impressions += d[2];
        }
        return new Window(byDay.size(), cost, clicks, impressions, conversions);
    }

    public static Measurement evaluate(List<Snapshot> beforeRows, List<Snapshot> afterRows) {
        return evaluate(sum(beforeRows), sum(afterRows));
    }

    public static Measurement evaluate(Window before, Window after) {
        Double cost = pct(before.costPerDayMicros(), after.costPerDayMicros());
        Double clicks = pct(before.clicksPerDay(), after.clicksPerDay());
        Double conv = pct(before.conversionsPerDay(), after.conversionsPerDay());
        Long cpaBefore = before.costPerConversionMicros();
        Long cpaAfter = after.costPerConversionMicros();

        if (before.days() < MIN_DAYS || after.days() < MIN_DAYS) {
            return new Measurement(Confidence.NONE, "UNKNOWN", before, after, cost, clicks, conv, cpaBefore, cpaAfter,
                    "Too little data to compare: " + before.days() + " day(s) before and " + after.days() + " day(s) after the change "
                            + "(at least " + MIN_DAYS + " on each side). Record UNKNOWN and look again later.");
        }
        double total = before.conversions() + after.conversions();
        if (total < MIN_CONVERSIONS) {
            return new Measurement(Confidence.LOW, "UNKNOWN", before, after, cost, clicks, conv, cpaBefore, cpaAfter,
                    "Only " + oneDecimal(total) + " sale(s) across both windows (at least " + (int) MIN_CONVERSIONS + " needed), so sales "
                            + "cannot show whether this worked. Cost and click movement is shown for context, not as proof.");
        }
        Confidence confidence = total >= HIGH_CONVERSIONS ? Confidence.HIGH : Confidence.MEDIUM;
        String verdict;
        String note;
        if (cpaBefore != null && cpaAfter != null) {
            double ratio = (double) cpaAfter / cpaBefore;
            if (ratio <= BETTER_RATIO) {
                verdict = "BETTER";
            } else if (ratio >= WORSE_RATIO) {
                verdict = "WORSE";
            } else {
                verdict = "NEUTRAL";
            }
            note = "Cost per sale went from " + cpaBefore + " to " + cpaAfter + " micros (x" + String.format(java.util.Locale.ROOT, "%.2f", ratio) + ").";
        } else if (cpaBefore != null) {
            verdict = after.costMicros() >= 2 * cpaBefore ? "WORSE" : "UNKNOWN";
            note = "No sales after the change; it spent " + after.costMicros() + " micros against a previous cost per sale of " + cpaBefore + ".";
        } else {
            verdict = "UNKNOWN";
            note = "No sales before the change to compare with.";
        }
        return new Measurement(confidence, verdict, before, after, cost, clicks, conv, cpaBefore, cpaAfter, note);
    }

    private static Double pct(double before, double after) {
        if (before <= 0) return null;
        return Math.round((after - before) / before * 1000.0) / 10.0;
    }

    private static String oneDecimal(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}

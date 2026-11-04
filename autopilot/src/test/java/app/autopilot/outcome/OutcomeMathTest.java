package app.autopilot.outcome;

import app.autopilot.outcome.OutcomeMath.Confidence;
import app.autopilot.outcome.OutcomeMath.Measurement;
import app.autopilot.outcome.OutcomeMath.Window;
import app.autopilot.store.MetricStore.Snapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OutcomeMathTest {

    private static final LocalDate D0 = LocalDate.of(2026, 10, 1);

    private static List<Snapshot> days(String key, int n, long costPerDay, long clicksPerDay, double convPerDay) {
        List<Snapshot> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rows.add(new Snapshot(D0.plusDays(i), key, "DAILY", costPerDay, clicksPerDay, clicksPerDay * 10, convPerDay, 0, Instant.EPOCH));
        }
        return rows;
    }

    @Test
    @DisplayName("[AC-47] windows sum cost, clicks and sales over the days that have data, also across several campaigns")
    void sums() {
        List<Snapshot> rows = new ArrayList<>(days("CAMPAIGN:1", 3, 10_000_000, 5, 1));
        rows.addAll(days("CAMPAIGN:2", 3, 4_000_000, 2, 0.5));
        Window w = OutcomeMath.sum(rows);
        assertThat(w.days()).as("same dates in two campaigns are three days, not six").isEqualTo(3);
        assertThat(w.costMicros()).isEqualTo(42_000_000L);
        assertThat(w.clicks()).isEqualTo(21);
        assertThat(w.conversions()).isEqualTo(4.5);
        assertThat(w.costPerConversionMicros()).isEqualTo(Math.round(42_000_000 / 4.5));
        assertThat(OutcomeMath.sum(List.of()).costPerConversionMicros()).isNull();
    }

    @Test
    @DisplayName("[AC-47] fewer than 3 days on either side cannot be compared: confidence NONE, verdict UNKNOWN")
    void tooFewDays() {
        for (int[] d : new int[][] {{2, 7}, {7, 2}, {0, 0}, {2, 2}}) {
            Measurement m = OutcomeMath.evaluate(days("C", d[0], 10_000_000, 5, 1), days("C", d[1], 10_000_000, 5, 1));
            assertThat(m.confidence()).as("%d before, %d after", d[0], d[1]).isEqualTo(Confidence.NONE);
            assertThat(m.suggestedVerdict()).isEqualTo("UNKNOWN");
            assertThat(m.note()).contains("Too little data");
        }
        assertThat(OutcomeMath.evaluate(days("C", 3, 1, 1, 1), days("C", 3, 1, 1, 1)).confidence()).isNotEqualTo(Confidence.NONE);
    }

    @Test
    @DisplayName("[AC-47] fewer than 10 sales across both windows is never judged on sales, however different the numbers look")
    void thinSalesAreUnknown() {
        // 7 days x 0.5 + 4 days x 1.5 = 9.5 sales: a halving of cost per sale that nobody should believe
        Measurement m = OutcomeMath.evaluate(days("C", 7, 20_000_000, 10, 0.5), days("C", 4, 20_000_000, 10, 1.5));
        assertThat(m.confidence()).isEqualTo(Confidence.LOW);
        assertThat(m.suggestedVerdict()).isEqualTo("UNKNOWN");
        assertThat(m.note()).contains("cannot show whether this worked");
        assertThat(m.conversionsPerDayChangePct()).as("the movement is still reported").isEqualTo(200.0);
    }

    @Test
    @DisplayName("[AC-47] with enough sales, cost per sale 20% lower is BETTER, 25% higher is WORSE, in between is NEUTRAL")
    void verdictBoundaries() {
        // before: 7 days, 14 sales for 140 PLN -> 10 PLN a sale
        List<Snapshot> before = days("C", 7, 20_000_000, 10, 2);
        // after, 4 days at 20 PLN: sales per day decide cost per sale
        assertThat(OutcomeMath.evaluate(before, days("C", 4, 20_000_000, 10, 2.5)).suggestedVerdict()).as("8 PLN a sale = x0.80").isEqualTo("BETTER");
        assertThat(OutcomeMath.evaluate(before, days("C", 4, 20_000_000, 10, 2.4)).suggestedVerdict()).as("x0.83").isEqualTo("NEUTRAL");
        assertThat(OutcomeMath.evaluate(before, days("C", 4, 20_000_000, 10, 1.6)).suggestedVerdict()).as("x1.25").isEqualTo("WORSE");
        assertThat(OutcomeMath.evaluate(before, days("C", 4, 20_000_000, 10, 1.7)).suggestedVerdict()).as("x1.18").isEqualTo("NEUTRAL");
        Measurement m = OutcomeMath.evaluate(before, days("C", 4, 20_000_000, 10, 2.5));
        assertThat(m.confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(m.costPerConversionBeforeMicros()).isEqualTo(10_000_000L);
        assertThat(m.costPerConversionAfterMicros()).isEqualTo(8_000_000L);
    }

    @Test
    @DisplayName("[AC-47] 30 or more sales is HIGH confidence; no sales afterwards is WORSE only when it already cost two sales' worth")
    void highConfidenceAndNoSalesAfter() {
        assertThat(OutcomeMath.evaluate(days("C", 7, 20_000_000, 10, 3), days("C", 4, 20_000_000, 10, 3)).confidence()).isEqualTo(Confidence.HIGH);

        List<Snapshot> before = days("C", 7, 20_000_000, 10, 2); // 10 PLN a sale
        assertThat(OutcomeMath.evaluate(before, days("C", 4, 4_000_000, 2, 0)).suggestedVerdict()).as("spent 16 PLN, under two sales' worth").isEqualTo("UNKNOWN");
        assertThat(OutcomeMath.evaluate(before, days("C", 4, 5_000_000, 2, 0)).suggestedVerdict()).as("spent 20 PLN = two sales' worth").isEqualTo("WORSE");
        // nothing to compare with before
        assertThat(OutcomeMath.evaluate(days("C", 7, 20_000_000, 10, 0), days("C", 4, 20_000_000, 10, 12)).suggestedVerdict()).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("[AC-47] per-day changes are reported in percent, and absent when there was nothing before")
    void percentChanges() {
        Measurement m = OutcomeMath.evaluate(days("C", 7, 20_000_000, 10, 2), days("C", 4, 10_000_000, 12, 1));
        assertThat(m.costPerDayChangePct()).isEqualTo(-50.0);
        assertThat(m.clicksPerDayChangePct()).isEqualTo(20.0);
        assertThat(m.conversionsPerDayChangePct()).isEqualTo(-50.0);
        Measurement none = OutcomeMath.evaluate(days("C", 7, 0, 0, 0), days("C", 4, 10_000_000, 12, 1));
        assertThat(none.costPerDayChangePct()).isNull();
        assertThat(none.clicksPerDayChangePct()).isNull();
    }
}

package app.autopilot.watchdog;

import app.autopilot.ads.AdsGateway;
import app.autopilot.ads.AdsGateway.Level;
import app.autopilot.ads.AdsGateway.PerformanceRow;
import app.autopilot.domain.Limits;
import app.autopilot.store.MetricStore;
import app.autopilot.store.MetricStore.Snapshot;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Keeps one daily account-wide metric row per day for the last two weeks, for baselines and the conversion-drop
 * check, plus one row per campaign from the same read (no extra Google Ads operations) so the effect of a change can be
 * measured later. A recent day is re-read until its conversion data has matured.
 */
@Component
public class SnapshotService {

    public static final String ACCOUNT = "ACCOUNT";
    static final int DAYS = 14;

    private final AdsGateway ads;
    private final MetricStore metrics;
    private final Clock clock;

    public SnapshotService(AdsGateway ads, MetricStore metrics, Clock clock) {
        this.ads = ads;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Returns the number of days read from Google. */
    public int refresh(Limits limits) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, limits.zone());
        int read = 0;
        for (int back = 1; back <= DAYS; back++) {
            LocalDate day = today.minusDays(back);
            List<Snapshot> existing = metrics.snapshots(ACCOUNT, "DAILY", day, day);
            if (!existing.isEmpty() && matured(existing.get(0), day, limits, now)) continue;
            if (!existing.isEmpty() && Duration.between(existing.get(0).takenAt(), now).compareTo(Duration.ofHours(12)) < 0) continue;
            List<PerformanceRow> rows = ads.performance(Level.CAMPAIGN, day, day, 1000);
            metrics.upsertSnapshot(new Snapshot(day, ACCOUNT, "DAILY", rows.stream().mapToLong(PerformanceRow::costMicros).sum(),
                    rows.stream().mapToLong(PerformanceRow::clicks).sum(), rows.stream().mapToLong(PerformanceRow::impressions).sum(),
                    rows.stream().mapToDouble(PerformanceRow::conversions).sum(),
                    rows.stream().mapToDouble(PerformanceRow::conversionValue).sum(), now), now);
            for (PerformanceRow r : rows) {
                metrics.upsertSnapshot(new Snapshot(day, "CAMPAIGN:" + r.id(), "DAILY", r.costMicros(), r.clicks(), r.impressions(),
                        r.conversions(), r.conversionValue(), now), now);
            }
            read++;
        }
        return read;
    }

    private boolean matured(Snapshot s, LocalDate day, Limits limits, Instant now) {
        Instant matureAt = day.plusDays(limits.conversionLagDays() + 1L).atStartOfDay(limits.zone()).toInstant();
        return s.takenAt() != null && s.takenAt().isAfter(matureAt);
    }
}

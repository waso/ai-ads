package app.autopilot.watchdog;

import app.autopilot.config.AutopilotProperties;
import app.autopilot.domain.Limits;
import app.autopilot.notify.AlertService;
import app.autopilot.notify.Notification.Severity;
import app.autopilot.store.MetricStore;
import app.autopilot.store.MetricStore.Snapshot;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Broken conversion tracking makes every performance decision wrong, so a sharp drop raises an alert and the
 * guard holds changes that rely on conversion data while it is open. Compares the last full week that has
 * matured (excluding the latest conversion-lag days) with the week before.
 */
@Component
public class ConversionMonitor {

    static final String ALERT = "CONVERSION_DROP";
    /** Below this many conversions in the earlier week the comparison is noise, so no alert. */
    static final double MIN_BASELINE = 10;

    private final MetricStore metrics;
    private final AlertService alerts;
    private final AutopilotProperties props;
    private final Clock clock;

    public ConversionMonitor(MetricStore metrics, AlertService alerts, AutopilotProperties props, Clock clock) {
        this.metrics = metrics;
        this.alerts = alerts;
        this.props = props;
        this.clock = clock;
    }

    /** True when an alert was raised or is still open. */
    public boolean check(Limits limits) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), limits.zone());
        LocalDate endA = today.minusDays(limits.conversionLagDays());
        LocalDate startA = endA.minusDays(6);
        LocalDate endB = startA.minusDays(1);
        LocalDate startB = endB.minusDays(6);
        List<Snapshot> a = metrics.snapshots(SnapshotService.ACCOUNT, "DAILY", startA, endA);
        List<Snapshot> b = metrics.snapshots(SnapshotService.ACCOUNT, "DAILY", startB, endB);
        if (a.size() < 7 || b.size() < 7) return false; // not enough history yet
        double convA = a.stream().mapToDouble(Snapshot::conversions).sum();
        double convB = b.stream().mapToDouble(Snapshot::conversions).sum();
        int dropPct = props.watchdog().conversionDropPct();
        if (convB >= MIN_BASELINE && convA < convB * (100 - dropPct) / 100.0) {
            long pct = Math.round((convB - convA) * 100 / convB);
            alerts.raise(ALERT, Severity.CRITICAL, "", "Conversions fell " + pct + "% (" + Math.round(convA) + " in " + startA + ".." + endA
                    + " against " + Math.round(convB) + " the week before). Tracking may be broken; changes that rely on conversion data are on hold.",
                    Map.of("recent", convA, "previous", convB, "dropPct", pct));
            return true;
        }
        alerts.resolve(ALERT, "");
        return false;
    }
}

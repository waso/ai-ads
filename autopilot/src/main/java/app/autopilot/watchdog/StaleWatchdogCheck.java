package app.autopilot.watchdog;

import app.autopilot.config.AutopilotProperties;
import app.autopilot.notify.AlertService;
import app.autopilot.notify.Notification.Severity;
import app.autopilot.store.MetricStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * The server's own check that the watchdog is still running: it alerts when no heartbeat arrived for two missed
 * runs plus slack. The external dead-man's switch covers the case where this server is down too.
 */
@Component
@ConditionalOnProperty(name = "autopilot.mode", havingValue = "server", matchIfMissing = true)
public class StaleWatchdogCheck {

    private final MetricStore metrics;
    private final AlertService alerts;
    private final AutopilotProperties props;
    private final Clock clock;
    private final Instant startedAt;

    public StaleWatchdogCheck(MetricStore metrics, AlertService alerts, AutopilotProperties props, Clock clock) {
        this.metrics = metrics;
        this.alerts = alerts;
        this.props = props;
        this.clock = clock;
        this.startedAt = clock.instant();
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT5M")
    public void scheduled() {
        try {
            check();
        } catch (RuntimeException e) {
            // the database being unavailable is reported elsewhere; this check simply retries
        }
    }

    /** True when the heartbeat is stale (or never arrived within the grace period). */
    public boolean check() {
        Instant now = clock.instant();
        Duration limit = Duration.ofMinutes(props.watchdog().staleAfterMinutes());
        Optional<Instant> last = metrics.lastBeat(Heartbeat.SOURCE);
        Instant reference = last.orElse(startedAt);
        boolean stale = Duration.between(reference, now).compareTo(limit) > 0;
        if (stale) {
            alerts.raise("WATCHDOG_STALE", Severity.CRITICAL, "", last.isPresent()
                    ? "No watchdog heartbeat since " + last.get() + " (more than " + limit.toMinutes() + " minutes). Spend is not being watched."
                    : "The watchdog has never reported since this server started. Spend is not being watched.", Map.of());
        } else {
            alerts.resolve("WATCHDOG_STALE", "");
        }
        return stale;
    }
}

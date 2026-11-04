package app.autopilot.watchdog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * The watchdog on a timer inside the server, for running everything on one machine without a CronJob. In a deployment the
 * watchdog stays a separate one-shot job so it keeps working when the server does not.
 */
@Component
@ConditionalOnProperty(name = "autopilot.watchdog.in-process", havingValue = "true")
class InProcessWatchdog {

    private static final Logger log = LoggerFactory.getLogger(InProcessWatchdog.class);

    private final Watchdog watchdog;

    InProcessWatchdog(Watchdog watchdog) {
        this.watchdog = watchdog;
    }

    @Scheduled(fixedDelayString = "${autopilot.watchdog.interval-minutes:15}", initialDelayString = "1", timeUnit = TimeUnit.MINUTES)
    void tick() {
        try {
            Watchdog.Report report = watchdog.runOnce();
            if (report.breakerTripped() || !report.healthy()) {
                log.warn("Watchdog run: breakerTripped={} failedSteps={}", report.breakerTripped(), report.failedSteps());
            } else {
                log.debug("Watchdog run: healthy");
            }
        } catch (RuntimeException e) {
            log.error("Watchdog run failed", e);
        }
    }
}

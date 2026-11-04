package app.autopilot.exec;

import app.autopilot.config.AutopilotProperties;
import app.autopilot.config.RuntimeConfig;
import app.autopilot.domain.Limits;
import app.autopilot.domain.Loop;
import app.autopilot.notify.AlertService;
import app.autopilot.notify.Notification.Severity;
import app.autopilot.store.RunStore;
import app.autopilot.store.RunStore.Run;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A Claude run is bound to one loop when it starts, and the loop decides what it may do. A scheduled loop
 * gets one run slot per window. A chat session that claims a scheduled loop can therefore not borrow its
 * write rights silently: it must be inside the loop's window, it uses up the slot, and the real run then
 * raises an alert.
 *
 * <p>This narrows the gap but does not close it: all runs share one connector identity. The Agent SDK
 * worker (see PRD) with its own credentials is what closes it.
 */
@Service
public class RunService {

    /** The run could not start. */
    public static class RunRefused extends RuntimeException {
        private final String rule;
        public RunRefused(String rule, String message) {
            super(message);
            this.rule = rule;
        }
        public String rule() { return rule; }
    }

    private final RunStore runs;
    private final RuntimeConfig config;
    private final AutopilotProperties props;
    private final AlertService alerts;
    private final Clock clock;

    public RunService(RunStore runs, RuntimeConfig config, AutopilotProperties props, AlertService alerts, Clock clock) {
        this.runs = runs;
        this.config = config;
        this.props = props;
        this.alerts = alerts;
        this.clock = clock;
    }

    public Run start(Loop loop, String promptVersion) {
        Limits limits = config.limits();
        Instant now = clock.instant();
        ZonedDateTime local = now.atZone(limits.zone());
        AutopilotProperties.Runs w = props.runs();
        String slot = null;
        switch (loop) {
            case TRIAGE -> slot = "TRIAGE@" + local.toLocalDate() + "T" + local.getHour();
            case DAILY -> {
                if (!w.anyTime()) requireWindow(loop, local.toLocalTime(), w.dailyFrom(), w.dailyTo(), limits.zone());
                slot = "DAILY@" + local.toLocalDate();
            }
            case WEEKLY -> {
                if (!w.anyTime() && local.getDayOfWeek() != w.weeklyDay()) {
                    throw new RunRefused("outside_window", "The weekly loop runs on " + w.weeklyDay() + "; today is "
                            + local.getDayOfWeek() + " (" + limits.zone() + ").");
                }
                if (!w.anyTime()) requireWindow(loop, local.toLocalTime(), w.weeklyFrom(), w.weeklyTo(), limits.zone());
                slot = "WEEKLY@" + local.get(IsoFields.WEEK_BASED_YEAR) + "-W" + local.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            }
            case CHAT -> slot = null;
        }
        Run run = new Run(UUID.randomUUID(), loop, promptVersion == null ? "unversioned" : promptVersion, slot, now,
                now.plus(Duration.ofMinutes(w.ttlMinutes())), null, null, null, null);
        if (!runs.insert(run)) {
            alerts.raise("RUN_SLOT_REUSED", Severity.WARNING, slot,
                    "A second " + loop + " run tried to start in the same window (" + slot
                            + "). Either a retry, or another session claiming this loop.", Map.of("loop", loop.name()));
            throw new RunRefused("slot_used", "The " + loop + " loop already started a run in this window (" + slot
                    + "). Each scheduled loop gets one run per window.");
        }
        return run;
    }

    private void requireWindow(Loop loop, LocalTime now, LocalTime from, LocalTime to, ZoneId zone) {
        if (now.isBefore(from) || now.isAfter(to)) {
            throw new RunRefused("outside_window", "The " + loop + " loop runs between " + from + " and " + to
                    + " (" + zone + "); it is " + now.withNano(0) + " now.");
        }
    }

    public Optional<Run> find(String runId) {
        try {
            return runs.find(UUID.fromString(runId));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public boolean end(UUID id, String checked, String did, String nextFocus) {
        return runs.end(id, clock.instant(), checked, did, nextFocus);
    }
}

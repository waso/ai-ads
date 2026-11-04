package app.autopilot.notify;

import app.autopilot.notify.Notification.Severity;
import app.autopilot.store.AlertStore;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Map;

/** Opens alerts (one per type and entity) and tells the owner the first time each one opens. */
@Service
public class AlertService {

    private final AlertStore alerts;
    private final Notifier notifier;
    private final Clock clock;

    public AlertService(AlertStore alerts, Notifier notifier, Clock clock) {
        this.alerts = alerts;
        this.notifier = notifier;
        this.clock = clock;
    }

    /** Returns true when a new alert was opened (and the owner notified). */
    public boolean raise(String type, Severity severity, String entityKey, String message, Map<String, Object> details) {
        boolean created;
        try {
            created = alerts.open(type, severity.name(), entityKey, message, details, clock.instant());
        } catch (RuntimeException e) {
            // The database is unavailable, so the alert cannot be stored or deduplicated. The owner is told anyway.
            created = true;
        }
        if (created) {
            notifier.send(Notification.of(severity, type, message));
        }
        return created;
    }

    public void resolve(String type, String entityKey) {
        try {
            alerts.resolve(type, entityKey, clock.instant());
        } catch (RuntimeException e) {
            // nothing to resolve while the database is unavailable
        }
    }
}

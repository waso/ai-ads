package app.autopilot.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Sends to every configured channel. One failing channel never blocks the others, and a failure is logged
 * rather than thrown so an alert can never break the watchdog.
 */
@Component
public class Notifier {

    private static final Logger log = LoggerFactory.getLogger(Notifier.class);

    private final List<NotificationChannel> channels;

    /** Spring: zero channels is valid (nothing configured yet); the message is still logged. */
    @Autowired
    public Notifier(ObjectProvider<NotificationChannel> channels) {
        this.channels = channels.orderedStream().toList();
    }

    public Notifier(List<NotificationChannel> channels) {
        this.channels = List.copyOf(channels);
    }

    /** Returns the number of channels that accepted the message. */
    public int send(Notification n) {
        log.info("NOTIFY [{}] {}: {}", n.severity(), n.title(), n.body());
        int delivered = 0;
        for (NotificationChannel c : channels) {
            try {
                c.send(n);
                delivered++;
            } catch (Exception e) {
                log.error("Notification channel {} failed: {}", c.name(), e.toString());
            }
        }
        if (delivered == 0 && !channels.isEmpty()) {
            log.error("No notification channel accepted: {}", n.title());
        }
        return delivered;
    }
}

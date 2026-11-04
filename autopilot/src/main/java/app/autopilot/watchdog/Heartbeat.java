package app.autopilot.watchdog;

import app.autopilot.config.AutopilotProperties;
import app.autopilot.store.MetricStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/**
 * Proof of life after every watchdog run: a row in the database for the server's own check, and a ping to the
 * external dead-man's switch, which alerts when the pings stop. The external switch is what catches the case
 * where this whole deployment is down.
 */
@Component
public class Heartbeat {

    public static final String SOURCE = "watchdog";

    private static final Logger log = LoggerFactory.getLogger(Heartbeat.class);

    private final MetricStore metrics;
    private final AutopilotProperties props;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public Heartbeat(MetricStore metrics, AutopilotProperties props, Clock clock) {
        this.metrics = metrics;
        this.props = props;
        this.clock = clock;
    }

    /** Returns true when the ping reached the dead-man's switch (or none is configured). */
    public boolean beat(Map<String, Object> details) {
        try {
            metrics.beat(SOURCE, clock.instant(), details);
        } catch (RuntimeException e) {
            log.warn("Heartbeat row not written: {}", e.toString());
        }
        String url = props.watchdog().heartbeatUrl();
        if (url == null || url.isBlank()) return true;
        try {
            HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return r.statusCode() / 100 == 2;
        } catch (Exception e) {
            log.warn("Dead-man's switch ping failed: {}", e.toString());
            return false;
        }
    }
}

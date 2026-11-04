package app.autopilot.mcp;

import app.autopilot.ads.QuotaMeter;
import app.autopilot.config.RuntimeConfig;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * At the configured share of the Google Ads quota (80% by default) Claude's reads pause. The watchdog does not
 * pass through here, so it keeps running and the breaker keeps working.
 */
@Component
public class ReadGate {

    private final QuotaMeter quota;
    private final RuntimeConfig config;

    public ReadGate(QuotaMeter quota, RuntimeConfig config) {
        this.quota = quota;
        this.config = config;
    }

    public Optional<Envelope> check() {
        boolean flagged = false;
        try {
            flagged = config.control().readsPaused();
        } catch (RuntimeException e) {
            // the database being down does not stop reads that do not need it
        }
        if (quota.readsShouldPause() || flagged) {
            return Optional.of(Envelope.refused("reads_paused", "Reads are paused: " + quota.percentUsed()
                    + "% of the daily Google Ads operations are used. The watchdog keeps running; reads resume as usage "
                    + "drops out of the 24-hour window."));
        }
        return Optional.empty();
    }
}

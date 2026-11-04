package app.autopilot.approval;

import app.autopilot.config.AutopilotProperties;
import app.autopilot.exec.Audit;
import app.autopilot.store.ConfigStore;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;

/**
 * Owner-only controls: the kill switch, clearing the circuit breaker and resuming Claude's reads. They are
 * reachable only from the signed-in approval and admin pages, never through MCP.
 */
@Service
public class AdminService {

    private final ConfigStore config;
    private final Audit audit;
    private final AutopilotProperties props;
    private final Clock clock;

    public AdminService(ConfigStore config, Audit audit, AutopilotProperties props, Clock clock) {
        this.config = config;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
    }

    public void killSwitch(boolean on, String reason, String owner) {
        requireOwner(owner);
        config.setKillSwitch(on, reason, owner);
        audit.recordOrLog(Audit.event(on ? "KILL_SWITCH_ON" : "KILL_SWITCH_OFF", clock.instant()).approver(owner)
                .message(on ? reason : "cleared by the owner").build());
    }

    /** Returns the campaigns the breaker had paused, for the owner to review before resuming them. */
    public List<String> clearBreaker(String owner) {
        requireOwner(owner);
        List<String> paused = config.clearBreaker(owner);
        audit.recordOrLog(Audit.event("BREAKER_CLEARED", clock.instant()).approver(owner)
                .message("breaker cleared; it had paused " + paused).build());
        return paused;
    }

    public void resumeReads(String owner) {
        requireOwner(owner);
        config.setReadsPaused(false);
        audit.recordOrLog(Audit.event("READS_RESUMED", clock.instant()).approver(owner).build());
    }

    private void requireOwner(String email) {
        if (email == null || email.isBlank() || props.ownerEmail() == null || props.ownerEmail().isBlank()
                || !props.ownerEmail().equalsIgnoreCase(email)) {
            throw new SecurityException("only the owner can do this");
        }
    }
}

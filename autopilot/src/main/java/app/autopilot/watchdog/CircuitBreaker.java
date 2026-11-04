package app.autopilot.watchdog;

import app.autopilot.domain.ChangeMeta;
import app.autopilot.domain.ChangeRequest.EmergencyPauseCampaign;
import app.autopilot.domain.Diff;
import app.autopilot.domain.EntityRef;
import app.autopilot.domain.Plan;
import app.autopilot.domain.Tier;
import app.autopilot.exec.Audit;
import app.autopilot.exec.ChangeExecutor;
import app.autopilot.store.ConfigStore;
import app.autopilot.watchdog.SpendMonitor.CampaignSpend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pauses agent-managed campaigns and blocks writes. It does not ask the guard: it only ever pauses, only
 * campaigns Claude manages, and it must work when Claude, the guard's rules or the database are not. If the
 * audit log cannot be written the pause still happens and the record goes to the log.
 */
@Component
public class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    public record Outcome(List<String> paused, List<String> failed) {}

    private final ChangeExecutor executor;
    private final ConfigStore config;
    private final Audit audit;
    private final Clock clock;

    public CircuitBreaker(ChangeExecutor executor, ConfigStore config, Audit audit, Clock clock) {
        this.executor = executor;
        this.config = config;
        this.audit = audit;
        this.clock = clock;
    }

    /** @param managedEnabled the enabled campaigns Claude manages; unmanaged campaigns are never touched */
    public Outcome trip(String reason, List<CampaignSpend> managedEnabled) {
        Instant now = clock.instant();
        List<String> paused = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (CampaignSpend c : managedEnabled) {
            Plan plan = new Plan(new EmergencyPauseCampaign(EntityRef.campaign(c.id())),
                    new ChangeMeta("Circuit breaker: " + reason, "Stop spend on agent-managed campaigns", "watchdog",
                            "breaker-" + now.getEpochSecond() + "-" + c.id()),
                    Tier.T1, new Diff(Map.of("status", "ENABLED"), Map.of("status", "PAUSED"), "Circuit breaker pause of campaign " + c.id()),
                    "breaker", now);
            try {
                ChangeExecutor.Result r = executor.execute(plan,
                        new ChangeExecutor.Actor("watchdog", null, null, null, "WATCHDOG", "breaker-" + now.getEpochSecond() + "-" + c.id()), false);
                (r.applied() ? paused : failed).add(c.id());
            } catch (RuntimeException e) {
                log.error("Breaker could not pause campaign {}: {}", c.id(), e.toString());
                failed.add(c.id());
            }
        }
        try {
            config.openBreaker(paused, now, "watchdog");
        } catch (RuntimeException e) {
            log.error("Breaker paused {} but could not record the open breaker (database unavailable?): {}", paused, e.toString());
        }
        audit.recordOrLog(Audit.event("BREAKER", now).tool("circuit_breaker").message(reason + "; paused " + paused
                + (failed.isEmpty() ? "" : "; could not pause " + failed)).build());
        return new Outcome(paused, failed);
    }
}

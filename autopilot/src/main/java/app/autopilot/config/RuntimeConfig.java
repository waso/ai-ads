package app.autopilot.config;

import app.autopilot.domain.ControlState;
import app.autopilot.domain.Goals;
import app.autopilot.domain.Limits;
import app.autopilot.store.ConfigStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads limits, goals and control state. The guard reads them straight from the database on every call, so
 * a database outage refuses writes. The watchdog's breaker reads the cached copy instead, so it can still
 * pause campaigns when the database is down.
 */
@Component
public class RuntimeConfig {

    private static final Logger log = LoggerFactory.getLogger(RuntimeConfig.class);

    private final ConfigStore store;
    private final Limits bootLimits;
    private final Goals bootGoals;
    private final AutopilotProperties props;

    private volatile Limits cachedLimits;
    private volatile Goals cachedGoals;
    private volatile ControlState cachedControl;

    public RuntimeConfig(ConfigStore store, Limits bootLimits, Goals bootGoals, AutopilotProperties props) {
        this.store = store;
        this.bootLimits = bootLimits;
        this.bootGoals = bootGoals;
        this.props = props;
        this.cachedLimits = bootLimits;
        this.cachedGoals = bootGoals;
    }

    /** At startup: writes a new version when server configuration differs from the latest stored one. */
    public void syncVersions() {
        if (store.latestLimits().filter(bootLimits::equals).isEmpty()) {
            store.saveLimits(bootLimits);
            log.info("Stored new limits version {}", store.limitsVersion());
        }
        if (store.latestGoals().filter(bootGoals::equals).isEmpty()) {
            store.saveGoals(bootGoals);
        }
    }

    /** From the database. Throws when it is unavailable. */
    public Limits limits() {
        Limits l = store.latestLimits().orElse(bootLimits);
        cachedLimits = l;
        return l;
    }

    public Goals goals() {
        Goals g = store.latestGoals().orElse(bootGoals);
        cachedGoals = g;
        return g;
    }

    /** From the database, with the server-configuration kill switch merged in. */
    public ControlState control() {
        ControlState s = withConfigKill(store.control());
        cachedControl = s;
        return s;
    }

    public Limits limitsOrCached() {
        try {
            return limits();
        } catch (RuntimeException e) {
            log.warn("Database unavailable, using cached limits: {}", e.toString());
            return cachedLimits;
        }
    }

    public Goals goalsOrCached() {
        try {
            return goals();
        } catch (RuntimeException e) {
            return cachedGoals;
        }
    }

    public ControlState controlOrCached() {
        try {
            return control();
        } catch (RuntimeException e) {
            ControlState c = cachedControl == null ? ControlState.clear() : cachedControl;
            return withConfigKill(c);
        }
    }

    /** The kill switch from server configuration wins even when the database cannot be read. */
    public boolean configKillSwitch() {
        return props.killSwitch();
    }

    private ControlState withConfigKill(ControlState s) {
        if (!props.killSwitch() || s.killSwitch()) return s;
        return new ControlState(true, "kill switch set in server configuration", s.breakerOpen(), s.breakerSince(),
                s.breakerPausedCampaigns(), s.readsPaused());
    }
}

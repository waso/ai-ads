package app.autopilot.ads;

import app.autopilot.config.RuntimeConfig;
import app.autopilot.domain.Limits;
import app.autopilot.store.MetricStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts Google Ads operations over a sliding 24 hours. Requests rejected by Google still count, which is
 * why the guard refuses bad calls before they reach Google. At the configured share of the daily allowance
 * the server pauses Claude's reads and keeps the watchdog running.
 */
@Component
public class QuotaMeter {

    private static final Logger log = LoggerFactory.getLogger(QuotaMeter.class);

    private final MetricStore store;
    private final RuntimeConfig config;
    private final Clock clock;
    /** Operations that could not be written to the database (it was down), by hour, until they can be. */
    private final Map<Long, AtomicLong> unpersisted = new ConcurrentHashMap<>();
    private volatile long lastKnownDatabaseCount;

    public QuotaMeter(MetricStore store, RuntimeConfig config, Clock clock) {
        this.store = store;
        this.config = config;
        this.clock = clock;
    }

    public void count(long operations) {
        Instant now = clock.instant();
        try {
            flushUnpersisted();
            store.addOps(now, operations);
        } catch (RuntimeException e) {
            log.warn("Could not persist quota usage ({}); keeping it in memory until the database is back", e.toString());
            unpersisted.computeIfAbsent(now.truncatedTo(ChronoUnit.HOURS).getEpochSecond(), k -> new AtomicLong()).addAndGet(operations);
        }
    }

    private void flushUnpersisted() {
        for (Map.Entry<Long, AtomicLong> e : unpersisted.entrySet()) {
            long n = e.getValue().get();
            if (n > 0) {
                store.addOps(Instant.ofEpochSecond(e.getKey()), n);
                e.getValue().addAndGet(-n);
            }
        }
        unpersisted.values().removeIf(v -> v.get() == 0);
    }

    /** Operations in the last 24 hours: what the database knows plus what it has not been told yet. */
    public long used() {
        Instant now = clock.instant();
        try {
            lastKnownDatabaseCount = store.opsLast24h(now);
        } catch (RuntimeException e) {
            // the database is down: the last count it gave, plus what happened since
        }
        return lastKnownDatabaseCount + unpersistedTotal(now);
    }

    private long unpersistedTotal(Instant now) {
        long from = now.minus(24, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS).getEpochSecond();
        unpersisted.keySet().removeIf(k -> k < from);
        return unpersisted.values().stream().mapToLong(AtomicLong::get).sum();
    }

    public int percentUsed() {
        Limits l = config.limitsOrCached();
        return (int) (used() * 100 / Math.max(1, l.quotaDailyOps()));
    }

    /** True from the configured share of the daily allowance (80% by default). */
    public boolean readsShouldPause() {
        return percentUsed() >= config.limitsOrCached().quotaPauseReadsPct();
    }
}

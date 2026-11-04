package app.autopilot.testkit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

public final class TestClock extends Clock {
    private volatile Instant now;

    public TestClock(Instant start) {
        this.now = start;
    }

    public void advance(Duration d) { now = now.plus(d); }
    public void set(Instant i) { now = i; }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
}

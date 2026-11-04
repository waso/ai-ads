package app.autopilot.exec;

import java.util.function.Supplier;

/**
 * Only one write runs at a time per account, so two concurrent changes cannot both pass the cooldown and
 * volume checks. The lock is held from the guard's check to the recorded result.
 */
public interface WriteLock {
    <T> T withLock(Supplier<T> body);
}

package app.autopilot.exec;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.Supplier;

/** A PostgreSQL session-level advisory lock on a dedicated connection; it also protects multiple replicas. */
@Component
public class PgWriteLock implements WriteLock {

    private static final long KEY = 7_141_001L;
    private static final long WAIT_MILLIS = 30_000;

    private final DataSource dataSource;

    public PgWriteLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public <T> T withLock(Supplier<T> body) {
        try (Connection c = dataSource.getConnection()) {
            acquire(c);
            try {
                return body.get();
            } finally {
                release(c);
            }
        } catch (SQLException e) {
            throw new DataAccessResourceFailureException("Could not take the write lock: " + e.getMessage(), e);
        }
    }

    private void acquire(Connection c) throws SQLException {
        long deadline = System.currentTimeMillis() + WAIT_MILLIS;
        while (true) {
            try (PreparedStatement ps = c.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                ps.setLong(1, KEY);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getBoolean(1)) return;
                }
            }
            if (System.currentTimeMillis() > deadline) {
                throw new SQLException("another write has held the lock for more than " + WAIT_MILLIS / 1000 + " s");
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("interrupted while waiting for the write lock", e);
            }
        }
    }

    private void release(Connection c) {
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            ps.setLong(1, KEY);
            ps.execute();
        } catch (SQLException e) {
            // A broken connection is discarded by the pool and takes its session lock with it.
            try {
                c.close();
            } catch (SQLException ignored) {
                // nothing more to do
            }
        }
    }
}

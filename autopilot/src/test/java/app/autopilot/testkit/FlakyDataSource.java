package app.autopilot.testkit;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/** A data source that can be switched "down" to simulate a database outage. */
public final class FlakyDataSource implements DataSource {
    private final DataSource delegate;
    private volatile boolean down;

    public FlakyDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    public void down(boolean down) { this.down = down; }

    @Override public Connection getConnection() throws SQLException {
        if (down) throw new SQLException("connection refused (injected outage)", "08001");
        return delegate.getConnection();
    }
    @Override public Connection getConnection(String u, String p) throws SQLException { return getConnection(); }
    @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter out) throws SQLException { delegate.setLogWriter(out); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { return delegate.unwrap(iface); }
    @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return delegate.isWrapperFor(iface); }
}

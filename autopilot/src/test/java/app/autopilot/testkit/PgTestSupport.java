package app.autopilot.testkit;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;

import java.io.IOException;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A real PostgreSQL (embedded, no Docker) with the real Flyway migrations and the restricted application role,
 * so tests prove things the database itself enforces, such as append-only tables.
 */
public final class PgTestSupport {

    public static final String APP_ROLE = "autopilot_app";
    private static final String APP_PASSWORD = "app-test-password";
    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static EmbeddedPostgres pg;

    private PgTestSupport() {}

    private static synchronized EmbeddedPostgres cluster() {
        if (pg == null) {
            try {
                pg = EmbeddedPostgres.builder().setServerConfig("max_connections", "500").start();
                try (Connection c = pg.getPostgresDatabase().getConnection(); Statement s = c.createStatement()) {
                    s.execute("CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD '" + APP_PASSWORD + "'");
                }
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        pg.close();
                    } catch (IOException ignored) {
                        // shutting down
                    }
                }));
            } catch (Exception e) {
                throw new IllegalStateException("could not start embedded PostgreSQL", e);
            }
        }
        return pg;
    }

    /** A freshly migrated database. {@code app} is the restricted role the application uses. */
    public record TestDb(String name, String jdbcUrl, HikariDataSource owner, HikariDataSource app) implements AutoCloseable {
        /** Closes both pools. Without this a full test run exhausts the server's connections. */
        @Override
        public void close() {
            app.close();
            owner.close();
        }

        public Map<String, String> springProperties() {
            return Map.of("spring.datasource.url", jdbcUrl, "spring.datasource.username", APP_ROLE,
                    "spring.datasource.password", APP_PASSWORD,
                    "spring.flyway.url", jdbcUrl, "spring.flyway.user", "postgres", "spring.flyway.password", "postgres",
                    "spring.flyway.placeholders.app_role", APP_ROLE);
        }
    }

    public static TestDb newDatabase() {
        EmbeddedPostgres cluster = cluster();
        String name = "t" + COUNTER.incrementAndGet() + "_" + Long.toHexString(System.nanoTime());
        try (Connection c = cluster.getPostgresDatabase().getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + name);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        String url = "jdbc:postgresql://localhost:" + cluster.getPort() + "/" + name;
        HikariConfig ownerCfg = new HikariConfig();
        ownerCfg.setJdbcUrl(url);
        ownerCfg.setUsername("postgres");
        ownerCfg.setPassword("postgres");
        ownerCfg.setMaximumPoolSize(2);
        ownerCfg.setMinimumIdle(0);
        HikariDataSource owner = new HikariDataSource(ownerCfg);
        Flyway.configure().dataSource(owner).locations("classpath:db/migration")
                .placeholders(Map.of("app_role", APP_ROLE)).load().migrate();

        HikariConfig appCfg = new HikariConfig();
        appCfg.setJdbcUrl(url);
        appCfg.setUsername(APP_ROLE);
        appCfg.setPassword(APP_PASSWORD);
        appCfg.setMaximumPoolSize(8);
        appCfg.setMinimumIdle(0);
        return new TestDb(name, url, owner, new HikariDataSource(appCfg));
    }
}

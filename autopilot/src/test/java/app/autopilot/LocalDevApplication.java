package app.autopilot;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.SpringApplication;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Runs the whole server on a laptop with nothing to install: PostgreSQL comes from the Maven artifacts the tests already use and
 * keeps its data in {@code ~/.ads-autopilot/pg} between runs, the profile is {@code local} (simulated Google Ads account, form
 * login on 127.0.0.1, in-process watchdog). Start it with {@code scripts/dev-start.sh}.
 *
 * <p>Lives in the test sources on purpose: the embedded database is a test-scope dependency and must never ship in the image.
 */
public final class LocalDevApplication {

    private static final String APP_ROLE = "autopilot_app";
    private static final String APP_PASSWORD = "local-app-password";

    private LocalDevApplication() {}

    public static void main(String[] args) throws Exception {
        Path home = Path.of(env("AUTOPILOT_HOME", System.getProperty("user.home") + "/.ads-autopilot"));
        Files.createDirectories(home);
        int port = Integer.parseInt(env("LOCAL_PG_PORT", "54329"));

        String url = "jdbc:postgresql://localhost:" + port + "/autopilot";
        EmbeddedPostgres pg = null;
        if (!reachable(port)) {
            System.out.println("Starting PostgreSQL on 127.0.0.1:" + port + " (data in " + home.resolve("pg") + ")");
            pg = EmbeddedPostgres.builder()
                    .setPort(port)
                    .setDataDirectory(home.resolve("pg"))
                    .setCleanDataDirectory(false)
                    .setServerConfig("max_connections", "60")
                    .start();
            EmbeddedPostgres started = pg;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    started.close();
                } catch (IOException ignored) {
                    // shutting down
                }
            }));
        } else {
            System.out.println("Reusing the PostgreSQL already running on port " + port);
        }
        prepare(port);

        setIfAbsent("DB_URL", url);
        setIfAbsent("DB_USER", APP_ROLE);
        setIfAbsent("DB_PASSWORD", APP_PASSWORD);
        setIfAbsent("FLYWAY_USER", "postgres");
        setIfAbsent("FLYWAY_PASSWORD", "postgres");
        setIfAbsent("spring.profiles.active", "local");
        SpringApplication.run(AutopilotApplication.class, args);
    }

    /** The application database and the restricted role; the migrations then run as the superuser and grant the role its rights. */
    private static void prepare(int port) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://localhost:" + port + "/postgres", "postgres", "postgres");
             Statement s = c.createStatement()) {
            if (!exists(s, "SELECT 1 FROM pg_roles WHERE rolname = '" + APP_ROLE + "'")) {
                s.execute("CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD '" + APP_PASSWORD + "'");
            }
            if (!exists(s, "SELECT 1 FROM pg_database WHERE datname = 'autopilot'")) {
                s.execute("CREATE DATABASE autopilot");
            }
        }
    }

    private static boolean exists(Statement s, String sql) throws SQLException {
        try (ResultSet rs = s.executeQuery(sql)) {
            return rs.next();
        }
    }

    private static boolean reachable(int port) {
        try (Connection ignored = DriverManager.getConnection("jdbc:postgresql://localhost:" + port + "/postgres", "postgres", "postgres")) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getenv(key) == null && System.getProperty(key) == null) System.setProperty(key, value);
    }
}

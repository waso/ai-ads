package app.autopilot.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Watchdog findings. One open alert per (type, entity); repeats refresh it instead of piling up. */
@Repository
public class AlertStore {

    public record Alert(long id, String type, String severity, String entityKey, String message,
                        Map<String, Object> details, Instant openedAt, Instant lastSeenAt,
                        Instant acknowledgedAt, Instant resolvedAt) {}

    private final JdbcClient jdbc;
    private final Json json;

    public AlertStore(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Opens an alert, or refreshes the open one. Returns true when a new alert was opened. */
    public boolean open(String type, String severity, String entityKey, String message,
                        Map<String, Object> details, Instant now) {
        String entity = entityKey == null ? "" : entityKey;
        int refreshed = jdbc.sql("""
                UPDATE alerts SET last_seen_at = :now, message = :m, severity = :s, details = CAST(:d AS jsonb)
                WHERE type = :t AND entity_key = :e AND resolved_at IS NULL""")
                .param("now", Db.ts(now)).param("m", message).param("s", severity)
                .param("d", json.write(details == null ? Map.of() : details)).param("t", type).param("e", entity).update();
        if (refreshed > 0) {
            return false;
        }
        jdbc.sql("""
                INSERT INTO alerts (type, severity, entity_key, message, details, opened_at, last_seen_at)
                VALUES (:t, :s, :e, :m, CAST(:d AS jsonb), :now, :now)""")
                .param("t", type).param("s", severity).param("e", entity).param("m", message)
                .param("d", json.write(details == null ? Map.of() : details)).param("now", Db.ts(now)).update();
        return true;
    }

    public boolean resolve(String type, String entityKey, Instant now) {
        return jdbc.sql("UPDATE alerts SET resolved_at = :now WHERE type = :t AND entity_key = :e AND resolved_at IS NULL")
                .param("now", Db.ts(now)).param("t", type).param("e", entityKey == null ? "" : entityKey).update() > 0;
    }

    public boolean acknowledge(long id, Instant now) {
        return jdbc.sql("UPDATE alerts SET acknowledged_at = :now WHERE id = :id AND acknowledged_at IS NULL")
                .param("now", Db.ts(now)).param("id", id).update() > 0;
    }

    public List<Alert> openAlerts() {
        return jdbc.sql("SELECT * FROM alerts WHERE resolved_at IS NULL ORDER BY opened_at")
                .query((rs, n) -> map(rs)).list();
    }

    public boolean hasOpen(String type) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM alerts WHERE type = :t AND resolved_at IS NULL)")
                .param("t", type).query(Boolean.class).single();
    }

    /**
     * Is the conversion signal unfit to optimize on right now: a sharp drop, or no enabled sale conversion? While true,
     * changes that rely on conversion data wait.
     */
    public boolean conversionSignalSuspect() {
        return hasOpen("CONVERSION_DROP") || hasOpen("CONVERSION_TRACKING_NO_CONVERSION_ACTION")
                || hasOpen("CONVERSION_TRACKING_NO_SALES_CONVERSION");
    }

    public boolean hasOpenWithPrefix(String prefix) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM alerts WHERE type LIKE :p AND resolved_at IS NULL)")
                .param("p", prefix + "%").query(Boolean.class).single();
    }

    public List<Alert> all() {
        return jdbc.sql("SELECT * FROM alerts ORDER BY id").query((rs, n) -> map(rs)).list();
    }

    private Alert map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Alert(rs.getLong("id"), rs.getString("type"), rs.getString("severity"), rs.getString("entity_key"),
                rs.getString("message"), json.readMap(rs.getString("details")), Db.instant(rs, "opened_at"),
                Db.instant(rs, "last_seen_at"), Db.instant(rs, "acknowledged_at"), Db.instant(rs, "resolved_at"));
    }
}

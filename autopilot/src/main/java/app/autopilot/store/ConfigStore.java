package app.autopilot.store;

import app.autopilot.domain.ControlState;
import app.autopilot.domain.Goals;
import app.autopilot.domain.Limits;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/** Limits, goals and stop-mechanism state. Versioned config is insert-only. */
@Repository
public class ConfigStore {

    private final JdbcClient jdbc;
    private final Json json;

    public ConfigStore(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<Limits> latestLimits() {
        return jdbc.sql("SELECT limits FROM config_limits ORDER BY version DESC LIMIT 1")
                .query(String.class).optional().map(j -> json.read(j, Limits.class));
    }

    public void saveLimits(Limits limits) {
        jdbc.sql("INSERT INTO config_limits (limits) VALUES (CAST(:l AS jsonb))")
                .param("l", json.write(limits)).update();
    }

    public long limitsVersion() {
        return jdbc.sql("SELECT coalesce(max(version), 0) FROM config_limits").query(Long.class).single();
    }

    public Optional<Goals> latestGoals() {
        return jdbc.sql("SELECT goals FROM goals ORDER BY version DESC LIMIT 1")
                .query(String.class).optional().map(j -> json.read(j, Goals.class));
    }

    public void saveGoals(Goals goals) {
        jdbc.sql("INSERT INTO goals (goals) VALUES (CAST(:g AS jsonb))")
                .param("g", json.write(goals)).update();
    }

    public ControlState control() {
        return jdbc.sql("""
                SELECT kill_switch, kill_reason, breaker_open, breaker_since, breaker_paused, reads_paused
                FROM control_state WHERE id = 1""")
                .query((rs, n) -> new ControlState(
                        rs.getBoolean("kill_switch"), rs.getString("kill_reason"),
                        rs.getBoolean("breaker_open"), Db.instant(rs, "breaker_since"),
                        stringList(rs.getString("breaker_paused")), rs.getBoolean("reads_paused")))
                .single();
    }

    public void setKillSwitch(boolean on, String reason, String by) {
        jdbc.sql("""
                UPDATE control_state SET kill_switch = :on, kill_reason = :reason,
                       updated_at = now(), updated_by = :by WHERE id = 1""")
                .param("on", on).param("reason", on ? reason : null).param("by", by).update();
    }

    /** Opens the breaker, or adds to the list of campaigns it paused. Returns true when it was closed before. */
    public boolean openBreaker(List<String> paused, Instant now, String by) {
        ControlState current = control();
        LinkedHashSet<String> all = new LinkedHashSet<>(current.breakerPausedCampaigns());
        all.addAll(paused);
        jdbc.sql("""
                UPDATE control_state SET breaker_open = true,
                       breaker_since = coalesce(breaker_since, :now),
                       breaker_paused = CAST(:paused AS jsonb), updated_at = now(), updated_by = :by
                WHERE id = 1""")
                .param("now", Db.ts(now)).param("paused", json.write(new ArrayList<>(all))).param("by", by).update();
        return !current.breakerOpen();
    }

    /** Owner only: clears the breaker. Returns the campaigns it had paused, for review. */
    public List<String> clearBreaker(String by) {
        List<String> paused = control().breakerPausedCampaigns();
        jdbc.sql("""
                UPDATE control_state SET breaker_open = false, breaker_since = NULL,
                       breaker_paused = '[]'::jsonb, updated_at = now(), updated_by = :by WHERE id = 1""")
                .param("by", by).update();
        return paused;
    }

    public void setReadsPaused(boolean paused) {
        jdbc.sql("UPDATE control_state SET reads_paused = :p, updated_at = now() WHERE id = 1")
                .param("p", paused).update();
    }

    private List<String> stringList(String jsonArray) {
        return jsonArray == null ? List.of() : json.read(jsonArray, List.class).stream()
                .map(Object::toString).toList();
    }
}

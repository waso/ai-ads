package app.autopilot.store;

import app.autopilot.domain.Loop;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RunStore {

    public record Run(UUID id, Loop loop, String promptVersion, String slotKey,
                      Instant startedAt, Instant expiresAt, Instant endedAt,
                      String checked, String did, String nextFocus) {
        public boolean activeAt(Instant now) {
            return endedAt == null && now.isBefore(expiresAt);
        }
    }

    private final JdbcClient jdbc;

    public RunStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns false when the slot was already used (the loop's window is one-shot). */
    public boolean insert(Run run) {
        try {
            jdbc.sql("""
                    INSERT INTO agent_runs (id, loop, prompt_version, slot_key, started_at, expires_at)
                    VALUES (:id, :loop, :pv, :slot, :started, :expires)""")
                    .param("id", run.id()).param("loop", run.loop().name()).param("pv", run.promptVersion())
                    .param("slot", run.slotKey()).param("started", Db.ts(run.startedAt()))
                    .param("expires", Db.ts(run.expiresAt())).update();
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * LOCAL TESTING ONLY (called from the sandbox page): frees every loop's window so each loop can start again today. The runs
     * themselves stay, so history and audit records still point at them.
     */
    public int releaseSlots() {
        return jdbc.sql("UPDATE agent_runs SET slot_key = NULL WHERE slot_key IS NOT NULL").update();
    }

    public Optional<Run> find(UUID id) {
        return jdbc.sql("SELECT * FROM agent_runs WHERE id = :id").param("id", id)
                .query((rs, n) -> map(rs)).optional();
    }

    public Optional<Run> latestEnded(Loop loop) {
        return jdbc.sql("SELECT * FROM agent_runs WHERE loop = :l AND ended_at IS NOT NULL ORDER BY ended_at DESC LIMIT 1")
                .param("l", loop.name()).query((rs, n) -> map(rs)).optional();
    }

    public boolean end(UUID id, Instant at, String checked, String did, String nextFocus) {
        return jdbc.sql("""
                UPDATE agent_runs SET ended_at = :at, checked = :c, did = :d, next_focus = :n
                WHERE id = :id AND ended_at IS NULL""")
                .param("id", id).param("at", Db.ts(at)).param("c", checked).param("d", did).param("n", nextFocus)
                .update() == 1;
    }

    private Run map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Run(Db.uuid(rs, "id"), Loop.valueOf(rs.getString("loop")), rs.getString("prompt_version"),
                rs.getString("slot_key"), Db.instant(rs, "started_at"), Db.instant(rs, "expires_at"),
                Db.instant(rs, "ended_at"), rs.getString("checked"), rs.getString("did"), rs.getString("next_focus"));
    }
}

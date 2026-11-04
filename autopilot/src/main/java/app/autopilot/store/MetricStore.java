package app.autopilot.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Metric snapshots, notes between runs, quota usage, heartbeats, and changes seen from outside. */
@Repository
public class MetricStore {

    public record Snapshot(LocalDate date, String entityKey, String kind, long costMicros, long clicks,
                           long impressions, double conversions, double conversionValue, Instant takenAt) {}

    public record Note(long id, String topic, String text, UUID runId, Instant createdAt, Instant expiresAt) {}

    private final JdbcClient jdbc;
    private final Json json;

    public MetricStore(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ---- snapshots ---------------------------------------------------------------------------

    public void upsertSnapshot(Snapshot s, Instant now) {
        jdbc.sql("""
                INSERT INTO metric_snapshots (date, entity_key, kind, cost_micros, clicks, impressions,
                       conversions, conversion_value, taken_at)
                VALUES (:d, :k, :kind, :cost, :clicks, :imp, :conv, :val, :now)
                ON CONFLICT (date, entity_key, kind) DO UPDATE SET cost_micros = EXCLUDED.cost_micros,
                       clicks = EXCLUDED.clicks, impressions = EXCLUDED.impressions,
                       conversions = EXCLUDED.conversions, conversion_value = EXCLUDED.conversion_value,
                       taken_at = EXCLUDED.taken_at""")
                .param("d", s.date()).param("k", s.entityKey()).param("kind", s.kind()).param("cost", s.costMicros())
                .param("clicks", s.clicks()).param("imp", s.impressions()).param("conv", s.conversions())
                .param("val", s.conversionValue()).param("now", Db.ts(now)).update();
    }

    public List<Snapshot> snapshots(String entityKey, String kind, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT * FROM metric_snapshots WHERE entity_key = :k AND kind = :kind AND date BETWEEN :f AND :t
                ORDER BY date""")
                .param("k", entityKey).param("kind", kind).param("f", from).param("t", to)
                .query((rs, n) -> new Snapshot(rs.getObject("date", LocalDate.class), rs.getString("entity_key"),
                        rs.getString("kind"), rs.getLong("cost_micros"), rs.getLong("clicks"), rs.getLong("impressions"),
                        rs.getDouble("conversions"), rs.getDouble("conversion_value"), Db.instant(rs, "taken_at"))).list();
    }

    /** Snapshots older than the retention are deleted: metric snapshots are kept for 13 months. */
    public int purgeSnapshotsBefore(LocalDate cutoff) {
        return jdbc.sql("DELETE FROM metric_snapshots WHERE date < :c").param("c", cutoff).update();
    }

    // ---- notes -------------------------------------------------------------------------------

    public void addNote(String topic, String text, UUID runId, Instant now, Instant expiresAt) {
        jdbc.sql("INSERT INTO agent_notes (topic, text, run_id, created_at, expires_at) VALUES (:t, :x, :r, :c, :e)")
                .param("t", topic).param("x", text).param("r", runId).param("c", Db.ts(now))
                .param("e", Db.ts(expiresAt)).update();
    }

    public List<Note> activeNotes(Instant now, int limit) {
        return jdbc.sql("SELECT * FROM agent_notes WHERE expires_at > :n ORDER BY created_at DESC LIMIT :l")
                .param("n", Db.ts(now)).param("l", limit)
                .query((rs, n) -> new Note(rs.getLong("id"), rs.getString("topic"), rs.getString("text"),
                        Db.uuid(rs, "run_id"), Db.instant(rs, "created_at"), Db.instant(rs, "expires_at"))).list();
    }

    // ---- quota -------------------------------------------------------------------------------

    public void addOps(Instant now, long ops) {
        Instant bucket = now.truncatedTo(ChronoUnit.HOURS);
        jdbc.sql("""
                INSERT INTO quota_usage (hour_bucket, ops) VALUES (:b, :o)
                ON CONFLICT (hour_bucket) DO UPDATE SET ops = quota_usage.ops + EXCLUDED.ops""")
                .param("b", Db.ts(bucket)).param("o", ops).update();
    }

    /** Operations in the sliding 24 hours ending now (hour buckets, so slightly conservative). */
    public long opsLast24h(Instant now) {
        Instant from = now.minus(24, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);
        return jdbc.sql("SELECT coalesce(sum(ops), 0) FROM quota_usage WHERE hour_bucket >= :f")
                .param("f", Db.ts(from)).query(Long.class).single();
    }

    // ---- heartbeat ---------------------------------------------------------------------------

    public void beat(String source, Instant at, Map<String, Object> details) {
        jdbc.sql("""
                INSERT INTO heartbeats (source, beat_at, details) VALUES (:s, :a, CAST(:d AS jsonb))
                ON CONFLICT (source) DO UPDATE SET beat_at = EXCLUDED.beat_at, details = EXCLUDED.details""")
                .param("s", source).param("a", Db.ts(at)).param("d", json.write(details == null ? Map.of() : details))
                .update();
    }

    public Optional<Instant> lastBeat(String source) {
        return jdbc.sql("SELECT beat_at FROM heartbeats WHERE source = :s").param("s", source)
                .query((rs, n) -> Db.instant(rs, 1)).optional();
    }

    // ---- changes seen from outside -----------------------------------------------------------

    /** Returns true when this event was new. */
    public boolean recordExternalChange(String resourceName, String entityKey, Instant at, String actor,
                                        String clientType, String operation) {
        return jdbc.sql("""
                INSERT INTO external_changes (resource_name, entity_key, at, actor, client_type, operation)
                VALUES (:r, :k, :a, :actor, :c, :o) ON CONFLICT DO NOTHING""")
                .param("r", resourceName).param("k", entityKey).param("a", Db.ts(at)).param("actor", actor)
                .param("c", clientType).param("o", operation).update() == 1;
    }

    public Optional<Instant> changeCursor() {
        return jdbc.sql("SELECT last_at FROM change_cursor WHERE id = 1").query((rs, n) -> Optional.ofNullable(Db.instant(rs, 1))).single();
    }

    public void setChangeCursor(Instant at) {
        jdbc.sql("UPDATE change_cursor SET last_at = :a WHERE id = 1").param("a", Db.ts(at)).update();
    }
}

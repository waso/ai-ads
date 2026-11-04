package app.autopilot.store;

import app.autopilot.domain.ChangeOutcome;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The audit trail and the record of what was sent to Google. {@code changes} and {@code audit_events} are
 * append-only: the application's database role cannot UPDATE or DELETE them.
 */
@Repository
public class LedgerStore {

    /** One audit row. Every attempt, including refused ones, gets one. */
    public record AuditEvent(
            Instant at, String event, UUID runId, String loop, String tool, String tier, String entityKey,
            String rule, String message, String reason, String expectedEffect,
            Map<String, Object> oldValues, Map<String, Object> newValues, String approver,
            String apiRequestId, List<String> resources, UUID proposalId, UUID changeId, String idempotencyKey) {}

    public record ChangeRecord(
            UUID id, UUID proposalId, UUID runId, String tool, String tier, String entityKey, String fingerprint,
            int units, String actor, String approver, String requestJson,
            Map<String, Object> oldValues, Map<String, Object> newValues, String apiRequestId,
            List<String> resources, String result, String error, Instant createdAt) {}

    public record IdempotentOutcome(String requestHash, ChangeOutcome outcome) {}

    public record OutcomeReview(UUID id, UUID changeId, Instant reviewAt, String baselineJson) {}

    public record OutcomeRow(UUID id, UUID changeId, Instant reviewAt, String baselineJson, String verdict, Instant reviewedAt) {}

    public record Learning(UUID changeId, String tool, String entityKey, String verdict, Instant reviewedAt, String learning, String confidence) {}

    private final JdbcClient jdbc;
    private final Json json;

    public LedgerStore(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ---- audit -------------------------------------------------------------------------------

    public void audit(AuditEvent e) {
        jdbc.sql("""
                INSERT INTO audit_events (at, event, run_id, loop, tool, tier, entity_key, rule, message, reason,
                       expected_effect, old_values, new_values, approver, api_request_id, resources,
                       proposal_id, change_id, idempotency_key)
                VALUES (:at, :event, :run, :loop, :tool, :tier, :entity, :rule, :message, :reason, :effect,
                        CAST(:old AS jsonb), CAST(:new AS jsonb), :approver, :req, CAST(:res AS jsonb),
                        :proposal, :change, :idem)""")
                .param("at", Db.ts(e.at())).param("event", e.event()).param("run", e.runId())
                .param("loop", e.loop()).param("tool", e.tool()).param("tier", e.tier())
                .param("entity", e.entityKey()).param("rule", e.rule()).param("message", e.message())
                .param("reason", e.reason()).param("effect", e.expectedEffect())
                .param("old", e.oldValues() == null ? null : json.write(e.oldValues()))
                .param("new", e.newValues() == null ? null : json.write(e.newValues()))
                .param("approver", e.approver()).param("req", e.apiRequestId())
                .param("res", e.resources() == null ? null : json.write(e.resources()))
                .param("proposal", e.proposalId()).param("change", e.changeId()).param("idem", e.idempotencyKey())
                .update();
    }

    public List<Map<String, Object>> auditFor(String event) {
        return jdbc.sql("SELECT * FROM audit_events WHERE event = :e ORDER BY id").param("e", event)
                .query().listOfRows();
    }

    public long auditCount() {
        return jdbc.sql("SELECT count(*) FROM audit_events").query(Long.class).single();
    }

    /** Is there an audit ATTEMPT between two instants? Used to reconcile Google's change history. */
    public boolean attemptBetween(Instant from, Instant to) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM audit_events WHERE event = 'ATTEMPT' AND at BETWEEN :f AND :t)")
                .param("f", Db.ts(from)).param("t", Db.ts(to)).query(Boolean.class).single();
    }

    // ---- changes -----------------------------------------------------------------------------

    public void insertChange(ChangeRecord c) {
        jdbc.sql("""
                INSERT INTO changes (id, proposal_id, run_id, tool, tier, entity_key, fingerprint, units, actor,
                       approver, request, old_values, new_values, api_request_id, resources, result, error, created_at)
                VALUES (:id, :proposal, :run, :tool, :tier, :entity, :fp, :units, :actor, :approver,
                        CAST(:req AS jsonb), CAST(:old AS jsonb), CAST(:new AS jsonb), :apiReq,
                        CAST(:res AS jsonb), :result, :error, :at)""")
                .param("id", c.id()).param("proposal", c.proposalId()).param("run", c.runId())
                .param("tool", c.tool()).param("tier", c.tier()).param("entity", c.entityKey())
                .param("fp", c.fingerprint() == null ? "" : c.fingerprint()).param("units", c.units())
                .param("actor", c.actor()).param("approver", c.approver()).param("req", c.requestJson())
                .param("old", json.write(c.oldValues())).param("new", json.write(c.newValues()))
                .param("apiReq", c.apiRequestId()).param("res", json.write(c.resources()))
                .param("result", c.result()).param("error", c.error()).param("at", Db.ts(c.createdAt()))
                .update();
    }

    public Optional<ChangeRecord> findChange(UUID id) {
        return jdbc.sql("SELECT * FROM changes WHERE id = :id").param("id", id)
                .query((rs, n) -> mapChange(rs)).optional();
    }

    public List<ChangeRecord> recentChanges(int limit) {
        return jdbc.sql("SELECT * FROM changes ORDER BY created_at DESC LIMIT :n").param("n", limit)
                .query((rs, n) -> mapChange(rs)).list();
    }

    /** Latest applied change to an entity by this system or seen from outside (owner in the UI), if any. */
    public Optional<Instant> lastChangeAt(String entityKey) {
        return jdbc.sql("""
                SELECT max(t) FROM (
                    SELECT max(created_at) AS t FROM changes WHERE entity_key = :k AND result = 'APPLIED'
                    UNION ALL
                    SELECT max(at) FROM external_changes WHERE entity_key = :k) x""")
                .param("k", entityKey).query((rs, n) -> Optional.ofNullable(Db.instant(rs, 1))).single();
    }

    public Optional<Instant> lastChangeAt(String entityKey, String tool) {
        return jdbc.sql("SELECT max(created_at) FROM changes WHERE entity_key = :k AND tool = :t AND result = 'APPLIED'")
                .param("k", entityKey).param("t", tool).query((rs, n) -> Optional.ofNullable(Db.instant(rs, 1))).single();
    }

    /** Sum of units (for example negative keywords added) applied by a tool since an instant. */
    public long unitsSince(String tool, Instant since) {
        return jdbc.sql("SELECT coalesce(sum(units), 0) FROM changes WHERE tool = :t AND result = 'APPLIED' AND created_at >= :s")
                .param("t", tool).param("s", Db.ts(since)).query(Long.class).single();
    }

    public long tier1ChangesSince(Instant since) {
        return jdbc.sql("SELECT count(*) FROM changes WHERE tier = 'T1' AND result = 'APPLIED' AND created_at >= :s")
                .param("s", Db.ts(since)).query(Long.class).single();
    }

    public boolean appliedWithFingerprintSince(String fingerprint, Instant since) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM changes WHERE fingerprint = :f AND result = 'APPLIED' AND created_at >= :s)")
                .param("f", fingerprint).param("s", Db.ts(since)).query(Boolean.class).single();
    }

    private ChangeRecord mapChange(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ChangeRecord(Db.uuid(rs, "id"), Db.uuid(rs, "proposal_id"), Db.uuid(rs, "run_id"),
                rs.getString("tool"), rs.getString("tier"), rs.getString("entity_key"), rs.getString("fingerprint"),
                rs.getInt("units"), rs.getString("actor"), rs.getString("approver"), rs.getString("request"),
                json.readMap(rs.getString("old_values")), json.readMap(rs.getString("new_values")),
                rs.getString("api_request_id"), stringList(rs.getString("resources")), rs.getString("result"),
                rs.getString("error"), Db.instant(rs, "created_at"));
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(String j) {
        return j == null ? List.of() : ((List<Object>) json.read(j, List.class)).stream().map(Object::toString).toList();
    }

    // ---- idempotency -------------------------------------------------------------------------

    public Optional<IdempotentOutcome> findIdempotent(String key) {
        return jdbc.sql("SELECT request_hash, outcome FROM idempotency WHERE key = :k").param("k", key)
                .query((rs, n) -> new IdempotentOutcome(rs.getString("request_hash"),
                        json.read(rs.getString("outcome"), ChangeOutcome.class))).optional();
    }

    /** First writer wins; a concurrent duplicate is ignored. */
    public void saveIdempotent(String key, String requestHash, ChangeOutcome outcome, Instant now) {
        try {
            jdbc.sql("INSERT INTO idempotency (key, request_hash, outcome, created_at) VALUES (:k, :h, CAST(:o AS jsonb), :t)")
                    .param("k", key).param("h", requestHash).param("o", json.write(outcome)).param("t", Db.ts(now)).update();
        } catch (DuplicateKeyException ignored) {
            // already stored by an earlier call
        }
    }

    // ---- outcome reviews ---------------------------------------------------------------------

    public void scheduleOutcome(UUID changeId, Instant reviewAt, Map<String, Object> baseline) {
        jdbc.sql("INSERT INTO outcomes (id, change_id, review_at, baseline) VALUES (:id, :c, :r, CAST(:b AS jsonb))")
                .param("id", UUID.randomUUID()).param("c", changeId).param("r", Db.ts(reviewAt))
                .param("b", json.write(baseline)).update();
    }

    public List<OutcomeReview> dueOutcomes(Instant now) {
        return jdbc.sql("SELECT id, change_id, review_at, baseline FROM outcomes WHERE reviewed_at IS NULL AND review_at <= :n ORDER BY review_at")
                .param("n", Db.ts(now)).query((rs, n) -> new OutcomeReview(Db.uuid(rs, "id"), Db.uuid(rs, "change_id"),
                        Db.instant(rs, "review_at"), rs.getString("baseline"))).list();
    }

    /** Returns false when the review was already completed (nothing is overwritten). */
    public boolean completeOutcome(UUID id, String verdict, Map<String, Object> result, Instant now) {
        return jdbc.sql("UPDATE outcomes SET reviewed_at = :n, verdict = :v, result = CAST(:r AS jsonb) WHERE id = :id AND reviewed_at IS NULL")
                .param("id", id).param("n", Db.ts(now)).param("v", verdict).param("r", json.write(result)).update() == 1;
    }

    /** The review of one change, completed or not. */
    public Optional<OutcomeRow> findOutcome(UUID changeId) {
        return jdbc.sql("SELECT id, change_id, review_at, baseline, verdict, reviewed_at FROM outcomes WHERE change_id = :c")
                .param("c", changeId).query((rs, n) -> new OutcomeRow(Db.uuid(rs, "id"), Db.uuid(rs, "change_id"),
                        Db.instant(rs, "review_at"), rs.getString("baseline"), rs.getString("verdict"), Db.instant(rs, "reviewed_at"))).optional();
    }

    /** What reviewers concluded about past changes, newest first. Reviews the watchdog closed unreviewed are left out. */
    public List<Learning> recentLearnings(int limit) {
        return jdbc.sql("""
                SELECT o.change_id, c.tool, c.entity_key, o.verdict, o.reviewed_at, o.result ->> 'learning' AS learning,
                       o.result -> 'measurement' ->> 'confidence' AS confidence
                FROM outcomes o JOIN changes c ON c.id = o.change_id
                WHERE o.reviewed_at IS NOT NULL AND o.verdict <> 'UNREVIEWED'
                ORDER BY o.reviewed_at DESC LIMIT :n""").param("n", limit)
                .query((rs, n) -> new Learning(Db.uuid(rs, "change_id"), rs.getString("tool"), rs.getString("entity_key"),
                        rs.getString("verdict"), Db.instant(rs, "reviewed_at"), rs.getString("learning"), rs.getString("confidence"))).list();
    }

    /** Why a change was made and what it was expected to do, from its APPLIED audit record. */
    public Optional<Map<String, String>> appliedIntent(UUID changeId) {
        return jdbc.sql("SELECT reason, expected_effect FROM audit_events WHERE change_id = :c AND event = 'APPLIED' ORDER BY id LIMIT 1")
                .param("c", changeId).query((rs, n) -> {
                    Map<String, String> m = new java.util.LinkedHashMap<>();
                    m.put("reason", rs.getString("reason"));
                    m.put("expectedEffect", rs.getString("expected_effect"));
                    return m;
                }).optional();
    }

    public long pendingOutcomes() {
        return jdbc.sql("SELECT count(*) FROM outcomes WHERE reviewed_at IS NULL").query(Long.class).single();
    }
}

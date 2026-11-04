package app.autopilot.store;

import app.autopilot.domain.ChangeMeta;
import app.autopilot.domain.ChangeRequest;
import app.autopilot.domain.Diff;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ProposalStore {

    public enum Status { PENDING, APPLIED, REJECTED, EXPIRED, STALE, FAILED }

    public record Proposal(
            UUID id, UUID runId, String tool, String tier, Status status,
            ChangeRequest request, ChangeMeta meta, Diff diff, String stateHash, String whyProposed,
            boolean rollback, String fingerprint, Instant createdAt, Instant expiresAt, Instant decidedAt, String decidedBy,
            String decisionNote, UUID changeId, String nonce, Instant nonceUsedAt) {}

    private final JdbcClient jdbc;
    private final Json json;

    public ProposalStore(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(Proposal p) {
        jdbc.sql("""
                INSERT INTO proposals (id, run_id, tool, tier, status, request, meta, diff, state_hash,
                       why_proposed, is_rollback, fingerprint, created_at, expires_at, nonce)
                VALUES (:id, :run, :tool, :tier, :status, CAST(:req AS jsonb), CAST(:meta AS jsonb),
                        CAST(:diff AS jsonb), :hash, :why, :rollback, :fp, :created, :expires, :nonce)""")
                .param("id", p.id()).param("run", p.runId()).param("tool", p.tool()).param("tier", p.tier())
                .param("status", p.status().name()).param("req", json.write(p.request()))
                .param("meta", json.write(p.meta())).param("diff", json.write(p.diff()))
                .param("hash", p.stateHash()).param("why", p.whyProposed()).param("rollback", p.rollback())
                .param("fp", p.fingerprint())
                .param("created", Db.ts(p.createdAt())).param("expires", Db.ts(p.expiresAt()))
                .param("nonce", p.nonce()).update();
    }

    public Optional<Proposal> find(UUID id) {
        return jdbc.sql("SELECT * FROM proposals WHERE id = :id").param("id", id)
                .query((rs, n) -> map(rs)).optional();
    }

    public Optional<Proposal> pendingWithFingerprint(String fingerprint, Instant now) {
        return jdbc.sql("""
                SELECT * FROM proposals WHERE fingerprint = :fp AND status = 'PENDING' AND expires_at > :now
                ORDER BY created_at DESC LIMIT 1""")
                .param("fp", fingerprint).param("now", Db.ts(now)).query((rs, n) -> map(rs)).optional();
    }

    public List<Proposal> list(Status status, int limit) {
        return jdbc.sql("SELECT * FROM proposals WHERE (:s::text IS NULL OR status = :s) ORDER BY created_at DESC LIMIT :n")
                .param("s", status == null ? null : status.name()).param("n", limit)
                .query((rs, n) -> map(rs)).list();
    }

    /** Pending proposals that are still open for decision. */
    public List<Proposal> open(Instant now) {
        return jdbc.sql("SELECT * FROM proposals WHERE status = 'PENDING' AND expires_at > :now ORDER BY created_at")
                .param("now", Db.ts(now)).query((rs, n) -> map(rs)).list();
    }

    /**
     * Consumes the single-use approval nonce. True only the first time, for a pending, unexpired proposal
     * whose nonce matches.
     */
    public boolean consumeNonce(UUID id, String nonce, Instant now) {
        return jdbc.sql("""
                UPDATE proposals SET nonce_used_at = :now
                WHERE id = :id AND nonce = :nonce AND nonce_used_at IS NULL
                  AND status = 'PENDING' AND expires_at > :now""")
                .param("id", id).param("nonce", nonce).param("now", Db.ts(now)).update() == 1;
    }

    /** Moves a PENDING proposal to a final status. False when it was no longer pending. */
    public boolean decide(UUID id, Status status, String by, String note, UUID changeId, Instant now) {
        return jdbc.sql("""
                UPDATE proposals SET status = :s, decided_at = :now, decided_by = :by,
                       decision_note = :note, change_id = :change
                WHERE id = :id AND status = 'PENDING'""")
                .param("id", id).param("s", status.name()).param("now", Db.ts(now)).param("by", by)
                .param("note", note).param("change", changeId).update() == 1;
    }

    public List<Proposal> expireDue(Instant now) {
        List<Proposal> due = jdbc.sql("SELECT * FROM proposals WHERE status = 'PENDING' AND expires_at <= :now")
                .param("now", Db.ts(now)).query((rs, n) -> map(rs)).list();
        due.forEach(p -> decide(p.id(), Status.EXPIRED, "system", "expired after lifetime", null, now));
        return due;
    }

    private Proposal map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Proposal(Db.uuid(rs, "id"), Db.uuid(rs, "run_id"), rs.getString("tool"), rs.getString("tier"),
                Status.valueOf(rs.getString("status")), json.read(rs.getString("request"), ChangeRequest.class),
                json.read(rs.getString("meta"), ChangeMeta.class), json.read(rs.getString("diff"), Diff.class),
                rs.getString("state_hash"), rs.getString("why_proposed"), rs.getBoolean("is_rollback"), rs.getString("fingerprint"),
                Db.instant(rs, "created_at"), Db.instant(rs, "expires_at"), Db.instant(rs, "decided_at"),
                rs.getString("decided_by"), rs.getString("decision_note"), Db.uuid(rs, "change_id"),
                rs.getString("nonce"), Db.instant(rs, "nonce_used_at"));
    }
}

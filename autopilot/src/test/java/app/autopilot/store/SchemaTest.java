package app.autopilot.store;

import app.autopilot.testkit.PgTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaTest {

    private static PgTestSupport.TestDb db;
    private static JdbcClient app;

    @BeforeAll
    static void start() {
        db = PgTestSupport.newDatabase();
        app = JdbcClient.create(db.app());
    }

    @AfterAll
    static void stop() {
        db.close();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("[AC-34] migrations create every table of the PRD data model on a real PostgreSQL")
    void migrationsCreateEveryTableOfTheDataModel() {
        List<String> tables = JdbcClient.create(db.owner())
                .sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")
                .query(String.class).list();
        // PRD data model tables, plus the tables the design needs for stop mechanisms, idempotency and quota
        assertThat(tables).as("[AC-34] PRD data model tables").contains("config_limits", "goals", "agent_runs", "proposals",
                "changes", "outcomes", "alerts", "metric_snapshots", "agent_notes");
        assertThat(tables).as("[AC-34] supporting tables").contains("control_state", "idempotency", "quota_usage",
                "heartbeats", "external_changes", "audit_events", "change_cursor");
        assertThat(JdbcClient.create(db.owner()).sql("SELECT count(*) FROM control_state").query(Long.class).single())
                .as("control_state starts with exactly one row").isEqualTo(1L);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("[AC-19] the application's database role can insert into changes and audit but never update or delete them")
    void appRoleCanInsertButNeverUpdateOrDeleteChangesAndAudit() {
        app.sql("""
                INSERT INTO audit_events (at, event) VALUES (now(), 'ATTEMPT')""").update();
        app.sql("""
                INSERT INTO changes (id, tool, tier, actor, request, old_values, new_values, result, created_at)
                VALUES (gen_random_uuid(), 'pause_keyword', 'T1', 'claude', '{}', '{}', '{}', 'APPLIED', now())""").update();

        assertThat(app.sql("SELECT count(*) FROM changes").query(Long.class).single()).isEqualTo(1L);

        assertDenied("UPDATE changes SET result = 'FAILED'");
        assertDenied("DELETE FROM changes");
        assertDenied("TRUNCATE changes");
        assertDenied("UPDATE audit_events SET event = 'x'");
        assertDenied("DELETE FROM audit_events");
        assertDenied("TRUNCATE audit_events");
        assertDenied("UPDATE config_limits SET limits = '{}'");
        assertDenied("DELETE FROM goals");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("[AC-19] the restricted role still has normal access to working tables")
    void appRoleCanStillWorkWithOrdinaryTables() {
        app.sql("UPDATE control_state SET kill_switch = true WHERE id = 1").update();
        app.sql("UPDATE control_state SET kill_switch = false WHERE id = 1").update();
        app.sql("INSERT INTO agent_notes (topic, text, created_at, expires_at) VALUES ('t', 'x', now(), now())").update();
        assertThat(app.sql("DELETE FROM agent_notes").update()).isEqualTo(1);
    }

    private void assertDenied(String sql) {
        assertThatThrownBy(() -> app.sql(sql).update())
                .as("[AC-19] database role must not be able to run: " + sql)
                .rootCause().hasMessageContaining("permission denied");
    }
}

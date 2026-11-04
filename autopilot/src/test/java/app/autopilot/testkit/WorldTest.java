package app.autopilot.testkit;

import app.autopilot.domain.ChangeOutcome;
import app.autopilot.domain.ChangeOutcome.Status;
import app.autopilot.domain.ChangeRequest;
import app.autopilot.domain.Loop;
import app.autopilot.store.RunStore.Run;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import static org.assertj.core.api.Assertions.assertThat;

/** Base for tests that run the real services against the fake account: a fresh world and a daily run each time. */
public abstract class WorldTest {

    protected TestWorld w;
    protected Run daily;

    @BeforeEach
    void startWorld() {
        w = new TestWorld();
        daily = w.startRun(Loop.DAILY);
    }

    @AfterEach
    void closeWorld() {
        w.close();
    }

    protected ChangeOutcome submit(ChangeRequest r) {
        return w.changes.submit(r, w.meta(daily));
    }

    protected ChangeOutcome submitAs(Run run, ChangeRequest r) {
        return w.changes.submit(r, w.meta(run));
    }

    protected void assertRefused(ChangeOutcome o, String rule) {
        assertThat(o.status()).as("outcome %s", o).isEqualTo(Status.REFUSED);
        assertThat(o.rule()).as("rule of %s", o).isEqualTo(rule);
        assertThat(o.message()).as("a refusal names the rule and the limit").isNotBlank();
    }

    protected void assertApplied(ChangeOutcome o) {
        assertThat(o.status()).as("outcome %s", o).isEqualTo(Status.APPLIED);
    }

    protected void assertProposed(ChangeOutcome o) {
        assertThat(o.status()).as("outcome %s", o).isEqualTo(Status.PROPOSED);
    }
}

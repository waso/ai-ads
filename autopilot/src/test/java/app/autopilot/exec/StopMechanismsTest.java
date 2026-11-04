package app.autopilot.exec;

import app.autopilot.ads.AdsException;
import app.autopilot.config.AutopilotProperties;
import app.autopilot.config.RuntimeConfig;
import app.autopilot.domain.ChangeOutcome;
import app.autopilot.domain.ChangeRequest.DecreaseBudget;
import app.autopilot.domain.ChangeRequest.EnableEntity;
import app.autopilot.domain.ChangeRequest.IncreaseBudget;
import app.autopilot.domain.ControlState;
import app.autopilot.domain.EntityRef;
import app.autopilot.domain.Goals;
import app.autopilot.guard.Guard;
import app.autopilot.testkit.TestLimits;
import app.autopilot.testkit.WorldTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StopMechanismsTest extends WorldTest {

    private static final EntityRef B1 = EntityRef.budget("B1");

    @Test
    @DisplayName("[AC-23] the kill switch makes the server read-only: changes and proposals are refused until the owner clears it")
    void killSwitch() {
        w.configStore.setKillSwitch(true, "testing", "owner@example.com");
        ChangeOutcome change = submit(new DecreaseBudget(B1, 45_000_000));
        assertRefused(change, Guard.Rule.KILL_SWITCH);
        assertThat(change.message()).contains("Only the owner can clear it");
        assertRefused(submit(new IncreaseBudget(B1, 55_000_000)), Guard.Rule.KILL_SWITCH);
        assertThat(w.changes.proposeRollback(java.util.UUID.randomUUID().toString(), w.meta(daily)).status())
                .isEqualTo(ChangeOutcome.Status.REFUSED);
        assertThat(w.fake.realMutations()).isZero();
        assertThat(w.ads.campaigns()).as("reads continue").isNotEmpty();

        w.configStore.setKillSwitch(false, null, "owner@example.com");
        assertApplied(submit(new DecreaseBudget(B1, 45_000_000)));
    }

    @Test
    @DisplayName("[AC-23] an open circuit breaker refuses writes; clearing it lists the campaigns it paused")
    void breaker() {
        assertThat(w.configStore.openBreaker(List.of("C1"), w.clock.instant(), "watchdog")).isTrue();
        assertThat(w.configStore.openBreaker(List.of("C1", "C7"), w.clock.instant(), "watchdog")).as("already open").isFalse();
        assertRefused(submit(new DecreaseBudget(B1, 45_000_000)), Guard.Rule.BREAKER_OPEN);
        assertThat(w.configStore.control().breakerOpen()).isTrue();

        List<String> paused = w.configStore.clearBreaker("owner@example.com");
        assertThat(paused).containsExactly("C1", "C7");
        assertThat(w.configStore.control().breakerOpen()).isFalse();
        assertApplied(submit(new DecreaseBudget(B1, 45_000_000)));
    }

    @Test
    @DisplayName("[AC-23] a kill switch set in server configuration works even when the database cannot be read")
    void configKillSwitchWorksWithoutDatabase() {
        AutopilotProperties p = w.props;
        AutopilotProperties killed = new AutopilotProperties(p.ownerEmail(), true, p.runs(), p.security(), p.alerting(),
                p.watchdog(), p.ads(), p.retention());
        RuntimeConfig cfg = new RuntimeConfig(w.configStore, TestLimits.defaults().build(), Goals.empty(), killed);
        assertThat(cfg.control().killSwitch()).isTrue();
        w.dataSource.down(true);
        ControlState cached = cfg.controlOrCached();
        assertThat(cached.killSwitch()).isTrue();
        assertThat(cached.killReason()).contains("server configuration");
        assertThatThrownBy(cfg::control).as("the database read itself fails").isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("[AC-24] with the database unavailable, writes are refused and reads continue")
    void databaseOutage() {
        w.dataSource.down(true);
        ChangeOutcome o = submit(new DecreaseBudget(B1, 45_000_000));
        assertRefused(o, ChangeService.DATABASE_UNAVAILABLE);
        assertThat(o.message()).contains("Reads continue");
        assertRefused(submit(new IncreaseBudget(B1, 55_000_000)), ChangeService.DATABASE_UNAVAILABLE);
        assertThat(w.fake.sent).as("Google was never called").isEmpty();

        // reads that do not need the database keep working, and quota counting degrades to memory
        assertThat(w.ads.campaigns()).hasSize(3);
        assertThat(w.ads.keywords(new app.autopilot.ads.AdsGateway.KeywordQuery("C1", true, null, null, 10))).isNotEmpty();
        assertThat(w.quota.used()).isPositive();

        w.dataSource.down(false);
        assertApplied(submit(new DecreaseBudget(B1, 45_000_000)));
    }

    @Test
    @DisplayName("[AC-28] every Ads operation counts, including calls Google rejects")
    void rejectedOperationsCount() {
        long before = w.quota.used();
        w.fake.failNextRealMutation = new AdsException("RESOURCE_EXHAUSTED", "quota (injected)", true, null);
        assertThatThrownBy(() -> w.ads.mutate(new EnableEntity(EntityRef.campaign("C1")), false)).isInstanceOf(AdsException.class);
        assertThat(w.quota.used()).isEqualTo(before + 1);
        w.ads.campaigns();
        assertThat(w.quota.used()).isEqualTo(before + 2);
    }

    @Test
    @DisplayName("[AC-28] a call the guard refuses never reaches Google, so it costs no operation")
    void guardRefusalsSpendNoQuota() {
        assertRefused(submit(new DecreaseBudget(B1, 25_000_000)), Guard.Rule.BUDGET_DECREASE_PCT);
        assertThat(w.fake.callCount("mutate")).isZero();
    }

    @Test
    @DisplayName("[AC-28] reads pause at 80% of the daily allowance, not before")
    void readsPauseAtEightyPercent() {
        long base = w.quota.used();
        w.quota.count(2303 - base);               // 2303 of 2880 = 79.97%
        assertThat(w.quota.percentUsed()).isEqualTo(79);
        assertThat(w.quota.readsShouldPause()).isFalse();
        w.quota.count(1);                         // 2304 of 2880 = 80.00%
        assertThat(w.quota.readsShouldPause()).isTrue();
    }

    @Test
    @DisplayName("[AC-28] quota is a sliding 24 hours: old usage ages out")
    void quotaWindowSlides() {
        w.quota.count(2400);
        assertThat(w.quota.readsShouldPause()).isTrue();
        w.clock.advance(Duration.ofHours(25));
        assertThat(w.quota.used()).isLessThan(100);
        assertThat(w.quota.readsShouldPause()).isFalse();
    }
}

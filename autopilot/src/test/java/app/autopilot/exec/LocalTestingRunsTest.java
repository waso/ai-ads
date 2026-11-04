package app.autopilot.exec;

import app.autopilot.config.AutopilotProperties;
import app.autopilot.domain.Loop;
import app.autopilot.store.RunStore.Run;
import app.autopilot.testkit.WorldTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;
import java.time.DayOfWeek;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Aids for trying the system on a laptop: loops may start at any time, and the sandbox page can free their windows. */
class LocalTestingRunsTest extends WorldTest {

    private RunService withAnyTime(boolean anyTime) {
        AutopilotProperties p = w.props;
        AutopilotProperties.Runs r = p.runs();
        AutopilotProperties props = new AutopilotProperties(p.ownerEmail(), p.killSwitch(),
                new AutopilotProperties.Runs(r.ttlMinutes(), LocalTime.of(4, 0), LocalTime.of(10, 0), DayOfWeek.MONDAY,
                        LocalTime.of(4, 0), LocalTime.of(12, 0), r.noteMaxChars(), r.noteDays(), anyTime),
                p.security(), p.alerting(), p.watchdog(), p.ads(), p.retention());
        return new RunService(w.runStore, w.config, props, w.alerts, w.clock);
    }

    @Test
    @DisplayName("[AC-49] by default the daily loop keeps its morning window and the weekly loop its day")
    void windowsAreEnforcedByDefault() {
        w.clock.advance(Duration.ofHours(8)); // Wednesday 13:30 in Warsaw
        RunService strict = withAnyTime(false);
        assertThatThrownBy(() -> strict.start(Loop.DAILY, "v1")).isInstanceOf(RunService.RunRefused.class)
                .extracting(e -> ((RunService.RunRefused) e).rule()).isEqualTo("outside_window");
        assertThatThrownBy(() -> strict.start(Loop.WEEKLY, "v1")).isInstanceOf(RunService.RunRefused.class);
    }

    @Test
    @DisplayName("[AC-49] with any-time on, daily and weekly start at any time, still once per window")
    void anyTimeStillOneRunPerWindow() {
        w.clock.advance(Duration.ofHours(32)); // Thursday 13:30: the world's own daily run used Wednesday's window
        RunService lax = withAnyTime(true);
        Run daily = lax.start(Loop.DAILY, "v1");
        Run weekly = lax.start(Loop.WEEKLY, "v1");
        assertThat(daily.slotKey()).startsWith("DAILY@");
        assertThat(weekly.slotKey()).startsWith("WEEKLY@");
        assertThatThrownBy(() -> lax.start(Loop.DAILY, "v1")).isInstanceOf(RunService.RunRefused.class)
                .extracting(e -> ((RunService.RunRefused) e).rule()).isEqualTo("slot_used");
    }

    @Test
    @DisplayName("[AC-49] releasing the windows lets every loop start again and keeps the earlier runs")
    void releasingSlots() {
        w.clock.advance(Duration.ofHours(32));
        RunService lax = withAnyTime(true);
        Run first = lax.start(Loop.DAILY, "v1");
        assertThat(w.runStore.releaseSlots()).isGreaterThanOrEqualTo(1);
        Run second = lax.start(Loop.DAILY, "v1");
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(w.runStore.find(first.id())).as("the earlier run is still on record").isPresent();
        assertThat(w.runStore.releaseSlots()).as("only the run that still holds a window").isEqualTo(1);
    }
}

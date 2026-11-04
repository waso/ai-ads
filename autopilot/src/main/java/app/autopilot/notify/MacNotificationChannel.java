package app.autopilot.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * A macOS notification banner, for running on a laptop. The text is passed to AppleScript as arguments and never spliced into
 * the script, so a search term or campaign name in an alert cannot run anything. Banners carry no approval link: the owner opens
 * the admin page, which lists every proposal.
 */
@Component
@ConditionalOnProperty(prefix = "autopilot.alerting", name = "macos", havingValue = "true")
class MacNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(MacNotificationChannel.class);
    static final int MAX_LENGTH = 220;

    private final Consumer<List<String>> runner;

    @Autowired
    MacNotificationChannel() {
        this(MacNotificationChannel::run);
    }

    MacNotificationChannel(Consumer<List<String>> runner) {
        this.runner = runner;
    }

    @Override
    public String name() {
        return "macos";
    }

    @Override
    public void send(Notification n) {
        runner.accept(command(n));
    }

    static List<String> command(Notification n) {
        return List.of("osascript",
                "-e", "on run argv",
                "-e", "display notification (item 1 of argv) with title (item 2 of argv)",
                "-e", "end run",
                clip(n.body()), "Ads Autopilot: " + clip(n.title()));
    }

    private static String clip(String s) {
        String t = s == null ? "" : s.replaceAll("[\\p{Cntrl}]+", " ").strip();
        return t.length() <= MAX_LENGTH ? t : t.substring(0, MAX_LENGTH - 1) + "…";
    }

    private static void run(List<String> command) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("mac")) return;
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("osascript did not finish in 5 seconds");
            }
        } catch (Exception e) {
            log.warn("Could not show a macOS notification: {}", e.toString());
        }
    }
}

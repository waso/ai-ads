package app.autopilot.watchdog;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * The watchdog as a one-shot process: a Kubernetes CronJob or a Cloud Run Job starts the same image with
 * {@code AUTOPILOT_MODE=watchdog}, it runs once and exits. The exit code is non-zero when a step failed, so the
 * scheduler's own failure alerting also fires.
 */
@Component
@ConditionalOnProperty(name = "autopilot.mode", havingValue = "watchdog")
class WatchdogRunner implements ApplicationRunner {

    private final Watchdog watchdog;
    private final ApplicationContext context;

    WatchdogRunner(Watchdog watchdog, ApplicationContext context) {
        this.watchdog = watchdog;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        Watchdog.Report report = watchdog.runOnce();
        int code = report.healthy() ? 0 : 1;
        System.exit(SpringApplication.exit(context, () -> code));
    }
}

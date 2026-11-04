package app.autopilot;

import app.autopilot.config.AutopilotProperties;
import app.autopilot.config.RuntimeConfig;
import app.autopilot.domain.Goals;
import app.autopilot.domain.Limits;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/**
 * One codebase, three entry points: the MCP tools, the approval page and the watchdog job. They share one
 * guard and one audit log, so a safety rule is built and tested once.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({Limits.class, Goals.class, AutopilotProperties.class})
public class AutopilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(AutopilotApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Stores a new limits and goals version when server configuration changed since the last start. */
    @Bean
    ApplicationRunner syncConfigVersions(RuntimeConfig config) {
        return args -> config.syncVersions();
    }
}

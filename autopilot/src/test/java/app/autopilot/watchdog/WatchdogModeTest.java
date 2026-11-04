package app.autopilot.watchdog;

import app.autopilot.AutopilotApplication;
import app.autopilot.testkit.AppTestBase;
import app.autopilot.testkit.PgTestSupport;
import app.autopilot.ads.sandbox.SandboxAdsGateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WatchdogModeTest {

    /** Command-line arguments: the highest-precedence way to override application.yml. */
    @SafeVarargs
    private static String[] asArgs(Map<String, String>... maps) {
        return java.util.Arrays.stream(maps).flatMap(m -> m.entrySet().stream()).map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new);
    }

    @Test
    @DisplayName("[AC-25] the watchdog runs as a one-shot process with no web server and no Claude session, and a breach still trips the breaker")
    void runsWithoutWebAndWithoutClaude() {
        PgTestSupport.TestDb db = PgTestSupport.newDatabase();
        SpringApplicationBuilder builder = new SpringApplicationBuilder(AutopilotApplication.class, AppTestBase.Fakes.class)
                .web(WebApplicationType.NONE);
        String[] args = asArgs(db.springProperties(), Map.of("autopilot.ads.mode", "test", "autopilot.owner-email", "owner@example.com",
                        "autopilot.limits.account-id", "1234567890", "autopilot.limits.monthly-cap-micros", "3000000000",
                        "autopilot.limits.autonomy", "L2", "autopilot.ads.service-account-email", SandboxAdsGateway.SERVICE_ACCOUNT,
                        "spring.security.oauth2.client.registration.google.client-id", "x",
                        "spring.security.oauth2.client.registration.google.client-secret", "x", "spring.main.banner-mode", "off"));
        try (ConfigurableApplicationContext ctx = builder.run(args)) {
            assertThat(ctx.getBeansOfType(SecurityFilterChain.class)).as("no web security without a web server").isEmpty();
            assertThat(ctx.containsBean("approvalController")).as("no approval page without a web server").isFalse();
            assertThat(ctx.containsBean("mcpJwtDecoder")).isTrue(); // only the test configuration provides one; production's is web-only

            SandboxAdsGateway fake = ctx.getBean(SandboxAdsGateway.class);
            fake.campaigns.get("C1").costToday = 150_000_000L;
            Watchdog.Report report = ctx.getBean(Watchdog.class).runOnce();

            assertThat(report.healthy()).isTrue();
            assertThat(report.breakerTripped()).isTrue();
            assertThat(fake.campaignStatus("C1")).isEqualTo("PAUSED");
            assertThat(ctx.getBean(app.autopilot.store.ConfigStore.class).control().breakerOpen()).isTrue();
        } finally {
            db.close();
        }
    }
}

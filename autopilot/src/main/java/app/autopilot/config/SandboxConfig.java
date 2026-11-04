package app.autopilot.config;

import app.autopilot.ads.sandbox.SandboxAdsGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * {@code autopilot.ads.mode=sandbox}: a simulated Google Ads account instead of the real one. Everything else runs for real
 * (MCP tools, guard, approval page, watchdog), which is the point: prove the whole stack locally before any Google credential
 * exists. Nothing is ever sent to Google.
 */
@Configuration
@ConditionalOnProperty(prefix = "autopilot.ads", name = "mode", havingValue = "sandbox")
class SandboxConfig {

    private static final Logger log = LoggerFactory.getLogger(SandboxConfig.class);

    @Bean(name = "rawAdsGateway")
    SandboxAdsGateway rawAdsGateway(Clock clock) {
        log.warn("""

                ============================================================
                 SANDBOX MODE: a simulated Google Ads account is in use.
                 Nothing is sent to Google. State lives in memory only.
                ============================================================""");
        return new SandboxAdsGateway(clock::instant).seedVibefactory();
    }
}

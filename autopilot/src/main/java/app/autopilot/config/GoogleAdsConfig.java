package app.autopilot.config;

import app.autopilot.ads.AdsGateway;
import app.autopilot.ads.google.GoogleAdsClientFactory;
import app.autopilot.ads.google.GoogleAdsGateway;
import app.autopilot.domain.Limits;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.Clock;

/**
 * The real Google Ads connection, bound to the one allowlisted account. Credentials are Application Default
 * Credentials (Workload Identity on the cluster), so no key file exists to leak.
 */
@Configuration
@ConditionalOnProperty(prefix = "autopilot.ads", name = "mode", havingValue = "google", matchIfMissing = true)
class GoogleAdsConfig {

    @Bean(name = "rawAdsGateway", destroyMethod = "close")
    AdsGateway rawAdsGateway(AutopilotProperties props, Limits limits, Clock clock) throws IOException {
        if (limits.accountId() == null || limits.accountId().isBlank()) {
            throw new IllegalStateException("autopilot.limits.account-id (ADS_CUSTOMER_ID) must name the allowlisted Google Ads "
                    + "account, or set autopilot.ads.mode=none to run without Google access");
        }
        GoogleAdsClientFactory factory = new GoogleAdsClientFactory(props.ads().developerToken(), props.ads().loginCustomerId());
        return new GoogleAdsGateway(factory.create(), limits.accountId().replace("-", ""), clock, limits.zone(), limits.managedLabel());
    }
}

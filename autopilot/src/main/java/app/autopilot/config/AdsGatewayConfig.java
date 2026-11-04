package app.autopilot.config;

import app.autopilot.ads.AdsGateway;
import app.autopilot.ads.MeteredAdsGateway;
import app.autopilot.ads.QuotaMeter;
import app.autopilot.ads.UnconfiguredAdsGateway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Wires the one gateway everything uses: the raw adapter wrapped in the quota meter. The raw adapter is
 * the real Google client (mode {@code google}, see {@code GoogleAdsConfig}), a stub that answers "not connected"
 * (mode {@code none}), or whatever a test provides as the bean named {@code rawAdsGateway}.
 */
@Configuration
public class AdsGatewayConfig {

    @Bean(name = "rawAdsGateway")
    @ConditionalOnProperty(prefix = "autopilot.ads", name = "mode", havingValue = "none")
    AdsGateway rawAdsGateway() {
        return new UnconfiguredAdsGateway();
    }

    @Bean
    @Primary
    AdsGateway adsGateway(@Qualifier("rawAdsGateway") AdsGateway raw, QuotaMeter meter) {
        return new MeteredAdsGateway(raw, meter);
    }
}

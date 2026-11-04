package app.autopilot.config;

import app.autopilot.domain.Limits;
import app.autopilot.testkit.TestLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleAdsConfigTest {

    @Test
    @DisplayName("[AC-36] the real Google connection refuses to start without an allowlisted account, naming the setting")
    void failsFastWithoutAccount() {
        TestLimits none = TestLimits.defaults();
        none.accountId = "";
        Limits limits = none.build();
        AutopilotProperties props = new AutopilotProperties("o@example.com", false, null, null, null, null,
                new AutopilotProperties.Ads("google", "dev-token", null, null), null);
        assertThatThrownBy(() -> new GoogleAdsConfig().rawAdsGateway(props, limits, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("autopilot.limits.account-id").hasMessageContaining("ADS_CUSTOMER_ID");
        assertThat(limits.capsConfigured()).isFalse();
    }
}

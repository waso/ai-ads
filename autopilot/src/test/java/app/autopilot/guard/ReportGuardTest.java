package app.autopilot.guard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportGuardTest {

    @Test
    @DisplayName("[AC-32] a plain SELECT on an allowlisted resource is accepted and always gets a row cap")
    void acceptsPlainSelectsAndAddsACap() {
        var v = ReportGuard.validate("SELECT campaign.id, metrics.clicks FROM campaign WHERE campaign.status = 'ENABLED'", null);
        assertThat(v.query()).isEqualTo("SELECT campaign.id, metrics.clicks FROM campaign WHERE campaign.status = 'ENABLED' LIMIT 200");
        assertThat(v.limit()).isEqualTo(200);
        assertThat(ReportGuard.validate("select ad_group.id from ad_group", 50).query()).isEqualTo("SELECT ad_group.id FROM ad_group LIMIT 50");
        assertThat(ReportGuard.validate("SELECT keyword_view.resource_name FROM keyword_view ORDER BY metrics.cost_micros DESC", 5000).limit())
                .as("the cap is clamped to 1000").isEqualTo(1000);
    }

    @Test
    @DisplayName("[AC-32] a LIMIT in the query is clamped to the cap, never raised")
    void limitsAreClamped() {
        assertThat(ReportGuard.validate("SELECT campaign.id FROM campaign LIMIT 10", null).query()).endsWith("LIMIT 10");
        assertThat(ReportGuard.validate("SELECT campaign.id FROM campaign LIMIT 999999", null).query()).endsWith("LIMIT 200");
        assertThat(ReportGuard.validate("SELECT campaign.id FROM campaign LIMIT 999999", 800).query()).endsWith("LIMIT 800");
        assertThat(ReportGuard.validate("SELECT campaign.id FROM campaign LIMIT 99999999999999999999", null).limit()).isEqualTo(200);
    }

    @Test
    @DisplayName("[AC-32] anything but one read query on an allowlisted resource is refused")
    void refusesEverythingElse() {
        List<String> bad = List.of(
                "", "   ", "DROP TABLE campaign", "UPDATE campaign SET x = 1", "SELECT * FROM campaign",
                "SELECT campaign.id FROM campaign; SELECT customer.id FROM customer", "SELECT campaign.id FROM campaign -- comment",
                "SELECT campaign.id FROM campaign /* x */", "SELECT campaign.id FROM `campaign`",
                "SELECT customer.id FROM customer", "SELECT billing_setup.id FROM billing_setup", "SELECT account_budget.id FROM account_budget",
                "SELECT user_access.email FROM user_access", "SELECT customer_client.id FROM customer_client",
                "SELECT customer.descriptive_name FROM campaign", "SELECT customer.currency_code, campaign.id FROM campaign",
                "SELECT campaign.id FROM campaign WHERE customer.id = 1", "SELECT campaign.id FROM campaign ORDER BY customer.id",
                "SELECT campaign.id FROM campaign PARAMETERS include_drafts=true", "SELECT metrics.cost_micros / 1000 FROM campaign",
                "SELECT campaign.id FROM conversion_action", "SELECT campaign.id FROM payments_account",
                "SELECT campaign.id FROM " + "campaign ".repeat(400));
        for (String q : bad) {
            assertThatThrownBy(() -> ReportGuard.validate(q, null)).as(q.length() > 80 ? q.substring(0, 80) : q)
                    .isInstanceOf(ReportGuard.Refused.class);
        }
    }

    @Test
    @DisplayName("[AC-32] a refusal says what is wrong")
    void refusalsExplain() {
        assertThatThrownBy(() -> ReportGuard.validate("SELECT customer.id FROM customer", null)).hasMessageContaining("Resource 'customer' is not allowed");
        assertThatThrownBy(() -> ReportGuard.validate("SELECT customer.id FROM campaign", null)).hasMessageContaining("billing or user data");
        assertThatThrownBy(() -> ReportGuard.validate("SELECT a FROM campaign; SELECT b FROM campaign", null)).hasMessageContaining("single plain SELECT");
    }
}

package app.autopilot.config;

import app.autopilot.domain.AutonomyLevel;
import app.autopilot.domain.Goals;
import app.autopilot.domain.Limits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What the server does with application.yml when the deployment sets nothing: it fails closed. */
class ConfigDefaultsTest {

    private Binder binderWith(Map<String, Object> env) throws Exception {
        StandardEnvironment e = new StandardEnvironment();
        e.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        e.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        e.getPropertySources().addFirst(new MapPropertySource("env", env));
        for (PropertySource<?> ps : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))) {
            e.getPropertySources().addLast(ps);
        }
        return new Binder(ConfigurationPropertySources.get(e), new PropertySourcesPlaceholdersResolver(e));
    }

    @Test
    @DisplayName("[AC-07] with nothing configured the server is read-only: autonomy L0, no cap, no owner, no secrets")
    void unsetMeansFailClosed() throws Exception {
        Binder b = binderWith(Map.of());
        Limits l = b.bind("autopilot.limits", Limits.class).get();
        assertThat(l.autonomy()).isEqualTo(AutonomyLevel.L0);
        assertThat(l.monthlyCapMicros()).isNull();
        assertThat(l.capsConfigured()).as("writes stay disabled until the cap and account are set").isFalse();
        assertThat(l.allowedDomains()).isEmpty();
        assertThat(l.allowedGeoTargets()).isEmpty();
        assertThat(l.accountTimeZone()).isEqualTo("Europe/Warsaw");

        Goals g = b.bind("autopilot.goals", Goals.class).get();
        assertThat(g.targetCpaMicros()).isNull();
        assertThat(g.protectedTerms()).isEmpty();
        assertThat(g.conversionActions()).isEmpty();

        AutopilotProperties p = b.bind("autopilot", AutopilotProperties.class).get();
        assertThat(p.killSwitch()).isFalse();
        assertThat(p.security().approvalLinkSecret()).isNullOrEmpty();
        assertThat(p.ads().mode()).isEqualTo("google");
    }

    @Test
    @DisplayName("[AC-01] the PRD's proposed limits are the defaults")
    void prdDefaults() throws Exception {
        Limits l = binderWith(Map.of()).bind("autopilot.limits", Limits.class).get();
        assertThat(l.budgetIncreasePct()).isEqualTo(20);
        assertThat(l.budgetIncreaseIntervalDays()).isEqualTo(7);
        assertThat(l.budgetDecreasePct()).isEqualTo(20);
        assertThat(l.budgetDecreaseIntervalHours()).isEqualTo(24);
        assertThat(l.bidChangePct()).isEqualTo(10);
        assertThat(l.bidIntervalDays()).isEqualTo(7);
        assertThat(l.negativesPerDay()).isEqualTo(25);
        assertThat(l.keywordPauseMinClicks()).isEqualTo(100);
        assertThat(l.keywordPauseWindowDays()).isEqualTo(30);
        assertThat(l.tier1PerDay()).isEqualTo(50);
        assertThat(l.cooldownHours()).isEqualTo(72);
        assertThat(l.conversionLagDays()).isEqualTo(3);
        assertThat(l.proposalLifetimeHours()).isEqualTo(48);
        assertThat(l.dailyAlertMultiple()).isEqualTo(1.5);
        assertThat(l.dailyBreakerMultiple()).isEqualTo(1.8);
        assertThat(l.quotaDailyOps()).isEqualTo(2880);
        assertThat(l.quotaPauseReadsPct()).isEqualTo(80);
    }

    @Test
    @DisplayName("[AC-41] unit economics and the small-budget limits bind from the environment; unset they change nothing")
    void unitEconomicsBind() throws Exception {
        Limits unset = binderWith(Map.of()).bind("autopilot.limits", Limits.class).get();
        assertThat(unset.maxActiveCampaigns()).isZero();
        assertThat(unset.minDailyBudgetMicros()).isZero();
        assertThat(unset.cooldownHours()).isEqualTo(72);
        Goals none = binderWith(Map.of()).bind("autopilot.goals", Goals.class).get();
        assertThat(none.breakEvenCpaMicros()).isNull();

        Binder b = binderWith(Map.of("COOLDOWN_HOURS", "168", "MAX_ACTIVE_CAMPAIGNS", "3", "MIN_DAILY_BUDGET_MICROS", "20000000",
                "VALUE_PER_SALE_MICROS", "38000000", "LIFETIME_VALUE_MICROS", "228000000", "GROSS_MARGIN_PCT", "80"));
        Limits l = b.bind("autopilot.limits", Limits.class).get();
        assertThat(l.cooldownHours()).isEqualTo(168);
        assertThat(l.maxActiveCampaigns()).isEqualTo(3);
        assertThat(l.minDailyBudgetMicros()).isEqualTo(20_000_000L);
        Goals g = b.bind("autopilot.goals", Goals.class).get();
        assertThat(g.valuePerSaleMicros()).isEqualTo(38_000_000L);
        assertThat(g.breakEvenCpaMicros()).as("228 PLN lifetime value x 80%").isEqualTo(182_400_000L);
    }

    @Test
    @DisplayName("[AC-37] environment variables from the deployment bind into limits, goals and settings")
    void environmentVariablesBind() throws Exception {
        Binder b = binderWith(Map.of("ADS_CUSTOMER_ID", "123-456-7890", "MONTHLY_CAP_MICROS", "3000000000", "AUTONOMY_LEVEL", "L2",
                "ALLOWED_DOMAINS", "example.com,shop.example.com", "ALLOWED_GEO_TARGETS", "2616,2276", "TARGET_CPA_MICROS", "20000000",
                "PROTECTED_TERMS", "acme brand,acme", "OWNER_EMAIL", "owner@example.com", "KILL_SWITCH", "true"));
        Limits l = b.bind("autopilot.limits", Limits.class).get();
        assertThat(l.accountId()).isEqualTo("123-456-7890");
        assertThat(l.monthlyCapMicros()).isEqualTo(3_000_000_000L);
        assertThat(l.autonomy()).isEqualTo(AutonomyLevel.L2);
        assertThat(l.allowedDomains()).containsExactly("example.com", "shop.example.com");
        assertThat(l.allowedGeoTargets()).containsExactly(2616L, 2276L);
        assertThat(l.capsConfigured()).isTrue();
        assertThat(b.bind("autopilot.goals", Goals.class).get().protectedTerms()).containsExactly("acme brand", "acme");
        AutopilotProperties p = b.bind("autopilot", AutopilotProperties.class).get();
        assertThat(p.ownerEmail()).isEqualTo("owner@example.com");
        assertThat(p.killSwitch()).isTrue();
    }
}

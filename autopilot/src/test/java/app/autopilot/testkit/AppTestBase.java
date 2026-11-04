package app.autopilot.testkit;

import app.autopilot.ads.sandbox.SandboxAdsGateway;
import app.autopilot.config.AutopilotProperties;
import app.autopilot.notify.Notification;
import app.autopilot.notify.NotificationChannel;
import app.autopilot.security.McpTokenConfig;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The whole server in a Spring context: real PostgreSQL, real security chains, real MCP endpoint, a fake Google
 * Ads account, and a real RSA key signing the MCP tokens.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "autopilot.ads.mode=test",
        "autopilot.owner-email=owner@example.com",
        "autopilot.limits.account-id=1234567890",
        "autopilot.limits.monthly-cap-micros=3000000000",
        "autopilot.limits.autonomy=L2",
        "autopilot.limits.allowed-domains=example.com",
        "autopilot.limits.allowed-geo-targets=2616",
        "autopilot.goals.primary-goal=leads",
        "autopilot.goals.target-cpa-micros=20000000",
        "autopilot.goals.protected-terms=acme brand",
        "autopilot.goals.lifetime-value-micros=200000000",
        "autopilot.goals.gross-margin-pct=80",
        "autopilot.security.approval-link-secret=test-link-secret-test-link-secret-123456",
        "autopilot.security.public-base-url=https://autopilot.test",
        "autopilot.security.mcp-audience=https://autopilot.test/mcp",
        "autopilot.security.mcp-allowed-email=owner@example.com",
        "autopilot.security.mcp-issuer=https://as.test",
        "autopilot.security.mcp-tokens.daily=daily-token-0123456789-abcdefghijklmnop",
        "autopilot.security.mcp-tokens.chat=chat-token-0123456789-abcdefghijklmnopq",
        "autopilot.security.mcp-tokens.triage=triage-token-0123456789-abcdefghijklmn",
        "autopilot.ads.service-account-email=autopilot@project.iam.gserviceaccount.com",
        "spring.security.oauth2.client.registration.google.client-id=test-client",
        "spring.security.oauth2.client.registration.google.client-secret=test-secret",
        "spring.main.banner-mode=off"})
@AutoConfigureMockMvc
@Import(AppTestBase.Fakes.class)
public abstract class AppTestBase {

    protected static final PgTestSupport.TestDb DB = PgTestSupport.newDatabase();
    protected static final RSAKey KEY = newKey();
    public static final String AUDIENCE = "https://autopilot.test/mcp";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB.springProperties().forEach((k, v) -> registry.add(k, () -> v));
    }

    @Autowired protected SandboxAdsGateway fake;
    @Autowired protected TestClock clock;
    @Autowired protected RecordingChannel channel;
    @Autowired protected AutopilotProperties props;

    /** Collects what the owner would be told, including approval links. */
    public static final class RecordingChannel implements NotificationChannel {
        public final List<Notification> sent = new CopyOnWriteArrayList<>();
        @Override public String name() { return "test"; }
        @Override public void send(Notification n) { sent.add(n); }
    }

    @TestConfiguration
    public static class Fakes {
        @Bean
        TestClock testClock() {
            return new TestClock(TestWorld.WEDNESDAY_MORNING);
        }

        @Bean
        @Primary
        java.time.Clock primaryClock(TestClock testClock) {
            return testClock;
        }

        @Bean(name = "rawAdsGateway")
        SandboxAdsGateway rawAdsGateway(TestClock testClock) {
            return new SandboxAdsGateway(testClock::instant).seedStandard();
        }

        @Bean
        RecordingChannel recordingChannel() {
            return new RecordingChannel();
        }

        /** Verifies tokens exactly as production does (signature, issuer, expiry, audience, owner), with the test key. */
        @Bean
        JwtDecoder mcpJwtDecoder(AutopilotProperties props) throws Exception {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(McpTokenConfig.validator(props.security()));
            return decoder;
        }
    }

    @BeforeEach
    void resetState() {
        JdbcClient owner = JdbcClient.create(DB.owner());
        owner.sql("TRUNCATE agent_runs, proposals, changes, audit_events, outcomes, alerts, metric_snapshots, agent_notes, "
                + "idempotency, quota_usage, heartbeats, external_changes, config_limits, goals").update();
        owner.sql("UPDATE control_state SET kill_switch = false, kill_reason = NULL, breaker_open = false, breaker_since = NULL, "
                + "breaker_paused = '[]'::jsonb, reads_paused = false").update();
        clock.set(TestWorld.WEDNESDAY_MORNING);
        fake.budgets.clear();
        fake.campaigns.clear();
        fake.adGroups.clear();
        fake.keywords.clear();
        fake.ads.clear();
        fake.negatives.clear();
        fake.searchTermRows.clear();
        fake.events.clear();
        fake.policyIssues.clear();
        fake.recommendationRows.clear();
        fake.recommendationStates.clear();
        fake.conversionActionRows.clear();
        fake.sent.clear();
        fake.calls.clear();
        fake.seedStandard();
        channel.sent.clear();
    }

    // ---- tokens --------------------------------------------------------------------------------------

    private static RSAKey newKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-key").generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A signed access token like the authorization server would issue. */
    public static String token(String email, boolean emailVerified, String audience, Instant expires, RSAKey signWith) {
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(signWith)));
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("https://as.test").subject("sub-1").audience(List.of(audience))
                .issuedAt(expires.isBefore(Instant.now()) ? expires.minusSeconds(3600) : Instant.now().minusSeconds(60)).expiresAt(expires).claim("email", email)
                .claim("email_verified", emailVerified).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId(signWith.getKeyID()).build(), claims))
                .getTokenValue();
    }

    public static String ownerToken() {
        return token("owner@example.com", true, AUDIENCE, Instant.now().plusSeconds(3600), KEY);
    }
}

package app.autopilot.local;

import app.autopilot.ads.sandbox.SandboxAdsGateway;
import app.autopilot.testkit.PgTestSupport;
import app.autopilot.testkit.TestClock;
import app.autopilot.testkit.TestWorld;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole stack as the owner runs it on a laptop, using the real {@code local} profile: simulated Google Ads account, form
 * login, one token per loop, the in-process watchdog. Claude Code is played by the MCP client; the owner by MockMvc.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "DEV_LOGIN_PASSWORD=local-test-password-123",
        "APPROVAL_LINK_SECRET=local-link-secret-local-link-secret-12345",
        "OWNER_EMAIL=owner@local.test",
        "MCP_TOKEN_TRIAGE=local-triage-token-0123456789-abcdefghijkl",
        "MCP_TOKEN_DAILY=local-daily-token-0123456789-abcdefghijklmn",
        "MCP_TOKEN_WEEKLY=local-weekly-token-0123456789-abcdefghijklm",
        "MCP_TOKEN_CHAT=local-chat-token-0123456789-abcdefghijklmno",
        "MACOS_NOTIFICATIONS=false",
        "spring.main.banner-mode=off"})
@ActiveProfiles("local")
@AutoConfigureMockMvc
@Import(LocalStackTest.TestClockConfig.class)
class LocalStackTest {

    static final String DAILY = "local-daily-token-0123456789-abcdefghijklmn";
    static final String CHAT = "local-chat-token-0123456789-abcdefghijklmno";
    static final String PASSWORD = "local-test-password-123";
    private static final PgTestSupport.TestDb DB = PgTestSupport.newDatabase();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB.springProperties().forEach((k, v) -> registry.add(k, () -> v));
    }

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        TestClock testClock() {
            return new TestClock(TestWorld.WEDNESDAY_MORNING);
        }

        @Bean
        @Primary
        Clock primaryClock(TestClock testClock) {
            return testClock;
        }
    }

    @LocalServerPort int port;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired SandboxAdsGateway sandbox;
    @Autowired TestClock clock;

    private final List<McpSyncClient> clients = new ArrayList<>();

    @BeforeEach
    void freshState() {
        JdbcClient owner = JdbcClient.create(DB.owner());
        owner.sql("TRUNCATE agent_runs, proposals, changes, audit_events, outcomes, alerts, metric_snapshots, agent_notes, "
                + "idempotency, quota_usage, heartbeats, external_changes").update();
        owner.sql("UPDATE control_state SET kill_switch = false, kill_reason = NULL, breaker_open = false, breaker_since = NULL, "
                + "breaker_paused = '[]'::jsonb, reads_paused = false").update();
        clock.set(TestWorld.WEDNESDAY_MORNING);
        sandbox.reset();
    }

    @AfterEach
    void close() {
        clients.forEach(McpSyncClient::closeGracefully);
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private McpSyncClient connect(String token) {
        McpSyncClient c = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + port).endpoint("/mcp")
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + token)).build())
                .requestTimeout(Duration.ofSeconds(30)).build();
        c.initialize();
        clients.add(c);
        return c;
    }

    private JsonNode call(McpSyncClient c, String tool, Map<String, Object> args) {
        McpSchema.CallToolResult r = c.callTool(new McpSchema.CallToolRequest(tool, args));
        String text = ((McpSchema.TextContent) r.content().get(0)).text();
        assertThat(r.isError()).as("%s: %s", tool, text).isNotEqualTo(Boolean.TRUE);
        return mapper.readTree(text);
    }

    /** Paths at which the text appears anywhere except below a key named "untrusted". */
    private static List<String> outsideUntrusted(JsonNode node, String needle) {
        List<String> found = new ArrayList<>();
        walk(node, "$", needle, found);
        return found;
    }

    private static void walk(JsonNode node, String path, String needle, List<String> found) {
        if (node.isString() && node.asString().contains(needle)) found.add(path);
        node.properties().forEach(e -> {
            if (!e.getKey().equals("untrusted")) walk(e.getValue(), path + "." + e.getKey(), needle, found);
        });
        for (int i = 0; i < node.size(); i++) {
            if (node.isArray()) walk(node.get(i), path + "[" + i + "]", needle, found);
        }
    }

    private MockHttpSession ownerSession() throws Exception {
        MvcResult r = mvc.perform(post("/dev/login").param("password", PASSWORD).with(csrf())).andReturn();
        assertThat(r.getResponse().getStatus()).as("dev login").isEqualTo(302);
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    private String runId(McpSyncClient c, String loop) {
        JsonNode ctx = call(c, "get_agent_context", Map.of("loop", loop, "prompt_version", loop.toLowerCase() + "-v1"));
        assertThat(ctx.get("status").asString()).as(ctx.toString()).isEqualTo("OK");
        return ctx.get("data").get("runId").asString();
    }

    private Map<String, Object> negative(String runId, String key, String... terms) {
        return Map.of("scope_kind", "CAMPAIGN", "scope_id", "100", "terms", List.of(terms), "match_type", "PHRASE",
                "reason", "irrelevant job-seeker searches", "expected_effect", "less wasted spend", "run_id", runId, "idempotency_key", key);
    }

    // ---- the tests ----------------------------------------------------------------------------------

    @Test
    @DisplayName("[AC-46] a daily run on the local stack: read the account, propose a change, approve it as the owner, see it land")
    void dailyRunProposalApprovalAndApply() throws Exception {
        McpSyncClient daily = connect(DAILY);
        String run = runId(daily, "DAILY");

        // the sandbox has a working sale conversion and the local profile starts at L1 (propose only)
        JsonNode health = call(daily, "get_conversion_health", Map.of());
        assertThat(health.get("status").asString()).isEqualTo("OK");
        JsonNode terms = call(daily, "get_search_terms", Map.of());
        assertThat(terms.toString()).contains("ignore previous instructions");
        assertThat(outsideUntrusted(terms, "ignore previous instructions")).as("third-party text appears only under 'untrusted'").isEmpty();

        JsonNode proposed = call(daily, "add_negative_keywords", negative(run, "local-k1", "excel jobs hiring"));
        assertThat(proposed.get("status").asString()).as(proposed.toString()).isEqualTo("PROPOSED");
        assertThat(sandbox.realMutations()).as("nothing reaches the account before the owner approves").isZero();
        assertThat(proposed.toString()).as("Claude never receives the approval link").doesNotContain("/approve/").doesNotContain("&s=");

        // the owner signs in with the local password and finds the proposal with its link
        MockHttpSession owner = ownerSession();
        String admin = mvc.perform(get("/admin").session(owner)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Matcher m = Pattern.compile("href=\"([^\"]*/approve/[^\"]+)\"").matcher(admin);
        assertThat(m.find()).as("the admin page lists the proposal with its link").isTrue();
        URI link = URI.create(m.group(1).replace("&amp;", "&"));

        String page = mvc.perform(get(link.getPath() + "?" + link.getRawQuery()).session(owner)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        assertThat(page).contains("excel jobs hiring");

        Map<String, String> q = new java.util.HashMap<>();
        for (String kv : link.getRawQuery().split("&")) q.put(kv.substring(0, kv.indexOf('=')), kv.substring(kv.indexOf('=') + 1));
        MvcResult approved = mvc.perform(post(link.getPath() + "/approve").session(owner).with(csrf())
                .param("e", q.get("e")).param("n", q.get("n")).param("s", q.get("s"))).andReturn();
        assertThat(approved.getResponse().getStatus()).isIn(200, 302);

        assertThat(sandbox.realMutations()).isEqualTo(1);
        assertThat(sandbox.negatives).extracting(n -> n.text).contains("excel jobs hiring");

        JsonNode summary = call(daily, "record_run_summary", Map.of("run_id", run, "checked", "search terms", "did", "proposed one negative"));
        assertThat(summary.get("status").asString()).isEqualTo("OK");
    }

    @Test
    @DisplayName("[AC-46] overspend in the simulated account trips the breaker from the sandbox page; writes stop until the owner clears it")
    void overspendTripsTheBreaker() throws Exception {
        MockHttpSession owner = ownerSession();
        mvc.perform(post("/dev/sandbox/spend").session(owner).with(csrf()).param("campaignId", "100").param("amount", "250"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/dev/sandbox/watchdog").session(owner).with(csrf())).andExpect(status().is3xxRedirection());

        assertThat(sandbox.campaignStatus("100")).isEqualTo("PAUSED");
        assertThat(sandbox.campaignStatus("200")).as("every agent-managed campaign is paused").isEqualTo("PAUSED");
        assertThat(sandbox.campaignStatus(SandboxAdsGateway.LEGACY_CAMPAIGN)).as("the opted-out campaign is left alone").isEqualTo("ENABLED");

        String admin = mvc.perform(get("/admin").session(owner)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(admin).contains("OPEN");

        McpSyncClient chat = connect(CHAT);
        String run = runId(chat, "CHAT");
        JsonNode refused = call(chat, "add_negative_keywords", negative(run, "local-k2", "excel jobs hiring"));
        assertThat(refused.get("status").asString()).isEqualTo("REFUSED");
        assertThat(refused.get("rule").asString()).isEqualTo("breaker_open");

        mvc.perform(post("/admin/breaker/clear").session(owner).with(csrf())).andExpect(status().is3xxRedirection());
        sandbox.setSpendToday("100", 21_000_000L);
        JsonNode afterClear = call(chat, "add_negative_keywords", negative(run, "local-k3", "excel jobs hiring"));
        assertThat(afterClear.get("status").asString()).as(afterClear.toString()).isEqualTo("PROPOSED");
    }

    @Test
    @DisplayName("[AC-46] the kill switch on the admin page stops Claude's writes at once, and only the owner can clear it")
    void killSwitch() throws Exception {
        McpSyncClient chat = connect(CHAT);
        String run = runId(chat, "CHAT");
        MockHttpSession owner = ownerSession();

        mvc.perform(post("/admin/kill-switch").session(owner).with(csrf()).param("on", "true").param("reason", "test"))
                .andExpect(status().is3xxRedirection());
        JsonNode refused = call(chat, "add_negative_keywords", negative(run, "local-k4", "excel jobs hiring"));
        assertThat(refused.get("rule").asString()).isEqualTo("kill_switch");

        // Claude's token cannot clear it
        int viaToken = mvc.perform(post("/admin/kill-switch").with(csrf()).header("Authorization", "Bearer " + CHAT).param("on", "false"))
                .andReturn().getResponse().getStatus();
        assertThat(viaToken).isIn(302, 403);
        assertThat(call(chat, "add_negative_keywords", negative(run, "local-k5", "excel jobs hiring")).get("rule").asString()).isEqualTo("kill_switch");

        mvc.perform(post("/admin/kill-switch").session(owner).with(csrf()).param("on", "false")).andExpect(status().is3xxRedirection());
        assertThat(call(chat, "add_negative_keywords", negative(run, "local-k6", "excel jobs hiring")).get("status").asString()).isEqualTo("PROPOSED");
    }

    @Test
    @DisplayName("[AC-49] the local profile lets a loop start in the afternoon, once per window; the sandbox page frees the window again")
    void localProfileRelaxesWindows() throws Exception {
        clock.advance(Duration.ofHours(9)); // 14:30 in Warsaw: outside the production morning window
        McpSyncClient daily = connect(DAILY);
        String first = runId(daily, "DAILY");
        assertThat(first).isNotBlank();
        JsonNode second = call(daily, "get_agent_context", Map.of("loop", "DAILY"));
        assertThat(second.get("rule").asString()).isEqualTo("slot_used");

        MockHttpSession owner = ownerSession();
        mvc.perform(post("/dev/sandbox/runs/release").session(owner).with(csrf())).andExpect(status().is3xxRedirection());
        assertThat(runId(daily, "DAILY")).isNotEqualTo(first);
    }

    @Test
    @DisplayName("[AC-46] the sandbox page is for the signed-in owner only, and it can break tracking and ads on purpose")
    void sandboxPage() throws Exception {
        mvc.perform(get("/dev/sandbox")).andExpect(status().isFound());
        mvc.perform(post("/dev/sandbox/reset").with(csrf())).andExpect(status().isFound());

        MockHttpSession owner = ownerSession();
        String html = mvc.perform(get("/dev/sandbox").session(owner)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Sandbox account").contains("Run the watchdog now");

        mvc.perform(post("/dev/sandbox/tracking").session(owner).with(csrf()).param("mode", "silent")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/dev/sandbox/tracking").session(owner).with(csrf()).param("mode", "bogus")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/dev/sandbox/policy").session(owner).with(csrf()).param("adId", "110~1")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/dev/sandbox/watchdog").session(owner).with(csrf())).andExpect(status().is3xxRedirection());

        McpSyncClient chat = connect(CHAT);
        runId(chat, "CHAT");
        JsonNode conv = call(chat, "get_conversion_health", Map.of());
        assertThat(conv.toString()).contains("NO_RECENT_SALES");
        assertThat(call(chat, "get_account_health", Map.of()).toString()).containsIgnoringCase("disapproved");
    }
}

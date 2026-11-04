package app.autopilot.security;

import app.autopilot.testkit.AppTestBase;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The per-loop API tokens over the real MCP endpoint: Claude Code on the owner's own machine uses these instead of OAuth. */
class McpTokenWireTest extends AppTestBase {

    static final String DAILY = "daily-token-0123456789-abcdefghijklmnop";
    static final String CHAT = "chat-token-0123456789-abcdefghijklmnopq";
    static final String TRIAGE = "triage-token-0123456789-abcdefghijklmn";

    @LocalServerPort int port;
    @Autowired JsonMapper mapper;
    @Autowired MockMvc mvc;

    private final List<McpSyncClient> clients = new ArrayList<>();

    @AfterEach
    void close() {
        clients.forEach(McpSyncClient::closeGracefully);
    }

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

    private int httpStatus(String uri, String header) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + uri))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"));
        if (header != null) b.header("Authorization", header);
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    /** A plain HTTP client that reads each response to its end, like any strict client; the MCP SDK client is more forgiving. */
    private String rawPost(String token, String sessionId, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer " + token).timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (sessionId != null) b.header("Mcp-Session-Id", sessionId);
        HttpResponse<String> r = HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(body).isIn(200, 202);
        return (sessionId == null ? r.headers().firstValue("Mcp-Session-Id").orElse("") + "\n" : "") + r.body();
    }

    @Test
    @DisplayName("[AC-43] a tool listing over a loop token is delivered completely, to a client that reads the stream to its end")
    void streamedResponseIsComplete() throws Exception {
        String init = rawPost(DAILY, null, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\","
                + "\"capabilities\":{},\"clientInfo\":{\"name\":\"raw\",\"version\":\"0\"}}}");
        String session = init.substring(0, init.indexOf('\n'));
        assertThat(session).isNotBlank();
        rawPost(DAILY, session, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        String listing = rawPost(DAILY, session, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        assertThat(listing).contains("get_agent_context").contains("add_ad_group");
    }

    @Test
    @DisplayName("[AC-43] a loop token authenticates; a wrong, near-miss or URL-borne token does not")
    void authentication() throws Exception {
        assertThat(httpStatus("/mcp", "Bearer " + DAILY)).isNotIn(401, 403);
        assertThat(httpStatus("/mcp", "Bearer " + DAILY + "x")).isEqualTo(401);
        assertThat(httpStatus("/mcp", "Bearer " + DAILY.substring(1))).isEqualTo(401);
        assertThat(httpStatus("/mcp", "Basic " + DAILY)).isEqualTo(401);
        assertThat(httpStatus("/mcp", null)).isEqualTo(401);
        assertThat(httpStatus("/mcp?token=" + DAILY, null)).as("a token in the URL is never read").isEqualTo(401);
        assertThat(httpStatus("/mcp?access_token=" + DAILY, null)).isEqualTo(401);
    }

    @Test
    @DisplayName("[AC-43] a token only starts runs of its own loop")
    void tokenBindsTheLoop() {
        McpSyncClient daily = connect(DAILY);
        for (String other : List.of("TRIAGE", "WEEKLY", "CHAT")) {
            JsonNode r = call(daily, "get_agent_context", Map.of("loop", other));
            assertThat(r.get("status").asString()).as(other).isEqualTo("REFUSED");
            assertThat(r.get("rule").asString()).isEqualTo("loop_not_allowed_for_token");
            assertThat(r.get("message").asString()).contains("DAILY");
        }
        JsonNode ok = call(daily, "get_agent_context", Map.of("loop", "DAILY"));
        assertThat(ok.get("status").asString()).isEqualTo("OK");
        assertThat(ok.get("data").get("loop").asString()).isEqualTo("DAILY");

        McpSyncClient chat = connect(CHAT);
        JsonNode refused = call(chat, "get_agent_context", Map.of("loop", "DAILY"));
        assertThat(refused.get("rule").asString()).isEqualTo("loop_not_allowed_for_token");
        assertThat(call(chat, "get_agent_context", Map.of("loop", "chat")).get("status").asString()).isEqualTo("OK");
        assertThat(call(connect(TRIAGE), "get_agent_context", Map.of("loop", "TRIAGE")).get("status").asString()).isEqualTo("OK");
    }

    @Test
    @DisplayName("[AC-43] a token cannot use, continue or close another loop's run, even knowing its id")
    void runsAreNotShared() {
        String dailyRun = call(connect(DAILY), "get_agent_context", Map.of("loop", "DAILY")).get("data").get("runId").asString();
        McpSyncClient chat = connect(CHAT);

        JsonNode change = call(chat, "decrease_budget", Map.of("budget_id", "B1", "new_amount_micros", 45_000_000L, "reason", "r",
                "expected_effect", "e", "run_id", dailyRun, "idempotency_key", "k-steal"));
        assertThat(change.get("rule").asString()).isEqualTo("run_not_yours");
        assertThat(fake.realMutations()).isZero();

        assertThat(call(chat, "get_agent_context", Map.of("loop", "CHAT", "run_id", dailyRun)).get("rule").asString()).isEqualTo("run_not_yours");
        assertThat(call(chat, "record_run_summary", Map.of("run_id", dailyRun, "checked", "x", "did", "y")).get("rule").asString())
                .isEqualTo("run_not_yours");
        assertThat(call(chat, "propose_rollback", Map.of("change_id", java.util.UUID.randomUUID().toString(), "reason", "r",
                "expected_effect", "e", "run_id", dailyRun, "idempotency_key", "k-rb")).get("rule").asString()).isEqualTo("run_not_yours");

        // the owner of that run can still use it
        JsonNode applied = call(connect(DAILY), "decrease_budget", Map.of("budget_id", "B1", "new_amount_micros", 45_000_000L, "reason", "r",
                "expected_effect", "e", "run_id", dailyRun, "idempotency_key", "k-own"));
        assertThat(applied.get("status").asString()).isEqualTo("APPLIED");
        assertThat(call(connect(DAILY), "record_run_summary", Map.of("run_id", dailyRun, "checked", "x", "did", "y")).get("status").asString()).isEqualTo("OK");
    }

    @Test
    @DisplayName("[AC-43] the owner signed in with OAuth is not bound to a loop; the transport context is not part of any tool's schema")
    void oauthCallerIsNotBoundAndContextIsHidden() {
        McpSyncClient owner = connect(ownerToken());
        assertThat(call(owner, "get_agent_context", Map.of("loop", "TRIAGE")).get("status").asString()).isEqualTo("OK");
        assertThat(call(owner, "get_agent_context", Map.of("loop", "WEEKLY")).get("rule").asString()).as("window rules still apply").isEqualTo("outside_window");

        for (McpSchema.Tool t : owner.listTools().tools()) {
            Map<?, ?> props = (Map<?, ?>) t.inputSchema().get("properties");
            for (String hidden : List.of("context", "transportContext", "exchange")) {
                assertThat(props.containsKey(hidden)).as("%s must not expose %s", t.name(), hidden).isFalse();
            }
            assertThat(String.valueOf(props)).as("%s schema", t.name()).doesNotContain("McpTransportContext");
        }
    }

    @Test
    @DisplayName("[AC-12][AC-43] a loop token is worthless on the approval and admin pages")
    void tokenIsWorthlessOnOwnerPages() throws Exception {
        mvc.perform(get("/admin").header("Authorization", "Bearer " + DAILY)).andExpect(status().is3xxRedirection());
        mvc.perform(get("/approve/" + java.util.UUID.randomUUID()).header("Authorization", "Bearer " + CHAT)).andExpect(status().is3xxRedirection());
        int killSwitch = mvc.perform(post("/admin/kill-switch").param("on", "true").header("Authorization", "Bearer " + DAILY)).andReturn().getResponse().getStatus();
        assertThat(killSwitch).as("never served: sent to sign-in or refused").isIn(302, 403);
        assertThat(fake.realMutations()).isZero();
    }
}

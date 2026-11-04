package app.autopilot.security;

import app.autopilot.config.AutopilotProperties;
import app.autopilot.domain.Loop;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpTokensTest {

    private static final String DAILY = "daily-token-0123456789-abcdefghijklmnop";
    private static final String CHAT = "chat-token-0123456789-abcdefghijklmnopq";

    private static McpTokens tokens(Map<String, String> map) {
        AutopilotProperties.Security s = new AutopilotProperties.Security(null, null, null, null, null, null, map, false, null);
        return new McpTokens(new AutopilotProperties("owner@example.com", false, null, s, null, null, null, null));
    }

    @Test
    @DisplayName("[AC-43] each token belongs to exactly one loop, and anything else matches nothing")
    void matchesOnlyItsOwnLoop() {
        McpTokens t = tokens(Map.of("daily", DAILY, "chat", CHAT));
        assertThat(t.match(DAILY)).contains(Loop.DAILY);
        assertThat(t.match(CHAT)).contains(Loop.CHAT);
        assertThat(t.match(DAILY + "x")).isEmpty();
        assertThat(t.match(DAILY.substring(1))).isEmpty();
        assertThat(t.match(DAILY.toUpperCase())).isEmpty();
        assertThat(t.match("")).isEmpty();
        assertThat(t.match(null)).isEmpty();
        assertThat(t.any()).isTrue();
        assertThat(tokens(Map.of()).any()).isFalse();
        assertThat(tokens(Map.of()).match(DAILY)).isEmpty();
    }

    @Test
    @DisplayName("[AC-43] loop names are case-insensitive and unset (blank) tokens are ignored")
    void caseAndBlanks() {
        Map<String, String> m = new HashMap<>();
        m.put("Daily", DAILY);
        m.put("weekly", "");
        m.put("triage", "   ");
        McpTokens t = tokens(m);
        assertThat(t.match(DAILY)).contains(Loop.DAILY);
        assertThat(t.any()).isTrue();
    }

    @Test
    @DisplayName("[AC-43] the server refuses to start with a short token, a duplicate token or an unknown loop")
    void invalidConfigurationFailsAtStartup() {
        assertThatThrownBy(() -> tokens(Map.of("daily", "short"))).isInstanceOf(IllegalStateException.class).hasMessageContaining("shorter than 32");
        assertThatThrownBy(() -> tokens(Map.of("daily", DAILY, "chat", DAILY))).isInstanceOf(IllegalStateException.class).hasMessageContaining("same MCP token");
        assertThatThrownBy(() -> tokens(Map.of("monthly", DAILY))).isInstanceOf(IllegalStateException.class).hasMessageContaining("unknown loop 'monthly'");
    }
}

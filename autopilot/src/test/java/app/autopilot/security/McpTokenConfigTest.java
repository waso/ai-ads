package app.autopilot.security;

import app.autopilot.config.AutopilotProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpTokenConfigTest {

    @Test
    @DisplayName("[AC-43] with no authorization server configured an OAuth token is refused as an invalid token (401), never as a server error")
    void failClosedIsAnInvalidToken() {
        var security = new AutopilotProperties.Security(null, null, null, null, null, null, Map.of(), false, null);
        var props = new AutopilotProperties("owner@example.com", false, null, security, null, null, null, null);
        var decoder = new McpTokenConfig().mcpJwtDecoder(props);
        assertThatThrownBy(() -> decoder.decode("anything")).isInstanceOf(BadJwtException.class);
        assertThatThrownBy(() -> decoder.decode("a.b.c")).isInstanceOf(BadJwtException.class);
    }
}

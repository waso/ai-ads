package app.autopilot.security;

import app.autopilot.mcp.LoopBinding;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

/**
 * The MCP Streamable HTTP transport, defined here instead of by auto-configuration for two reasons. A context extractor copies
 * the loop a per-loop API token is bound to into every tool call, so tools can enforce it. And the number of live MCP sessions
 * is bounded and idle ones expire, so a client that never closes its sessions cannot exhaust the server.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class McpTransportConfig {

    static final long MAX_SESSIONS = 50;
    static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);

    @Bean
    WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider(JsonMapper jsonMapper,
                                                                                    McpServerStreamableHttpProperties props) {
        WebMvcStreamableServerTransportProvider.Builder b = WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
                .mcpEndpoint(props.getMcpEndpoint())
                .disallowDelete(props.isDisallowDelete())
                .maxSessions(MAX_SESSIONS)
                .sessionIdleTimeout(IDLE_TIMEOUT)
                .contextExtractor(request -> {
                    Authentication a = SecurityContextHolder.getContext().getAuthentication();
                    return a instanceof McpTokenAuthentication t ? LoopBinding.of(t.loop()) : McpTransportContext.EMPTY;
                });
        if (props.getKeepAliveInterval() != null) {
            b.keepAliveInterval(props.getKeepAliveInterval());
        }
        return b.build();
    }
}

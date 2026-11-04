package app.autopilot.security;

import app.autopilot.domain.Loop;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

/** A request that presented one of the per-loop API tokens. */
public final class McpTokenAuthentication extends AbstractAuthenticationToken {

    private final Loop loop;

    McpTokenAuthentication(Loop loop) {
        super(AuthorityUtils.createAuthorityList("ROLE_MCP_TOKEN"));
        this.loop = loop;
        setAuthenticated(true);
    }

    public Loop loop() {
        return loop;
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public Object getPrincipal() {
        return "mcp-token:" + loop.name().toLowerCase();
    }
}

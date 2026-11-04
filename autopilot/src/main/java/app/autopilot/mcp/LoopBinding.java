package app.autopilot.mcp;

import app.autopilot.domain.Loop;
import io.modelcontextprotocol.common.McpTransportContext;

import java.util.Optional;

/**
 * Which loop the caller's credential is for. A per-loop API token binds a request to one loop; an OAuth caller (the owner
 * signed in) is not bound and may start any loop. The transport copies the binding into the MCP transport context, and
 * tools read it here, so a token for the daily loop can never start or continue a triage or chat run.
 */
public final class LoopBinding {
    private LoopBinding() {}

    public static final String KEY = "autopilot.loop";

    public static McpTransportContext of(Loop loop) {
        return McpTransportContext.create(java.util.Map.of(KEY, loop.name()));
    }

    public static Optional<Loop> boundLoop(McpTransportContext context) {
        Object v = context == null ? null : context.get(KEY);
        if (v == null) return Optional.empty();
        try {
            return Optional.of(Loop.valueOf(v.toString()));
        } catch (IllegalArgumentException e) {
            // an unreadable binding is treated as the most restrictive one: nothing may run
            return Optional.of(Loop.CHAT);
        }
    }
}

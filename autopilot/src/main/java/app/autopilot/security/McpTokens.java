package app.autopilot.security;

import app.autopilot.config.AutopilotProperties;
import app.autopilot.domain.Loop;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The static per-loop API tokens. Each is a long random secret for one loop. Comparison is constant-time (over SHA-256
 * digests), so a wrong guess reveals nothing through timing.
 */
@Component
public class McpTokens {

    public static final int MIN_LENGTH = 32;

    private final Map<Loop, byte[]> digests = new EnumMap<>(Loop.class);

    public McpTokens(AutopilotProperties props) {
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, String> e : props.security().mcpTokens().entrySet()) {
            String token = e.getValue() == null ? "" : e.getValue().strip();
            if (token.isEmpty()) continue; // unset
            Loop loop;
            try {
                loop = Loop.valueOf(e.getKey().strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException("autopilot.security.mcp-tokens has an unknown loop '" + e.getKey()
                        + "'; use triage, daily, weekly or chat");
            }
            if (token.length() < MIN_LENGTH) {
                throw new IllegalStateException("The MCP token for the " + loop + " loop is shorter than " + MIN_LENGTH + " characters");
            }
            if (!seen.add(token)) {
                throw new IllegalStateException("Two loops share the same MCP token; each loop needs its own");
            }
            digests.put(loop, sha256(token));
        }
    }

    @PostConstruct
    void logConfigured() {
        // nothing secret is logged: only which loops have a token
        org.slf4j.LoggerFactory.getLogger(McpTokens.class).info("MCP API tokens configured for loops: {}", digests.keySet());
    }

    public boolean any() {
        return !digests.isEmpty();
    }

    /** The loop this token belongs to, if it is one of ours. */
    public Optional<Loop> match(String presented) {
        if (presented == null || presented.isEmpty() || digests.isEmpty()) return Optional.empty();
        byte[] candidate = sha256(presented);
        Loop found = null;
        for (Map.Entry<Loop, byte[]> e : digests.entrySet()) {
            // compare against every token, without stopping early
            if (MessageDigest.isEqual(candidate, e.getValue())) found = e.getKey();
        }
        return Optional.ofNullable(found);
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

package app.autopilot.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Authenticates a request that carries one of the per-loop API tokens as {@code Authorization: Bearer <token>}. Anything
 * else is left for the OAuth (JWT) path, or refused. The token is read only from the header, never from the URL.
 */
final class McpTokenFilter extends OncePerRequestFilter {

    private final McpTokens tokens;
    private final BearerTokenResolver resolver = new DefaultBearerTokenResolver();
    private final SecurityContextRepository contexts = new RequestAttributeSecurityContextRepository();

    McpTokenFilter(McpTokens tokens) {
        this.tokens = tokens;
    }

    /** A resolver for the JWT filter that steps aside for our own tokens, so they are not parsed as JWTs. */
    BearerTokenResolver jwtResolver() {
        return request -> {
            String presented = headerToken(request);
            return presented != null && tokens.match(presented).isPresent() ? null : resolver.resolve(request);
        };
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = headerToken(request);
        if (presented != null) {
            tokens.match(presented).ifPresent(loop -> {
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(new McpTokenAuthentication(loop));
                SecurityContextHolder.setContext(context);
                // Kept on the request as well: a streamed (server-sent events) response is authorized again when it completes,
                // and without this it finds an anonymous caller, is denied after the data was sent and cuts the stream short.
                contexts.saveContext(context, request, response);
            });
        }
        chain.doFilter(request, response);
    }

    private static String headerToken(HttpServletRequest request) {
        String h = request.getHeader("Authorization");
        if (h == null || !h.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String t = h.substring(7).strip();
        return t.isEmpty() ? null : t;
    }
}

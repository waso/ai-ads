package app.autopilot.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import app.autopilot.config.AutopilotProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * Two worlds that never mix:
 * <ul>
 *   <li><b>/mcp</b>: stateless, bearer tokens issued to the Claude connector for the owner's Google account. A
 *       token that is valid here is worthless on the other chain.</li>
 *   <li><b>/approve, /admin</b>: a browser session from the owner's verified Google sign-in, with CSRF protection.
 *       Bearer tokens are not accepted at all, so Claude holds nothing that can approve a proposal.</li>
 * </ul>
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain mcpChain(HttpSecurity http, AutopilotProperties props, JwtDecoder mcpJwtDecoder, McpTokens tokens) throws Exception {
        McpTokenFilter tokenFilter = new McpTokenFilter(tokens);
        BearerTokenAuthenticationEntryPoint entryPoint = new BearerTokenAuthenticationEntryPoint();
        String base = props.security().publicBaseUrl() == null ? "" : props.security().publicBaseUrl().replaceAll("/+$", "");
        entryPoint.setResourceMetadataParameterResolver(request -> base + "/.well-known/oauth-protected-resource");
        http.securityMatcher("/mcp", "/mcp/**", "/.well-known/oauth-protected-resource")
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.GET, "/.well-known/oauth-protected-resource").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(tokenFilter, org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class)
                .oauth2ResourceServer(o -> o.jwt(j -> j.decoder(mcpJwtDecoder)).bearerTokenResolver(tokenFilter.jwtResolver()).authenticationEntryPoint(entryPoint)
                        // RFC 9728 metadata, so an MCP client can find the authorization server for this resource
                        .protectedResourceMetadata(m -> m.protectedResourceMetadataCustomizer(b -> {
                            b.resource(props.security().mcpAudience() != null ? props.security().mcpAudience() : base + "/mcp");
                            if (props.security().mcpIssuer() != null) b.authorizationServer(props.security().mcpIssuer());
                            b.bearerMethod("header");
                        })))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain ownerChain(HttpSecurity http, AutopilotProperties props) throws Exception {
        boolean devLogin = props.security().devLogin();
        http.securityMatcher("/approve/**", "/admin/**", "/dev/**", "/login/**", "/oauth2/**", "/login", "/logout")
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/login/**", "/oauth2/**", "/login", "/dev/login").permitAll()
                        .anyRequest().access(ownerOnly(props)))
                .logout(Customizer.withDefaults())
                .headers(h -> h.frameOptions(f -> f.deny()).contentSecurityPolicy(c -> c.policyDirectives(
                        "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")));
        if (devLogin) {
            // local use only (DevLoginCheck refuses to start unless the server listens on a loopback address)
            http.exceptionHandling(e -> e.authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/dev/login")));
        } else {
            http.oauth2Login(Customizer.withDefaults());
        }
        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain restChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().denyAll())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    /** The signed-in user must be the owner's Google account with a verified email address. */
    static AuthorizationManager<RequestAuthorizationContext> ownerOnly(AutopilotProperties props) {
        return (authentication, context) -> {
            Authentication a = authentication.get();
            if (a == null || !a.isAuthenticated() || !(a.getPrincipal() instanceof OAuth2User user)) {
                return new AuthorizationDecision(false);
            }
            Object email = user.getAttributes().get("email");
            Object verified = user.getAttributes().get("email_verified");
            boolean owner = props.ownerEmail() != null && !props.ownerEmail().isBlank() && email != null
                    && props.ownerEmail().equalsIgnoreCase(email.toString())
                    && Boolean.TRUE.equals(verified instanceof String s ? Boolean.valueOf(s) : verified);
            return new AuthorizationDecision(owner);
        };
    }
}

package app.autopilot.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import app.autopilot.config.AutopilotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Validates MCP access tokens: signature from the authorization server's keys, issuer, expiry, the audience of
 * this server, and the owner's identity. With no authorization server configured, every token is refused, so a
 * missing setting fails closed.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Configuration
public class McpTokenConfig {

    private static final Logger log = LoggerFactory.getLogger(McpTokenConfig.class);

    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    JwtDecoder mcpJwtDecoder(AutopilotProperties props) {
        AutopilotProperties.Security s = props.security();
        if (s.mcpJwkSetUri() == null || s.mcpJwkSetUri().isBlank()) {
            log.warn("autopilot.security.mcp-jwk-set-uri is not set: every MCP request will be refused until an "
                    + "authorization server is configured");
            return token -> { throw new BadJwtException("The MCP authorization server is not configured"); };
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(s.mcpJwkSetUri()).build();
        decoder.setJwtValidator(validator(s));
        return decoder;
    }

    public static OAuth2TokenValidator<Jwt> validator(AutopilotProperties.Security s) {
        return new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(),
                s.mcpIssuer() == null ? jwt -> OAuth2TokenValidatorResult.success() : new JwtIssuerValidator(s.mcpIssuer()),
                audience(s.mcpAudience()), owner(s.mcpAllowedEmail()));
    }

    private static OAuth2TokenValidator<Jwt> audience(String expected) {
        return jwt -> expected != null && !expected.isBlank() && jwt.getAudience() != null && jwt.getAudience().contains(expected)
                ? OAuth2TokenValidatorResult.success()
                : failure("The token is not meant for this server (audience).");
    }

    private static OAuth2TokenValidator<Jwt> owner(String allowedEmail) {
        return jwt -> {
            String email = jwt.getClaimAsString("email");
            boolean verified = Boolean.TRUE.equals(jwt.getClaim("email_verified"));
            return allowedEmail != null && !allowedEmail.isBlank() && email != null && allowedEmail.equalsIgnoreCase(email) && verified
                    ? OAuth2TokenValidatorResult.success()
                    : failure("The token does not belong to the owner's account.");
        };
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", description, null));
    }
}

package app.autopilot.security;

import app.autopilot.config.AutopilotProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * LOCAL USE ONLY. Signs the owner in with a password from server configuration so the approval and admin pages work on a
 * laptop without a Google OAuth client. Only requests from a loopback address are served, wrong passwords lock the form for
 * a minute after five tries, and the resulting session is the same one Google sign-in would produce (an OAuth2 principal with the
 * owner's verified email), so every other check stays unchanged.
 */
@Controller
@ConditionalOnProperty(prefix = "autopilot.security", name = "dev-login", havingValue = "true")
class DevLoginController {

    private static final Logger log = LoggerFactory.getLogger(DevLoginController.class);
    static final int MAX_FAILURES = 5;
    static final Duration LOCK = Duration.ofMinutes(1);

    private final AutopilotProperties props;
    private final Clock clock;
    private final SecurityContextRepository contexts = new HttpSessionSecurityContextRepository();

    private int failures;
    private Instant lockedUntil = Instant.MIN;

    DevLoginController(AutopilotProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    @GetMapping("/dev/login")
    String form(HttpServletRequest request, Model model) {
        requireLoopback(request);
        model.addAttribute("owner", props.ownerEmail());
        return "dev-login";
    }

    @PostMapping("/dev/login")
    String login(@RequestParam String password, HttpServletRequest request, HttpServletResponse response, Model model) {
        requireLoopback(request);
        model.addAttribute("owner", props.ownerEmail());
        if (locked()) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            model.addAttribute("error", "Too many wrong passwords. Wait a minute.");
            return "dev-login";
        }
        if (!matches(password)) {
            recordFailure();
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            model.addAttribute("error", "Wrong password.");
            return "dev-login";
        }
        reset();
        if (request.getSession(false) != null) request.changeSessionId();
        List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_OWNER"));
        var user = new DefaultOAuth2User(authorities,
                Map.of("email", props.ownerEmail(), "email_verified", true, "name", "Local owner"), "email");
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new OAuth2AuthenticationToken(user, authorities, "dev"));
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        log.info("Dev login: owner signed in from {}", request.getRemoteAddr());
        return "redirect:/admin";
    }

    private boolean matches(String given) {
        String expected = props.security().devLoginPassword();
        if (given == null || expected == null) return false;
        return MessageDigest.isEqual(sha256(given), sha256(expected));
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private synchronized boolean locked() {
        return clock.instant().isBefore(lockedUntil);
    }

    private synchronized void recordFailure() {
        if (++failures >= MAX_FAILURES) {
            lockedUntil = clock.instant().plus(LOCK);
            failures = 0;
            log.warn("Dev login: {} wrong passwords, locked for {}", MAX_FAILURES, LOCK);
        }
    }

    private synchronized void reset() {
        failures = 0;
        lockedUntil = Instant.MIN;
    }

    /** Defence in depth next to the startup check: even if the server were reachable, only the machine itself may use this form. */
    static void requireLoopback(HttpServletRequest request) {
        try {
            if (!InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "local use only");
            }
        } catch (UnknownHostException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "local use only");
        }
    }
}

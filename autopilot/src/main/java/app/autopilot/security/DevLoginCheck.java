package app.autopilot.security;

import app.autopilot.config.AutopilotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * The form login for the approval and admin pages replaces Google sign-in, so it must never be reachable from outside the
 * machine. The server refuses to start with it on unless it is bound to a loopback address, the owner is configured and the
 * password is long enough to not be a placeholder.
 */
@Component
@ConditionalOnProperty(prefix = "autopilot.security", name = "dev-login", havingValue = "true")
class DevLoginCheck implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(DevLoginCheck.class);
    static final int MIN_PASSWORD_LENGTH = 12;

    private final Environment env;
    private final AutopilotProperties props;

    DevLoginCheck(Environment env, AutopilotProperties props) {
        this.env = env;
        this.props = props;
    }

    @Override
    public void afterPropertiesSet() {
        validate(env.getProperty("server.address"), props.ownerEmail(), props.security().devLoginPassword());
        log.warn("DEV LOGIN is on: the approval and admin pages accept a local password instead of Google sign-in. Listening on {} only.",
                env.getProperty("server.address"));
    }

    /** Throws {@link IllegalStateException} with the reason when the combination is unsafe. */
    static void validate(String serverAddress, String ownerEmail, String password) {
        if (!isLoopback(serverAddress)) {
            throw new IllegalStateException("autopilot.security.dev-login needs server.address set to a loopback address "
                    + "(127.0.0.1 or ::1), but it is '" + (serverAddress == null ? "" : serverAddress)
                    + "'. Without it the server would listen on every interface and accept this password from your network.");
        }
        if (ownerEmail == null || ownerEmail.isBlank()) {
            throw new IllegalStateException("autopilot.security.dev-login needs OWNER_EMAIL.");
        }
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException("autopilot.security.dev-login-password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }
    }

    /** Literal loopback addresses and "localhost" only; nothing is resolved through DNS. */
    static boolean isLoopback(String address) {
        if (address == null || address.isBlank()) return false;
        String a = address.strip();
        if (a.equalsIgnoreCase("localhost")) return true;
        if (a.startsWith("[") && a.endsWith("]")) a = a.substring(1, a.length() - 1);
        boolean ipv4 = a.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        boolean ipv6 = a.contains(":") && a.matches("[0-9a-fA-F:.]+");
        if (!ipv4 && !ipv6) return false;
        try {
            return InetAddress.getByName(a).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}

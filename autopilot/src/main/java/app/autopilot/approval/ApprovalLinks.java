package app.autopilot.approval;

import app.autopilot.config.AutopilotProperties;
import app.autopilot.store.ProposalStore.Proposal;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Signed approval links. A link carries the proposal id, its expiry and a random nonce, signed with
 * HMAC-SHA256 under a secret only the server has. Opening it (GET) changes nothing; deciding needs a POST
 * from the owner's signed-in session, and consumes the nonce, so a link works once.
 *
 * <p>No MCP tool builds or returns a link: they are produced here and sent to the owner directly.
 */
@Component
public class ApprovalLinks {

    private final AutopilotProperties props;

    public ApprovalLinks(AutopilotProperties props) {
        this.props = props;
    }

    /** The three query parameters of a link. */
    public record Token(long expiresAt, String nonce, String signature) {}

    public boolean configured() {
        String secret = props.security().approvalLinkSecret();
        return secret != null && secret.length() >= 32;
    }

    public Token token(Proposal p) {
        long exp = p.expiresAt().getEpochSecond();
        return new Token(exp, p.nonce(), sign(p.id(), exp, p.nonce()));
    }

    public String url(Proposal p) {
        Token t = token(p);
        String base = props.security().publicBaseUrl() == null ? "" : props.security().publicBaseUrl().replaceAll("/+$", "");
        return base + "/approve/" + p.id() + "?e=" + t.expiresAt() + "&n=" + t.nonce() + "&s=" + t.signature();
    }

    /** True when the signature matches and the link has not expired. Constant-time comparison. */
    public boolean valid(UUID id, long expiresAt, String nonce, String signature, Instant now) {
        if (!configured() || nonce == null || signature == null) return false;
        if (now.getEpochSecond() >= expiresAt) return false;
        byte[] expected = sign(id, expiresAt, nonce).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(UUID id, long expiresAt, String nonce) {
        if (!configured()) throw new IllegalStateException("autopilot.security.approval-link-secret must be set (32+ characters)");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.security().approvalLinkSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal((id + "|" + expiresAt + "|" + nonce).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}

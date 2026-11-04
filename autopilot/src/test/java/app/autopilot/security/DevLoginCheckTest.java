package app.autopilot.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DevLoginCheckTest {

    private static final String GOOD_PASSWORD = "twelve-chars-or-more";

    @Test
    @DisplayName("[AC-45] the dev login starts only when the server listens on a loopback address")
    void loopbackOnly() {
        for (String ok : new String[] {"127.0.0.1", "127.1.2.3", "::1", "[::1]", "0:0:0:0:0:0:0:1", "localhost", "LOCALHOST", " 127.0.0.1 "}) {
            assertThatCode(() -> DevLoginCheck.validate(ok, "o@x.test", GOOD_PASSWORD)).as(ok).doesNotThrowAnyException();
        }
        for (String bad : new String[] {null, "", "  ", "0.0.0.0", "::", "192.168.1.20", "10.0.0.5", "example.com", "127.0.0.1.evil.com",
                "localhost.evil.com", "*", "[::]", "8.8.8.8", "999.1.1.1"}) {
            assertThatThrownBy(() -> DevLoginCheck.validate(bad, "o@x.test", GOOD_PASSWORD)).as("address '%s'", bad)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("loopback");
        }
    }

    @Test
    @DisplayName("[AC-45] the dev login needs an owner and a password of at least 12 characters")
    void ownerAndPassword() {
        assertThatThrownBy(() -> DevLoginCheck.validate("127.0.0.1", null, GOOD_PASSWORD)).hasMessageContaining("OWNER_EMAIL");
        assertThatThrownBy(() -> DevLoginCheck.validate("127.0.0.1", " ", GOOD_PASSWORD)).hasMessageContaining("OWNER_EMAIL");
        assertThatThrownBy(() -> DevLoginCheck.validate("127.0.0.1", "o@x.test", null)).hasMessageContaining("password");
        assertThatThrownBy(() -> DevLoginCheck.validate("127.0.0.1", "o@x.test", "short")).hasMessageContaining("12");
        assertThatThrownBy(() -> DevLoginCheck.validate("127.0.0.1", "o@x.test", "x".repeat(11))).hasMessageContaining("12");
        assertThat(DevLoginCheck.isLoopback("127.0.0.1")).isTrue();
        assertThatCode(() -> DevLoginCheck.validate("127.0.0.1", "o@x.test", "x".repeat(12))).doesNotThrowAnyException();
    }
}

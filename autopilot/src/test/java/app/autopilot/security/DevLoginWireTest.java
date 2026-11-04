package app.autopilot.security;

import app.autopilot.testkit.AppTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The local password login for the approval and admin pages, with the real security chains. */
@TestPropertySource(properties = {
        "autopilot.security.dev-login=true",
        "autopilot.security.dev-login-password=local-dev-password-123",
        "server.address=127.0.0.1"})
class DevLoginWireTest extends AppTestBase {

    private static final String PASSWORD = "local-dev-password-123";

    @Autowired MockMvc mvc;

    /** The login form keeps its failure count between requests, so every test starts from a clean, unlocked form. */
    @BeforeEach
    void unlockForm() throws Exception {
        clock.advance(Duration.ofMinutes(10));
        assertThat(login(PASSWORD, new MockHttpSession(), "127.0.0.1").getResponse().getStatus()).isEqualTo(302);
    }

    private MvcResult login(String password, MockHttpSession session, String remoteAddr) throws Exception {
        return mvc.perform(post("/dev/login").param("password", password).with(csrf()).session(session)
                .with(r -> { r.setRemoteAddr(remoteAddr); return r; })).andReturn();
    }

    private MockHttpSession signedIn() throws Exception {
        MockHttpSession session = new MockHttpSession();
        MvcResult r = login(PASSWORD, session, "127.0.0.1");
        assertThat(r.getResponse().getStatus()).isEqualTo(302);
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    @Test
    @DisplayName("[AC-45] without a session the admin and approval pages send the browser to the local sign-in, never to a 200")
    void signedOutIsSentToLogin() throws Exception {
        mvc.perform(get("/admin")).andExpect(status().isFound()).andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/dev/login")));
        mvc.perform(get("/approve/00000000-0000-0000-0000-000000000000")).andExpect(status().isFound());
        mvc.perform(get("/dev/login")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Password")));
    }

    @Test
    @DisplayName("[AC-45] the right password signs the owner in as a verified owner; the admin page then opens")
    void rightPassword() throws Exception {
        MockHttpSession session = signedIn();
        mvc.perform(get("/admin").session(session)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Ads Autopilot")));
    }

    @Test
    @DisplayName("[AC-45] a wrong or empty password signs nobody in")
    void wrongPassword() throws Exception {
        for (String wrong : new String[] {"nope", "", "local-dev-password-12", PASSWORD.toUpperCase()}) {
            MockHttpSession session = new MockHttpSession();
            MvcResult r = login(wrong, session, "127.0.0.1");
            assertThat(r.getResponse().getStatus()).as("password '%s'", wrong).isEqualTo(401);
            mvc.perform(get("/admin").session(session)).andExpect(status().isFound());
        }
        // a failed attempt does not lock out the right password
        signedIn();
    }

    @Test
    @DisplayName("[AC-45] the sign-in form needs the CSRF token")
    void csrfRequired() throws Exception {
        mvc.perform(post("/dev/login").param("password", PASSWORD)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[AC-45] five wrong passwords lock the form for a minute, even for the right password")
    void lockout() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(login("wrong-" + i, new MockHttpSession(), "127.0.0.1").getResponse().getStatus()).isEqualTo(401);
        }
        MockHttpSession locked = new MockHttpSession();
        MvcResult r = login(PASSWORD, locked, "127.0.0.1");
        assertThat(r.getResponse().getStatus()).isEqualTo(429);
        mvc.perform(get("/admin").session(locked)).andExpect(status().isFound());

        clock.advance(Duration.ofMinutes(2));
        signedIn(); // also clears the lock for the tests that follow
    }

    @Test
    @DisplayName("[AC-45] a request that does not come from the machine itself is refused, with or without the password")
    void loopbackRemoteOnly() throws Exception {
        mvc.perform(get("/dev/login").with(r -> { r.setRemoteAddr("192.168.1.50"); return r; })).andExpect(status().isForbidden());
        MvcResult r = login(PASSWORD, new MockHttpSession(), "192.168.1.50");
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        mvc.perform(get("/admin").session((MockHttpSession) r.getRequest().getSession(false) == null ? new MockHttpSession() : (MockHttpSession) r.getRequest().getSession(false)))
                .andExpect(status().isFound());
    }

    @Test
    @DisplayName("[AC-45] a bearer token, even the owner's, opens nothing on the owner pages")
    void bearerIsWorthlessThere() throws Exception {
        mvc.perform(get("/admin").header("Authorization", "Bearer " + ownerToken())).andExpect(status().isFound());
        mvc.perform(get("/admin").header("Authorization", "Bearer daily-token-0123456789-abcdefghijklmnop")).andExpect(status().isFound());
    }
}

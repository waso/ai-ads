package app.autopilot.security;

import app.autopilot.testkit.AppTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** By default there is no password login: only the owner's Google sign-in opens the owner pages. */
class DevLoginOffTest extends AppTestBase {

    @Autowired MockMvc mvc;

    @Test
    @DisplayName("[AC-45] by default the dev login does not exist and the owner pages go to Google sign-in")
    void off() throws Exception {
        mvc.perform(get("/dev/login")).andExpect(status().isNotFound());
        assertThat(mvc.perform(post("/dev/login").param("password", "anything-long-enough").with(csrf())).andReturn().getResponse().getStatus())
                .isIn(404, 405);
        mvc.perform(get("/admin")).andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/oauth2/authorization/google")));
        // the sandbox page does not exist outside sandbox mode
        mvc.perform(get("/dev/sandbox")).andExpect(status().isFound());
    }
}

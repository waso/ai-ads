package app.autopilot.approval;

import app.autopilot.domain.ChangeOutcome;
import app.autopilot.domain.ChangeRequest.PauseAd;
import app.autopilot.domain.ChangeMeta;
import app.autopilot.domain.EntityRef;
import app.autopilot.domain.Loop;
import app.autopilot.exec.ChangeService;
import app.autopilot.exec.RunService;
import app.autopilot.store.ConfigStore;
import app.autopilot.store.ProposalStore;
import app.autopilot.store.ProposalStore.Proposal;
import app.autopilot.testkit.AppTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The approval and admin pages: only the owner's verified Google session gets in, and a bearer token is worthless there. */
class WebSecurityTest extends AppTestBase {

    @Autowired MockMvc mvc;
    @Autowired ChangeService changes;
    @Autowired RunService runs;
    @Autowired ProposalStore proposals;
    @Autowired ApprovalLinks links;
    @Autowired ConfigStore configStore;

    private Proposal proposal;
    private String path;

    @BeforeEach
    void fileProposal() {
        var run = runs.start(Loop.CHAT, "test");
        ChangeOutcome o = changes.submit(new PauseAd(EntityRef.ad("G1~A1")),
                new ChangeMeta("weak ad", "better CTR", run.id().toString(), "k-" + UUID.randomUUID()));
        assertThat(o.status()).isEqualTo(ChangeOutcome.Status.PROPOSED);
        proposal = proposals.find(UUID.fromString(o.proposalId())).orElseThrow();
        var t = links.token(proposal);
        path = "/approve/" + proposal.id() + "?e=" + t.expiresAt() + "&n=" + t.nonce() + "&s=" + t.signature();
    }

    private static RequestPostProcessor google(String email, boolean verified) {
        return oidcLogin().idToken(t -> t.claim("email", email).claim("email_verified", verified).subject("google-sub"));
    }

    private static RequestPostProcessor owner() {
        return google("owner@example.com", true);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder decide(String action, RequestPostProcessor who, boolean withCsrf) {
        var t = links.token(proposal);
        var b = post("/approve/" + proposal.id() + "/" + action).param("e", String.valueOf(t.expiresAt())).param("n", t.nonce())
                .param("s", t.signature()).with(who);
        return withCsrf ? b.with(csrf()) : b;
    }

    @Test
    @DisplayName("[AC-12] an MCP bearer token is rejected by the approval and admin endpoints")
    void mcpTokenIsWorthlessOnApprovalPages() throws Exception {
        String bearer = "Bearer " + ownerToken();
        // no session: sent to the owner's Google sign-in, never served
        mvc.perform(get(path).header("Authorization", bearer)).andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/oauth2/authorization/google")));
        mvc.perform(get("/admin").header("Authorization", bearer)).andExpect(status().is3xxRedirection());
        mvc.perform(post("/admin/kill-switch").param("on", "true").header("Authorization", bearer).with(csrf()))
                .andExpect(status().is3xxRedirection());
        mvc.perform(decide("approve", SecurityMockMvcRequestPostProcessors.anonymous(), true).header("Authorization", bearer))
                .andExpect(status().is3xxRedirection());
        assertThat(fake.realMutations()).isZero();
        assertThat(configStore.control().killSwitch()).isFalse();
        assertThat(proposals.find(proposal.id()).orElseThrow().status()).isEqualTo(ProposalStore.Status.PENDING);
    }

    @Test
    @DisplayName("[AC-12] a signed-in account that is not the owner, or whose email is unverified, is refused")
    void onlyTheVerifiedOwnerGetsIn() throws Exception {
        mvc.perform(get(path).with(google("someone.else@example.com", true))).andExpect(status().isForbidden());
        mvc.perform(get(path).with(google("owner@example.com", false))).andExpect(status().isForbidden());
        mvc.perform(get("/admin").with(google("someone.else@example.com", true))).andExpect(status().isForbidden());
        mvc.perform(decide("approve", google("someone.else@example.com", true), true)).andExpect(status().isForbidden());
        mvc.perform(decide("approve", google("owner@example.com", false), true)).andExpect(status().isForbidden());
        assertThat(fake.realMutations()).isZero();

        mvc.perform(get("/admin").with(owner())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("[AC-13] opening an approval link shows the exact diff and changes nothing")
    void viewingChangesNothing() throws Exception {
        long auditBefore = JdbcCount.audit();
        mvc.perform(get(path).with(owner())).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Pause ad G1~A1")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("weak ad")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Approve")));
        mvc.perform(get(path).with(owner())).andExpect(status().isOk()); // a link scanner opening it again
        assertThat(proposals.find(proposal.id()).orElseThrow().status()).isEqualTo(ProposalStore.Status.PENDING);
        assertThat(proposals.find(proposal.id()).orElseThrow().nonceUsedAt()).isNull();
        assertThat(fake.realMutations()).isZero();
        assertThat(JdbcCount.audit()).isEqualTo(auditBefore);
    }

    @Test
    @DisplayName("[AC-13] approving needs a POST with a CSRF token; then it is applied exactly once")
    void approvalNeedsCsrfAndAppliesOnce() throws Exception {
        mvc.perform(decide("approve", owner(), false)).andExpect(status().isForbidden());
        assertThat(fake.realMutations()).isZero();

        mvc.perform(decide("approve", owner(), true)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Pause ad G1~A1")));
        assertThat(fake.realMutations()).isEqualTo(1);
        assertThat(fake.ads.get("G1~A1").status).isEqualTo("PAUSED");
        assertThat(proposals.find(proposal.id()).orElseThrow().status()).isEqualTo(ProposalStore.Status.APPLIED);

        mvc.perform(decide("approve", owner(), true)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("already")));
        assertThat(fake.realMutations()).as("a second POST applies nothing").isEqualTo(1);
    }

    @Test
    @DisplayName("[AC-13] a tampered link shows nothing about the proposal, and a rejected proposal applies nothing")
    void tamperedLinkAndRejection() throws Exception {
        mvc.perform(get(path.replaceAll("&s=.*", "&s=forged")).with(owner())).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("invalid or has expired")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("weak ad"))));

        mvc.perform(decide("reject", owner(), true).param("note", "not now")).andExpect(status().isOk());
        assertThat(proposals.find(proposal.id()).orElseThrow().status()).isEqualTo(ProposalStore.Status.REJECTED);
        assertThat(fake.realMutations()).isZero();
    }

    @Test
    @DisplayName("[AC-23] the kill switch and the breaker are controlled from the admin page, with CSRF, by the owner only")
    void adminControls() throws Exception {
        mvc.perform(post("/admin/kill-switch").param("on", "true").param("reason", "test").with(owner())).andExpect(status().isForbidden());
        mvc.perform(post("/admin/kill-switch").param("on", "true").param("reason", "test").with(google("x@y.z", true)).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(configStore.control().killSwitch()).isFalse();

        mvc.perform(post("/admin/kill-switch").param("on", "true").param("reason", "drill").with(owner()).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(configStore.control().killSwitch()).isTrue();
        mvc.perform(get("/admin").with(owner())).andExpect(content().string(org.hamcrest.Matchers.containsString("ON")));
        // with the kill switch on, approving is refused by the server's re-check
        mvc.perform(decide("approve", owner(), true)).andExpect(content().string(org.hamcrest.Matchers.containsString("kill_switch")));
        assertThat(fake.realMutations()).isZero();

        mvc.perform(post("/admin/kill-switch").param("on", "false").with(owner()).with(csrf())).andExpect(status().is3xxRedirection());
        assertThat(configStore.control().killSwitch()).isFalse();

        configStore.openBreaker(java.util.List.of("C1"), clock.instant(), "watchdog");
        mvc.perform(post("/admin/breaker/clear").with(owner()).with(csrf())).andExpect(status().is3xxRedirection());
        assertThat(configStore.control().breakerOpen()).isFalse();
    }

    @Test
    @DisplayName("[AC-13] text from a proposal is escaped on the approval page, which also sends a strict content security policy")
    void approvalPageEscapesProposalText() throws Exception {
        var run = runs.start(Loop.CHAT, "xss");
        String evil = "<img src=x onerror=alert(1)>";
        ChangeOutcome o = changes.submit(new app.autopilot.domain.ChangeRequest.CreateAd(EntityRef.adGroup("G1"),
                java.util.List.of(evil, "Second headline", "Third headline"), java.util.List.of("First description", "Second description"),
                "https://example.com/x"), new ChangeMeta("<script>alert('reason')</script>", "e", run.id().toString(), "k-xss"));
        assertThat(o.status()).isEqualTo(ChangeOutcome.Status.PROPOSED);
        Proposal p = proposals.find(UUID.fromString(o.proposalId())).orElseThrow();
        var t = links.token(p);
        mvc.perform(get("/approve/" + p.id() + "?e=" + t.expiresAt() + "&n=" + t.nonce() + "&s=" + t.signature()).with(owner()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("&lt;img src=x onerror=alert(1)&gt;")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("&lt;script&gt;alert(&#39;reason&#39;)&lt;/script&gt;")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<img src=x"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<script>alert"))))
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("default-src 'none'")))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    @DisplayName("[AC-16] the owner can only approve or reject: there is no endpoint that edits a proposal")
    void proposalsCannotBeEdited() throws Exception {
        var t = links.token(proposal);
        String base = "/approve/" + proposal.id();
        for (var method : new org.springframework.http.HttpMethod[] {org.springframework.http.HttpMethod.PUT,
                org.springframework.http.HttpMethod.PATCH, org.springframework.http.HttpMethod.DELETE}) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(method, base)
                    .param("e", String.valueOf(t.expiresAt())).param("n", t.nonce()).param("s", t.signature())
                    .param("diff", "amountMicros=999").with(owner()).with(csrf())).andExpect(status().is4xxClientError());
        }
        mvc.perform(decide("edit", owner(), true)).andExpect(status().is4xxClientError());
        mvc.perform(decide("approve", owner(), true).param("amountMicros", "999999999")).andExpect(status().isOk());
        assertThat(proposals.find(proposal.id()).orElseThrow().request()).as("extra form fields cannot change the proposal")
                .isEqualTo(proposal.request());
        assertThat(fake.ads.get("G1~A1").status).isEqualTo("PAUSED");
    }

    @Test
    @DisplayName("[AC-12] only the health endpoint is open; every other path is denied")
    void everythingElseDenied() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        for (String p : new String[] {"/actuator/env", "/actuator/beans", "/", "/api/anything", "/h2-console"}) {
            mvc.perform(get(p).with(owner())).andExpect(status().is4xxClientError());
        }
    }

    /** Counts audit rows through the owner connection. */
    private static final class JdbcCount {
        static long audit() {
            return org.springframework.jdbc.core.simple.JdbcClient.create(DB.owner()).sql("SELECT count(*) FROM audit_events")
                    .query(Long.class).single();
        }
    }
}

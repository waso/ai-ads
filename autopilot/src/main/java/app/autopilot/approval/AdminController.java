package app.autopilot.approval;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import app.autopilot.config.RuntimeConfig;
import app.autopilot.store.AlertStore;
import app.autopilot.store.LedgerStore;
import app.autopilot.store.ProposalStore;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Clock;
import java.util.List;

/** Owner-only controls and an overview: the kill switch, the circuit breaker, alerts and open proposals. */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Controller
class AdminController {

    private final AdminService admin;
    private final ApprovalService approvals;
    private final ApprovalLinks links;
    private final RuntimeConfig config;
    private final AlertStore alerts;
    private final ProposalStore proposals;
    private final LedgerStore ledger;
    private final Clock clock;

    AdminController(AdminService admin, ApprovalService approvals, ApprovalLinks links, RuntimeConfig config,
                    AlertStore alerts, ProposalStore proposals, LedgerStore ledger, Clock clock) {
        this.admin = admin;
        this.approvals = approvals;
        this.links = links;
        this.config = config;
        this.alerts = alerts;
        this.proposals = proposals;
        this.ledger = ledger;
        this.clock = clock;
    }

    /** A proposal with its signed link, shown only to the signed-in owner. */
    record Row(ProposalStore.Proposal p, String url) {}

    @GetMapping("/admin")
    String overview(Model model) {
        approvals.expireDue();
        model.addAttribute("control", config.control());
        model.addAttribute("limits", config.limits());
        model.addAttribute("alerts", alerts.openAlerts());
        List<Row> rows = proposals.open(clock.instant()).stream()
                .map(p -> new Row(p, links.configured() ? links.url(p) : null)).toList();
        model.addAttribute("proposals", rows);
        model.addAttribute("changes", ledger.recentChanges(15));
        return "admin";
    }

    @PostMapping("/admin/kill-switch")
    String killSwitch(@RequestParam boolean on, @RequestParam(required = false) String reason,
                      @AuthenticationPrincipal OAuth2User user, RedirectAttributes redirect) {
        admin.killSwitch(on, reason == null || reason.isBlank() ? "set from the admin page" : reason.strip(), email(user));
        redirect.addFlashAttribute("flash", on ? "Kill switch is ON: the server is read-only." : "Kill switch cleared.");
        return "redirect:/admin";
    }

    @PostMapping("/admin/breaker/clear")
    String clearBreaker(@AuthenticationPrincipal OAuth2User user, RedirectAttributes redirect) {
        List<String> paused = admin.clearBreaker(email(user));
        redirect.addFlashAttribute("flash", "Breaker cleared. It had paused campaigns " + paused
                + ". Review them in Google Ads before resuming; nothing was resumed automatically.");
        return "redirect:/admin";
    }

    @PostMapping("/admin/reads/resume")
    String resumeReads(@AuthenticationPrincipal OAuth2User user, RedirectAttributes redirect) {
        admin.resumeReads(email(user));
        redirect.addFlashAttribute("flash", "Claude's reads resumed.");
        return "redirect:/admin";
    }

    private static String email(OAuth2User user) {
        Object email = user == null ? null : user.getAttributes().get("email");
        return email == null ? null : email.toString();
    }
}

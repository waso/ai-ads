package app.autopilot.approval;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import app.autopilot.store.ProposalStore.Proposal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Optional;
import java.util.UUID;

/**
 * The approval page. Opening a link (GET) only shows the exact diff and changes nothing, so email scanners and
 * link previews are harmless. Deciding is a POST from the owner's signed-in session with a CSRF token.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Controller
class ApprovalController {

    private final ApprovalService approvals;

    ApprovalController(ApprovalService approvals) {
        this.approvals = approvals;
    }

    @GetMapping("/approve/{id}")
    String view(@PathVariable UUID id, @RequestParam(name = "e", required = false) Long exp,
                @RequestParam(name = "n", required = false) String nonce, @RequestParam(name = "s", required = false) String sig,
                Model model) {
        Optional<Proposal> p = exp == null ? Optional.empty() : approvals.preview(id, exp, nonce, sig);
        if (p.isEmpty()) {
            model.addAttribute("message", "This approval link is invalid or has expired.");
            return "result";
        }
        model.addAttribute("p", p.get());
        model.addAttribute("pending", p.get().status() == app.autopilot.store.ProposalStore.Status.PENDING);
        model.addAttribute("exp", exp);
        model.addAttribute("nonce", nonce);
        model.addAttribute("sig", sig);
        return "approve";
    }

    @PostMapping("/approve/{id}/approve")
    String approve(@PathVariable UUID id, @RequestParam long e, @RequestParam String n, @RequestParam String s,
                   @AuthenticationPrincipal OAuth2User user, Model model) {
        ApprovalService.Result r = approvals.approve(id, e, n, s, email(user));
        model.addAttribute("message", r.message());
        model.addAttribute("ok", r.ok());
        return "result";
    }

    @PostMapping("/approve/{id}/reject")
    String reject(@PathVariable UUID id, @RequestParam long e, @RequestParam String n, @RequestParam String s,
                  @RequestParam(required = false) String note, @AuthenticationPrincipal OAuth2User user, Model model) {
        ApprovalService.Result r = approvals.reject(id, e, n, s, email(user), note);
        model.addAttribute("message", r.message());
        model.addAttribute("ok", r.ok());
        return "result";
    }

    private static String email(OAuth2User user) {
        Object email = user == null ? null : user.getAttributes().get("email");
        return email == null ? null : email.toString();
    }
}

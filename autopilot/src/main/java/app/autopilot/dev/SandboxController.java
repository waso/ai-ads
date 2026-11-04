package app.autopilot.dev;

import app.autopilot.ads.sandbox.SandboxAdsGateway;
import app.autopilot.config.RuntimeConfig;
import app.autopilot.store.AlertStore;
import app.autopilot.store.RunStore;
import app.autopilot.watchdog.Watchdog;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Owner-only page to break the simulated Google Ads account on purpose and watch the safety systems react: overspend, a lost
 * sale conversion, a disapproved ad. It exists only with {@code autopilot.ads.mode=sandbox}; against a real account there is
 * nothing to break and the page does not exist.
 */
@Controller
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "autopilot.ads", name = "mode", havingValue = "sandbox")
class SandboxController {

    private final SandboxAdsGateway sandbox;
    private final Watchdog watchdog;
    private final RuntimeConfig config;
    private final AlertStore alerts;
    private final RunStore runStore;

    SandboxController(SandboxAdsGateway sandbox, Watchdog watchdog, RuntimeConfig config, AlertStore alerts, RunStore runStore) {
        this.runStore = runStore;
        this.sandbox = sandbox;
        this.watchdog = watchdog;
        this.config = config;
        this.alerts = alerts;
    }

    @GetMapping("/dev/sandbox")
    String page(Model model) {
        model.addAttribute("account", sandbox.describe());
        model.addAttribute("sent", sandbox.realRequests().stream().map(Object::toString).toList());
        model.addAttribute("control", config.control());
        model.addAttribute("alerts", alerts.openAlerts());
        return "dev-sandbox";
    }

    @PostMapping("/dev/sandbox/spend")
    String spend(@RequestParam String campaignId, @RequestParam BigDecimal amount, RedirectAttributes redirect) {
        if (amount.signum() < 0 || amount.compareTo(new BigDecimal("100000")) > 0) {
            return flash(redirect, "Amount must be between 0 and 100000.");
        }
        long micros = amount.multiply(BigDecimal.valueOf(1_000_000)).setScale(0, RoundingMode.HALF_UP).longValueExact();
        try {
            sandbox.setSpendToday(campaignId.strip(), micros);
        } catch (IllegalArgumentException e) {
            return flash(redirect, e.getMessage());
        }
        return flash(redirect, "Campaign " + campaignId.strip() + " now shows " + amount.toPlainString() + " spent today. Run the watchdog to see the reaction.");
    }

    @PostMapping("/dev/sandbox/tracking")
    String tracking(@RequestParam String mode, RedirectAttributes redirect) {
        try {
            sandbox.setConversionTracking(mode);
        } catch (IllegalArgumentException e) {
            return flash(redirect, e.getMessage());
        }
        return flash(redirect, "Conversion tracking is now '" + mode + "'. Run the watchdog to see the reaction.");
    }

    @PostMapping("/dev/sandbox/policy")
    String policy(@RequestParam String adId, @RequestParam(defaultValue = "TRADEMARKS") String topic, RedirectAttributes redirect) {
        try {
            sandbox.addPolicyIssue(adId.strip(), topic.strip());
        } catch (IllegalArgumentException e) {
            return flash(redirect, e.getMessage());
        }
        return flash(redirect, "Ad " + adId.strip() + " is now disapproved (" + topic.strip() + ").");
    }

    @PostMapping("/dev/sandbox/policy/clear")
    String clearPolicy(RedirectAttributes redirect) {
        sandbox.clearPolicyIssues();
        return flash(redirect, "All policy issues cleared.");
    }

    @PostMapping("/dev/sandbox/reset")
    String reset(RedirectAttributes redirect) {
        sandbox.reset();
        return flash(redirect, "The simulated account is back to its seeded state. The database (audit, proposals, alerts) is untouched.");
    }

    @PostMapping("/dev/sandbox/runs/release")
    String releaseRuns(RedirectAttributes redirect) {
        int n = runStore.releaseSlots();
        return flash(redirect, "Freed " + n + " loop window(s): every loop may start a new run now.");
    }

    @PostMapping("/dev/sandbox/watchdog")
    String runWatchdog(RedirectAttributes redirect) {
        Watchdog.Report r = watchdog.runOnce();
        return flash(redirect, "Watchdog ran. Breaker tripped: " + r.breakerTripped() + ". Failed steps: " + r.failedSteps() + ".");
    }

    private static String flash(RedirectAttributes redirect, String message) {
        redirect.addFlashAttribute("flash", message);
        return "redirect:/dev/sandbox";
    }
}

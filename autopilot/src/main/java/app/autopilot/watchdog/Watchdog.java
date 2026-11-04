package app.autopilot.watchdog;

import app.autopilot.ads.AdsException;
import app.autopilot.ads.AdsGateway;
import app.autopilot.ads.AdsGateway.CampaignInfo;
import app.autopilot.ads.AdsGateway.ChangeEvent;
import app.autopilot.ads.AdsGateway.PolicyIssue;
import app.autopilot.ads.QuotaMeter;
import app.autopilot.exec.ProposalExpiry;
import app.autopilot.config.AutopilotProperties;
import app.autopilot.config.RuntimeConfig;
import app.autopilot.domain.ControlState;
import app.autopilot.domain.Limits;
import app.autopilot.notify.AlertService;
import app.autopilot.notify.Notification;
import app.autopilot.notify.Notification.Severity;
import app.autopilot.notify.Notifier;
import app.autopilot.store.AlertStore;
import app.autopilot.store.ConfigStore;
import app.autopilot.store.LedgerStore;
import app.autopilot.store.MetricStore;
import app.autopilot.store.ProposalStore;
import app.autopilot.watchdog.SpendMonitor.CampaignSpend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The deterministic check that runs every 15 minutes with no Claude involved: spend against caps, disapprovals,
 * stopped delivery, changes made outside this system, conversion drops and quota. It may only pause, through
 * the circuit breaker. Each step runs on its own, so one failing step never hides the others, and the spend check
 * runs first.
 */
@Component
public class Watchdog {

    private static final Logger log = LoggerFactory.getLogger(Watchdog.class);

    public record Report(boolean breakerTripped, List<String> failedSteps, boolean heartbeatDelivered) {
        public boolean healthy() { return failedSteps.isEmpty(); }
    }

    private final AdsGateway ads;
    private final SpendMonitor spend;
    private final CircuitBreaker breaker;
    private final RuntimeConfig config;
    private final ConfigStore configStore;
    private final AlertService alerts;
    private final AlertStore alertStore;
    private final LedgerStore ledger;
    private final MetricStore metrics;
    private final QuotaMeter quota;
    private final SnapshotService snapshots;
    private final ConversionMonitor conversions;
    private final ProposalExpiry expiry;
    private final ProposalStore proposals;
    private final Notifier notifier;
    private final Heartbeat heartbeat;
    private final AutopilotProperties props;
    private final Clock clock;

    public Watchdog(AdsGateway ads, SpendMonitor spend, CircuitBreaker breaker, RuntimeConfig config, ConfigStore configStore,
                    AlertService alerts, AlertStore alertStore, LedgerStore ledger, MetricStore metrics, QuotaMeter quota,
                    SnapshotService snapshots, ConversionMonitor conversions, ProposalExpiry expiry,
                    ProposalStore proposals, Notifier notifier, Heartbeat heartbeat, AutopilotProperties props, Clock clock) {
        this.ads = ads;
        this.spend = spend;
        this.breaker = breaker;
        this.config = config;
        this.configStore = configStore;
        this.alerts = alerts;
        this.alertStore = alertStore;
        this.ledger = ledger;
        this.metrics = metrics;
        this.quota = quota;
        this.snapshots = snapshots;
        this.conversions = conversions;
        this.expiry = expiry;
        this.proposals = proposals;
        this.notifier = notifier;
        this.heartbeat = heartbeat;
        this.props = props;
        this.clock = clock;
    }

    /** One run. The limits come from the database when it is up and from the last known copy when it is not. */
    public synchronized Report runOnce() {
        Limits limits = config.limitsOrCached();
        List<String> failed = new ArrayList<>();
        boolean[] tripped = {false};
        step("spend", failed, () -> tripped[0] = checkSpend(limits));
        step("health", failed, () -> checkPolicyAndDelivery(limits));
        step("changes", failed, () -> reconcileChanges());
        step("conversions", failed, () -> {
            snapshots.refresh(limits);
            conversions.check(limits);
        });
        step("tracking", failed, () -> checkConversionTracking(limits));
        step("quota", failed, () -> checkQuota(limits));
        step("proposals", failed, () -> housekeeping(limits));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("failedSteps", failed);
        details.put("breakerTripped", tripped[0]);
        boolean delivered = heartbeat.beat(details);
        return new Report(tripped[0], failed, delivered);
    }

    private void step(String name, List<String> failed, Runnable body) {
        try {
            body.run();
        } catch (AdsException e) {
            failed.add(name);
            log.warn("Watchdog step {} could not read Google Ads: {} {}", name, e.code(), e.getMessage());
            alerts.raise("ADS_UNREACHABLE", Severity.WARNING, name, "The watchdog could not read Google Ads during '" + name
                    + "' (" + e.code() + "). Spend is not being watched until this clears.", Map.of("step", name));
        } catch (RuntimeException e) {
            failed.add(name);
            log.error("Watchdog step {} failed", name, e);
        }
    }

    // ---- spend -----------------------------------------------------------------------------------------

    /** Returns true when the breaker was tripped by this run. */
    boolean checkSpend(Limits limits) {
        SpendMonitor.Snapshot s = spend.snapshot(limits);
        alerts.resolve("ADS_UNREACHABLE", "spend");
        BigDecimal today = BigDecimal.valueOf(s.todayMicros());
        BigDecimal summed = BigDecimal.valueOf(s.summedDailyBudgetsMicros());
        boolean dailyBreaker = summed.signum() > 0 && today.compareTo(summed.multiply(BigDecimal.valueOf(limits.dailyBreakerMultiple()))) >= 0;
        boolean dailyWarning = summed.signum() > 0 && today.compareTo(summed.multiply(BigDecimal.valueOf(limits.dailyAlertMultiple()))) >= 0;
        boolean monthlyBreach = limits.monthlyCapMicros() != null && limits.monthlyCapMicros() > 0 && s.monthMicros() >= limits.monthlyCapMicros();
        ControlState control = config.controlOrCached();
        boolean tripped = false;

        if (dailyBreaker || monthlyBreach) {
            String reason = dailyBreaker
                    ? String.format("Spend today is %.2fx the summed daily budgets (breaker at %.1fx)", s.todayMultiple(), limits.dailyBreakerMultiple())
                    : "Month-to-date spend reached the monthly cap";
            if (!control.breakerOpen()) {
                List<CampaignSpend> managed = s.campaigns().stream().filter(c -> c.managed() && c.enabled() && c.todayMicros() >= 0).toList();
                List<CampaignSpend> toPause = s.managedTodayMicros() > 0 || monthlyBreach ? managed : List.of();
                breaker.trip(reason, toPause);
                tripped = true;
                if (toPause.isEmpty() || s.unmanagedTodayMicros() > s.managedTodayMicros()) {
                    alerts.raise("BREAKER_CANNOT_ACT", Severity.CRITICAL, "", reason + ", but most of today's spend is in campaigns outside agent "
                            + "management, which the breaker never touches. Pause them in Google Ads.",
                            Map.of("unmanagedTodayMicros", s.unmanagedTodayMicros(), "managedTodayMicros", s.managedTodayMicros()));
                }
            }
            alerts.raise(dailyBreaker ? "SPEND_DAILY_BREAKER" : "SPEND_MONTHLY_CAP", Severity.CRITICAL, "",
                    reason + (tripped ? ": agent-managed campaigns were paused and writes are blocked until you clear the breaker." : "."),
                    Map.of("todayMicros", s.todayMicros(), "summedDailyBudgetsMicros", s.summedDailyBudgetsMicros(), "monthMicros", s.monthMicros()));
        } else if (dailyWarning) {
            alerts.raise("SPEND_DAILY_WARNING", Severity.WARNING, "", String.format("Spend today is %.2fx the summed daily budgets "
                    + "(alert at %.1fx). Google allows up to 2x on a day, so this is a warning, not necessarily a fault.",
                    s.todayMultiple(), limits.dailyAlertMultiple()), Map.of("todayMicros", s.todayMicros(),
                    "summedDailyBudgetsMicros", s.summedDailyBudgetsMicros()));
        }

        if (!dailyWarning) alerts.resolve("SPEND_DAILY_WARNING", "");
        if (!dailyBreaker && !control.breakerOpen()) alerts.resolve("SPEND_DAILY_BREAKER", "");
        if (!monthlyBreach && !control.breakerOpen()) alerts.resolve("SPEND_MONTHLY_CAP", "");
        if (limits.monthlyCapMicros() != null && limits.monthlyCapMicros() > 0 && !monthlyBreach
                && s.monthMicros() * 10 >= limits.monthlyCapMicros() * 9) {
            alerts.raise("MONTHLY_CAP_NEAR", Severity.WARNING, "", "Month-to-date spend is above 90% of the monthly cap.",
                    Map.of("monthMicros", s.monthMicros()));
        } else {
            alerts.resolve("MONTHLY_CAP_NEAR", "");
        }
        return tripped;
    }

    // ---- policy and delivery ---------------------------------------------------------------------------

    private void checkPolicyAndDelivery(Limits limits) {
        Set<String> seen = new HashSet<>();
        for (PolicyIssue p : ads.policyIssues()) {
            seen.add(p.entity().key());
            alerts.raise("POLICY_DISAPPROVAL", Severity.WARNING, p.entity().key(),
                    p.entity().key() + " is " + p.approvalStatus() + " (" + String.join(", ", p.topics()) + ").",
                    Map.of("approvalStatus", p.approvalStatus(), "topics", p.topics()));
        }
        resolveMissing("POLICY_DISAPPROVAL", seen);

        ZonedDateTime local = clock.instant().atZone(limits.zone());
        Set<String> stopped = new HashSet<>();
        Set<String> silent = new HashSet<>();
        for (CampaignInfo c : ads.campaigns()) {
            if (!c.enabled()) continue;
            String ps = c.primaryStatus() == null ? "" : c.primaryStatus();
            if (ps.equals("NOT_ELIGIBLE") || ps.equals("ENDED")) {
                stopped.add("CAMPAIGN:" + c.id());
                alerts.raise("DELIVERY_STOPPED", Severity.WARNING, "CAMPAIGN:" + c.id(),
                        "Campaign " + c.id() + " is enabled but Google reports " + ps + ": it is not delivering.", Map.of("primaryStatus", ps));
            } else if (local.getHour() >= props.watchdog().noDeliveryAfterHour() && c.impressionsToday() == 0) {
                silent.add("CAMPAIGN:" + c.id());
                alerts.raise("NO_IMPRESSIONS", Severity.WARNING, "CAMPAIGN:" + c.id(), "Campaign " + c.id()
                        + " is enabled but has had no impressions today (it is past " + props.watchdog().noDeliveryAfterHour() + ":00).", Map.of());
            }
        }
        resolveMissing("DELIVERY_STOPPED", stopped);
        resolveMissing("NO_IMPRESSIONS", silent);
    }

    private void resolveMissing(String type, Set<String> stillPresent) {
        for (AlertStore.Alert a : alertStore.openAlerts()) {
            if (a.type().equals(type) && !stillPresent.contains(a.entityKey())) alerts.resolve(type, a.entityKey());
        }
    }

    // ---- changes outside this system, and reconciliation of ours --------------------------------------------

    /**
     * Reads Google's change history. A change by the owner in the Google Ads interface is never reverted: it is
     * recorded as the new baseline and starts the cooldown. A change by another client is an alert. A change by this
     * system's service account with no matching audit record is critical: something wrote without the guard.
     */
    void reconcileChanges() {
        String ours = props.ads().serviceAccountEmail();
        if (ours == null || ours.isBlank()) {
            alerts.raise("CONFIG_SERVICE_ACCOUNT", Severity.WARNING, "", "autopilot.ads.service-account-email is not set, so changes made "
                    + "by this system cannot be told apart from the owner's and cannot be reconciled.", Map.of());
            return;
        }
        alerts.resolve("CONFIG_SERVICE_ACCOUNT", "");
        Instant now = clock.instant();
        Instant since = metrics.changeCursor().orElse(now.minus(Duration.ofHours(24))).minus(Duration.ofMinutes(5));
        Instant newest = null;
        for (ChangeEvent e : ads.changeHistory(since)) {
            if (newest == null || e.at().isAfter(newest)) newest = e.at();
            if (ours.equalsIgnoreCase(e.actorEmail())) {
                if (!ledger.attemptBetween(e.at().minus(Duration.ofMinutes(15)), e.at().plusSeconds(60))) {
                    alerts.raise("UNAUDITED_CHANGE", Severity.CRITICAL, e.resourceName(), "Google's change history shows a change by this "
                            + "system's service account that has no audit record: " + e.operation() + " " + e.resourceName() + " at " + e.at() + ".",
                            Map.of("at", e.at().toString(), "operation", String.valueOf(e.operation())));
                }
                continue;
            }
            String key = ResourceKeys.entityKey(e.resourceName());
            metrics.recordExternalChange(e.resourceName(), key, e.at(), e.actorEmail(), e.clientType(), e.operation());
            boolean ownerInterface = "GOOGLE_ADS_WEB_CLIENT".equals(e.clientType());
            if (!ownerInterface) {
                alerts.raise("UNEXPECTED_CHANGE", Severity.WARNING, e.resourceName(), "A change by " + e.clientType() + " (" + e.actorEmail()
                        + ") that did not come from this system: " + e.operation() + " " + e.resourceName() + ".",
                        Map.of("clientType", String.valueOf(e.clientType()), "actor", String.valueOf(e.actorEmail())));
            }
        }
        if (newest != null) metrics.setChangeCursor(newest);
    }

    // ---- conversion tracking ---------------------------------------------------------------------------------

    private static final String TRACKING_ALERT = "CONVERSION_TRACKING_";

    /**
     * Once a day: is the conversion signal real (a sale action is primary, recording and valued)? Each finding becomes
     * an alert; the two that make conversion data unfit to optimize on also hold performance-based changes.
     */
    void checkConversionTracking(Limits limits) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, limits.zone());
        boolean doneToday = metrics.lastBeat("conversion-health").map(t -> LocalDate.ofInstant(t, limits.zone()).equals(today)).orElse(false);
        if (doneToday) return;
        List<ConversionHealth.Finding> findings = ConversionHealth.evaluate(ads.conversionActions(), spend.snapshot(limits).monthMicros() > 0);
        Set<String> present = new HashSet<>();
        for (ConversionHealth.Finding f : findings) {
            present.add(TRACKING_ALERT + f.code());
            Severity severity = switch (f.severity()) {
                case CRITICAL -> Severity.CRITICAL;
                case WARNING -> Severity.WARNING;
                default -> Severity.INFO;
            };
            alerts.raise(TRACKING_ALERT + f.code(), severity, "", f.message(), Map.of("code", f.code()));
        }
        for (String code : List.of(ConversionHealth.NO_CONVERSION_ACTION, ConversionHealth.NO_SALES_CONVERSION, ConversionHealth.NON_SALES_PRIMARY,
                ConversionHealth.NO_RECENT_SALES, ConversionHealth.NO_SALE_VALUE)) {
            if (!present.contains(TRACKING_ALERT + code)) alerts.resolve(TRACKING_ALERT + code, "");
        }
        metrics.beat("conversion-health", now, Map.of("findings", findings.size()));
    }

    // ---- quota, proposals, outcomes --------------------------------------------------------------------------

    private void checkQuota(Limits limits) {
        int pct = quota.percentUsed();
        ControlState control = config.controlOrCached();
        if (pct >= limits.quotaPauseReadsPct()) {
            alerts.raise("QUOTA_HIGH", Severity.WARNING, "", pct + "% of the daily Google Ads operations are used. Claude's reads are paused; "
                    + "the watchdog keeps running.", Map.of("percent", pct));
            if (!control.readsPaused()) configStore.setReadsPaused(true);
        } else if (pct < limits.quotaPauseReadsPct() - 10) {
            alerts.resolve("QUOTA_HIGH", "");
            if (control.readsPaused()) configStore.setReadsPaused(false);
        }
    }

    private void housekeeping(Limits limits) {
        expiry.expireDue();
        Instant now = clock.instant();
        for (LedgerStore.OutcomeReview r : ledger.dueOutcomes(now.minus(Duration.ofDays(7)))) {
            ledger.completeOutcome(r.id(), "UNREVIEWED", Map.of("note", "the review window passed without a recorded verdict"), now);
        }
        sendDigest(limits, now);
    }

    /** One message a day instead of one per proposal, so approval does not turn into rubber-stamping. */
    private void sendDigest(Limits limits, Instant now) {
        LocalDate today = LocalDate.ofInstant(now, limits.zone());
        boolean sentToday = metrics.lastBeat("digest").map(t -> LocalDate.ofInstant(t, limits.zone()).equals(today)).orElse(false);
        if (sentToday || now.atZone(limits.zone()).getHour() < 7) return;
        List<ProposalStore.Proposal> open = proposals.open(now);
        if (open.isEmpty()) return;
        StringBuilder body = new StringBuilder(open.size() + " proposal(s) are waiting for you, oldest first:\n");
        for (ProposalStore.Proposal p : open) {
            body.append("\n- Tier ").append(p.tier().substring(1)).append(" ").append(p.tool()).append(": ").append(p.diff().summary())
                    .append(" (expires ").append(p.expiresAt()).append(")");
        }
        String base = props.security().publicBaseUrl();
        notifier.send(new Notification(Severity.INFO, "Daily digest: " + open.size() + " proposal(s)", body.toString(),
                base == null || base.isBlank() ? null : base.replaceAll("/+$", "") + "/admin"));
        metrics.beat("digest", now, Map.of("proposals", open.size()));
    }
}

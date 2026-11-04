package app.autopilot.watchdog;

import app.autopilot.ads.AdsGateway.ChangeEvent;
import app.autopilot.ads.AdsGateway.PolicyIssue;
import app.autopilot.domain.ChangeOutcome;
import app.autopilot.domain.ChangeRequest.AdjustKeywordBid;
import app.autopilot.domain.ChangeRequest.DecreaseBudget;
import app.autopilot.domain.ChangeRequest.PauseKeyword;
import app.autopilot.domain.EntityRef;
import app.autopilot.guard.Guard;
import app.autopilot.store.AlertStore;
import app.autopilot.store.MetricStore.Snapshot;
import app.autopilot.ads.sandbox.SandboxAdsGateway;
import app.autopilot.testkit.TestLimits;
import app.autopilot.testkit.WorldTest;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The watchdog needs no Claude session and no web server. Test spend: managed campaign C1 (budget 50), unmanaged C9
 * (budget 20) and C8 (carries no-agent, budget 10). Summed daily budgets are 80.00, so 1.5x is 120.00, 1.8x is 144.00.
 */
class WatchdogTest extends WorldTest {

    private AlertStore.Alert alert(String type) {
        return w.alertStore.openAlerts().stream().filter(a -> a.type().equals(type)).findFirst().orElse(null);
    }

    // ---- spend: alert, breaker, scope ------------------------------------------------------------------

    @Test
    @DisplayName("[AC-25] an injected spend breach raises an alert and trips the breaker in the same run, pausing only agent-managed campaigns")
    void breachTripsBreakerAndPausesOnlyManaged() {
        w.fake.campaigns.get("C1").costToday = 100_000_000L;
        w.fake.campaigns.get("C9").costToday = 30_000_000L;
        w.fake.campaigns.get("C8").costToday = 20_000_000L; // 150.00 = 1.875x of 80.00

        Watchdog.Report report = w.watchdog.runOnce();

        assertThat(report.breakerTripped()).isTrue();
        assertThat(alert("SPEND_DAILY_BREAKER")).isNotNull().satisfies(a -> assertThat(a.severity()).isEqualTo("CRITICAL"));
        assertThat(w.channel.byTitle("SPEND_DAILY_BREAKER")).hasSize(1);
        assertThat(w.fake.campaignStatus("C1")).isEqualTo("PAUSED");
        assertThat(w.fake.campaignStatus("C9")).as("not agent-managed: never touched").isEqualTo("ENABLED");
        assertThat(w.fake.campaignStatus("C8")).as("carries no-agent: never touched").isEqualTo("ENABLED");
        assertThat(w.fake.realRequests()).hasSize(1);
        assertThat(w.configStore.control().breakerOpen()).isTrue();
        assertThat(w.configStore.control().breakerPausedCampaigns()).containsExactly("C1");
        assertThat(w.ledger.recentChanges(5)).singleElement().satisfies(c -> {
            assertThat(c.actor()).isEqualTo("watchdog");
            assertThat(c.tool()).isEqualTo("emergency_pause_campaign");
        });
        assertThat(w.ledger.auditFor("BREAKER")).hasSize(1);
        assertThat(report.healthy()).isTrue();
        // and Claude's writes are blocked until the owner clears it
        ChangeOutcome blocked = submit(new DecreaseBudget(EntityRef.budget("B1"), 45_000_000));
        assertRefused(blocked, Guard.Rule.BREAKER_OPEN);
    }

    @Test
    @DisplayName("[AC-25] when the overspend is in unmanaged campaigns, the breaker blocks writes and alerts critically but pauses nothing")
    void unmanagedOverspendPausesNothing() {
        w.fake.campaigns.get("C9").costToday = 150_000_000L;

        Watchdog.Report report = w.watchdog.runOnce();

        assertThat(report.breakerTripped()).isTrue();
        assertThat(w.fake.realMutations()).as("no campaign was paused").isZero();
        assertThat(w.fake.campaignStatus("C9")).isEqualTo("ENABLED");
        assertThat(alert("BREAKER_CANNOT_ACT")).isNotNull().satisfies(a -> assertThat(a.severity()).isEqualTo("CRITICAL"));
        assertThat(alert("SPEND_DAILY_BREAKER")).isNotNull();
        assertThat(w.configStore.control().breakerOpen()).isTrue();
        assertThat(w.configStore.control().breakerPausedCampaigns()).isEmpty();
    }

    @Test
    @DisplayName("[AC-25] the monthly cap also trips the breaker")
    void monthlyCapTripsBreaker() {
        w.fake.campaigns.get("C1").costMonth = 3_000_000_000L;
        assertThat(w.watchdog.runOnce().breakerTripped()).isTrue();
        assertThat(alert("SPEND_MONTHLY_CAP")).isNotNull();
        assertThat(w.fake.campaignStatus("C1")).isEqualTo("PAUSED");
    }

    @Test
    @DisplayName("[AC-25] the breaker boundary: just under 1.8x does not trip, exactly 1.8x does")
    void breakerBoundary() {
        w.fake.campaigns.get("C1").costToday = 143_999_999L;
        assertThat(w.watchdog.runOnce().breakerTripped()).isFalse();
        w.fake.campaigns.get("C1").costToday = 144_000_000L;
        assertThat(w.watchdog.runOnce().breakerTripped()).isTrue();
    }

    @Test
    @DisplayName("[AC-26] at 1.5x the watchdog alerts but does not pause; the alert clears when spend falls")
    void warningOnlyAtOnePointFive() {
        w.fake.campaigns.get("C1").costToday = 119_999_999L;
        w.watchdog.runOnce();
        assertThat(alert("SPEND_DAILY_WARNING")).isNull();

        w.fake.campaigns.get("C1").costToday = 120_000_000L;
        Watchdog.Report r = w.watchdog.runOnce();
        assertThat(r.breakerTripped()).isFalse();
        assertThat(alert("SPEND_DAILY_WARNING")).isNotNull().satisfies(a -> assertThat(a.severity()).isEqualTo("WARNING"));
        assertThat(w.fake.realMutations()).isZero();
        assertThat(w.configStore.control().breakerOpen()).isFalse();
        assertThat(w.channel.byTitle("SPEND_DAILY_WARNING")).hasSize(1);

        w.watchdog.runOnce(); // still high: same alert, no second notification
        assertThat(w.channel.byTitle("SPEND_DAILY_WARNING")).hasSize(1);

        w.fake.campaigns.get("C1").costToday = 60_000_000L;
        w.watchdog.runOnce();
        assertThat(alert("SPEND_DAILY_WARNING")).isNull();
    }

    @Test
    @DisplayName("[AC-24] with the database down the breaker still pauses, using cached limits, and the audit record goes to the log")
    void breakerWorksWithoutDatabase() {
        TestLimits tight = TestLimits.defaults();
        tight.dailyBreakerMultiple = 1.2;
        w.useLimits(tight.build());
        w.config.limits(); // the watchdog has read the limits once while the database was up
        w.fake.campaigns.get("C1").costToday = 100_000_000L; // 1.25x: over 1.2, under the 1.8x default

        Logger auditLog = (Logger) LoggerFactory.getLogger("audit");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        auditLog.addAppender(appender);
        try {
            w.dataSource.down(true);
            Watchdog.Report report = w.watchdog.runOnce();

            assertThat(report.breakerTripped()).as("tripped with the cached 1.2x limit").isTrue();
            assertThat(w.fake.campaignStatus("C1")).isEqualTo("PAUSED");
            assertThat(w.channel.byTitle("SPEND_DAILY_BREAKER")).as("the owner is told even though alerts cannot be stored").isNotEmpty();
            List<String> logged = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(logged).as("the pause's audit record went to the log").anySatisfy(m ->
                    assertThat(m).contains("AUDIT (database unavailable)").contains("\"event\":\"ATTEMPT\"").contains("emergency_pause_campaign")
                            .contains("CAMPAIGN:C1"));
            assertThat(logged).as("so did the breaker record").anySatisfy(m ->
                    assertThat(m).contains("AUDIT (database unavailable)").contains("\"event\":\"BREAKER\"").contains("paused [C1]"));
        } finally {
            w.dataSource.down(false);
            auditLog.detachAppender(appender);
        }
    }

    // ---- health ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("[AC-27] disapprovals raise one alert per entity, deduplicated across runs, and clear when fixed")
    void disapprovalsDeduplicated() {
        w.fake.policyIssues.add(new PolicyIssue(EntityRef.ad("G1~A1"), "ad", "DISAPPROVED", List.of("TRADEMARKS")));
        w.watchdog.runOnce();
        w.watchdog.runOnce();
        assertThat(w.alertStore.openAlerts().stream().filter(a -> a.type().equals("POLICY_DISAPPROVAL"))).hasSize(1);
        assertThat(w.channel.byTitle("POLICY_DISAPPROVAL")).as("the owner is told once").hasSize(1);
        w.fake.policyIssues.clear();
        w.watchdog.runOnce();
        assertThat(alert("POLICY_DISAPPROVAL")).isNull();
    }

    @Test
    @DisplayName("[AC-27] stopped delivery and silent campaigns are detected")
    void stoppedDelivery() {
        w.fake.campaigns.get("C1").primaryStatus = "NOT_ELIGIBLE";
        w.watchdog.runOnce();
        assertThat(alert("DELIVERY_STOPPED")).isNotNull();
        w.fake.campaigns.get("C1").primaryStatus = "ELIGIBLE";
        w.watchdog.runOnce();
        assertThat(alert("DELIVERY_STOPPED")).isNull();

        w.fake.campaigns.get("C9").impressionsToday = 0;
        w.clock.set(Instant.parse("2026-11-04T08:00:00Z")); // 09:00 Warsaw, before the 12:00 line
        w.watchdog.runOnce();
        assertThat(alert("NO_IMPRESSIONS")).isNull();
        w.clock.set(Instant.parse("2026-11-04T12:00:00Z")); // 13:00 Warsaw
        w.watchdog.runOnce();
        assertThat(alert("NO_IMPRESSIONS")).isNotNull();
    }

    @Test
    @DisplayName("[AC-27] a change by another API client is an alert; the owner's change in the interface is a new baseline, not an alert")
    void unexpectedChanges() {
        Instant now = w.clock.instant();
        w.fake.events.add(new ChangeEvent(now.minusSeconds(120), "someone@example.com", "GOOGLE_ADS_SCRIPTS", "CAMPAIGN_BUDGET",
                "customers/1234567890/campaignBudgets/B9", "UPDATE"));
        w.fake.events.add(new ChangeEvent(now.minusSeconds(60), "owner@example.com", "GOOGLE_ADS_WEB_CLIENT", "CAMPAIGN_BUDGET",
                "customers/1234567890/campaignBudgets/B1", "UPDATE"));
        w.watchdog.runOnce();

        assertThat(w.alertStore.openAlerts().stream().filter(a -> a.type().equals("UNEXPECTED_CHANGE"))).hasSize(1);
        assertThat(alert("UNEXPECTED_CHANGE").message()).contains("GOOGLE_ADS_SCRIPTS");
        // the owner's change starts the cooldown on that budget and nothing is reverted
        assertRefused(submit(new DecreaseBudget(EntityRef.budget("B1"), 45_000_000)), Guard.Rule.COOLDOWN);
        assertThat(w.fake.realMutations()).isZero();

        w.watchdog.runOnce(); // the same events again: no duplicates
        assertThat(w.alertStore.openAlerts().stream().filter(a -> a.type().equals("UNEXPECTED_CHANGE"))).hasSize(1);
    }

    @Test
    @DisplayName("[AC-29] the service account's changes must match an audit record; one without it is a critical alert")
    void reconciliation() {
        assertApplied(submit(new DecreaseBudget(EntityRef.budget("B1"), 45_000_000))); // the fake logs a service-account event
        w.watchdog.runOnce();
        assertThat(alert("UNAUDITED_CHANGE")).as("our own audited change reconciles").isNull();

        Instant later = w.clock.instant().plus(Duration.ofHours(3));
        w.clock.set(later);
        w.fake.events.add(new ChangeEvent(later.minusSeconds(30), SandboxAdsGateway.SERVICE_ACCOUNT, "GOOGLE_ADS_API", "CAMPAIGN",
                "customers/1234567890/campaigns/C9", "UPDATE")); // written without going through the guard
        w.watchdog.runOnce();
        AlertStore.Alert a = alert("UNAUDITED_CHANGE");
        assertThat(a).isNotNull();
        assertThat(a.severity()).isEqualTo("CRITICAL");
        assertThat(a.entityKey()).isEqualTo("customers/1234567890/campaigns/C9");
        assertThat(w.channel.byTitle("UNAUDITED_CHANGE")).hasSize(1);
    }

    // ---- conversions -------------------------------------------------------------------------------------

    private void seedConversions(double earlierWeekPerDay, double recentWeekPerDay) {
        LocalDate today = LocalDate.of(2026, 11, 4);
        for (int i = 0; i < 7; i++) { // recent week: Oct 29 .. Nov 1 is days 3..6 back from Nov 4, so use Oct 26..Nov 1
            LocalDate d = today.minusDays(3 + i);
            w.metricStore.upsertSnapshot(new Snapshot(d, SnapshotService.ACCOUNT, "DAILY", 10_000_000, 50, 500, recentWeekPerDay, 0, w.clock.instant()), w.clock.instant());
        }
        for (int i = 0; i < 7; i++) {
            LocalDate d = today.minusDays(10 + i);
            w.metricStore.upsertSnapshot(new Snapshot(d, SnapshotService.ACCOUNT, "DAILY", 10_000_000, 50, 500, earlierWeekPerDay, 0, w.clock.instant()), w.clock.instant());
        }
    }

    @Test
    @DisplayName("[AC-27] a sharp conversion drop raises an alert, and changes that rely on conversion data wait while it is open")
    void conversionDrop() {
        seedConversions(10, 2); // 70 -> 14: down 80%
        w.watchdog.runOnce();
        AlertStore.Alert a = alert("CONVERSION_DROP");
        assertThat(a).isNotNull();
        assertThat(a.severity()).isEqualTo("CRITICAL");
        assertThat(a.message()).contains("fell 80%");

        ChangeOutcome pause = submit(new PauseKeyword(EntityRef.keyword("G1~K1")));
        assertRefused(pause, Guard.Rule.CONVERSION_DROP);
        assertRefused(submit(new AdjustKeywordBid(EntityRef.keyword("G1~K2"), 2_100_000)), Guard.Rule.CONVERSION_DROP);
        assertApplied(submit(new DecreaseBudget(EntityRef.budget("B1"), 45_000_000))); // reducing spend is still allowed

        seedConversions(10, 9);
        w.watchdog.runOnce();
        assertThat(alert("CONVERSION_DROP")).as("clears when conversions recover").isNull();
        assertApplied(submit(new AdjustKeywordBid(EntityRef.keyword("G1~K2"), 2_100_000)));
    }

    @Test
    @DisplayName("[AC-40] conversion tracking is checked once a day: an alert per finding, resolved when fixed, and changes that rely on conversion data wait while no sale conversion exists")
    void conversionTrackingDaily() {
        w.fake.conversionActionRows.clear(); // the account has no conversion action at all
        w.watchdog.runOnce();
        AlertStore.Alert a = alert("CONVERSION_TRACKING_NO_CONVERSION_ACTION");
        assertThat(a).isNotNull();
        assertThat(a.severity()).isEqualTo("CRITICAL");
        assertThat(w.channel.byTitle("CONVERSION_TRACKING_NO_CONVERSION_ACTION")).hasSize(1);
        int reads = w.fake.callCount("conversionActions");
        w.watchdog.runOnce();
        assertThat(w.fake.callCount("conversionActions")).as("read once a day, not every 15 minutes").isEqualTo(reads);

        assertRefused(submit(new PauseKeyword(EntityRef.keyword("G1~K1"))), Guard.Rule.CONVERSION_DROP);
        assertRefused(submit(new AdjustKeywordBid(EntityRef.keyword("G1~K2"), 2_100_000)), Guard.Rule.CONVERSION_DROP);
        assertApplied(submit(new DecreaseBudget(EntityRef.budget("B1"), 45_000_000))); // reducing spend is still allowed

        w.fake.conversionActionRows.add(SandboxAdsGateway.conversionAction("900", "Paid subscription", "ENABLED", "SUBSCRIBE_PAID", true, 3, 9));
        w.clock.advance(Duration.ofDays(1));
        var next = w.startRun(app.autopilot.domain.Loop.DAILY);
        w.watchdog.runOnce();
        assertThat(alert("CONVERSION_TRACKING_NO_CONVERSION_ACTION")).as("resolved the next day").isNull();
        assertApplied(submitAs(next, new AdjustKeywordBid(EntityRef.keyword("G1~K2"), 2_100_000)));
    }

    @Test
    @DisplayName("[AC-40] a primary sign-up with no sale conversion is critical and holds changes; with a sale next to it, it is only a warning")
    void signUpPrimary() {
        w.fake.conversionActionRows.clear();
        w.fake.conversionActionRows.add(SandboxAdsGateway.conversionAction("5", "Signup", "ENABLED", "SIGNUP", true, 20, 80));
        w.watchdog.runOnce();
        assertThat(alert("CONVERSION_TRACKING_NO_SALES_CONVERSION")).isNotNull();
        assertRefused(submit(new PauseKeyword(EntityRef.keyword("G1~K1"))), Guard.Rule.CONVERSION_DROP);

        w.fake.conversionActionRows.add(SandboxAdsGateway.conversionAction("900", "Paid", "ENABLED", "SUBSCRIBE_PAID", true, 3, 9));
        w.clock.advance(Duration.ofDays(1));
        var next = w.startRun(app.autopilot.domain.Loop.DAILY);
        w.watchdog.runOnce();
        assertThat(alert("CONVERSION_TRACKING_NO_SALES_CONVERSION")).isNull();
        assertThat(alert("CONVERSION_TRACKING_NON_SALES_PRIMARY")).isNotNull().satisfies(x -> assertThat(x.severity()).isEqualTo("WARNING"));
        assertApplied(submitAs(next, new PauseKeyword(EntityRef.keyword("G1~K1"))));
    }

    @Test
    @DisplayName("[AC-40] with no sales recorded in 30 days while spending, an alert is raised and keyword pauses wait, but other work does not")
    void noRecentSalesHoldsKeywordPauses() {
        w.fake.conversionActionRows.clear();
        w.fake.conversionActionRows.add(SandboxAdsGateway.conversionAction("900", "Paid", "ENABLED", "SUBSCRIBE_PAID", true, 0, 0));
        w.fake.campaigns.get("C1").costMonth = 200_000_000L;
        w.watchdog.runOnce();
        assertThat(alert("CONVERSION_TRACKING_NO_RECENT_SALES")).isNotNull().satisfies(x -> assertThat(x.message()).contains("gclid"));
        ChangeOutcome pause = submit(new PauseKeyword(EntityRef.keyword("G1~K1")));
        assertRefused(pause, Guard.Rule.CONVERSION_DROP);
        assertThat(pause.message()).contains("proves nothing");
        assertApplied(submit(new AdjustKeywordBid(EntityRef.keyword("G1~K2"), 2_100_000))); // not judged on conversions
    }

    @Test
    @DisplayName("[AC-27] a small baseline is noise, not a conversion drop")
    void smallBaselineIsNoise() {
        seedConversions(1, 0); // 7 -> 0, but under the 10-conversion minimum
        w.watchdog.runOnce();
        assertThat(alert("CONVERSION_DROP")).isNull();
    }

    @Test
    @DisplayName("[AC-27] daily snapshots are read once, recent days are re-read until their data matures")
    void snapshotsRefresh() {
        LocalDate today = LocalDate.of(2026, 11, 4);
        for (int i = 1; i <= 14; i++) w.fake.dailyMetrics.put(today.minusDays(i), new double[] {10_000_000, 50, 500, 5});
        assertThat(w.snapshots.refresh(w.config.limits())).isEqualTo(14);
        assertThat(w.snapshots.refresh(w.config.limits())).as("nothing new within 12 hours").isZero();
        w.clock.advance(Duration.ofHours(13));
        assertThat(w.snapshots.refresh(w.config.limits())).as("the 3 days whose conversions are still maturing").isEqualTo(3);
        assertThat(w.metricStore.snapshots(SnapshotService.ACCOUNT, "DAILY", today.minusDays(14), today.minusDays(1))).hasSize(14);
    }

    // ---- heartbeat, quota, failure of Google ----------------------------------------------------------------

    @Test
    @DisplayName("[AC-27] every run emits a heartbeat, a stale heartbeat is detected, and the dead-man's switch is pinged")
    void heartbeats() throws Exception {
        AtomicInteger pings = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ping", ex -> {
            pings.incrementAndGet();
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        try {
            var p = w.props;
            var withUrl = new app.autopilot.config.AutopilotProperties(p.ownerEmail(), false, p.runs(), p.security(), p.alerting(),
                    new app.autopilot.config.AutopilotProperties.Watchdog(15, "http://127.0.0.1:" + server.getAddress().getPort() + "/ping", 35, 12, 40),
                    p.ads(), p.retention());
            Heartbeat hb = new Heartbeat(w.metricStore, withUrl, w.clock);
            assertThat(hb.beat(Map.of())).isTrue();
            assertThat(pings.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
        var unreachable = new app.autopilot.config.AutopilotProperties(w.props.ownerEmail(), false, w.props.runs(), w.props.security(),
                w.props.alerting(), new app.autopilot.config.AutopilotProperties.Watchdog(15, "http://127.0.0.1:1/ping", 35, 12, 40),
                w.props.ads(), w.props.retention());
        assertThat(new Heartbeat(w.metricStore, unreachable, w.clock).beat(Map.of())).as("a failed ping is reported, not thrown").isFalse();

        w.watchdog.runOnce();
        assertThat(w.metricStore.lastBeat(Heartbeat.SOURCE)).hasValue(w.clock.instant());
        assertThat(w.staleCheck.check()).isFalse();

        w.clock.advance(Duration.ofMinutes(36)); // two missed runs plus slack
        assertThat(w.staleCheck.check()).isTrue();
        assertThat(alert("WATCHDOG_STALE")).isNotNull().satisfies(a -> assertThat(a.severity()).isEqualTo("CRITICAL"));
        assertThat(w.channel.byTitle("WATCHDOG_STALE")).hasSize(1);

        w.watchdog.runOnce();
        assertThat(w.staleCheck.check()).isFalse();
        assertThat(alert("WATCHDOG_STALE")).isNull();
    }

    @Test
    @DisplayName("[AC-27] a watchdog that never reported gets a grace period after start, then an alert")
    void neverReported() {
        assertThat(w.staleCheck.check()).isFalse();
        w.clock.advance(Duration.ofMinutes(36));
        assertThat(w.staleCheck.check()).isTrue();
        assertThat(alert("WATCHDOG_STALE").message()).contains("never reported");
    }

    @Test
    @DisplayName("[AC-28] at 80% of the quota the watchdog pauses Claude's reads and keeps running; reads resume when usage falls")
    void quotaStep() {
        w.quota.count(2400);
        Watchdog.Report r = w.watchdog.runOnce();
        assertThat(r.healthy()).as("the watchdog's own queries continue").isTrue();
        assertThat(alert("QUOTA_HIGH")).isNotNull();
        assertThat(w.configStore.control().readsPaused()).isTrue();

        w.clock.advance(Duration.ofHours(25));
        w.watchdog.runOnce();
        assertThat(alert("QUOTA_HIGH")).isNull();
        assertThat(w.configStore.control().readsPaused()).isFalse();
    }

    @Test
    @DisplayName("[AC-27] if Google cannot be read the watchdog says so, still beats, and reports itself unhealthy")
    void googleUnreachable() {
        w.fake.failAllReads = true;
        Watchdog.Report r = w.watchdog.runOnce();
        assertThat(r.healthy()).isFalse();
        assertThat(r.failedSteps()).contains("spend", "health");
        assertThat(w.alertStore.openAlerts().stream().filter(a -> a.type().equals("ADS_UNREACHABLE"))).isNotEmpty();
        assertThat(w.metricStore.lastBeat(Heartbeat.SOURCE)).isPresent();

        w.fake.failAllReads = false;
        assertThat(w.watchdog.runOnce().healthy()).isTrue();
        assertThat(w.alertStore.openAlerts().stream().filter(a -> a.type().equals("ADS_UNREACHABLE") && a.entityKey().equals("spend"))).isEmpty();
    }

    @Test
    @DisplayName("[AC-14] housekeeping expires proposals; a digest is sent once a day, not one message per proposal")
    void housekeepingAndDigest() {
        var run = w.startRun(app.autopilot.domain.Loop.CHAT);
        assertProposed(w.changes.submit(new app.autopilot.domain.ChangeRequest.PauseAd(EntityRef.ad("G1~A1")), w.meta(run)));
        w.watchdog.runOnce();
        assertThat(w.channel.byTitle("Daily digest: 1 proposal(s)")).as("05:30 is before the digest hour").isEmpty();

        w.clock.set(Instant.parse("2026-11-04T07:00:00Z")); // 08:00 Warsaw
        w.watchdog.runOnce();
        w.watchdog.runOnce();
        assertThat(w.channel.byTitle("Daily digest: 1 proposal(s)")).hasSize(1);
        assertThat(w.channel.byTitle("Daily digest: 1 proposal(s)").get(0).link()).isEqualTo("https://autopilot.test/admin");

        w.clock.advance(Duration.ofHours(49));
        w.watchdog.runOnce();
        assertThat(w.proposalStore.list(app.autopilot.store.ProposalStore.Status.EXPIRED, 10)).hasSize(1);
    }
}

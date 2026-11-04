package app.autopilot.testkit;

import app.autopilot.ads.sandbox.SandboxAdsGateway;
import app.autopilot.ads.MeteredAdsGateway;
import app.autopilot.approval.AdminService;
import app.autopilot.watchdog.CircuitBreaker;
import app.autopilot.watchdog.ConversionMonitor;
import app.autopilot.watchdog.Heartbeat;
import app.autopilot.watchdog.SnapshotService;
import app.autopilot.watchdog.SpendMonitor;
import app.autopilot.watchdog.StaleWatchdogCheck;
import app.autopilot.watchdog.Watchdog;
import app.autopilot.approval.ApprovalLinks;
import app.autopilot.approval.ApprovalService;
import app.autopilot.ads.QuotaMeter;
import app.autopilot.config.AutopilotProperties;
import app.autopilot.config.RuntimeConfig;
import app.autopilot.domain.ChangeMeta;
import app.autopilot.domain.Goals;
import app.autopilot.domain.Limits;
import app.autopilot.domain.Loop;
import app.autopilot.domain.MatchType;
import app.autopilot.exec.Audit;
import app.autopilot.exec.ChangeExecutor;
import app.autopilot.exec.ChangeService;
import app.autopilot.exec.PgWriteLock;
import app.autopilot.exec.ProposalExpiry;
import app.autopilot.exec.ProposalListener;
import app.autopilot.exec.RunService;
import app.autopilot.guard.Guard;
import app.autopilot.notify.AlertService;
import app.autopilot.notify.Notification;
import app.autopilot.notify.NotificationChannel;
import app.autopilot.notify.Notifier;
import app.autopilot.store.AlertStore;
import app.autopilot.store.ConfigStore;
import app.autopilot.store.Json;
import app.autopilot.store.LedgerStore;
import app.autopilot.store.MetricStore;
import app.autopilot.store.ProposalStore;
import app.autopilot.store.RunStore;
import app.autopilot.store.RunStore.Run;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The real services wired by hand around a real PostgreSQL and a fake Google Ads account. No Spring context,
 * so guard and service tests stay fast, yet they exercise the same classes the server runs.
 */
public final class TestWorld {

    /** Wednesday 2026-11-04 05:30 Warsaw: inside the daily window. */
    public static final Instant WEDNESDAY_MORNING = Instant.parse("2026-11-04T04:30:00Z");
    /** Monday 2026-11-02 05:30 Warsaw: inside the weekly window. */
    public static final Instant MONDAY_MORNING = Instant.parse("2026-11-02T04:30:00Z");

    public final TestClock clock;
    public final PgTestSupport.TestDb db;
    public final FlakyDataSource dataSource;
    public final SandboxAdsGateway fake;
    public final MeteredAdsGateway ads;
    public final QuotaMeter quota;
    public final Json json = Json.standalone();
    public final ConfigStore configStore;
    public final RunStore runStore;
    public final ProposalStore proposalStore;
    public final LedgerStore ledger;
    public final AlertStore alertStore;
    public final MetricStore metricStore;
    public final RuntimeConfig config;
    public final AutopilotProperties props;
    public final RecordingChannel channel = new RecordingChannel();
    public final Notifier notifier = new Notifier(List.of(channel));
    public final AlertService alerts;
    public final Audit audit;
    public final RunService runs;
    public final Guard guard;
    public final ChangeExecutor executor;
    public final ChangeService changes;
    public final ApprovalLinks links;
    public final ApprovalService approvals;
    public final AdminService admin;
    public final SpendMonitor spendMonitor;
    public final CircuitBreaker breaker;
    public final SnapshotService snapshots;
    public final ConversionMonitor conversions;
    public final Heartbeat heartbeat;
    public final Watchdog watchdog;
    public final StaleWatchdogCheck staleCheck;
    public final app.autopilot.outcome.OutcomeService outcomes;
    public final List<app.autopilot.store.ProposalStore.Proposal> filed = new CopyOnWriteArrayList<>();

    private final AtomicInteger keyCounter = new AtomicInteger();

    /** Collects what the owner would be told. */
    public static final class RecordingChannel implements NotificationChannel {
        public final List<Notification> sent = new CopyOnWriteArrayList<>();
        @Override public String name() { return "test"; }
        @Override public void send(Notification n) { sent.add(n); }
        public List<Notification> byTitle(String title) { return sent.stream().filter(n -> n.title().equals(title)).toList(); }
    }

    public TestWorld() {
        this(TestLimits.defaults().build(), WEDNESDAY_MORNING);
    }

    public TestWorld(Limits limits, Instant start) {
        this.clock = new TestClock(start);
        this.db = PgTestSupport.newDatabase();
        this.dataSource = new FlakyDataSource(db.app());
        this.fake = new SandboxAdsGateway(clock::instant);
        JdbcClient jdbc = JdbcClient.create(dataSource);
        this.configStore = new ConfigStore(jdbc, json);
        this.runStore = new RunStore(jdbc);
        this.proposalStore = new ProposalStore(jdbc, json);
        this.ledger = new LedgerStore(jdbc, json);
        this.alertStore = new AlertStore(jdbc, json);
        this.metricStore = new MetricStore(jdbc, json);
        Goals goals = Goals.of("leads", List.of("lead"), 20_000_000L, null, List.of("acme brand"));
        this.props = new AutopilotProperties("owner@example.com", false,
                new AutopilotProperties.Runs(120, LocalTime.of(4, 0), LocalTime.of(10, 0), DayOfWeek.MONDAY,
                        LocalTime.of(4, 0), LocalTime.of(12, 0), 1000, 14, false),
                new AutopilotProperties.Security("test-link-secret-test-link-secret-123456", "https://autopilot.test",
                        "https://autopilot.test/mcp", "owner@example.com", null, null, java.util.Map.of(), false, null),
                new AutopilotProperties.Alerting(null, null, null, null, false),
                new AutopilotProperties.Watchdog(15, null, 35, 12, 40),
                new AutopilotProperties.Ads("none", null, null, SandboxAdsGateway.SERVICE_ACCOUNT),
                new AutopilotProperties.Retention(24, 13));
        this.config = new RuntimeConfig(configStore, limits, goals, props);
        config.syncVersions();
        this.quota = new QuotaMeter(metricStore, config, clock);
        this.ads = new MeteredAdsGateway(fake, quota);
        this.alerts = new AlertService(alertStore, notifier, clock);
        this.audit = new Audit(ledger, json.mapper());
        this.runs = new RunService(runStore, config, props, alerts, clock);
        this.guard = new Guard(ads, ledger, alertStore, clock);
        this.executor = new ChangeExecutor(ads, audit, ledger, alerts, json, clock);
        ProposalListener listener = filed::add;
        this.changes = new ChangeService(guard, executor, config, runs, ledger, proposalStore, alertStore, audit,
                new PgWriteLock(dataSource), json, clock, List.of(listener));
        this.links = new ApprovalLinks(props);
        ProposalExpiry expiry = new ProposalExpiry(proposalStore, audit, clock);
        this.approvals = new ApprovalService(proposalStore, links, guard, executor, config, alertStore, audit, expiry,
                new PgWriteLock(dataSource), props, clock);
        this.admin = new AdminService(configStore, audit, props, clock);
        this.spendMonitor = new SpendMonitor(ads);
        this.breaker = new CircuitBreaker(executor, configStore, audit, clock);
        this.snapshots = new SnapshotService(ads, metricStore, clock);
        this.conversions = new ConversionMonitor(metricStore, alerts, props, clock);
        this.heartbeat = new Heartbeat(metricStore, props, clock);
        this.watchdog = new Watchdog(ads, spendMonitor, breaker, config, configStore, alerts, alertStore, ledger, metricStore, quota,
                snapshots, conversions, expiry, proposalStore, notifier, heartbeat, props, clock);
        this.staleCheck = new StaleWatchdogCheck(metricStore, alerts, props, clock);
        this.outcomes = new app.autopilot.outcome.OutcomeService(ads, metricStore, ledger, config, audit, json, clock);
        seed();
    }

    /** Approves with the proposal's own valid link, as the owner. */
    public ApprovalService.Result approve(app.autopilot.store.ProposalStore.Proposal p) {
        ApprovalLinks.Token t = links.token(p);
        return approvals.approve(p.id(), t.expiresAt(), t.nonce(), t.signature(), "owner@example.com");
    }

    public app.autopilot.store.ProposalStore.Proposal proposal(String id) {
        return proposalStore.find(UUID.fromString(id)).orElseThrow();
    }

    private void seed() {
        fake.seedStandard();
    }

    /** Releases the database connections. */
    public void close() {
        db.close();
    }

    public void useLimits(Limits limits) {
        configStore.saveLimits(limits);
    }

    public Run startRun(Loop loop) {
        return runs.start(loop, "test-v1");
    }

    public ChangeMeta meta(Run run) {
        return meta(run, "key-" + keyCounter.incrementAndGet());
    }

    public ChangeMeta meta(Run run, String key) {
        return new ChangeMeta("test reason", "test expected effect", run.id().toString(), key);
    }

    public List<String> auditEvents() {
        List<String> out = new ArrayList<>();
        ledger.auditFor("ATTEMPT").forEach(r -> out.add("ATTEMPT"));
        return out;
    }

    /** Records a change as already applied at a given time, to test cooldowns, intervals and daily limits. */
    public void recordApplied(String tool, String tier, String entityKey, int units, Instant at, String fingerprint) {
        recordApplied(tool, tier, entityKey, units, at, fingerprint, "{}");
    }

    public void recordApplied(String tool, String tier, String entityKey, int units, Instant at, String fingerprint,
                              String requestJson) {
        ledger.insertChange(new LedgerStore.ChangeRecord(UUID.randomUUID(), null, null, tool, tier, entityKey,
                fingerprint == null ? "" : fingerprint, units, "claude", null, requestJson, java.util.Map.of(),
                java.util.Map.of(), "seed", List.of(), "APPLIED", null, at));
    }

    public UUID newRunId() {
        return UUID.randomUUID();
    }
}

package app.autopilot.outcome;

import app.autopilot.ads.AdsGateway;
import app.autopilot.config.RuntimeConfig;
import app.autopilot.domain.EntityKind;
import app.autopilot.domain.EntityRef;
import app.autopilot.domain.Limits;
import app.autopilot.exec.Audit;
import app.autopilot.outcome.OutcomeMath.Measurement;
import app.autopilot.store.Json;
import app.autopilot.store.LedgerStore;
import app.autopilot.store.LedgerStore.ChangeRecord;
import app.autopilot.store.LedgerStore.OutcomeRow;
import app.autopilot.store.MetricStore;
import app.autopilot.store.MetricStore.Snapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Closes the loop on a change: measures what happened in the campaigns it touched (from the daily snapshots the watchdog
 * keeps, so reviewing costs no Google Ads operations) and stores the reviewer's verdict and what was learned. The learnings
 * are shown to later runs, as untrusted text, so the agent stops repeating what did not work.
 */
@Component
public class OutcomeService {

    private static final Logger log = LoggerFactory.getLogger(OutcomeService.class);

    /** Days before the change that form the baseline. */
    public static final int BEFORE_DAYS = 7;
    public static final Set<String> VERDICTS = Set.of("BETTER", "NEUTRAL", "WORSE", "UNKNOWN");
    public static final int LEARNING_MAX = 500;

    public enum Recorded { SAVED, UNKNOWN_CHANGE, NOT_DUE, ALREADY_REVIEWED }

    public record Review(UUID changeId, String tool, String entityKey, Instant appliedAt, Instant reviewAt, boolean due,
                         String recordedVerdict, List<String> campaignIds, Measurement measurement, Map<String, String> intent) {}

    private final AdsGateway ads;
    private final MetricStore metrics;
    private final LedgerStore ledger;
    private final RuntimeConfig config;
    private final Audit audit;
    private final Json json;
    private final Clock clock;

    public OutcomeService(AdsGateway ads, MetricStore metrics, LedgerStore ledger, RuntimeConfig config, Audit audit, Json json, Clock clock) {
        this.ads = ads;
        this.metrics = metrics;
        this.ledger = ledger;
        this.config = config;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    /** Changes whose review time has come and that nobody has reviewed yet. */
    public List<Review> due(int limit) {
        List<Review> out = new ArrayList<>();
        for (LedgerStore.OutcomeReview r : ledger.dueOutcomes(clock.instant())) {
            if (out.size() >= limit) break;
            ledger.findOutcome(r.changeId()).flatMap(this::measure).ifPresent(out::add);
        }
        return out;
    }

    public Optional<Review> review(UUID changeId) {
        return ledger.findOutcome(changeId).flatMap(this::measure);
    }

    Optional<Review> measure(OutcomeRow row) {
        Optional<ChangeRecord> found = ledger.findChange(row.changeId());
        if (found.isEmpty()) return Optional.empty();
        ChangeRecord change = found.get();
        Limits limits = config.limitsOrCached();
        Instant now = clock.instant();

        List<String> campaigns = campaignIdsFor(change.entityKey());
        LocalDate changeDay = LocalDate.ofInstant(change.createdAt(), limits.zone());
        LocalDate today = LocalDate.ofInstant(now, limits.zone());
        // Conversions arrive late, so the latest days are left out of the "after" side; costs and clicks use the same days to stay comparable.
        LocalDate afterTo = today.minusDays(1L + limits.conversionLagDays());
        List<Snapshot> before = new ArrayList<>();
        List<Snapshot> after = new ArrayList<>();
        for (String id : campaigns) {
            before.addAll(metrics.snapshots("CAMPAIGN:" + id, "DAILY", changeDay.minusDays(BEFORE_DAYS), changeDay.minusDays(1)));
            if (!afterTo.isBefore(changeDay.plusDays(1))) {
                after.addAll(metrics.snapshots("CAMPAIGN:" + id, "DAILY", changeDay.plusDays(1), afterTo));
            }
        }
        Measurement m = OutcomeMath.evaluate(before, after);
        if (campaigns.isEmpty()) {
            m = new Measurement(OutcomeMath.Confidence.NONE, "UNKNOWN", m.before(), m.after(), null, null, null, null, null,
                    "The campaign this change touched could not be determined (it may have been removed), so nothing was measured.");
        }
        return Optional.of(new Review(change.id(), change.tool(), change.entityKey(), change.createdAt(), row.reviewAt(),
                !row.reviewAt().isAfter(now), row.reviewedAt() == null ? null : row.verdict(), campaigns, m,
                ledger.appliedIntent(change.id()).orElse(Map.of())));
    }

    /** Stores the reviewer's verdict and learning for a change that is due. Never overwrites an earlier review. */
    public Recorded record(UUID changeId, String verdict, String learning, UUID runId, String loop) {
        Optional<OutcomeRow> found = ledger.findOutcome(changeId);
        if (found.isEmpty()) return Recorded.UNKNOWN_CHANGE;
        OutcomeRow row = found.get();
        if (row.reviewedAt() != null) return Recorded.ALREADY_REVIEWED;
        Instant now = clock.instant();
        if (row.reviewAt().isAfter(now)) return Recorded.NOT_DUE;

        Optional<Review> review = measure(row);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("learning", learning);
        review.ifPresent(r -> result.put("measurement", json.readMap(json.write(r.measurement()))));
        result.put("reviewedInRun", runId == null ? null : runId.toString());
        result.put("reviewedByLoop", loop);
        if (!ledger.completeOutcome(row.id(), verdict, result, now)) return Recorded.ALREADY_REVIEWED;

        String entity = review.map(Review::entityKey).orElse(null);
        audit.recordOrLog(Audit.event("OUTCOME_REVIEWED", now).run(runId).loop(loop).tool("record_outcome_review").entity(entity)
                .message(verdict + ": " + learning).change(changeId).build());
        log.info("Outcome of change {} reviewed: {}", changeId, verdict);
        return Recorded.SAVED;
    }

    /** The campaigns a change touched, from its ledger key such as CAMPAIGN:1, BUDGET:2, KEYWORD:3~4 or AD_GROUP:5#add_keywords. */
    List<String> campaignIdsFor(String entityKey) {
        if (entityKey == null) return List.of();
        String key = entityKey.contains("#") ? entityKey.substring(0, entityKey.indexOf('#')) : entityKey;
        int colon = key.indexOf(':');
        if (colon < 0) return List.of();
        try {
            EntityKind kind = EntityKind.valueOf(key.substring(0, colon));
            String id = key.substring(colon + 1);
            Set<String> out = new LinkedHashSet<>();
            switch (kind) {
                case CAMPAIGN -> out.add(id);
                case BUDGET -> ads.budgets().stream().filter(b -> b.id().equals(id)).forEach(b -> out.addAll(b.campaignIds()));
                default -> ads.entityState(new EntityRef(kind, id), null, null).map(s -> s.campaignId()).ifPresent(out::add);
            }
            return List.copyOf(out);
        } catch (RuntimeException e) {
            log.warn("Could not determine the campaign of {}: {}", entityKey, e.toString());
            return List.of();
        }
    }
}

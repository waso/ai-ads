package app.autopilot.guard;

import app.autopilot.domain.BiddingStrategy;
import app.autopilot.domain.ChangeOutcome;
import app.autopilot.domain.ChangeRequest.AddNegativeKeywords;
import app.autopilot.domain.ChangeRequest.AdjustKeywordBid;
import app.autopilot.domain.ChangeRequest.PauseKeyword;
import app.autopilot.domain.EntityRef;
import app.autopilot.domain.Goals;
import app.autopilot.domain.Loop;
import app.autopilot.domain.MatchType;
import app.autopilot.store.RunStore.Run;
import app.autopilot.ads.sandbox.SandboxAdsGateway;
import app.autopilot.testkit.TestLimits;
import app.autopilot.testkit.WorldTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GuardKeywordTest extends WorldTest {

    private static final EntityRef K1 = EntityRef.keyword("G1~K1"); // bid 1.00, 150 clicks, 90.00 cost, 0 conversions
    private static final EntityRef C1 = EntityRef.campaign("C1");

    private ChangeOutcome bid(long micros) { return submit(new AdjustKeywordBid(K1, micros)); }

    private ChangeOutcome negatives(MatchType mt, String... terms) {
        return submit(new AddNegativeKeywords(C1, List.of(terms), mt));
    }

    // ---- bids ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("[AC-01] bid change: +10% and -10% are applied, one micro over either way is refused")
    void bidBoundary() {
        ChangeOutcome up = bid(1_100_001);
        assertRefused(up, Guard.Rule.BID_CHANGE_PCT);
        assertThat(up.message()).contains("exceeds the +-10% limit");
        assertRefused(bid(899_999), Guard.Rule.BID_CHANGE_PCT);
        assertApplied(bid(1_100_000));
        assertThat(w.fake.keywords.get("G1~K1").cpcBidMicros).isEqualTo(1_100_000L);
    }

    @Test
    @DisplayName("[AC-01] bid change is allowed once per 7 days per keyword")
    void bidInterval() {
        Instant now = w.clock.instant();
        w.recordApplied("adjust_keyword_bid", "T1", "KEYWORD:G1~K1", 1, now.minus(Duration.ofDays(7)).plusSeconds(1), "f");
        assertRefused(bid(1_050_000), Guard.Rule.BID_INTERVAL);
        w.clock.advance(Duration.ofSeconds(1));
        assertApplied(bid(1_050_000));
    }

    @Test
    @DisplayName("[AC-01] bids can be changed only on Manual CPC campaigns")
    void manualCpcOnly() {
        w.fake.campaigns.get("C1").bidding = BiddingStrategy.maximizeConversions();
        ChangeOutcome o = bid(1_050_000);
        assertRefused(o, Guard.Rule.MANUAL_CPC_ONLY);
        assertThat(o.message()).contains("MAXIMIZE_CONVERSIONS");
    }

    // ---- negatives ----------------------------------------------------------------------------------

    private List<String> terms(int n) {
        List<String> t = new ArrayList<>();
        for (int i = 1; i <= n; i++) t.add("zzterm" + i);
        return t;
    }

    @Test
    @DisplayName("[AC-01] negative keywords: 25 per day is applied, 26 is refused")
    void negativesPerDayBoundary() {
        ChangeOutcome over = negatives(MatchType.PHRASE, terms(26).toArray(new String[0]));
        assertRefused(over, Guard.Rule.NEGATIVES_PER_DAY);
        assertThat(over.message()).contains("limit of 25 per day");
        assertApplied(negatives(MatchType.PHRASE, terms(25).toArray(new String[0])));
        assertThat(w.fake.negatives).hasSize(25);
    }

    @Test
    @DisplayName("[AC-01] negative keywords count what was already added today")
    void negativesCountTodaysUnits() {
        w.recordApplied("add_negative_keywords", "T1", "CAMPAIGN:C1#add_negative_keywords", 20, w.clock.instant(), "seed");
        assertRefused(negatives(MatchType.PHRASE, terms(6).toArray(new String[0])), Guard.Rule.NEGATIVES_PER_DAY);
        assertApplied(negatives(MatchType.PHRASE, terms(5).toArray(new String[0])));
    }

    @Test
    @DisplayName("[AC-01] negative keywords must be exact or phrase match")
    void negativesNeverBroad() {
        assertRefused(negatives(MatchType.BROAD, "zzterm1"), Guard.Rule.NEGATIVE_MATCH_TYPE);
    }

    @Test
    @DisplayName("[AC-09] a negative that would block an active keyword is refused")
    void negativeBlocksActiveKeyword() {
        ChangeOutcome o = negatives(MatchType.PHRASE, "shoes");
        assertRefused(o, Guard.Rule.NEGATIVE_CONFLICT);
        assertThat(o.message()).contains("active keyword 'red shoes'");
        assertRefused(negatives(MatchType.EXACT, "red shoes"), Guard.Rule.NEGATIVE_CONFLICT);
        assertApplied(negatives(MatchType.EXACT, "red"));
    }

    @Test
    @DisplayName("[AC-09] a negative that would block a protected term is refused")
    void negativeBlocksProtectedTerm() {
        w.fake.keywords.remove("G1~K3"); // the brand keyword would also be a conflict; isolate the protected-term rule
        ChangeOutcome o = negatives(MatchType.PHRASE, "acme");
        assertRefused(o, Guard.Rule.NEGATIVE_CONFLICT);
        assertThat(o.message()).contains("protected term 'acme brand'");
    }

    @Test
    @DisplayName("[AC-09] a negative that would block a search term that converted is refused")
    void negativeBlocksConvertedTerm() {
        w.fake.searchTerm("cheap red shoes", "C1", "G1", 12, 9_000_000, 1);
        w.fake.searchTerm("free stuff", "C1", "G1", 30, 9_000_000, 0);
        ChangeOutcome o = negatives(MatchType.PHRASE, "cheap");
        assertRefused(o, Guard.Rule.NEGATIVE_CONFLICT);
        assertThat(o.message()).contains("converted in the last 90 days");
        assertApplied(negatives(MatchType.PHRASE, "free"));
    }

    @Test
    @DisplayName("[AC-09] an existing negative is not added twice")
    void negativeAlreadyExists() {
        assertApplied(negatives(MatchType.PHRASE, "zzterm1"));
        w.clock.advance(Duration.ofDays(4));
        Run later = w.startRun(Loop.DAILY);
        assertRefused(submitAs(later, new AddNegativeKeywords(C1, List.of("zzterm1"), MatchType.PHRASE)), Guard.Rule.NEGATIVE_EXISTS);
    }

    // ---- keyword pause -----------------------------------------------------------------------------

    private ChangeOutcome pause() { return submit(new PauseKeyword(K1)); }

    @Test
    @DisplayName("[AC-10] keyword pause needs 100 clicks: 99 refused, 100 applied")
    void pauseClickBoundary() {
        SandboxAdsGateway.Keyword k = w.fake.keywords.get("G1~K1");
        k.clicks = 99;
        ChangeOutcome o = pause();
        assertRefused(o, Guard.Rule.KEYWORD_THRESHOLD);
        assertThat(o.message()).contains("99 clicks").contains("minimum is 100");
        k.clicks = 100;
        assertApplied(pause());
        assertThat(k.status).isEqualTo("PAUSED");
    }

    @Test
    @DisplayName("[AC-10] keyword pause needs a cost of 2 x target CPA: one micro under is refused")
    void pauseCostBoundary() {
        SandboxAdsGateway.Keyword k = w.fake.keywords.get("G1~K1");
        k.costMicros = 39_999_999;
        assertRefused(pause(), Guard.Rule.KEYWORD_THRESHOLD);
        k.costMicros = 40_000_000;
        assertApplied(pause());
    }

    @Test
    @DisplayName("[AC-10] a keyword with any conversion is never paused")
    void pauseNeedsZeroConversions() {
        w.fake.keywords.get("G1~K1").conversions = 0.5;
        assertRefused(pause(), Guard.Rule.KEYWORD_THRESHOLD);
    }

    @Test
    @DisplayName("[AC-10] keyword pause needs a target CPA in the goals")
    void pauseNeedsTargetCpa() {
        w.configStore.saveGoals(Goals.of("leads", List.of("lead"), null, null, List.of()));
        assertRefused(pause(), Guard.Rule.TARGET_CPA_MISSING);
    }

    @Test
    @DisplayName("[AC-10] the ad group keeps at least one active keyword")
    void pauseKeepsOneActiveKeyword() {
        w.fake.keywords.get("G1~K2").status = "PAUSED";
        w.fake.keywords.get("G1~K3").status = "PAUSED";
        assertRefused(pause(), Guard.Rule.LAST_KEYWORD);
    }

    @Test
    @DisplayName("[AC-10] performance data excludes the latest 3 days: the window ends 3 days ago")
    void pauseWindowExcludesRecentDays() {
        assertApplied(pause());
        // account-local today is 2026-11-04; the 30-day window must end on 2026-11-01
        assertThat(w.fake.lastMetricsTo).isEqualTo(java.time.LocalDate.of(2026, 11, 1));
        assertThat(w.fake.lastMetricsFrom).isEqualTo(java.time.LocalDate.of(2026, 10, 3));
    }

    @Test
    @DisplayName("[AC-01] Tier 1 volume: the 51st change of a day is refused")
    void tier1VolumeBoundary() {
        w.useLimits(TestLimits.defaults().tier1PerDay(2).build());
        w.recordApplied("pause_keyword", "T1", "KEYWORD:G9~K9", 1, w.clock.instant(), "a");
        assertApplied(bid(1_050_000)); // second change of the day: allowed
        ChangeOutcome third = submit(new AdjustKeywordBid(EntityRef.keyword("G1~K2"), 2_100_000));
        assertRefused(third, Guard.Rule.TIER1_VOLUME);
        assertThat(third.message()).contains("limit 2 per day");
    }

    @Test
    @DisplayName("[AC-01] negatives are additive: two batches on the same campaign in one day are both allowed")
    void negativesDoNotStartCooldownOnTheCampaign() {
        assertApplied(negatives(MatchType.PHRASE, "zzterm1", "zzterm2"));
        assertApplied(negatives(MatchType.PHRASE, "zzterm3"));
        // and the campaign itself is not in cooldown for a real change such as a pause of its budget
        assertApplied(submit(new app.autopilot.domain.ChangeRequest.DecreaseBudget(EntityRef.budget("B1"), 45_000_000)));
    }
}

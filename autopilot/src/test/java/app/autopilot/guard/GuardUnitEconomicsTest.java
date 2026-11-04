package app.autopilot.guard;

import app.autopilot.domain.BiddingStrategy;
import app.autopilot.domain.ChangeOutcome;
import app.autopilot.domain.ChangeRequest.CampaignSpec;
import app.autopilot.domain.ChangeRequest.ChangeBiddingStrategy;
import app.autopilot.domain.ChangeRequest.CreateCampaign;
import app.autopilot.domain.ChangeRequest.DecreaseBudget;
import app.autopilot.domain.ChangeRequest.EnableEntity;
import app.autopilot.domain.ChangeRequest.EuPoliticalAds;
import app.autopilot.domain.ChangeRequest.KeywordSpec;
import app.autopilot.domain.EntityRef;
import app.autopilot.domain.Goals;
import app.autopilot.domain.MatchType;
import app.autopilot.testkit.TestLimits;
import app.autopilot.testkit.WorldTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The guard rules that keep a small budget pointed at sales: break-even, campaign count, minimum budget. */
class GuardUnitEconomicsTest extends WorldTest {

    private static final List<String> HEADLINES = List.of("Dashboards in minutes", "From a spreadsheet", "Try it today");
    private static final List<String> DESCRIPTIONS = List.of("Turn a spreadsheet into a live dashboard.", "Start for a few dollars a month.");

    private CampaignSpec spec(String name, long budget, BiddingStrategy bidding) {
        return new CampaignSpec(name, budget, bidding, List.of(2616L), "Ad group 1",
                List.of(new KeywordSpec("spreadsheet dashboard", MatchType.PHRASE, 1_000_000L)), HEADLINES, DESCRIPTIONS,
                "https://example.com/pricing", EuPoliticalAds.NO_POLITICAL_ADS);
    }

    private void breakEven160() {
        // lifetime value 200 PLN x 80% margin = 160 PLN break-even cost per sale
        w.configStore.saveGoals(new Goals("paid subscriptions", List.of("paid"), 100_000_000L, null, List.of(), 38_000_000L, 200_000_000L, 80));
    }

    // ---- break-even -------------------------------------------------------------------------------------

    @Test
    @DisplayName("[AC-41] break-even is lifetime value x gross margin, and unknown when either is missing")
    void breakEvenArithmetic() {
        assertThat(new Goals("g", List.of(), null, null, List.of(), null, 200_000_000L, 80).breakEvenCpaMicros()).isEqualTo(160_000_000L);
        assertThat(new Goals("g", List.of(), null, null, List.of(), null, 182_000_000L, 100).breakEvenCpaMicros()).isEqualTo(182_000_000L);
        assertThat(new Goals("g", List.of(), null, null, List.of(), null, 200_000_000L, null).breakEvenCpaMicros()).isNull();
        assertThat(new Goals("g", List.of(), null, null, List.of(), null, null, 80).breakEvenCpaMicros()).isNull();
        assertThat(new Goals("g", List.of(), null, null, List.of(), null, 0L, 80).breakEvenCpaMicros()).isNull();
        assertThat(Goals.empty().breakEvenCpaMicros()).isNull();
    }

    @Test
    @DisplayName("[AC-41] a new campaign with a target CPA above break-even is refused; at break-even it is proposed")
    void createCampaignBreakEven() {
        breakEven160();
        ChangeOutcome over = submit(new CreateCampaign(spec("Over", 10_000_000, BiddingStrategy.targetCpa(160_000_001))));
        assertRefused(over, Guard.Rule.TARGET_CPA_ABOVE_BREAK_EVEN);
        assertThat(over.message()).contains("160.00").contains("lifetime value x gross margin").contains("lose money");
        assertProposed(submit(new CreateCampaign(spec("At break-even", 10_000_000, BiddingStrategy.targetCpa(160_000_000)))));
        assertProposed(submit(new CreateCampaign(spec("Manual", 10_000_000, BiddingStrategy.manualCpc())))); // not a target CPA
    }

    @Test
    @DisplayName("[AC-41] changing a campaign to a target CPA above break-even is refused")
    void changeBiddingBreakEven() {
        breakEven160();
        EntityRef c1 = EntityRef.campaign("C1");
        assertRefused(submit(new ChangeBiddingStrategy(c1, BiddingStrategy.targetCpa(160_000_001))), Guard.Rule.TARGET_CPA_ABOVE_BREAK_EVEN);
        assertProposed(submit(new ChangeBiddingStrategy(c1, BiddingStrategy.targetCpa(160_000_000))));
    }

    @Test
    @DisplayName("[AC-41] without lifetime value and margin nothing is checked against break-even")
    void noBreakEvenNoCheck() {
        assertProposed(submit(new ChangeBiddingStrategy(EntityRef.campaign("C1"), BiddingStrategy.targetCpa(9_999_000_000L))));
    }

    // ---- campaign count and minimum budget -----------------------------------------------------------------

    @Test
    @DisplayName("[AC-42] at the limit of enabled campaigns none is created or enabled; one more fits when the limit is raised")
    void campaignLimit() {
        // the test account has 3 enabled campaigns
        w.useLimits(TestLimits.defaults().cap(10_000_000_000L).maxActiveCampaigns(3).build());
        ChangeOutcome create = submit(new CreateCampaign(spec("Fourth", 10_000_000, BiddingStrategy.manualCpc())));
        assertRefused(create, Guard.Rule.CAMPAIGN_LIMIT);
        assertThat(create.message()).contains("At most 3 campaigns").contains("pause one first");

        w.fake.budget("B5", 20_000_000L);
        w.fake.campaign("C5", "Paused one", "B5", "agent-managed").status = "PAUSED";
        assertRefused(submit(new EnableEntity(EntityRef.campaign("C5"))), Guard.Rule.CAMPAIGN_LIMIT);

        w.useLimits(TestLimits.defaults().cap(10_000_000_000L).maxActiveCampaigns(4).build());
        assertProposed(submit(new CreateCampaign(spec("Fourth", 10_000_000, BiddingStrategy.manualCpc()))));
        assertProposed(submit(new EnableEntity(EntityRef.campaign("C5"))));
    }

    @Test
    @DisplayName("[AC-42] a limit of 0 means no limit")
    void zeroMeansUnlimited() {
        w.useLimits(TestLimits.defaults().cap(10_000_000_000L).maxActiveCampaigns(0).minDailyBudget(0).build());
        assertProposed(submit(new CreateCampaign(spec("Tiny", 1_000_000, BiddingStrategy.manualCpc()))));
    }

    @Test
    @DisplayName("[AC-42] a campaign cannot be created below the daily minimum, exactly at it is fine")
    void minimumBudgetOnCreate() {
        w.useLimits(TestLimits.defaults().cap(10_000_000_000L).minDailyBudget(20_000_000L).build());
        ChangeOutcome low = submit(new CreateCampaign(spec("Low", 19_999_999, BiddingStrategy.manualCpc())));
        assertRefused(low, Guard.Rule.BUDGET_BELOW_MINIMUM);
        assertThat(low.message()).contains("19.999999").contains("minimum of 20.00");
        assertProposed(submit(new CreateCampaign(spec("Enough", 20_000_000, BiddingStrategy.manualCpc()))));
    }

    @Test
    @DisplayName("[AC-42] a budget cannot be lowered below the daily minimum; pause the campaign instead")
    void minimumBudgetOnDecrease() {
        w.useLimits(TestLimits.defaults().minDailyBudget(45_000_000L).build());
        ChangeOutcome low = submit(new DecreaseBudget(EntityRef.budget("B1"), 44_999_999)); // -10%: inside the step limit
        assertRefused(low, Guard.Rule.BUDGET_BELOW_MINIMUM);
        assertThat(low.message()).contains("pause the campaign instead");
        assertApplied(submit(new DecreaseBudget(EntityRef.budget("B1"), 45_000_000)));
    }
}

package app.autopilot.watchdog;

import app.autopilot.ads.AccountMath;
import app.autopilot.ads.AdsGateway;
import app.autopilot.ads.AdsGateway.BudgetInfo;
import app.autopilot.ads.AdsGateway.CampaignInfo;
import app.autopilot.domain.Limits;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Spend against caps. Spend is read for the whole account, because that is where the money goes. The breaker
 * can only pause the campaigns Claude manages, so each campaign says whether it is managed.
 */
@Component
public class SpendMonitor {

    public record CampaignSpend(String id, String name, boolean enabled, boolean managed, long todayMicros,
                                long monthMicros, long dailyBudgetMicros) {}

    public record Snapshot(long todayMicros, long monthMicros, long summedDailyBudgetsMicros,
                           Long monthlyCapMicros, List<CampaignSpend> campaigns) {
        /** Today's spend as a multiple of the summed daily budgets (0 when there are no enabled budgets). */
        public double todayMultiple() {
            return summedDailyBudgetsMicros == 0 ? 0 : (double) todayMicros / summedDailyBudgetsMicros;
        }

        public long managedTodayMicros() {
            return campaigns.stream().filter(CampaignSpend::managed).mapToLong(CampaignSpend::todayMicros).sum();
        }

        public long unmanagedTodayMicros() {
            return todayMicros - managedTodayMicros();
        }
    }

    private final AdsGateway ads;

    public SpendMonitor(AdsGateway ads) {
        this.ads = ads;
    }

    public Snapshot snapshot(Limits limits) {
        List<CampaignInfo> campaigns = ads.campaigns();
        List<BudgetInfo> budgets = ads.budgets();
        List<CampaignSpend> rows = campaigns.stream().map(c -> new CampaignSpend(c.id(), c.name(), c.enabled(),
                c.labels().contains(limits.managedLabel()) && !c.labels().contains(limits.noAgentLabel()),
                c.costTodayMicros(), c.costMonthMicros(), AccountMath.budgetOf(c, budgets))).toList();
        return new Snapshot(rows.stream().mapToLong(CampaignSpend::todayMicros).sum(),
                rows.stream().mapToLong(CampaignSpend::monthMicros).sum(),
                AccountMath.sumDailyBudgets(campaigns, budgets), limits.monthlyCapMicros(), rows);
    }
}

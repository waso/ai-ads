package app.autopilot.ads;

import app.autopilot.ads.AdsGateway.BudgetInfo;
import app.autopilot.ads.AdsGateway.CampaignInfo;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Budget arithmetic shared by the guard, the watchdog and the account-health tool. */
public final class AccountMath {
    private AccountMath() {}

    /** Sum of daily budgets of budgets used by at least one enabled campaign, each counted once. */
    public static long sumDailyBudgets(List<CampaignInfo> campaigns, List<BudgetInfo> budgets) {
        Set<String> enabledBudgets = new HashSet<>();
        for (CampaignInfo c : campaigns) {
            if (c.enabled() && c.budgetId() != null) enabledBudgets.add(c.budgetId());
        }
        long sum = 0;
        for (BudgetInfo b : budgets) {
            if (enabledBudgets.contains(b.id())) sum += b.amountMicros();
        }
        return sum;
    }

    /** Daily budget of each campaign (a shared budget shows on each campaign that uses it). */
    public static long budgetOf(CampaignInfo c, List<BudgetInfo> budgets) {
        return budgets.stream().filter(b -> b.id().equals(c.budgetId())).mapToLong(BudgetInfo::amountMicros).findFirst().orElse(0);
    }
}

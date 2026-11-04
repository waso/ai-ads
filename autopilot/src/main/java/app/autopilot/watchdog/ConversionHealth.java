package app.autopilot.watchdog;

import app.autopilot.ads.AdsGateway.ConversionActionInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Is the signal the account optimizes toward real? The goal is paid subscriptions, so the primary conversion must
 * be a sale, must be recording results, and must carry a value. Pure logic: the same findings feed the read tool Claude
 * uses and the daily watchdog check.
 *
 * <p>Findings never contain action names (they are free text); they name actions by id.
 */
public final class ConversionHealth {
    private ConversionHealth() {}

    public enum Severity { INFO, WARNING, CRITICAL }

    public record Finding(String code, Severity severity, String message) {}

    public static final String NO_CONVERSION_ACTION = "NO_CONVERSION_ACTION";
    public static final String NO_SALES_CONVERSION = "NO_SALES_CONVERSION";
    public static final String NON_SALES_PRIMARY = "NON_SALES_PRIMARY";
    public static final String NO_RECENT_SALES = "NO_RECENT_SALES";
    public static final String NO_SALE_VALUE = "NO_SALE_VALUE";

    /** Google conversion categories that count as a sale. */
    public static final Set<String> SALES_CATEGORIES = Set.of("PURCHASE", "SUBSCRIBE_PAID");

    public static List<Finding> evaluate(List<ConversionActionInfo> actions, boolean accountIsSpending) {
        List<Finding> out = new ArrayList<>();
        List<ConversionActionInfo> enabled = actions.stream().filter(a -> "ENABLED".equals(a.status())).toList();
        if (enabled.isEmpty()) {
            out.add(new Finding(NO_CONVERSION_ACTION, Severity.CRITICAL,
                    "The account has no enabled conversion action, so there is nothing to optimize toward."));
            return out;
        }
        List<ConversionActionInfo> primary = enabled.stream().filter(ConversionActionInfo::primaryForGoal).toList();
        List<ConversionActionInfo> primarySales = primary.stream().filter(a -> SALES_CATEGORIES.contains(a.category())).toList();
        if (primarySales.isEmpty()) {
            String categories = primary.stream().map(ConversionActionInfo::category).distinct().collect(Collectors.joining(", "));
            out.add(new Finding(NO_SALES_CONVERSION, Severity.CRITICAL, "No primary conversion action is a sale (PURCHASE or "
                    + "SUBSCRIBE_PAID). Bidding is optimizing toward: " + (categories.isEmpty() ? "nothing" : categories)
                    + ". Make the paid-subscription action primary for the goal."));
        }
        List<String> nonSales = primary.stream().filter(a -> !SALES_CATEGORIES.contains(a.category())).map(ConversionActionInfo::id).toList();
        if (!primarySales.isEmpty() && !nonSales.isEmpty()) {
            out.add(new Finding(NON_SALES_PRIMARY, Severity.WARNING, "Primary conversion action(s) " + nonSales
                    + " are not sales, so bidding also chases them and cheap sign-ups can look like wins."));
        }
        for (ConversionActionInfo a : primarySales) {
            if (accountIsSpending && a.conversions30d() <= 0) {
                out.add(new Finding(NO_RECENT_SALES, Severity.WARNING, "Primary sale action " + a.id() + " recorded no conversions in 30 "
                        + "days while the account was spending. Check the tracking; if the sale event is sent from your server, check "
                        + "that the click id (gclid, gbraid or wbraid) is stored at sign-up and sent with it, or Google cannot "
                        + "attribute the sale to an ad."));
            } else if (a.conversions30d() > 0 && a.conversionValue30d() <= 0) {
                out.add(new Finding(NO_SALE_VALUE, Severity.WARNING, "Primary sale action " + a.id() + " recorded "
                        + Math.round(a.conversions30d()) + " conversions in 30 days with no value, so revenue-based optimization has "
                        + "nothing to work with."));
            }
        }
        return out;
    }
}

package app.autopilot.guard;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates the GAQL that {@code run_report} will run. GAQL cannot write, so the risk is reading what Claude has
 * no business reading and pulling unbounded data. A query is accepted only when it is one SELECT on an
 * allowlisted resource, selects no account-level or billing fields, and carries a row cap.
 */
public final class ReportGuard {
    private ReportGuard() {}

    public static final int DEFAULT_ROWS = 200;
    public static final int MAX_ROWS = 1000;

    static final Set<String> RESOURCES = Set.of("campaign", "ad_group", "ad_group_ad", "ad_group_criterion", "keyword_view",
            "search_term_view", "campaign_budget", "change_event", "recommendation", "campaign_criterion", "campaign_label",
            "ad_group_label", "label", "ad_group_ad_label", "ad_group_criterion_label", "geographic_view", "age_range_view",
            "gender_view", "landing_page_view", "ad_schedule_view");

    /** Field path prefixes that expose the account, billing, users or other accounts. */
    private static final List<String> DENIED_FIELDS = List.of("customer", "billing", "account_budget", "payments",
            "user_", "accessible_", "manager", "invoice", "account_link", "conversion_action", "customer_client");

    private static final Pattern SHAPE = Pattern.compile("^\\s*SELECT\\s+(.+?)\\s+FROM\\s+([a-z_]+)\\b(.*)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern LIMIT = Pattern.compile("\\bLIMIT\\s+(\\d+)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern FIELD = Pattern.compile("[a-z_][a-z0-9_]*(\\.[a-z_][a-z0-9_]*)*");

    public record Validated(String query, int limit) {}

    public static final class Refused extends RuntimeException {
        public Refused(String message) {
            super(message, null, false, false);
        }
    }

    public static Validated validate(String gaql, Integer requestedRows) {
        if (gaql == null || gaql.isBlank()) throw new Refused("The query is empty.");
        if (gaql.length() > 2000) throw new Refused("The query is longer than 2000 characters.");
        if (gaql.contains(";") || gaql.contains("--") || gaql.contains("/*") || gaql.contains("`")) {
            throw new Refused("Only a single plain SELECT is allowed: no semicolons, comments or quoting tricks.");
        }
        Matcher m = SHAPE.matcher(gaql.strip());
        if (!m.matches()) throw new Refused("The query must be: SELECT fields FROM resource [WHERE ...] [ORDER BY ...] [LIMIT n].");
        String resource = m.group(2).toLowerCase(Locale.ROOT);
        if (!RESOURCES.contains(resource)) {
            throw new Refused("Resource '" + resource + "' is not allowed. Allowed: " + new java.util.TreeSet<>(RESOURCES) + ".");
        }
        List<String> fields = java.util.Arrays.stream(m.group(1).split(",")).map(String::strip).toList();
        for (String f : fields) {
            String lower = f.toLowerCase(Locale.ROOT);
            if (!FIELD.matcher(lower).matches()) throw new Refused("Field '" + f + "' is not a plain field path (no * and no expressions).");
            for (String denied : DENIED_FIELDS) {
                if (lower.startsWith(denied)) throw new Refused("Field '" + f + "' exposes account, billing or user data and is not allowed.");
            }
        }
        String rest = m.group(3);
        if (Pattern.compile("\\bPARAMETERS\\b", Pattern.CASE_INSENSITIVE).matcher(rest).find()) {
            throw new Refused("PARAMETERS is not allowed.");
        }
        for (String denied : DENIED_FIELDS) {
            if (Pattern.compile("\\b" + denied, Pattern.CASE_INSENSITIVE).matcher(rest).find()) {
                // a WHERE or ORDER BY on an account-level field would leak it through filtering
                throw new Refused("The WHERE or ORDER BY clause mentions '" + denied + "', which is not allowed.");
            }
        }
        int cap = Math.min(MAX_ROWS, requestedRows == null || requestedRows <= 0 ? DEFAULT_ROWS : requestedRows);
        Matcher lim = LIMIT.matcher(rest.strip());
        int limit = cap;
        String body = rest.strip();
        if (lim.find()) {
            limit = (int) Math.min(cap, Long.parseLong(lim.group(1).length() > 9 ? "999999999" : lim.group(1)));
            body = body.substring(0, lim.start()).strip();
        }
        String query = "SELECT " + String.join(", ", fields) + " FROM " + resource + (body.isEmpty() ? "" : " " + body) + " LIMIT " + limit;
        return new Validated(query, limit);
    }
}

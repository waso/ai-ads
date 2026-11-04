package app.autopilot.ads.google;

import app.autopilot.ads.AdsGateway.KeywordQuery;
import app.autopilot.ads.AdsGateway.Level;

import com.google.ads.googleads.v26.services.GoogleAdsRow;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GaqlTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 30);

    /** Every query the adapter can send, keyed by the resource it must read FROM. */
    private static Map<String, String> allQueries() {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("campaigns", Gaql.CAMPAIGNS);
        q.put("campaignSpend", Gaql.campaignSpend(FROM, TO));
        q.put("labels", Gaql.LABELS);
        q.put("labelByName", Gaql.labelByName("agent-managed"));
        q.put("budgets", Gaql.BUDGETS);
        q.put("budgetUsers", Gaql.BUDGET_USERS);
        q.put("policyAds", Gaql.POLICY_ADS);
        q.put("policyKeywords", Gaql.POLICY_KEYWORDS);
        q.put("changeEvents", Gaql.changeEvents(LocalDateTime.of(2026, 10, 5, 8, 0, 0), LocalDateTime.of(2026, 11, 3, 12, 30, 15)));
        for (Level level : Level.values()) {
            q.put("performance " + level, Gaql.performance(level, FROM, TO, 100));
        }
        q.put("searchTerms", Gaql.searchTerms(FROM, TO, 50));
        q.put("keywords", Gaql.keywords(new KeywordQuery("123", true, FROM, TO, 200)));
        q.put("keywordsAll", Gaql.keywords(new KeywordQuery(null, false, null, null, 200)));
        q.put("ads", Gaql.ads("123", 20));
        q.put("adsAll", Gaql.ads(null, 20));
        q.put("recommendations", Gaql.recommendations(25));
        q.put("campaignNegatives", Gaql.campaignNegatives("123"));
        q.put("adGroupNegatives", Gaql.adGroupNegatives("456"));
        q.put("campaign", Gaql.campaign("123"));
        q.put("campaignLabels", Gaql.campaignLabels("123"));
        q.put("adGroup", Gaql.adGroup("456"));
        q.put("adGroupCampaign", Gaql.adGroupCampaign("456"));
        q.put("budget", Gaql.budget("789"));
        q.put("budgetUsersOf", Gaql.budgetUsers("1234567890", "789"));
        q.put("adsOfAdGroup", Gaql.adsOfAdGroup("456"));
        q.put("keyword", Gaql.keyword("1234567890", "456~789"));
        q.put("adGroupKeywords", Gaql.adGroupKeywords("456"));
        q.put("adContent", Gaql.adContent("456", "999"));
        q.put("enabledKeywordIds", Gaql.enabledKeywordIds("456"));
        q.put("keywordMetrics", Gaql.keywordMetrics("1234567890", "456~789", FROM, TO));
        q.put("recommendation", Gaql.recommendation("1234567890", "abc_123"));
        q.put("conversionActions", Gaql.CONVERSION_ACTIONS);
        q.put("conversionResults7", Gaql.conversionResults("LAST_7_DAYS"));
        q.put("conversionResults30", Gaql.conversionResults("LAST_30_DAYS"));
        return q;
    }

    private static final Map<String, String> EXPECTED_RESOURCE = Map.ofEntries(
            Map.entry("conversionActions", "conversion_action"), Map.entry("conversionResults7", "conversion_action"),
            Map.entry("conversionResults30", "conversion_action"), Map.entry("campaigns", "campaign"), Map.entry("campaignSpend", "campaign"), Map.entry("labels", "label"),
            Map.entry("labelByName", "label"), Map.entry("budgets", "campaign_budget"), Map.entry("budgetUsers", "campaign"),
            Map.entry("policyAds", "ad_group_ad"), Map.entry("policyKeywords", "ad_group_criterion"),
            Map.entry("changeEvents", "change_event"), Map.entry("performance CAMPAIGN", "campaign"),
            Map.entry("performance AD_GROUP", "ad_group"), Map.entry("performance KEYWORD", "ad_group_criterion"),
            Map.entry("performance AD", "ad_group_ad"), Map.entry("searchTerms", "search_term_view"),
            Map.entry("keywords", "ad_group_criterion"), Map.entry("keywordsAll", "ad_group_criterion"),
            Map.entry("ads", "ad_group_ad"), Map.entry("adsAll", "ad_group_ad"), Map.entry("recommendations", "recommendation"),
            Map.entry("campaignNegatives", "campaign_criterion"), Map.entry("adGroupNegatives", "ad_group_criterion"),
            Map.entry("campaign", "campaign"), Map.entry("campaignLabels", "campaign"), Map.entry("adGroup", "ad_group"),
            Map.entry("adGroupCampaign", "ad_group"), Map.entry("budget", "campaign_budget"),
            Map.entry("budgetUsersOf", "campaign"), Map.entry("adsOfAdGroup", "ad_group_ad"),
            Map.entry("keyword", "ad_group_criterion"), Map.entry("adGroupKeywords", "ad_group_criterion"),
            Map.entry("adContent", "ad_group_ad"), Map.entry("enabledKeywordIds", "ad_group_criterion"),
            Map.entry("keywordMetrics", "ad_group_criterion"), Map.entry("recommendation", "recommendation"));

    private static String withoutLiterals(String gaql) {
        return gaql.replaceAll("'(?:[^'\\\\]|\\\\.)*'", "''");
    }

    // ---- building blocks ------------------------------------------------------------------

    @Test
    @DisplayName("[GAQL] string literals are single-quoted with quote and backslash escaped")
    void quoteEscapes() {
        assertThat(Gaql.quote("agent-managed")).isEqualTo("'agent-managed'");
        assertThat(Gaql.quote("it's")).isEqualTo("'it\\'s'");
        assertThat(Gaql.quote("a\\b")).isEqualTo("'a\\\\b'");
        // an attempt to close the literal and add a condition stays inside the literal
        String evil = Gaql.quote("x' OR campaign.id > 0 OR 'y");
        assertThat(evil).isEqualTo("'x\\' OR campaign.id > 0 OR \\'y'");
        assertThat(withoutLiterals("WHERE label.name = " + evil)).isEqualTo("WHERE label.name = ''");
        assertThatThrownBy(() -> Gaql.quote("line\nbreak")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Gaql.quote(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("[GAQL] ids must be numeric, so nothing else can reach a query")
    void idsMustBeNumeric() {
        assertThat(Gaql.campaign("123")).contains("campaign.id = 123 ");
        for (String bad : new String[] {"1 OR 1=1", "12'3", "", " ", "-5", "1.5", "0x10", "123; DROP"}) {
            assertThatThrownBy(() -> Gaql.campaign(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Gaql.adGroup(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Gaql.budget(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> Gaql.keyword("1234567890", "456")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Gaql.keyword("1234567890", "456~x' OR 'a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Gaql.recommendation("1234567890", "x' OR '1'='1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Gaql.ads("1 OR 1=1", 5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("[GAQL] date windows are quoted ISO literals, both ends inclusive")
    void dateLiterals() {
        assertThat(Gaql.date(LocalDate.of(2026, 11, 3))).isEqualTo("'2026-11-03'");
        assertThat(Gaql.dateBetween(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3)))
                .isEqualTo("segments.date BETWEEN '2026-11-01' AND '2026-11-03'");
        assertThat(Gaql.dateBetween(FROM, FROM)).isEqualTo("segments.date BETWEEN '2026-10-01' AND '2026-10-01'");
        assertThatThrownBy(() -> Gaql.dateBetween(TO, FROM)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Gaql.dateBetween(null, TO)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Gaql.campaignSpend(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3)))
                .contains("segments.date BETWEEN '2026-11-01' AND '2026-11-03'");
    }

    @Test
    @DisplayName("[GAQL] LIMIT is always present on list reads and clamped to 1..10000")
    void limits() {
        assertThat(Gaql.performance(Level.CAMPAIGN, FROM, TO, 50)).endsWith("LIMIT 50");
        assertThat(Gaql.performance(Level.CAMPAIGN, FROM, TO, 0)).endsWith("LIMIT 1");
        assertThat(Gaql.performance(Level.CAMPAIGN, FROM, TO, -5)).endsWith("LIMIT 1");
        assertThat(Gaql.performance(Level.CAMPAIGN, FROM, TO, 1_000_000)).endsWith("LIMIT 10000");
        assertThat(Gaql.searchTerms(FROM, TO, 25)).endsWith("LIMIT 25");
        assertThat(Gaql.ads(null, 7)).endsWith("LIMIT 7");
        assertThat(Gaql.recommendations(9)).endsWith("LIMIT 9");
        assertThat(Gaql.keywords(new KeywordQuery(null, false, FROM, TO, 33))).endsWith("LIMIT 33");
    }

    // ---- individual queries ---------------------------------------------------------------

    @Test
    @DisplayName("[GAQL] change history filters by date-time on both ends, newest first, at most 10000 rows")
    void changeEventsQuery() {
        String q = Gaql.changeEvents(LocalDateTime.of(2026, 10, 5, 8, 0, 0), LocalDateTime.of(2026, 11, 3, 12, 30, 15));

        assertThat(q).contains("FROM change_event")
                .contains("change_event.change_date_time >= '2026-10-05 08:00:00'")
                .contains("change_event.change_date_time <= '2026-11-03 12:30:15'")
                .endsWith("ORDER BY change_event.change_date_time DESC LIMIT 10000");
        for (String field : new String[] {"user_email", "client_type", "change_resource_type", "change_resource_name", "resource_change_operation"}) {
            assertThat(q).contains("change_event." + field);
        }
    }

    @Test
    @DisplayName("[GAQL] campaign list is Search campaigns that are not removed")
    void campaignListQuery() {
        assertThat(Gaql.CAMPAIGNS).contains("FROM campaign")
                .contains("campaign.status != 'REMOVED'")
                .contains("campaign.advertising_channel_type = 'SEARCH'")
                .contains("campaign.labels")
                .contains("campaign.campaign_budget");
    }

    @Test
    @DisplayName("[GAQL] budget users are found over all campaign types, not only Search")
    void budgetUsersQuery() {
        String q = Gaql.budgetUsers("1234567890", "789");
        assertThat(q).contains("campaign.campaign_budget = 'customers/1234567890/campaignBudgets/789'")
                .contains("campaign.status != 'REMOVED'")
                .doesNotContain("advertising_channel_type");
    }

    @Test
    @DisplayName("[GAQL] a keyword is found by its resource name and its ancestors' labels and bidding are read with it")
    void keywordQuery() {
        String q = Gaql.keyword("1234567890", "456~789");
        assertThat(q).contains("ad_group_criterion.resource_name = 'customers/1234567890/adGroupCriteria/456~789'")
                .contains("ad_group_criterion.labels").contains("ad_group.labels").contains("campaign.labels")
                .contains("campaign.bidding_strategy_type").contains("ad_group_criterion.cpc_bid_micros")
                .contains("ad_group_criterion.status != 'REMOVED'");
        assertThat(Gaql.enabledKeywordIds("456")).contains("ad_group.id = 456")
                .contains("ad_group_criterion.status = 'ENABLED'").contains("ad_group_criterion.negative = FALSE")
                .contains("ad_group_criterion.type = 'KEYWORD'");
        assertThat(Gaql.keywordMetrics("1234567890", "456~789", FROM, TO))
                .contains("metrics.clicks").contains("metrics.cost_micros").contains("metrics.conversions")
                .contains("segments.date BETWEEN '2026-10-01' AND '2026-10-30'");
    }

    @Test
    @DisplayName("[GAQL] keyword list filters: enabled only, one campaign, default window when none is given")
    void keywordListFilters() {
        String enabled = Gaql.keywords(new KeywordQuery("123", true, FROM, TO, 10));
        assertThat(enabled).contains("ad_group_criterion.status = 'ENABLED'").contains("campaign.id = 123")
                .contains("segments.date BETWEEN '2026-10-01' AND '2026-10-30'").contains("ORDER BY metrics.cost_micros DESC");
        String all = Gaql.keywords(new KeywordQuery(null, false, null, null, 10));
        assertThat(all).contains("ad_group_criterion.status != 'REMOVED'").doesNotContain("campaign.id =")
                .contains("segments.date DURING LAST_30_DAYS");
    }

    @Test
    @DisplayName("[GAQL] negatives are read per scope, positive criteria are excluded")
    void negativesQueries() {
        assertThat(Gaql.campaignNegatives("123")).contains("FROM campaign_criterion").contains("campaign.id = 123")
                .contains("campaign_criterion.negative = TRUE").contains("campaign_criterion.type = 'KEYWORD'");
        assertThat(Gaql.adGroupNegatives("456")).contains("FROM ad_group_criterion").contains("ad_group.id = 456")
                .contains("ad_group_criterion.negative = TRUE");
    }

    @Test
    @DisplayName("[GAQL] label lookup quotes the label name")
    void labelLookupEscapes() {
        assertThat(Gaql.labelByName("agent-managed")).contains("label.name = 'agent-managed'").contains("label.status = 'ENABLED'");
        assertThat(Gaql.labelByName("o'brien")).contains("label.name = 'o\\'brien'");
    }

    // ---- whole set ------------------------------------------------------------------------

    @Test
    @DisplayName("[GAQL] every query is SELECT-only and reads the expected resource")
    void everyQueryIsSelectOnly() {
        Pattern writeWords = Pattern.compile("\\b(INSERT|UPDATE|DELETE|REMOVE|MUTATE|CREATE|DROP|ALTER|SET)\\b", Pattern.CASE_INSENSITIVE);
        for (Map.Entry<String, String> e : allQueries().entrySet()) {
            String q = e.getValue();
            String bare = withoutLiterals(q);
            assertThat(q).as(e.getKey()).startsWith("SELECT ");
            assertThat(q).as(e.getKey()).doesNotContain(";");
            assertThat(writeWords.matcher(bare).find()).as(e.getKey()).isFalse();
            Matcher from = Pattern.compile("\\bFROM ([a-z_]+)").matcher(bare);
            assertThat(from.find()).as(e.getKey()).isTrue();
            assertThat(from.group(1)).as(e.getKey()).isEqualTo(EXPECTED_RESOURCE.get(e.getKey()));
            assertThat(from.find()).as(e.getKey() + " has one FROM").isFalse();
        }
        assertThat(allQueries().keySet()).containsExactlyInAnyOrderElementsOf(EXPECTED_RESOURCE.keySet());
    }

    @Test
    @DisplayName("[GAQL] every field a query names exists in the v26 GoogleAdsRow")
    void everyFieldExistsInV26() {
        Pattern path = Pattern.compile("\\b[a-z_]+(?:\\.[a-z_]+)+\\b");
        for (Map.Entry<String, String> e : allQueries().entrySet()) {
            String bare = withoutLiterals(e.getValue());
            Matcher m = path.matcher(bare);
            int found = 0;
            while (m.find()) {
                found++;
                assertThat(resolves(m.group())).as(e.getKey() + ": " + m.group()).isTrue();
            }
            assertThat(found).as(e.getKey()).isPositive();
            Matcher from = Pattern.compile("\\bFROM ([a-z_]+)").matcher(bare);
            assertThat(from.find() && GoogleAdsRow.getDescriptor().findFieldByName(from.group(1)) != null)
                    .as(e.getKey() + " resource").isTrue();
        }
    }

    @Test
    @DisplayName("[GAQL] the field check does fail for a field that does not exist")
    void fieldCheckCanFail() {
        assertThat(resolves("campaign.no_such_field")).isFalse();
        assertThat(resolves("campaign.id.too_deep")).isFalse();
        assertThat(resolves("metrics.cost_micros")).isTrue();
    }

    /** True when "a.b.c" walks down GoogleAdsRow's message fields to an existing field. */
    private static boolean resolves(String fieldPath) {
        Descriptor d = GoogleAdsRow.getDescriptor();
        String[] parts = fieldPath.split("\\.");
        for (int i = 0; i < parts.length; i++) {
            FieldDescriptor f = d.findFieldByName(parts[i]);
            if (f == null) {
                return false;
            }
            if (i < parts.length - 1) {
                if (f.getJavaType() != FieldDescriptor.JavaType.MESSAGE) {
                    return false;
                }
                d = f.getMessageType();
            }
        }
        return true;
    }
}

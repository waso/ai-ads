package app.autopilot.ads.google;

import app.autopilot.ads.AdsGateway.KeywordQuery;
import app.autopilot.ads.AdsGateway.Level;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Every GAQL query the adapter sends. The queries are plain strings built here and executed
 * elsewhere, so they can be tested without a Google Ads client.
 *
 * <p>All queries are SELECT-only. Ids are checked to be numeric and strings are quoted with
 * {@link #quote}, so nothing from outside can add query syntax.
 */
final class Gaql {

    private Gaql() {}

    /** GAQL allows at most 10,000 rows in a LIMIT on most resources; also our own ceiling. */
    static final int MAX_LIMIT = 10_000;

    private static final DateTimeFormatter CHANGE_EVENT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ---- building blocks ------------------------------------------------------------------

    /** A single-quoted GAQL string literal. Backslash and quote are escaped; control characters are refused. */
    static String quote(String value) {
        if (value == null) {
            throw new IllegalArgumentException("string literal is required");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw new IllegalArgumentException("string literal contains a control character");
            }
        }
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    /** A quoted ISO date literal, for example {@code '2026-11-03'}. */
    static String date(LocalDate date) {
        return quote(date.toString());
    }

    /** {@code segments.date BETWEEN 'a' AND 'b'}, both ends inclusive. */
    static String dateBetween(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from and to are required");
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("to is before from");
        }
        return "segments.date BETWEEN " + date(from) + " AND " + date(to);
    }

    /** Clamps a caller limit into 1..{@link #MAX_LIMIT}. */
    static int limit(int requested) {
        return Math.max(1, Math.min(requested, MAX_LIMIT));
    }

    // ---- campaign fields ------------------------------------------------------------------

    /** Selected so that the campaign bidding strategy oneof is populated whichever member is in use. */
    static final String CAMPAIGN_BIDDING_FIELDS =
            "campaign.bidding_strategy_type, campaign.bidding_strategy, "
                    + "campaign.manual_cpc.enhanced_cpc_enabled, "
                    + "campaign.maximize_conversions.target_cpa_micros, "
                    + "campaign.maximize_conversion_value.target_roas, "
                    + "campaign.target_cpa.target_cpa_micros, "
                    + "campaign.target_roas.target_roas";

    private static final String CAMPAIGN_FIELDS =
            "campaign.id, campaign.name, campaign.status, campaign.primary_status, "
                    + "campaign.advertising_channel_type, campaign.campaign_budget, campaign.labels, "
                    + CAMPAIGN_BIDDING_FIELDS;

    /** Settings read back after a campaign is created. */
    private static final String CAMPAIGN_SETTINGS_FIELDS =
            "campaign.network_settings.target_google_search, campaign.network_settings.target_search_network, "
                    + "campaign.network_settings.target_content_network, campaign.network_settings.target_partner_search_network, "
                    + "campaign.geo_target_type_setting.positive_geo_target_type, campaign.contains_eu_political_advertising";

    // ---- account level reads --------------------------------------------------------------

    /** Every non-removed Search campaign, no metrics (metrics come from {@link #campaignSpend}). */
    static final String CAMPAIGNS =
            "SELECT " + CAMPAIGN_FIELDS + " FROM campaign "
                    + "WHERE campaign.status != 'REMOVED' AND campaign.advertising_channel_type = 'SEARCH' "
                    + "ORDER BY campaign.id";

    /**
     * Daily cost and impressions per campaign over a date window. Campaigns without any delivery in the
     * window have no row, which the mapper reads as zero.
     */
    static String campaignSpend(LocalDate from, LocalDate to) {
        return "SELECT campaign.id, segments.date, metrics.cost_micros, metrics.impressions FROM campaign "
                + "WHERE campaign.status != 'REMOVED' AND campaign.advertising_channel_type = 'SEARCH' "
                + "AND " + dateBetween(from, to);
    }

    /** Conversion actions themselves (no metrics, so actions without results still show up). */
    static final String CONVERSION_ACTIONS =
            "SELECT conversion_action.id, conversion_action.name, conversion_action.type, conversion_action.status, "
                    + "conversion_action.category, conversion_action.primary_for_goal, conversion_action.counting_type, "
                    + "conversion_action.value_settings.default_value, conversion_action.value_settings.always_use_default_value "
                    + "FROM conversion_action WHERE conversion_action.status != 'REMOVED' ORDER BY conversion_action.id";

    /** Results per conversion action over a named range such as {@code LAST_7_DAYS} or {@code LAST_30_DAYS}. */
    static String conversionResults(String during) {
        if (!during.matches("LAST_(7|14|30)_DAYS")) {
            throw new IllegalArgumentException("unsupported range: " + during);
        }
        return "SELECT conversion_action.id, metrics.conversions, metrics.conversions_value FROM conversion_action "
                + "WHERE conversion_action.status != 'REMOVED' AND segments.date DURING " + during;
    }

    /** Label resource name to label name, for turning the repeated {@code labels} fields into names. */
    static final String LABELS =
            "SELECT label.resource_name, label.id, label.name FROM label WHERE label.status = 'ENABLED'";

    static String labelByName(String name) {
        return "SELECT label.resource_name, label.id, label.name FROM label "
                + "WHERE label.name = " + quote(name) + " AND label.status = 'ENABLED'";
    }

    static final String BUDGETS =
            "SELECT campaign_budget.id, campaign_budget.name, campaign_budget.amount_micros, "
                    + "campaign_budget.explicitly_shared, campaign_budget.status "
                    + "FROM campaign_budget WHERE campaign_budget.status != 'REMOVED' ORDER BY campaign_budget.id";

    /** Which budget each non-removed campaign (of any channel type) uses. */
    static final String BUDGET_USERS =
            "SELECT campaign.id, campaign.campaign_budget, campaign.labels FROM campaign "
                    + "WHERE campaign.status != 'REMOVED' ORDER BY campaign.id";

    static final String POLICY_ADS =
            "SELECT ad_group.id, ad_group_ad.ad.id, ad_group_ad.ad.name, ad_group_ad.policy_summary.approval_status, "
                    + "ad_group_ad.policy_summary.policy_topic_entries FROM ad_group_ad "
                    + "WHERE ad_group_ad.status != 'REMOVED' "
                    + "AND ad_group_ad.policy_summary.approval_status IN ('DISAPPROVED', 'APPROVED_LIMITED', 'AREA_OF_INTEREST_ONLY')";

    /** Keywords expose only an approval status and disapproval reasons; "limited" is not visible here. */
    static final String POLICY_KEYWORDS =
            "SELECT ad_group.id, ad_group_criterion.criterion_id, ad_group_criterion.keyword.text, "
                    + "ad_group_criterion.approval_status, ad_group_criterion.disapproval_reasons FROM ad_group_criterion "
                    + "WHERE ad_group_criterion.type = 'KEYWORD' AND ad_group_criterion.negative = FALSE "
                    + "AND ad_group_criterion.status != 'REMOVED' AND ad_group_criterion.approval_status = 'DISAPPROVED'";

    /**
     * The change history. Both ends of the date-time window are required by Google; the format is
     * {@code yyyy-MM-dd HH:mm:ss} in the account time zone.
     */
    static String changeEvents(LocalDateTime fromLocal, LocalDateTime toLocal) {
        return "SELECT change_event.change_date_time, change_event.user_email, change_event.client_type, "
                + "change_event.change_resource_type, change_event.change_resource_name, "
                + "change_event.resource_change_operation FROM change_event "
                + "WHERE change_event.change_date_time >= " + quote(CHANGE_EVENT_TIME.format(fromLocal))
                + " AND change_event.change_date_time <= " + quote(CHANGE_EVENT_TIME.format(toLocal))
                + " ORDER BY change_event.change_date_time DESC LIMIT " + MAX_LIMIT;
    }

    // ---- optimization reads ---------------------------------------------------------------

    static String performance(Level level, LocalDate from, LocalDate to, int limit) {
        String select;
        String from_;
        String where;
        switch (level) {
            case CAMPAIGN -> {
                select = "campaign.id, campaign.name";
                from_ = "campaign";
                where = "campaign.status != 'REMOVED'";
            }
            case AD_GROUP -> {
                select = "ad_group.id, ad_group.name, campaign.id";
                from_ = "ad_group";
                where = "ad_group.status != 'REMOVED'";
            }
            case KEYWORD -> {
                select = "ad_group_criterion.criterion_id, ad_group_criterion.keyword.text, ad_group.id, campaign.id";
                from_ = "ad_group_criterion";
                where = "ad_group_criterion.type = 'KEYWORD' AND ad_group_criterion.negative = FALSE "
                        + "AND ad_group_criterion.status != 'REMOVED'";
            }
            case AD -> {
                select = "ad_group_ad.ad.id, ad_group_ad.ad.name, ad_group.id, campaign.id";
                from_ = "ad_group_ad";
                where = "ad_group_ad.status != 'REMOVED'";
            }
            default -> throw new IllegalArgumentException("unknown level " + level);
        }
        return "SELECT " + select + ", " + METRICS + " FROM " + from_ + " WHERE " + where
                + " AND " + dateBetween(from, to) + " ORDER BY metrics.cost_micros DESC LIMIT " + limit(limit);
    }

    private static final String METRICS =
            "metrics.impressions, metrics.clicks, metrics.cost_micros, metrics.conversions, metrics.conversions_value";

    static String searchTerms(LocalDate from, LocalDate to, int limit) {
        return "SELECT search_term_view.search_term, campaign.id, ad_group.id, metrics.impressions, metrics.clicks, "
                + "metrics.cost_micros, metrics.conversions FROM search_term_view WHERE "
                + dateBetween(from, to) + " ORDER BY metrics.cost_micros DESC LIMIT " + limit(limit);
    }

    static String keywords(KeywordQuery q) {
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT ad_group_criterion.criterion_id, ad_group.id, campaign.id, ad_group_criterion.keyword.text, "
                + "ad_group_criterion.keyword.match_type, ad_group_criterion.status, ad_group_criterion.cpc_bid_micros, "
                + "ad_group_criterion.quality_info.quality_score, "
                + "metrics.impressions, metrics.clicks, metrics.cost_micros, metrics.conversions "
                + "FROM ad_group_criterion WHERE ad_group_criterion.type = 'KEYWORD' "
                + "AND ad_group_criterion.negative = FALSE ");
        sb.append(q.enabledOnly()
                ? "AND ad_group_criterion.status = 'ENABLED' "
                : "AND ad_group_criterion.status != 'REMOVED' ");
        if (q.campaignId() != null && !q.campaignId().isBlank()) {
            sb.append("AND campaign.id = ").append(ResourceNames.numeric(q.campaignId())).append(' ');
        }
        if (q.from() != null && q.to() != null) {
            sb.append("AND ").append(dateBetween(q.from(), q.to())).append(' ');
        } else {
            sb.append("AND segments.date DURING LAST_30_DAYS ");
        }
        sb.append("ORDER BY metrics.cost_micros DESC LIMIT ").append(limit(q.limit()));
        return sb.toString();
    }

    static String ads(String campaignId, int limit) {
        String campaignFilter = campaignId == null || campaignId.isBlank()
                ? ""
                : " AND campaign.id = " + ResourceNames.numeric(campaignId);
        return "SELECT ad_group_ad.ad.id, ad_group.id, campaign.id, ad_group_ad.status, ad_group_ad.ad_strength, "
                + "ad_group_ad.policy_summary.approval_status, ad_group_ad.ad.responsive_search_ad.headlines, "
                + "ad_group_ad.ad.responsive_search_ad.descriptions, ad_group_ad.ad.final_urls FROM ad_group_ad "
                + "WHERE ad_group_ad.status != 'REMOVED'" + campaignFilter + " LIMIT " + limit(limit);
    }

    static String recommendations(int limit) {
        return "SELECT recommendation.resource_name, recommendation.type, recommendation.campaign, "
                + "recommendation.dismissed FROM recommendation LIMIT " + limit(limit);
    }

    /** Negative keywords attached to one campaign. */
    static String campaignNegatives(String campaignId) {
        return "SELECT campaign.id, campaign_criterion.criterion_id, campaign_criterion.keyword.text, "
                + "campaign_criterion.keyword.match_type FROM campaign_criterion "
                + "WHERE campaign.id = " + ResourceNames.numeric(campaignId)
                + " AND campaign_criterion.type = 'KEYWORD' AND campaign_criterion.negative = TRUE";
    }

    /** Negative keywords attached to one ad group. */
    static String adGroupNegatives(String adGroupId) {
        return "SELECT ad_group.id, campaign.id, ad_group_criterion.criterion_id, ad_group_criterion.keyword.text, "
                + "ad_group_criterion.keyword.match_type FROM ad_group_criterion "
                + "WHERE ad_group.id = " + ResourceNames.numeric(adGroupId)
                + " AND ad_group_criterion.type = 'KEYWORD' AND ad_group_criterion.negative = TRUE "
                + "AND ad_group_criterion.status != 'REMOVED'";
    }

    // ---- state for the guard --------------------------------------------------------------

    static String campaign(String campaignId) {
        return "SELECT " + CAMPAIGN_FIELDS + ", " + CAMPAIGN_SETTINGS_FIELDS + " FROM campaign WHERE campaign.id = "
                + ResourceNames.numeric(campaignId) + " AND campaign.status != 'REMOVED'";
    }

    /** Just the labels of a campaign, whatever its status. */
    static String campaignLabels(String campaignId) {
        return "SELECT campaign.id, campaign.labels FROM campaign WHERE campaign.id = " + ResourceNames.numeric(campaignId);
    }

    static String adGroup(String adGroupId) {
        return "SELECT ad_group.id, ad_group.name, ad_group.status, ad_group.labels, campaign.id, campaign.status, "
                + "campaign.labels FROM ad_group WHERE ad_group.id = " + ResourceNames.numeric(adGroupId)
                + " AND ad_group.status != 'REMOVED'";
    }

    /** The owning campaign of an ad group (used to find inherited negatives). */
    static String adGroupCampaign(String adGroupId) {
        return "SELECT ad_group.id, campaign.id FROM ad_group WHERE ad_group.id = " + ResourceNames.numeric(adGroupId);
    }

    static String budget(String budgetId) {
        return "SELECT campaign_budget.id, campaign_budget.name, campaign_budget.amount_micros, "
                + "campaign_budget.explicitly_shared, campaign_budget.status FROM campaign_budget "
                + "WHERE campaign_budget.id = " + ResourceNames.numeric(budgetId)
                + " AND campaign_budget.status != 'REMOVED'";
    }

    /** All non-removed campaigns, of any type, that use one budget. */
    static String budgetUsers(String customerId, String budgetId) {
        return "SELECT campaign.id, campaign.campaign_budget, campaign.labels FROM campaign "
                + "WHERE campaign.campaign_budget = "
                + quote("customers/" + customerId + "/campaignBudgets/" + ResourceNames.numeric(budgetId))
                + " AND campaign.status != 'REMOVED'";
    }

    /** All non-removed ads of one ad group, with labels, so one query serves both the ad and the enabled count. */
    static String adsOfAdGroup(String adGroupId) {
        return "SELECT ad_group_ad.ad.id, ad_group_ad.status, ad_group_ad.labels, ad_group.id, ad_group.labels, "
                + "campaign.id, campaign.labels FROM ad_group_ad WHERE ad_group.id = " + ResourceNames.numeric(adGroupId)
                + " AND ad_group_ad.status != 'REMOVED'";
    }

    /** One keyword criterion, found by resource name, with the labels and bidding of its ancestors. */
    static String keyword(String customerId, String compositeId) {
        return "SELECT ad_group_criterion.criterion_id, ad_group_criterion.status, ad_group_criterion.negative, "
                + "ad_group_criterion.type, ad_group_criterion.keyword.text, ad_group_criterion.keyword.match_type, "
                + "ad_group_criterion.cpc_bid_micros, ad_group_criterion.labels, ad_group.id, ad_group.labels, "
                + "campaign.id, campaign.labels, " + CAMPAIGN_BIDDING_FIELDS + " FROM ad_group_criterion "
                + "WHERE ad_group_criterion.resource_name = "
                + quote("customers/" + customerId + "/adGroupCriteria/" + ResourceNames.composite(compositeId))
                + " AND ad_group_criterion.status != 'REMOVED'";
    }

    /** Positive keywords of an ad group, for checking what an add_keywords call produced. */
    static String adGroupKeywords(String adGroupId) {
        return "SELECT ad_group_criterion.criterion_id, ad_group_criterion.status, ad_group_criterion.keyword.text, "
                + "ad_group_criterion.keyword.match_type, ad_group_criterion.cpc_bid_micros FROM ad_group_criterion "
                + "WHERE ad_group.id = " + ResourceNames.numeric(adGroupId)
                + " AND ad_group_criterion.type = 'KEYWORD' AND ad_group_criterion.negative = FALSE "
                + "AND ad_group_criterion.status != 'REMOVED'";
    }

    /** One ad with its text, found by id, for checking what create_ad produced. */
    static String adContent(String adGroupId, String adId) {
        return "SELECT ad_group_ad.ad.id, ad_group.id, campaign.id, ad_group_ad.status, ad_group_ad.ad_strength, "
                + "ad_group_ad.policy_summary.approval_status, ad_group_ad.ad.responsive_search_ad.headlines, "
                + "ad_group_ad.ad.responsive_search_ad.descriptions, ad_group_ad.ad.final_urls FROM ad_group_ad "
                + "WHERE ad_group.id = " + ResourceNames.numeric(adGroupId)
                + " AND ad_group_ad.ad.id = " + ResourceNames.numeric(adId)
                + " AND ad_group_ad.status != 'REMOVED'";
    }

    /** Ids of the ENABLED positive keywords of an ad group. */
    static String enabledKeywordIds(String adGroupId) {
        return "SELECT ad_group_criterion.criterion_id FROM ad_group_criterion WHERE ad_group.id = "
                + ResourceNames.numeric(adGroupId) + " AND ad_group_criterion.type = 'KEYWORD' "
                + "AND ad_group_criterion.negative = FALSE AND ad_group_criterion.status = 'ENABLED'";
    }

    static String keywordMetrics(String customerId, String compositeId, LocalDate from, LocalDate to) {
        return "SELECT ad_group_criterion.criterion_id, metrics.clicks, metrics.cost_micros, metrics.conversions "
                + "FROM ad_group_criterion WHERE ad_group_criterion.resource_name = "
                + quote("customers/" + customerId + "/adGroupCriteria/" + ResourceNames.composite(compositeId))
                + " AND " + dateBetween(from, to);
    }

    static String recommendation(String customerId, String recommendationId) {
        return "SELECT recommendation.resource_name, recommendation.type, recommendation.campaign, "
                + "recommendation.dismissed FROM recommendation WHERE recommendation.resource_name = "
                + quote(new ResourceNames(customerId).recommendation(recommendationId));
    }
}

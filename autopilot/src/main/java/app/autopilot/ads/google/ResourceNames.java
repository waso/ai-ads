package app.autopilot.ads.google;

import java.util.regex.Pattern;

/**
 * Builds and parses Google Ads resource names for the one customer this adapter is bound to.
 *
 * <p>Every id that reaches a resource name or a GAQL literal goes through {@link #numeric}, so an id
 * that came from outside can never carry query syntax.
 */
final class ResourceNames {

    private static final Pattern DIGITS = Pattern.compile("\\d{1,19}");

    private final String customerId;

    ResourceNames(String customerId) {
        this.customerId = customerId;
    }

    String customerId() {
        return customerId;
    }

    String campaign(String id) {
        return "customers/" + customerId + "/campaigns/" + numeric(id);
    }

    String campaignBudget(String id) {
        return "customers/" + customerId + "/campaignBudgets/" + numeric(id);
    }

    String adGroup(String id) {
        return "customers/" + customerId + "/adGroups/" + numeric(id);
    }

    String label(String id) {
        return "customers/" + customerId + "/labels/" + numeric(id);
    }

    /** {@code adGroupAd} and {@code adGroupCriterion} ids are "{adGroupId}~{childId}". */
    String adGroupAd(String compositeId) {
        return "customers/" + customerId + "/adGroupAds/" + composite(compositeId);
    }

    String adGroupCriterion(String compositeId) {
        return "customers/" + customerId + "/adGroupCriteria/" + composite(compositeId);
    }

    String campaignCriterion(String campaignId, String criterionId) {
        return "customers/" + customerId + "/campaignCriteria/" + numeric(campaignId) + "~" + numeric(criterionId);
    }

    String recommendation(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_~.=-]{1,256}")) {
            throw new IllegalArgumentException("recommendation id has unexpected characters");
        }
        return "customers/" + customerId + "/recommendations/" + id;
    }

    /** A temporary resource name for an entity created in the same atomic request. */
    String temp(String collection, long negativeId) {
        if (negativeId >= 0) {
            throw new IllegalArgumentException("temporary ids must be negative");
        }
        return "customers/" + customerId + "/" + collection + "/" + negativeId;
    }

    static String geoTarget(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("geo target id must be positive");
        }
        return "geoTargetConstants/" + id;
    }

    /** Validates a numeric id and returns it unchanged. */
    static String numeric(String id) {
        if (id == null || !DIGITS.matcher(id).matches()) {
            throw new IllegalArgumentException("expected a numeric id but got: " + safe(id));
        }
        return id;
    }

    static boolean isNumeric(String id) {
        return id != null && DIGITS.matcher(id).matches();
    }

    /** Validates "{a}~{b}" with both parts numeric and returns it unchanged. */
    static String composite(String id) {
        String[] parts = split(id);
        return parts[0] + "~" + parts[1];
    }

    /** Splits "{a}~{b}" into its two numeric parts. */
    static String[] split(String id) {
        if (id == null) {
            throw new IllegalArgumentException("expected an id like 123~456 but got null");
        }
        int i = id.indexOf('~');
        if (i < 0) {
            throw new IllegalArgumentException("expected an id like 123~456 but got: " + safe(id));
        }
        return new String[] {numeric(id.substring(0, i)), numeric(id.substring(i + 1))};
    }

    /** The part of a resource name after the last slash ("customers/1/campaigns/22" gives "22"). */
    static String lastSegment(String resourceName) {
        if (resourceName == null || resourceName.isEmpty()) {
            return "";
        }
        return resourceName.substring(resourceName.lastIndexOf('/') + 1);
    }

    private static String safe(String s) {
        if (s == null) {
            return "null";
        }
        String t = s.length() > 40 ? s.substring(0, 40) + "..." : s;
        return t.replaceAll("[^\\x20-\\x7E]", "?");
    }
}

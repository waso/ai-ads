package app.autopilot.watchdog;

/** Maps a Google Ads resource name to the entity key the ledger uses, so outside changes can start cooldowns. */
final class ResourceKeys {
    private ResourceKeys() {}

    /** Null when the resource is not one the guard tracks. */
    static String entityKey(String resourceName) {
        if (resourceName == null) return null;
        String[] parts = resourceName.split("/");
        if (parts.length < 4) return null;
        String type = parts[2];
        String id = parts[3];
        return switch (type) {
            case "campaigns" -> "CAMPAIGN:" + id;
            case "campaignBudgets" -> "BUDGET:" + id;
            case "adGroups" -> "AD_GROUP:" + id;
            case "adGroupCriteria" -> "KEYWORD:" + id.replace("~", "~");
            case "adGroupAds" -> "AD:" + id;
            default -> null;
        };
    }
}

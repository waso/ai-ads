package app.autopilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.DayOfWeek;
import java.time.LocalTime;

/**
 * Server configuration that is not a limit. Limits and goals have their own typed properties
 * ({@link app.autopilot.domain.Limits}, {@link app.autopilot.domain.Goals}). Everything here is set by the
 * owner in deployment configuration; Claude has no tool that can read secrets or change any of it.
 */
@ConfigurationProperties("autopilot")
public record AutopilotProperties(
        /* The only Google account that may sign in to the approval and admin pages. */
        String ownerEmail,
        /* Kill switch from server configuration: works even when the database is down. */
        @DefaultValue("false") boolean killSwitch,
        @DefaultValue Runs runs,
        @DefaultValue Security security,
        @DefaultValue Alerting alerting,
        @DefaultValue Watchdog watchdog,
        @DefaultValue Ads ads,
        @DefaultValue Retention retention) {

    /** When each scheduled loop may start a run, in the account time zone. */
    public record Runs(
            @DefaultValue("120") int ttlMinutes,
            @DefaultValue("04:00") LocalTime dailyFrom,
            @DefaultValue("10:00") LocalTime dailyTo,
            @DefaultValue("MONDAY") DayOfWeek weeklyDay,
            @DefaultValue("04:00") LocalTime weeklyFrom,
            @DefaultValue("12:00") LocalTime weeklyTo,
            @DefaultValue("1000") int noteMaxChars,
            @DefaultValue("14") int noteDays,
            /* LOCAL TESTING: the daily and weekly loops may start at any time and on any day. A loop still gets one run per window. */
            @DefaultValue("false") boolean anyTime) {}

    public record Security(
            /* Secret for signing approval links (HMAC-SHA256). Required for the approval page. */
            String approvalLinkSecret,
            /* Public base URL of the approval page, for links in alerts. */
            String publicBaseUrl,
            /* Audience claim an MCP access token must carry. */
            String mcpAudience,
            /* The owner's Google account for MCP sign-in; tokens for other subjects are refused. */
            String mcpAllowedEmail,
            /* The OAuth authorization server that issues MCP tokens: issuer and JWKS location. */
            String mcpIssuer,
            String mcpJwkSetUri,
            /* Static per-loop API tokens for the MCP endpoint: loop name (triage, daily, weekly, chat) to secret. A token only starts
               runs of its own loop. For clients that cannot do OAuth, such as Claude Code on the owner's own network. */
            java.util.Map<String, String> mcpTokens,
            /* LOCAL USE ONLY: a form login as the owner for the approval and admin pages instead of Google sign-in. The server
               refuses to start with this on unless it listens on a loopback address. */
            @DefaultValue("false") boolean devLogin,
            String devLoginPassword) {

        public Security {
            mcpTokens = mcpTokens == null ? java.util.Map.of() : java.util.Map.copyOf(mcpTokens);
        }
    }

    public record Alerting(
            String emailTo,
            String emailFrom,
            /* ntfy-compatible push endpoint; a POST with the message as body. */
            String pushUrl,
            String pushToken,
            /* LOCAL USE: show alerts as macOS notification banners. */
            @DefaultValue("false") boolean macos) {}

    public record Watchdog(
            @DefaultValue("15") int intervalMinutes,
            /* The dead-man's switch pinged after every run; it alerts when pings stop. */
            String heartbeatUrl,
            /* A run is stale when no heartbeat arrived for this long (two missed runs plus slack). */
            @DefaultValue("35") int staleAfterMinutes,
            /* Alert when enabled campaigns show no impressions after this local hour. */
            @DefaultValue("12") int noDeliveryAfterHour,
            @DefaultValue("40") int conversionDropPct) {}

    public record Ads(
            /* "google" for the real API; "none" leaves the server read-less (used in tests and dry setups). */
            @DefaultValue("google") String mode,
            String developerToken,
            String loginCustomerId,
            /* Email of the service account, to tell this system's changes from the owner's in change history. */
            String serviceAccountEmail) {}

    public record Retention(
            @DefaultValue("24") int auditMonths,
            @DefaultValue("13") int metricMonths) {}
}

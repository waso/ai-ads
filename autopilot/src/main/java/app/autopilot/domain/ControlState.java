package app.autopilot.domain;

import java.time.Instant;
import java.util.List;

/**
 * Stop mechanisms. Only the owner chain (approval page or server configuration) can clear them.
 */
public record ControlState(
        boolean killSwitch,
        String killReason,
        boolean breakerOpen,
        Instant breakerSince,
        List<String> breakerPausedCampaigns,
        boolean readsPaused) {

    public ControlState {
        breakerPausedCampaigns = breakerPausedCampaigns == null ? List.of() : List.copyOf(breakerPausedCampaigns);
    }

    public static ControlState clear() {
        return new ControlState(false, null, false, null, List.of(), false);
    }

    public boolean writesBlocked() {
        return killSwitch || breakerOpen;
    }
}

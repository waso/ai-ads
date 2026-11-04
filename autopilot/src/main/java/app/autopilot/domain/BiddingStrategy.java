package app.autopilot.domain;

/** A campaign bidding strategy, with only the parameters the server understands. */
public record BiddingStrategy(Type type, Long targetCpaMicros, Double targetRoas) {
    public enum Type { MANUAL_CPC, MAXIMIZE_CONVERSIONS, MAXIMIZE_CONVERSION_VALUE, TARGET_CPA, TARGET_ROAS }

    public BiddingStrategy {
        if (type == null) throw new IllegalArgumentException("bidding strategy type is required");
        if (type == Type.TARGET_CPA && (targetCpaMicros == null || targetCpaMicros <= 0)) {
            throw new IllegalArgumentException("TARGET_CPA needs target_cpa_micros > 0");
        }
        if (type == Type.TARGET_ROAS && (targetRoas == null || targetRoas <= 0)) {
            throw new IllegalArgumentException("TARGET_ROAS needs target_roas > 0");
        }
    }

    public static BiddingStrategy manualCpc() { return new BiddingStrategy(Type.MANUAL_CPC, null, null); }
    public static BiddingStrategy maximizeConversions() { return new BiddingStrategy(Type.MAXIMIZE_CONVERSIONS, null, null); }
    public static BiddingStrategy targetCpa(long micros) { return new BiddingStrategy(Type.TARGET_CPA, micros, null); }

    public String describe() {
        return switch (type) {
            case TARGET_CPA -> "TARGET_CPA(" + targetCpaMicros + " micros)";
            case TARGET_ROAS -> "TARGET_ROAS(" + targetRoas + ")";
            default -> type.name();
        };
    }
}

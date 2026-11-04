package app.autopilot.ads;

/** A Google Ads failure. Rejected requests still count against quota, so the guard refuses bad calls first. */
public class AdsException extends RuntimeException {

    private final String code;
    private final boolean quotaRelated;

    public AdsException(String code, String message) {
        this(code, message, false, null);
    }

    public AdsException(String code, String message, boolean quotaRelated, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.quotaRelated = quotaRelated;
    }

    public String code() {
        return code;
    }

    public boolean quotaRelated() {
        return quotaRelated;
    }
}

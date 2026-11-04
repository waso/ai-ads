package app.autopilot.notify;

/** A message to the owner. {@code link} is set for approval requests and never reaches Claude. */
public record Notification(Severity severity, String title, String body, String link) {

    public enum Severity { INFO, WARNING, CRITICAL }

    public static Notification of(Severity severity, String title, String body) {
        return new Notification(severity, title, body, null);
    }
}

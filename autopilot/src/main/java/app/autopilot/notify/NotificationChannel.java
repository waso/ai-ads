package app.autopilot.notify;

/** One way to reach the owner (email, push). Sent directly by the server, so alerts work without Claude. */
public interface NotificationChannel {
    String name();

    void send(Notification notification) throws Exception;
}

package app.autopilot.notify;

import app.autopilot.config.AutopilotProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Push through an ntfy-compatible endpoint: POST with the message as the body. */
@Component
@ConditionalOnExpression("!'${autopilot.alerting.push-url:}'.isBlank()")
class PushChannel implements NotificationChannel {

    private final AutopilotProperties props;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    PushChannel(AutopilotProperties props) {
        this.props = props;
    }

    @Override
    public String name() {
        return "push";
    }

    @Override
    public void send(Notification n) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(props.alerting().pushUrl()))
                .timeout(Duration.ofSeconds(10))
                .header("Title", "Ads Autopilot: " + n.title())
                .header("Priority", switch (n.severity()) { case CRITICAL -> "urgent"; case WARNING -> "high"; default -> "default"; })
                .POST(HttpRequest.BodyPublishers.ofString(n.body()));
        if (n.link() != null) b.header("Click", n.link());
        if (props.alerting().pushToken() != null) b.header("Authorization", "Bearer " + props.alerting().pushToken());
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) throw new IllegalStateException("push endpoint answered " + r.statusCode());
    }
}

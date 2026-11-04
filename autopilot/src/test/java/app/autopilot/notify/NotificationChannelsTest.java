package app.autopilot.notify;

import app.autopilot.config.AutopilotProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The alert channels as the real configuration wires them. The watchdog's alerts must reach the owner without
 * Claude, and a server with no channel configured yet must still start.
 */
class NotificationChannelsTest {

    @Configuration
    @EnableConfigurationProperties(AutopilotProperties.class)
    static class Props {
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(Props.class, Notifier.class, EmailChannel.class, PushChannel.class);
    }

    @Test
    @DisplayName("[AC-37] with no alert channel configured the server still starts, and a notification is logged and counted as undelivered")
    void startsWithoutAnyChannel() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(EmailChannel.class).doesNotHaveBean(PushChannel.class);
            assertThat(ctx.getBean(Notifier.class).send(Notification.of(Notification.Severity.INFO, "t", "b"))).isZero();
        });
    }

    @Test
    @DisplayName("[AC-27] the email channel activates from configuration and sends through the mail sender")
    void emailChannelActivates() {
        JavaMailSender sender = mock(JavaMailSender.class);
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
                .withUserConfiguration(Props.class, Notifier.class, EmailChannel.class)
                .withBean("testSender", JavaMailSender.class, () -> sender)
                .withPropertyValues("spring.mail.host=localhost", "autopilot.alerting.email-to=owner@example.com",
                        "autopilot.alerting.email-from=autopilot@example.com")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed().hasSingleBean(EmailChannel.class);
                    int delivered = ctx.getBean(Notifier.class).send(new Notification(Notification.Severity.CRITICAL, "SPEND_DAILY_BREAKER",
                            "Spend is 1.9x", "https://autopilot.test/approve/x"));
                    assertThat(delivered).isEqualTo(1);
                    ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
                    verify(sender).send(mail.capture());
                    assertThat(mail.getValue().getTo()).containsExactly("owner@example.com");
                    assertThat(mail.getValue().getSubject()).contains("CRITICAL").contains("SPEND_DAILY_BREAKER");
                    assertThat(mail.getValue().getText()).contains("Spend is 1.9x").contains("https://autopilot.test/approve/x");
                });
    }

    @Test
    @DisplayName("[AC-27] a configured email address without an SMTP host fails loudly instead of dropping alerts silently")
    void emailWithoutSmtpFails() {
        runner().withPropertyValues("autopilot.alerting.email-to=owner@example.com").run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(EmailChannel.class);
            assertThatThrownBy(() -> ctx.getBean(EmailChannel.class).send(Notification.of(Notification.Severity.INFO, "t", "b")))
                    .hasMessageContaining("SMTP_HOST");
            assertThat(ctx.getBean(Notifier.class).send(Notification.of(Notification.Severity.INFO, "t", "b")))
                    .as("the failure is contained: the watchdog is never broken by a channel").isZero();
        });
    }

    @Test
    @DisplayName("[AC-27] the push channel posts the alert with its priority, a link to open and the bearer token")
    void pushChannelPosts() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/topic", ex -> {
            seen.add(ex.getRequestMethod() + "|" + ex.getRequestHeaders().getFirst("Title") + "|" + ex.getRequestHeaders().getFirst("Priority")
                    + "|" + ex.getRequestHeaders().getFirst("Click") + "|" + ex.getRequestHeaders().getFirst("Authorization") + "|"
                    + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        try {
            runner().withPropertyValues("autopilot.alerting.push-url=http://127.0.0.1:" + server.getAddress().getPort() + "/topic",
                    "autopilot.alerting.push-token=tok").run(ctx -> {
                assertThat(ctx).hasSingleBean(PushChannel.class);
                assertThat(ctx.getBean(Notifier.class).send(new Notification(Notification.Severity.CRITICAL, "SPEND_DAILY_BREAKER", "body", "https://x/y"))).isEqualTo(1);
            });
        } finally {
            server.stop(0);
        }
        assertThat(seen).containsExactly("POST|Ads Autopilot: SPEND_DAILY_BREAKER|urgent|https://x/y|Bearer tok|body");
    }

    @Test
    @DisplayName("[AC-27] one failing channel never blocks the others")
    void failingChannelDoesNotBlockOthers() {
        List<String> got = new CopyOnWriteArrayList<>();
        NotificationChannel broken = new NotificationChannel() {
            public String name() { return "broken"; }
            public void send(Notification n) { throw new IllegalStateException("down"); }
        };
        NotificationChannel good = new NotificationChannel() {
            public String name() { return "good"; }
            public void send(Notification n) { got.add(n.title()); }
        };
        assertThat(new Notifier(List.of(broken, good)).send(Notification.of(Notification.Severity.WARNING, "alert", "x"))).isEqualTo(1);
        assertThat(got).containsExactly("alert");
    }
}

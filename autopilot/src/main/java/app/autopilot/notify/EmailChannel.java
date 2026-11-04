package app.autopilot.notify;

import app.autopilot.config.AutopilotProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("!'${autopilot.alerting.email-to:}'.isBlank()")
class EmailChannel implements NotificationChannel {

    // An ObjectProvider, not @ConditionalOnBean: that condition is evaluated before auto-configuration creates the mail
    // sender and would silently never match.
    private final ObjectProvider<JavaMailSender> mail;
    private final AutopilotProperties props;

    EmailChannel(ObjectProvider<JavaMailSender> mail, AutopilotProperties props) {
        this.mail = mail;
        this.props = props;
    }

    @Override
    public String name() {
        return "email";
    }

    @Override
    public void send(Notification n) {
        JavaMailSender sender = mail.getIfAvailable();
        if (sender == null) throw new IllegalStateException("autopilot.alerting.email-to is set but no SMTP host is configured (SMTP_HOST)");
        SimpleMailMessage m = new SimpleMailMessage();
        m.setTo(props.alerting().emailTo());
        if (props.alerting().emailFrom() != null) m.setFrom(props.alerting().emailFrom());
        m.setSubject("[Ads Autopilot " + n.severity() + "] " + n.title());
        m.setText(n.body() + (n.link() == null ? "" : "\n\nOpen: " + n.link()));
        sender.send(m);
    }
}

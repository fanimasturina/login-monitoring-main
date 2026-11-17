package com.ilm.web.service;

import com.ilm.core.util.Util;
import com.ilm.web.config.AppProps;
import com.ilm.web.dao.AdminDao;
import com.ilm.web.dao.RiskDao;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

/**
 * Email notification for new alerts (FR-29). Off until mail.enabled=true.
 * The email carries only a masked user ID and a link; details stay inside the application.
 */
@Service
public class MailService implements DisposableBean {
    private static final Logger LOG = Logger.getLogger(MailService.class.getName());
    private final AppProps props;
    private final RiskDao riskDao;
    private final AdminDao adminDao;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ilm-mail");
        t.setDaemon(true);
        return t;
    });

    public MailService(AppProps props, RiskDao riskDao, AdminDao adminDao) {
        this.props = props;
        this.riskDao = riskDao;
        this.adminDao = adminDao;
    }

    public boolean enabled() { return props.bool("mail.enabled", false); }

    /** Called after new alerts are committed. Sending happens in the background. */
    public void notifyNewAlerts(List<Long> alertIds) {
        if (!enabled() || alertIds.isEmpty()) return;
        // After a big historical upload, one summary is enough: do not flood the inbox.
        int max = props.i("mail.max.per.run", 10);
        List<Long> ids = alertIds.size() > max ? alertIds.subList(0, max) : alertIds;
        int more = alertIds.size() - ids.size();
        executor.submit(() -> {
            for (Long id : ids) send(id, more);
        });
    }

    private void send(long alertId, int more) {
        try {
            Map<String, Object> a = riskDao.alert(alertId);
            if (a == null) return;
            String to = props.get("mail.to");
            if (to == null) to = String.join(",", adminDao.emailsOf("ANALYST"));
            if (to.isBlank()) { LOG.warning("Alert " + alertId + ": no email recipient (set mail.to or analyst emails)"); return; }
            String subject = "[ILM] " + a.get("severity") + " login risk alert #" + alertId + " - " + a.get("app_code");
            String link = props.get("app.base.url", "") + "/alerts/" + alertId;
            String body = "A login risk alert needs review.\n\n"
                    + "Alert      : #" + alertId + "\n"
                    + "Severity   : " + a.get("severity") + " (risk score " + a.get("max_risk_score") + "/100)\n"
                    + "Application: " + a.get("app_code") + "\n"
                    + "User ID    : " + Util.maskUser(String.valueOf(a.get("user_id"))) + " (open the alert to see the full ID)\n"
                    + "Time       : " + a.get("first_event_time") + "\n"
                    + "Main reason: " + a.get("top_factor") + "\n\n"
                    + "Open: " + link + "\n"
                    + (more > 0 ? "\n" + more + " more new alerts were created in the same run. See the Alerts page.\n" : "")
                    + "\nThis is an automated message from Intelligent Login Monitoring. The score is a signal for investigation, not proof of misuse.\n";
            Properties p = new Properties();
            p.put("mail.smtp.host", props.get("mail.smtp.host", "localhost"));
            p.put("mail.smtp.port", props.get("mail.smtp.port", "25"));
            p.put("mail.smtp.connectiontimeout", "10000");
            p.put("mail.smtp.timeout", "10000");
            if (props.bool("mail.smtp.starttls", true)) {
                p.put("mail.smtp.starttls.enable", "true");
                p.put("mail.smtp.starttls.required", "true");
            }
            String user = props.get("mail.smtp.user");
            String pass = props.get("mail.smtp.password");
            Session session;
            if (user != null) {
                p.put("mail.smtp.auth", "true");
                session = Session.getInstance(p, new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() { return new PasswordAuthentication(user, pass == null ? "" : pass); }
                });
            } else {
                session = Session.getInstance(p);
            }
            MimeMessage m = new MimeMessage(session);
            m.setFrom(new InternetAddress(props.get("mail.from", "ilm-noreply@localhost")));
            m.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            m.setSubject(subject, "UTF-8");
            m.setText(body, "UTF-8");
            Transport.send(m);
            riskDao.markEmailSent(alertId);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not send email for alert " + alertId + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void destroy() { executor.shutdownNow(); }
}

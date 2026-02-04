package fun.ai.studio.alert;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 轻量邮件告警发送器（纯文本）。
 */
@Service
public class MailAlertService {
    private static final Logger log = LoggerFactory.getLogger(MailAlertService.class);

    private final AlertMailProperties props;
    private final JavaMailSender mailSender;

    public MailAlertService(AlertMailProperties props, JavaMailSender mailSender) {
        this.props = props;
        this.mailSender = mailSender;
    }

    public boolean isEnabled() {
        return props != null && props.isEnabled();
    }

    public void send(String subject, String body) {
        if (!isEnabled()) {
            log.debug("mail alert disabled, skip: subject={}", subject);
            return;
        }
        if (mailSender == null) {
            log.warn("JavaMailSender not available, skip email alert");
            return;
        }
        List<String> to = props.toList();
        if (to == null || to.isEmpty()) {
            log.warn("mail alert enabled but to is empty, skip");
            return;
        }
        String from = props.getFrom();
        if (!StringUtils.hasText(from)) {
            log.warn("mail alert enabled but from is empty, skip");
            return;
        }
        String subj = (props.getSubjectPrefix() == null ? "" : props.getSubjectPrefix().trim()) + " " + (subject == null ? "" : subject);
        String text = body == null ? "" : body;

        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(msg, false, StandardCharsets.UTF_8.name());
            h.setFrom(new InternetAddress(from.trim()));
            h.setTo(to.toArray(new String[0]));
            List<String> cc = props.ccList();
            if (cc != null && !cc.isEmpty()) {
                h.setCc(cc.toArray(new String[0]));
            }
            h.setSubject(subj.trim());
            h.setText(text, false);
            mailSender.send(msg);
            log.info("mail alert sent: to={}, subject={}", to, subj);
        } catch (Exception e) {
            log.warn("mail alert send failed: subject={}, err={}", subj, e.getMessage(), e);
        }
    }
}


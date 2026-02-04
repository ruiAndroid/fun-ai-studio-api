package fun.ai.studio.alert;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 邮件告警配置（SMTP 连接使用 Spring Boot 标准 spring.mail.*）。
 */
@Component
@ConfigurationProperties(prefix = "funai.alert.mail")
public class AlertMailProperties {
    /**
     * 是否启用邮件告警
     */
    private boolean enabled = false;

    /**
     * 发件人邮箱（From）
     */
    private String from;

    /**
     * 收件人列表（逗号分隔）
     */
    private String to;

    /**
     * 可选：抄送列表（逗号分隔）
     */
    private String cc;

    /**
     * 邮件标题前缀
     */
    private String subjectPrefix = "[FunAI]";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getTo() {
        return to;
    }

    public void setTo(String to) {
        this.to = to;
    }

    public String getCc() {
        return cc;
    }

    public void setCc(String cc) {
        this.cc = cc;
    }

    public String getSubjectPrefix() {
        return subjectPrefix;
    }

    public void setSubjectPrefix(String subjectPrefix) {
        this.subjectPrefix = subjectPrefix;
    }

    public List<String> toList() {
        return splitCsv(to);
    }

    public List<String> ccList() {
        return splitCsv(cc);
    }

    private List<String> splitCsv(String s) {
        List<String> out = new ArrayList<>();
        if (!StringUtils.hasText(s)) return out;
        for (String part : s.split(",")) {
            String x = (part == null) ? "" : part.trim();
            if (!x.isEmpty()) out.add(x);
        }
        return out;
    }
}


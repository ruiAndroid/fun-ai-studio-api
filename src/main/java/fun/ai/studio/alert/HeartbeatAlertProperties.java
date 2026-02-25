package fun.ai.studio.alert;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 心跳告警配置。
 */
@Component
@ConfigurationProperties(prefix = "funai.alert.heartbeat")
public class HeartbeatAlertProperties {
    /**
     * 是否启用心跳告警检查
     */
    private boolean enabled = true;

    /**
     * 检查频率（cron），默认每 5 分钟一次
     */
    private String cron = "0 */5 * * * ?";

    /**
     * runtime 节点 stale 阈值（秒）
     */
    private long runtimeStaleSeconds = 90;

    /**
     * 相同“异常持续”情况下重复告警的最小间隔（分钟），避免刷屏
     */
    private long repeatMinutes = 30;

    /**
     * 是否发送恢复通知（unhealthy -> healthy）
     */
    private boolean sendRecovery = true;

    /**
     * 连续不健康次数阈值（达到后才触发告警）。最小为 1。
     */
    private int unhealthyThreshold = 1;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public long getRuntimeStaleSeconds() {
        return runtimeStaleSeconds;
    }

    public void setRuntimeStaleSeconds(long runtimeStaleSeconds) {
        this.runtimeStaleSeconds = runtimeStaleSeconds;
    }

    public long getRepeatMinutes() {
        return repeatMinutes;
    }

    public void setRepeatMinutes(long repeatMinutes) {
        this.repeatMinutes = repeatMinutes;
    }

    public boolean isSendRecovery() {
        return sendRecovery;
    }

    public void setSendRecovery(boolean sendRecovery) {
        this.sendRecovery = sendRecovery;
    }

    public int getUnhealthyThreshold() {
        return unhealthyThreshold;
    }

    public void setUnhealthyThreshold(int unhealthyThreshold) {
        this.unhealthyThreshold = unhealthyThreshold;
    }
}


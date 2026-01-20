package fun.ai.studio.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * workspace-node 故障转移配置（API 服务侧）。
 *
 * guarded auto-reassign：仅在判定安全时自动重分配（默认关闭）。
 */
@Component
@ConfigurationProperties(prefix = "workspace-node.failover")
public class WorkspaceNodeFailoverProperties {
    private AutoReassign autoReassign = new AutoReassign();

    public AutoReassign getAutoReassign() {
        return autoReassign;
    }

    public void setAutoReassign(AutoReassign autoReassign) {
        this.autoReassign = autoReassign;
    }

    public static class AutoReassign {
        /**
         * 是否启用条件自动迁移（默认关闭）
         */
        private boolean enabled = false;

        /**
         * 仅当 placement.last_active_at 距今超过该分钟数，才允许自动迁移（降低误切概率）
         */
        private long maxIdleMinutes = 30;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getMaxIdleMinutes() {
            return maxIdleMinutes;
        }

        public void setMaxIdleMinutes(long maxIdleMinutes) {
            this.maxIdleMinutes = maxIdleMinutes;
        }
    }
}



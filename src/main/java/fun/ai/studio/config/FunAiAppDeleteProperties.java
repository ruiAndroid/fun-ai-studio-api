package fun.ai.studio.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * /api/fun-ai/app/delete 行为配置：
 * - DB 删除仍为同步完成
 * - 外部清理（workspace/gitea/deploy）在后台并发执行
 * - cleanup-wait-ms 用于决定接口最多等待多久来收集“快速失败/快速成功”的结果（超时则秒回）
 */
@Component
@ConfigurationProperties(prefix = "funai.app.delete")
public class FunAiAppDeleteProperties {

    /**
     * 删除应用接口对“外部清理任务”的等待时间（毫秒）。
     * - 0：完全不等待，直接返回（最快）
     * - 建议 300~1500：能拿到部分清理失败提示，但不会被重操作拖慢太多
     */
    private long cleanupWaitMs = 800;

    public long getCleanupWaitMs() {
        return cleanupWaitMs;
    }

    public void setCleanupWaitMs(long cleanupWaitMs) {
        this.cleanupWaitMs = cleanupWaitMs;
    }
}



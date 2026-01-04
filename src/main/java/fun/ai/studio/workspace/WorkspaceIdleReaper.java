package fun.ai.studio.workspace;

import fun.ai.studio.service.FunAiWorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 单机版：定时回收 idle workspace
 * - 10 分钟无操作：stop run
 * - 20 分钟无操作：stop 容器
 */
@Component
public class WorkspaceIdleReaper {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceIdleReaper.class);

    private final WorkspaceActivityTracker tracker;
    private final FunAiWorkspaceService workspaceService;
    private final WorkspaceProperties props;

    public WorkspaceIdleReaper(WorkspaceActivityTracker tracker,
                               FunAiWorkspaceService workspaceService,
                               WorkspaceProperties props) {
        this.tracker = tracker;
        this.workspaceService = workspaceService;
        this.props = props;
    }

    @Scheduled(fixedDelay = 60_000L)
    public void sweep() {
        if (!props.isEnabled()) return;

        long now = System.currentTimeMillis();
        long stopRunMs = props.getIdleStopRunMinutes() * 60_000L;
        long stopContainerMs = props.getIdleStopContainerMinutes() * 60_000L;

        Map<Long, Long> snap = tracker.snapshot();
        for (var e : snap.entrySet()) {
            Long userId = e.getKey();
            Long lastAt = e.getValue();
            if (userId == null || lastAt == null) continue;
            long idle = now - lastAt;

            try {
                if (idle >= stopContainerMs) {
                    // 先 stop run 再停容器（避免残留 pid/meta）
                    workspaceService.stopRunForIdle(userId);
                    workspaceService.stopContainerForIdle(userId);
                } else if (idle >= stopRunMs) {
                    workspaceService.stopRunForIdle(userId);
                }
            } catch (Exception ex) {
                log.warn("idle reaper failed: userId={}, idleMs={}, error={}", userId, idle, ex.getMessage());
            }
        }
    }
}



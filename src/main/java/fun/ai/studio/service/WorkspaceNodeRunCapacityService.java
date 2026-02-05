package fun.ai.studio.service;

import fun.ai.studio.common.WorkspaceNodeCapacityException;
import fun.ai.studio.workspace.WorkspaceNodeClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * workspace-node 节点运行态容量限制（按 RUNNING/STARTING/BUILDING/INSTALLING 统计）。
 */
@Service
public class WorkspaceNodeRunCapacityService {
    private static final String CAPACITY_REJECT_MESSAGE = "服务器资源紧张请稍后进入";

    private final WorkspaceNodeClient workspaceNodeClient;

    @Value("${funai.workspace-node.limits.enabled:false}")
    private boolean limitEnabled;

    @Value("${funai.workspace-node.limits.max-running-per-node:0}")
    private int maxRunningPerNode;

    public WorkspaceNodeRunCapacityService(WorkspaceNodeClient workspaceNodeClient) {
        this.workspaceNodeClient = workspaceNodeClient;
    }

    public void assertCanStart(Long userId) {
        if (!limitEnabled || maxRunningPerNode <= 0) return;
        if (userId == null) return;
        if (workspaceNodeClient == null || !workspaceNodeClient.isEnabled()) return;

        Map<String, Object> snapshot;
        try {
            snapshot = workspaceNodeClient.getRunBusyCount(userId);
        } catch (WorkspaceNodeCapacityException e) {
            throw e;
        } catch (Exception ignore) {
            return;
        }
        if (snapshot == null || snapshot.isEmpty()) return;

        boolean userBusy = asBool(snapshot.get("userBusy"));
        if (userBusy) return;

        long busyCount = asLong(snapshot.get("busyCount"));
        if (busyCount >= maxRunningPerNode) {
            throw new WorkspaceNodeCapacityException(CAPACITY_REJECT_MESSAGE);
        }
    }

    private long asLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception ignore) {
            return 0L;
        }
    }

    private boolean asBool(Object v) {
        if (v instanceof Boolean b) return b;
        if (v == null) return false;
        return "true".equalsIgnoreCase(String.valueOf(v));
    }
}

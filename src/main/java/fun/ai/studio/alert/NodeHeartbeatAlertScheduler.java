package fun.ai.studio.alert;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import fun.ai.studio.config.AdminSecurityProperties;
import fun.ai.studio.config.WorkspaceNodeRegistryProperties;
import fun.ai.studio.deploy.DeployAdminProxyClient;
import fun.ai.studio.entity.FunAiWorkspaceNode;
import fun.ai.studio.mapper.FunAiWorkspaceNodeMapper;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 节点心跳告警（workspace/runtime）。
 *
 * <p>策略：仅在状态变化（HEALTHY->STALE）或异常持续超过 repeatMinutes 时重复发送，避免刷屏。</p>
 */
@Component
public class NodeHeartbeatAlertScheduler {
    private static final Logger log = LoggerFactory.getLogger(NodeHeartbeatAlertScheduler.class);

    private final HeartbeatAlertProperties alertProps;
    private final MailAlertService mail;

    private final FunAiWorkspaceNodeMapper wsNodeMapper;
    private final WorkspaceNodeRegistryProperties wsRegistryProps;

    private final DeployAdminProxyClient deployAdminProxy;
    private final AdminSecurityProperties adminProps;

    // in-memory state (ShedLock ensures single runner across instances)
    private final Map<String, Boolean> lastHealthy = new HashMap<>();
    private final Map<String, Long> lastAlertAtMs = new HashMap<>();

    public NodeHeartbeatAlertScheduler(HeartbeatAlertProperties alertProps,
                                       MailAlertService mail,
                                       FunAiWorkspaceNodeMapper wsNodeMapper,
                                       WorkspaceNodeRegistryProperties wsRegistryProps,
                                       DeployAdminProxyClient deployAdminProxy,
                                       AdminSecurityProperties adminProps) {
        this.alertProps = alertProps;
        this.mail = mail;
        this.wsNodeMapper = wsNodeMapper;
        this.wsRegistryProps = wsRegistryProps;
        this.deployAdminProxy = deployAdminProxy;
        this.adminProps = adminProps;
    }

    @Scheduled(cron = "${funai.alert.heartbeat.cron:0 */5 * * * ?}")
    @SchedulerLock(name = "nodeHeartbeatAlert", lockAtLeastFor = "PT20S", lockAtMostFor = "PT3M")
    public void check() {
        if (alertProps == null || !alertProps.isEnabled()) return;
        if (mail == null || !mail.isEnabled()) {
            log.debug("mail alert disabled, skip heartbeat alert check");
            return;
        }

        long now = System.currentTimeMillis();
        long repeatMs = Duration.ofMinutes(Math.max(1, alertProps.getRepeatMinutes())).toMillis();

        List<String> events = new ArrayList<>();
        List<String> unhealthySnapshot = new ArrayList<>();

        // 1) workspace nodes
        try {
            Duration stale = (wsRegistryProps == null) ? Duration.ofSeconds(60) : wsRegistryProps.heartbeatStaleDuration();
            long staleMs = Math.max(1, stale.toMillis());

            List<FunAiWorkspaceNode> nodes = (wsNodeMapper == null) ? List.of() : wsNodeMapper.selectList(new QueryWrapper<>());
            for (FunAiWorkspaceNode n : nodes == null ? List.<FunAiWorkspaceNode>of() : nodes) {
                if (n == null) continue;
                if (n.getEnabled() == null || n.getEnabled() != 1) continue;
                String key = "workspace:" + (n.getId() == null ? "?" : String.valueOf(n.getId()));

                long lastMs = toEpochMs(n.getLastHeartbeatAt());
                boolean healthy = lastMs > 0 && (now - lastMs) <= staleMs;

                String detail = "Workspace 节点"
                        + " id=" + safe(n.getId())
                        + " name=" + safe(n.getName())
                        + " apiBaseUrl=" + safe(n.getApiBaseUrl())
                        + " lastHeartbeatAt=" + (lastMs <= 0 ? "null" : Instant.ofEpochMilli(lastMs))
                        + " 距今秒数=" + (lastMs <= 0 ? "?" : String.valueOf((now - lastMs) / 1000))
                        + " stale阈值秒=" + (staleMs / 1000);

                handleState(key, healthy, detail, now, repeatMs, events, unhealthySnapshot);
            }
        } catch (Exception e) {
            log.warn("workspace heartbeat alert check failed: {}", e.getMessage(), e);
        }

        // 2) runtime nodes (via deploy control plane)
        try {
            if (deployAdminProxy != null && deployAdminProxy.isEnabled()) {
                long staleMs = Duration.ofSeconds(Math.max(10, alertProps.getRuntimeStaleSeconds())).toMillis();
                String adminToken = (adminProps == null ? null : adminProps.getToken());
                if (!StringUtils.hasText(adminToken)) {
                    log.warn("admin token not configured, cannot query deploy runtime nodes for alerting");
                } else {
                    fun.ai.studio.common.Result<List<Map<String, Object>>> res = deployAdminProxy.get(
                            "/admin/runtime-nodes/list",
                            null,
                            adminToken,
                            new ParameterizedTypeReference<>() {
                            }
                    );
                    if (res == null || res.getCode() == null || res.getCode() != 200) {
                        log.warn("deploy runtime nodes list failed: {}", res == null ? "null" : res.getMessage());
                    } else {
                        List<Map<String, Object>> list = res.getData() == null ? List.of() : res.getData();
                        for (Map<String, Object> m : list) {
                            if (m == null) continue;
                            boolean enabled = asBool(m.get("enabled"));
                            if (!enabled) continue;
                            String nodeId = String.valueOf(m.get("nodeId"));
                            String key = "runtime:" + nodeId;

                            long lastMs = parseHeartbeatMs(m.get("lastHeartbeatAt"), m.get("lastHeartbeatAtMs"));
                            String healthStr = m.get("health") == null ? null : String.valueOf(m.get("health"));
                            boolean staleByHealth = healthStr != null && "STALE".equalsIgnoreCase(healthStr.trim());
                            boolean healthy = !staleByHealth && lastMs > 0 && (now - lastMs) <= staleMs;

                            String detail = "Runtime 节点"
                                    + " nodeId=" + safe(m.get("nodeId"))
                                    + " name=" + safe(m.get("name"))
                                    + " agentBaseUrl=" + safe(m.get("agentBaseUrl"))
                                    + " lastHeartbeatAt=" + (lastMs <= 0 ? "null" : Instant.ofEpochMilli(lastMs))
                                    + " 距今秒数=" + (lastMs <= 0 ? "?" : String.valueOf((now - lastMs) / 1000))
                                    + " stale阈值秒=" + (staleMs / 1000)
                                    + (healthStr == null ? "" : (" health=" + healthStr));

                            handleState(key, healthy, detail, now, repeatMs, events, unhealthySnapshot);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("runtime heartbeat alert check failed: {}", e.getMessage(), e);
        }

        if (events.isEmpty()) {
            return;
        }

        // 根据事件类型动态生成标题：断联/恢复等直接体现在标题里，便于收件箱快速筛选
        int down = 0;
        int recovered = 0;
        int repeat = 0;
        int stale = 0;
        for (String e : events) {
            if (e == null) continue;
            if (e.startsWith("[下线/心跳超时]")) down++;
            else if (e.startsWith("[恢复]")) recovered++;
            else if (e.startsWith("[持续异常重复告警]")) repeat++;
            else if (e.startsWith("[异常]")) stale++;
        }
        StringBuilder subj = new StringBuilder("节点心跳告警");
        if (down > 0) subj.append("【断联×").append(down).append("】");
        if (recovered > 0) subj.append("【恢复×").append(recovered).append("】");
        if (repeat > 0) subj.append("【持续异常×").append(repeat).append("】");
        // 仅初次异常且没有 down（例如 lastHeartbeatAt=null 的初始化场景）：也在标题里标记一下
        if (down == 0 && repeat == 0 && stale > 0) subj.append("【异常×").append(stale).append("】");

        StringBuilder body = new StringBuilder();
        body.append("时间：").append(Instant.ofEpochMilli(now)).append("\n\n");
        body.append("事件：\n");
        for (String e : events) body.append("- ").append(e).append("\n");
        if (!unhealthySnapshot.isEmpty()) {
            body.append("\n当前异常节点快照：\n");
            for (String d : unhealthySnapshot) body.append("- ").append(d).append("\n");
        }

        mail.send(subj.toString(), body.toString());
    }

    private void handleState(String key,
                             boolean healthy,
                             String detail,
                             long nowMs,
                             long repeatMs,
                             List<String> events,
                             List<String> unhealthySnapshot) {
        Boolean prev = lastHealthy.get(key);
        boolean prevHealthy = prev == null || prev;

        if (!healthy) {
            unhealthySnapshot.add(detail);
        }

        if (prev == null) {
            lastHealthy.put(key, healthy);
            if (!healthy) {
                events.add("[异常] " + detail);
                lastAlertAtMs.put(key, nowMs);
            }
            return;
        }

        if (prevHealthy && !healthy) {
            lastHealthy.put(key, false);
            events.add("[下线/心跳超时] " + detail);
            lastAlertAtMs.put(key, nowMs);
            return;
        }

        if (!prevHealthy && healthy) {
            lastHealthy.put(key, true);
            if (alertProps != null && alertProps.isSendRecovery()) {
                events.add("[恢复] " + detail);
            }
            lastAlertAtMs.remove(key);
            return;
        }

        // still unhealthy: repeat
        if (!healthy) {
            Long lastAt = lastAlertAtMs.get(key);
            if (lastAt == null || (nowMs - lastAt) >= repeatMs) {
                events.add("[持续异常重复告警] " + detail);
                lastAlertAtMs.put(key, nowMs);
            }
        }
    }

    private long toEpochMs(LocalDateTime t) {
        if (t == null) return 0;
        try {
            return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception ignore) {
            return 0;
        }
    }

    private long parseHeartbeatMs(Object lastHeartbeatAt, Object lastHeartbeatAtMs) {
        // 1) prefer explicit ms
        try {
            if (lastHeartbeatAtMs instanceof Number n) return n.longValue();
            if (lastHeartbeatAtMs != null) return Long.parseLong(String.valueOf(lastHeartbeatAtMs));
        } catch (Exception ignore) {
        }
        // 2) parse ISO instant string
        try {
            if (lastHeartbeatAt != null) {
                Instant at = Instant.parse(String.valueOf(lastHeartbeatAt));
                return at.toEpochMilli();
            }
        } catch (Exception ignore) {
        }
        return 0;
    }

    private boolean asBool(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() == 1;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        return "true".equals(s) || "1".equals(s) || "yes".equals(s);
    }

    private String safe(Object o) {
        if (o == null) return "";
        String s = String.valueOf(o).trim();
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}


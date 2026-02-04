package fun.ai.studio.scheduled;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.ai.studio.entity.FunAiApp;
import fun.ai.studio.mapper.FunAiAppMapper;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 孤立数据定时清理调度器（主控）
 * 
 * 运行在主项目（91 服务器），负责：
 * 1. 查询数据库获取所有应用 ID
 * 2. 调用 workspace（87）清理接口，清理开发态数据
 * 3. 调用 runtime（102）清理接口，清理部署态数据
 * 
 * 执行时间：每天凌晨 2:00
 */
@Component
public class OrphanedDataCleanupScheduler {
    private static final Logger log = LoggerFactory.getLogger(OrphanedDataCleanupScheduler.class);

    private final FunAiAppMapper appMapper;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private static final String HDR_RUNTIME_TOKEN = "X-Runtime-Token";

    @Value("${funai.cleanup.workspace-url:http://172.21.138.87:7001}")
    private String workspaceUrl;

    @Value("${funai.cleanup.runtime-url:http://172.21.138.102:7005}")
    private String runtimeUrl;

    /**
     * runtime-agent token（Header: X-Runtime-Token）
     */
    @Value("${funai.cleanup.runtime-token:}")
    private String runtimeToken;

    @Value("${funai.cleanup.enabled:true}")
    private boolean enabled;

    public OrphanedDataCleanupScheduler(FunAiAppMapper appMapper, ObjectMapper objectMapper) {
        this.appMapper = appMapper;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                // runtime-agent（FastAPI/uvicorn）对 HTTP/2 prior-knowledge/h2c 兼容性不一致；强制 HTTP/1.1 避免 "Invalid HTTP request received."
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 每天凌晨 2:00 执行清理任务
     * cron 格式：秒 分 时 日 月 周
     */
    @Scheduled(cron = "${funai.cleanup.cron:0 0 2 * * ?}")
    @SchedulerLock(name = "orphanedDataCleanup", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void cleanOrphanedData() {
        if (!enabled) {
            log.debug("孤立数据清理已禁用");
            return;
        }

        log.info("=== 开始孤立数据清理调度 ===");
        long startTime = System.currentTimeMillis();

        try {
            // 1. 从数据库查询所有存在的应用 ID
            Set<Long> existingAppIds = loadExistingAppIds();
            log.info("数据库中存在的应用数量: {}", existingAppIds.size());

            // 2. 调用 workspace（87）清理开发态数据
            cleanWorkspaceData(existingAppIds);

            // 3. 调用 runtime（102）清理部署态数据
            cleanRuntimeData(existingAppIds);

            long duration = System.currentTimeMillis() - startTime;
            log.info("=== 孤立数据清理调度完成，耗时: {}ms ===", duration);
        } catch (Exception e) {
            log.error("孤立数据清理调度失败", e);
        }
    }

    /**
     * 从数据库加载所有存在的应用 ID
     */
    private Set<Long> loadExistingAppIds() {
        try {
            List<FunAiApp> apps = appMapper.selectList(new QueryWrapper<>());
            return apps.stream()
                    .map(FunAiApp::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            log.error("加载应用列表失败", e);
            return new HashSet<>();
        }
    }

    /**
     * 调用 workspace（87）清理开发态数据
     */
    private void cleanWorkspaceData(Set<Long> existingAppIds) {
        if (!StringUtils.hasText(workspaceUrl)) {
            log.warn("workspace URL 未配置，跳过开发态清理");
            return;
        }

        try {
            String url = workspaceUrl.replaceAll("/$", "") + "/api/fun-ai/workspace/internal/cleanup-orphaned";
            
            Map<String, Object> request = new HashMap<>();
            request.put("existingAppIds", new ArrayList<>(existingAppIds));
            
            String requestBody = objectMapper.writeValueAsString(request);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            
            log.info("调用 workspace 清理接口: {}", url);
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                log.info("workspace 清理完成: {}", response.body());
            } else {
                log.error("workspace 清理失败: HTTP {}, body: {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.error("调用 workspace 清理接口失败", e);
        }
    }

    /**
     * 调用 runtime（102）清理部署态数据
     */
    private void cleanRuntimeData(Set<Long> existingAppIds) {
        if (!StringUtils.hasText(runtimeUrl)) {
            log.warn("runtime URL 未配置，跳过部署态清理");
            return;
        }
        if (!StringUtils.hasText(runtimeToken)) {
            log.warn("runtimeToken 未配置（X-Runtime-Token）：跳过部署态清理（否则 runtime-agent 会返回 401 unauthorized）");
            return;
        }

        try {
            String url = runtimeUrl.replaceAll("/$", "") + "/agent/cleanup-orphaned";
            
            Map<String, Object> request = new HashMap<>();
            request.put("existingAppIds", new ArrayList<>(existingAppIds));
            
            String requestBody = objectMapper.writeValueAsString(request);
            
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    .header(HDR_RUNTIME_TOKEN, runtimeToken)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            
            log.info("调用 runtime 清理接口: {}", url);
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                log.info("runtime 清理完成: {}", response.body());
            } else {
                log.error("runtime 清理失败: HTTP {}, body: {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.error("调用 runtime 清理接口失败", e);
        }
    }
}

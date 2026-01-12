package fun.ai.studio.controller.workspace.mongo;

import fun.ai.studio.common.Result;
import fun.ai.studio.entity.request.WorkspaceMongoFindRequest;
import fun.ai.studio.entity.response.WorkspaceMongoCollectionsResponse;
import fun.ai.studio.entity.response.WorkspaceMongoDocResponse;
import fun.ai.studio.entity.response.WorkspaceMongoFindResponse;
import fun.ai.studio.service.FunAiAppService;
import fun.ai.studio.service.FunAiWorkspaceService;
import fun.ai.studio.workspace.WorkspaceActivityTracker;
import fun.ai.studio.workspace.WorkspaceProperties;
import fun.ai.studio.workspace.mongo.WorkspaceMongoShellClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Workspace Mongo Explorer（只读）
 * <p>
 * 设计目标：不暴露 27017，通过 docker exec 在容器内调用 mongosh 查询。
 */
@RestController
@RequestMapping("/api/fun-ai/workspace/mongo")
@Tag(name = "Fun AI Workspace Mongo Explorer", 
description = "Mongo Explorer（只读）访问地址 http://{{公网ip}}/workspace-mongo.html?userId={{userId}}&appId={{appId}}#token={{token}} ")
public class FunAiWorkspaceMongoController {
    private static final Logger log = LoggerFactory.getLogger(FunAiWorkspaceMongoController.class);

    private final FunAiWorkspaceService workspaceService;
    private final FunAiAppService funAiAppService;
    private final WorkspaceProperties workspaceProperties;
    private final WorkspaceActivityTracker activityTracker;
    private final WorkspaceMongoShellClient mongoShellClient;

    public FunAiWorkspaceMongoController(FunAiWorkspaceService workspaceService,
                                         FunAiAppService funAiAppService,
                                         WorkspaceProperties workspaceProperties,
                                         WorkspaceActivityTracker activityTracker,
                                         WorkspaceMongoShellClient mongoShellClient) {
        this.workspaceService = workspaceService;
        this.funAiAppService = funAiAppService;
        this.workspaceProperties = workspaceProperties;
        this.activityTracker = activityTracker;
        this.mongoShellClient = mongoShellClient;
    }

    @GetMapping("/collections")
    @Operation(summary = "列出集合（只读）", description = "在容器内使用 mongosh 列出 db_{appId} 的集合列表")
    public Result<WorkspaceMongoCollectionsResponse> collections(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId
    ) {
        try {
            assertAppOwned(userId, appId);
            activityTracker.touch(userId);
            // 确保容器存在且运行（DB Explorer 属于交互操作，允许有副作用）
            workspaceService.ensureAppDir(userId, appId);

            String containerName = containerName(userId);
            String dbName = dbNameForApp(appId);
            List<String> cols = mongoShellClient.listCollections(containerName, dbName);

            WorkspaceMongoCollectionsResponse resp = new WorkspaceMongoCollectionsResponse();
            resp.setUserId(userId);
            resp.setAppId(appId);
            resp.setDbName(dbName);
            resp.setCollections(cols);
            return Result.success(resp);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("mongo collections failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("mongo collections failed: " + e.getMessage());
        }
    }

    @PostMapping("/find")
    @Operation(summary = "查询文档列表（只读）", description = "在容器内使用 mongosh 执行 find（带 limit/skip/sort/projection）")
    public Result<WorkspaceMongoFindResponse> find(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @RequestBody WorkspaceMongoFindRequest req
    ) {
        try {
            assertAppOwned(userId, appId);
            activityTracker.touch(userId);
            workspaceService.ensureAppDir(userId, appId);

            if (req == null) throw new IllegalArgumentException("body 不能为空");

            String containerName = containerName(userId);
            String dbName = dbNameForApp(appId);

            Map<String, Object> out = mongoShellClient.find(
                    containerName,
                    dbName,
                    req.getCollection(),
                    req.getFilter(),
                    req.getProjection(),
                    req.getSort(),
                    req.getLimit(),
                    req.getSkip()
            );

            WorkspaceMongoFindResponse resp = new WorkspaceMongoFindResponse();
            resp.setUserId(userId);
            resp.setAppId(appId);
            resp.setDbName(dbName);
            resp.setCollection(asString(out.get("collection")));
            resp.setLimit(asInt(out.get("limit")));
            resp.setSkip(asInt(out.get("skip")));
            resp.setReturned(asInt(out.get("returned")));
            resp.setItems(toListOfStringObjectMap(out.get("items")));
            return Result.success(resp);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("mongo find failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("mongo find failed: " + e.getMessage());
        }
    }

    @GetMapping("/doc")
    @Operation(summary = "按 _id 查询单条文档（只读）", description = "id 尝试按 ObjectId 解析，失败则按字符串 _id 查询")
    public Result<WorkspaceMongoDocResponse> doc(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @Parameter(description = "集合名", required = true) @RequestParam String collection,
            @Parameter(description = "文档 _id（ObjectId hex 或 string）", required = true) @RequestParam String id
    ) {
        try {
            assertAppOwned(userId, appId);
            activityTracker.touch(userId);
            workspaceService.ensureAppDir(userId, appId);

            String containerName = containerName(userId);
            String dbName = dbNameForApp(appId);

            Map<String, Object> out = mongoShellClient.findOneById(containerName, dbName, collection, id);

            WorkspaceMongoDocResponse resp = new WorkspaceMongoDocResponse();
            resp.setUserId(userId);
            resp.setAppId(appId);
            resp.setDbName(dbName);
            resp.setCollection(asString(out.get("collection")));
            resp.setId(asString(out.get("id")));
            resp.setDoc(toStringObjectMapOrNull(out.get("doc")));
            return Result.success(resp);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("mongo doc failed: userId={}, appId={}, collection={}, id={}, error={}",
                    userId, appId, collection, id, e.getMessage(), e);
            return Result.error("mongo doc failed: " + e.getMessage());
        }
    }

    private void assertAppOwned(Long userId, Long appId) {
        if (userId == null || appId == null) {
            throw new IllegalArgumentException("userId/appId 不能为空");
        }
        if (funAiAppService.getAppByIdAndUserId(appId, userId) == null) {
            throw new IllegalArgumentException("应用不存在或无权限操作");
        }
    }

    private String containerName(Long userId) {
        String prefix = workspaceProperties == null ? null : workspaceProperties.getContainerNamePrefix();
        if (!StringUtils.hasText(prefix)) prefix = "ws-u-";
        return prefix + userId;
    }

    private String dbNameForApp(Long appId) {
        String prefix = (workspaceProperties == null || workspaceProperties.getMongo() == null)
                ? null
                : workspaceProperties.getMongo().getDbNamePrefix();
        if (!StringUtils.hasText(prefix)) prefix = "db_";
        return prefix + appId;
    }

    private static String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static Integer asInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception ignore) {
            return null;
        }
    }

    private static List<Map<String, Object>> toListOfStringObjectMap(Object v) {
        if (!(v instanceof List<?> l)) return List.of();
        return l.stream()
                .map(FunAiWorkspaceMongoController::toStringObjectMapOrNull)
                .filter(m -> m != null && !m.isEmpty())
                .toList();
    }

    private static Map<String, Object> toStringObjectMapOrNull(Object v) {
        if (!(v instanceof Map<?, ?> m)) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String k = e.getKey() == null ? null : String.valueOf(e.getKey());
            out.put(k, e.getValue());
        }
        return out;
    }
}



package fun.ai.studio.controller.workspace.mongo;

import fun.ai.studio.common.Result;
import fun.ai.studio.common.WorkspaceNodeProxyException;
import fun.ai.studio.entity.request.WorkspaceMongoCreateCollectionRequest;
import fun.ai.studio.entity.request.WorkspaceMongoDeleteByIdRequest;
import fun.ai.studio.entity.request.WorkspaceMongoFindRequest;
import fun.ai.studio.entity.request.WorkspaceMongoInsertOneRequest;
import fun.ai.studio.entity.request.WorkspaceMongoOpRequest;
import fun.ai.studio.entity.request.WorkspaceMongoUpdateByIdRequest;
import fun.ai.studio.entity.response.WorkspaceMongoCollectionsResponse;
import fun.ai.studio.entity.response.WorkspaceMongoCreateCollectionResponse;
import fun.ai.studio.entity.response.WorkspaceMongoDeleteOneResponse;
import fun.ai.studio.entity.response.WorkspaceMongoDocResponse;
import fun.ai.studio.entity.response.WorkspaceMongoFindResponse;
import fun.ai.studio.entity.response.WorkspaceMongoInsertOneResponse;
import fun.ai.studio.entity.response.WorkspaceMongoUpdateOneResponse;
import fun.ai.studio.service.FunAiAppService;
import fun.ai.studio.workspace.WorkspaceActivityTracker;
import fun.ai.studio.workspace.WorkspaceNodeClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workspace Mongo Explorer
 *
 * <p>双机模式下：API 服务器（小机）仅做鉴权与 appOwned 校验，然后转发到 workspace-node（大机）执行。</p>
 * <p>为了避免 API 工程残留 docker/mongosh 重实现导致维护误判，这里不再提供本机 docker exec 的 fallback。</p>
 *
 * 访问地址示例：
 * http://{{公网ip}}/workspace-mongo.html?userId={{userId}}&appId={{appId}}#token={{token}}
 */
@RestController
@RequestMapping("/api/fun-ai/workspace/mongo")
@Tag(name = "Fun AI Workspace Mongo Explorer", description = "Mongo Explorer（经 API 校验后转发到 workspace-node 执行）")
public class FunAiWorkspaceMongoController {
    private static final Logger log = LoggerFactory.getLogger(FunAiWorkspaceMongoController.class);

    private final FunAiAppService funAiAppService;
    private final WorkspaceActivityTracker activityTracker;
    private final WorkspaceNodeClient workspaceNodeClient;

    public FunAiWorkspaceMongoController(FunAiAppService funAiAppService,
                                         WorkspaceActivityTracker activityTracker,
                                         WorkspaceNodeClient workspaceNodeClient) {
        this.funAiAppService = funAiAppService;
        this.activityTracker = activityTracker;
        this.workspaceNodeClient = workspaceNodeClient;
    }

    private void assertAppOwned(Long userId, Long appId) {
        if (userId == null || appId == null) {
            throw new IllegalArgumentException("userId/appId 不能为空");
        }
        if (funAiAppService == null || funAiAppService.getAppByIdAndUserId(appId, userId) == null) {
            throw new IllegalArgumentException("应用不存在或已删除");
        }
    }

    private void requireWorkspaceNode() {
        if (workspaceNodeClient == null || !workspaceNodeClient.isEnabled()) {
            throw new WorkspaceNodeProxyException("workspace-node 未启用或不可用（请检查 API<->workspace-node 网络、shared-secret、以及 workspace-node 服务状态）");
        }
    }

    @GetMapping("/collections")
    @Operation(summary = "列出集合", description = "列出 db_{appId} 的集合列表（由 workspace-node 执行）")
    public Result<WorkspaceMongoCollectionsResponse> collections(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId
    ) {
        try {
            assertAppOwned(userId, appId);
            if (activityTracker != null) activityTracker.touch(userId);
            requireWorkspaceNode();
            return Result.success(workspaceNodeClient.mongoCollections(userId, appId));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("mongo collections failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("mongo collections failed: " + e.getMessage());
        }
    }

    @PostMapping("/find")
    @Operation(summary = "查询文档", description = "find（由 workspace-node 执行）")
    public Result<WorkspaceMongoFindResponse> find(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @RequestBody WorkspaceMongoFindRequest req
    ) {
        try {
            assertAppOwned(userId, appId);
            if (activityTracker != null) activityTracker.touch(userId);
            requireWorkspaceNode();
            return Result.success(workspaceNodeClient.mongoFind(userId, appId, req));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("mongo find failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("mongo find failed: " + e.getMessage());
        }
    }

    @GetMapping("/doc")
    @Operation(summary = "读取单条文档", description = "按 _id 查询单条文档（由 workspace-node 执行）")
    public Result<WorkspaceMongoDocResponse> doc(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @Parameter(description = "集合名", required = true) @RequestParam String collection,
            @Parameter(description = "文档ID(_id)", required = true) @RequestParam String id
    ) {
        try {
            assertAppOwned(userId, appId);
            if (activityTracker != null) activityTracker.touch(userId);
            requireWorkspaceNode();
            return Result.success(workspaceNodeClient.mongoDoc(userId, appId, collection, id));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("mongo doc failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("mongo doc failed: " + e.getMessage());
        }
    }

    @PostMapping("/create-collection")
    @Operation(summary = "创建集合", description = "创建集合（由 workspace-node 执行）")
    public Result<WorkspaceMongoCreateCollectionResponse> createCollection(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @RequestBody WorkspaceMongoCreateCollectionRequest req
    ) {
        try {
            assertAppOwned(userId, appId);
            if (activityTracker != null) activityTracker.touch(userId);
            requireWorkspaceNode();
            return Result.success(workspaceNodeClient.mongoCreateCollection(userId, appId, req));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("mongo create-collection failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("mongo create-collection failed: " + e.getMessage());
        }
    }

    @PostMapping("/insert-one")
    @Operation(summary = "插入一条文档", description = "insertOne（由 workspace-node 执行）")
    public Result<WorkspaceMongoInsertOneResponse> insertOne(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @RequestBody WorkspaceMongoInsertOneRequest req
    ) {
        try {
            assertAppOwned(userId, appId);
            if (activityTracker != null) activityTracker.touch(userId);
            requireWorkspaceNode();
            return Result.success(workspaceNodeClient.mongoInsertOne(userId, appId, req));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("mongo insert-one failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("mongo insert-one failed: " + e.getMessage());
        }
    }

    @PostMapping("/update-by-id")
    @Operation(summary = "按 _id 更新文档", description = "按 _id 更新（由 workspace-node 执行）")
    public Result<WorkspaceMongoUpdateOneResponse> updateById(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @RequestBody WorkspaceMongoUpdateByIdRequest req
    ) {
        try {
            assertAppOwned(userId, appId);
            if (activityTracker != null) activityTracker.touch(userId);
            requireWorkspaceNode();
            return Result.success(workspaceNodeClient.mongoUpdateById(userId, appId, req));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("mongo update-by-id failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("mongo update-by-id failed: " + e.getMessage());
        }
    }

    @PostMapping("/delete-by-id")
    @Operation(summary = "按 _id 删除文档", description = "按 _id 删除（由 workspace-node 执行）")
    public Result<WorkspaceMongoDeleteOneResponse> deleteById(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @RequestBody WorkspaceMongoDeleteByIdRequest req
    ) {
        try {
            assertAppOwned(userId, appId);
            if (activityTracker != null) activityTracker.touch(userId);
            requireWorkspaceNode();
            return Result.success(workspaceNodeClient.mongoDeleteById(userId, appId, req));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("mongo delete-by-id failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("mongo delete-by-id failed: " + e.getMessage());
        }
    }

    @PostMapping("/op")
    @Operation(summary = "执行 Mongo 指令（高级）", description = "高级操作入口（由 workspace-node 执行；写操作，需谨慎）")
    public Result<Object> op(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @RequestBody WorkspaceMongoOpRequest req
    ) {
        try {
            assertAppOwned(userId, appId);
            if (activityTracker != null) activityTracker.touch(userId);
            requireWorkspaceNode();
            return Result.success(workspaceNodeClient.mongoOp(userId, appId, req));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("mongo op failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("mongo op failed: " + e.getMessage());
        }
    }
}



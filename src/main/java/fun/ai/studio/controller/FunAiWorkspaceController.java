package fun.ai.studio.controller;

import fun.ai.studio.common.Result;
import fun.ai.studio.service.FunAiWorkspaceService;
import fun.ai.studio.entity.response.FunAiWorkspaceInfoResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceProjectDirResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceStatusResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.*;

/**
 * Workspace 控制器（阶段B：先打通持久化目录 + 容器挂载）
 */
@RestController
@RequestMapping("/api/fun-ai/workspace")
@Tag(name = "Fun AI Workspace 容器", description = "用户在线开发 workspace（容器）管理接口")

public class FunAiWorkspaceController {
    private static final Logger log = LoggerFactory.getLogger(FunAiWorkspaceController.class);

    private final FunAiWorkspaceService funAiWorkspaceService;

    public FunAiWorkspaceController(FunAiWorkspaceService funAiWorkspaceService) {
        this.funAiWorkspaceService = funAiWorkspaceService;
    }

    @PostMapping("/ensure")
    @Operation(summary = "确保 workspace 存在并启动", description = "创建宿主机目录并确保 ws-u-{userId} 容器运行，挂载到 /workspace")
    public Result<FunAiWorkspaceInfoResponse> ensure(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId
    ) {
        try {
            return Result.success(funAiWorkspaceService.ensureWorkspace(userId));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("ensure workspace failed: userId={}, error={}", userId, e.getMessage(), e);
            return Result.error("ensure workspace failed: " + e.getMessage());
        }
    }

    @GetMapping("/status")
    @Operation(summary = "查询 workspace 状态", description = "返回容器状态、端口、宿主机目录")
    public Result<FunAiWorkspaceStatusResponse> status(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId
    ) {
        try {
            return Result.success(funAiWorkspaceService.getStatus(userId));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("get workspace status failed: userId={}, error={}", userId, e.getMessage(), e);
            return Result.error("get workspace status failed: " + e.getMessage());
        }
    }

    @PostMapping("/projects/ensure-dir")
    @Operation(summary = "确保项目目录存在", description = "确保 /workspace/projects/<projectId> 的宿主机目录存在（会先 ensure workspace）")
    public Result<FunAiWorkspaceProjectDirResponse> ensureProjectDir(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "项目ID（字符串）", required = true) @RequestParam String projectId
    ) {
        try {
            return Result.success(funAiWorkspaceService.ensureProjectDir(userId, projectId));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("ensure project dir failed: userId={}, projectId={}, error={}", userId, projectId, e.getMessage(), e);
            return Result.error("ensure project dir failed: " + e.getMessage());
        }
    }

    @PostMapping("/projects/upload")
    @Operation(summary = "上传项目 zip 并解压到 workspace 项目目录", description = "上传 zip 后解压到 {hostRoot}/{userId}/projects/{projectId}，容器内可见 /workspace/projects/{projectId}")
    public Result<FunAiWorkspaceProjectDirResponse> uploadProjectZip(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "项目ID（字符串）", required = true) @RequestParam String projectId,
            @Parameter(description = "zip文件", required = true) @RequestParam("file") MultipartFile file,
            @Parameter(description = "是否覆盖已存在目录（默认 true）") @RequestParam(defaultValue = "true") boolean overwrite
    ) {
        try {
            return Result.success(funAiWorkspaceService.uploadProjectZip(userId, projectId, file, overwrite));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("upload project zip failed: userId={}, projectId={}, error={}", userId, projectId, e.getMessage(), e);
            return Result.error("upload project zip failed: " + e.getMessage());
        }
    }
}



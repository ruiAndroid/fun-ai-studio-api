package fun.ai.studio.service;

import fun.ai.studio.entity.response.FunAiWorkspaceInfoResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceProjectDirResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceRunStatusResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceStatusResponse;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;

public interface FunAiWorkspaceService {

    FunAiWorkspaceInfoResponse ensureWorkspace(Long userId);

    FunAiWorkspaceStatusResponse getStatus(Long userId);

    FunAiWorkspaceProjectDirResponse ensureAppDir(Long userId, Long appId);

    /**
     * 上传 zip 并解压到指定 app 目录：
     * 宿主机：{hostRoot}/{userId}/apps/{appId}
     * 容器内：/workspace/apps/{appId}
     */
    FunAiWorkspaceProjectDirResponse uploadAppZip(Long userId, Long appId, MultipartFile file, boolean overwrite);

    /**
     * 启动 dev server（当前阶段：同一用户同时只能运行一个应用）
     */
    FunAiWorkspaceRunStatusResponse startDev(Long userId, Long appId);

    /**
     * 停止当前运行任务（如果存在）
     */
    FunAiWorkspaceRunStatusResponse stopRun(Long userId);

    /**
     * 查询当前运行状态
     */
    FunAiWorkspaceRunStatusResponse getRunStatus(Long userId);

    /**
     * 将指定 app 目录打包为 zip 文件并返回 zipPath（位于 run/ 目录下的临时文件）
     */
    Path exportAppAsZip(Long userId, Long appId);
}



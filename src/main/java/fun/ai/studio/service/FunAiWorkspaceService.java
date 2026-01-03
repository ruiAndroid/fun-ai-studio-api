package fun.ai.studio.service;

import fun.ai.studio.entity.response.FunAiWorkspaceInfoResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceProjectDirResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceStatusResponse;
import org.springframework.web.multipart.MultipartFile;

public interface FunAiWorkspaceService {

    FunAiWorkspaceInfoResponse ensureWorkspace(Long userId);

    FunAiWorkspaceStatusResponse getStatus(Long userId);

    FunAiWorkspaceProjectDirResponse ensureProjectDir(Long userId, String projectId);

    /**
     * 上传 zip 并解压到指定 project 目录：
     * 宿主机：{hostRoot}/{userId}/projects/{projectId}
     * 容器内：/workspace/projects/{projectId}
     */
    FunAiWorkspaceProjectDirResponse uploadProjectZip(Long userId, String projectId, MultipartFile file, boolean overwrite);
}



package fun.ai.studio.entity.response;

import fun.ai.studio.entity.FunAiApp;
import lombok.Data;

@Data
public class FunAiOpenEditorResponse {
    private Long userId;
    private Long appId;

    /**
     * 应用信息（包含 runtime 字段：workspaceRunState/workspacePreviewUrl 等）
     */
    private FunAiApp app;

    /**
     * workspace 应用目录信息
     */
    private FunAiWorkspaceProjectDirResponse projectDir;

    /**
     * 是否检测到 package.json（maxDepth=2）
     */
    private boolean hasPackageJson;

    /**
     * 当前运行状态：若 hasPackageJson=true 会自动触发 startDev
     */
    private FunAiWorkspaceRunStatusResponse runStatus;

    /**
     * 给前端的提示（引导上传/新建）
     */
    private String message;
}



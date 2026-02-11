package fun.ai.studio.entity.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * FunAI 应用基础信息更新请求
 */
@Data
@Schema(description = "FunAI 应用基础信息更新请求")
public class UpdateFunAiAppBasicInfoRequest {

    @Schema(description = "用户ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "userId 不能为空")
    private Long userId;

    @Schema(description = "应用ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "100")
    @NotNull(message = "appId 不能为空")
    private Long appId;

    @Schema(description = "应用名称（同一用户下唯一）", example = "我的AI应用")
    @Size(max = 255, message = "appName 过长（最大 255 字符）")
    private String appName;

    @Schema(description = "应用描述", example = "这是一个很棒的应用")
    // 更精确的限制由 service 层根据 funai.app.limits.max-app-description-length 控制（避免 DB 截断）
    @Size(max = 5000, message = "appDescription 过长（最大 5000 字符）")
    private String appDescription;

    @Schema(description = "应用类型", example = "website")
    @Size(max = 255, message = "appType 过长（最大 255 字符）")
    private String appType;
}



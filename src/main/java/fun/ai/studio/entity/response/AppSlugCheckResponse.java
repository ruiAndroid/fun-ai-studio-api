package fun.ai.studio.entity.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "appSlug 可用性检查结果")
public class AppSlugCheckResponse {

    @Schema(description = "规范化后的 slug", example = "ai-writer")
    private String normalizedSlug;

    @Schema(description = "是否可用")
    private Boolean available;

    @Schema(description = "不可用或校验失败的原因")
    private String reason;
}

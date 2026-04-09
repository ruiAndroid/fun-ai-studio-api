package fun.ai.studio.entity.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Data
@Schema(description = "创建反馈请求")
public class FeedbackCreateRequest {

    @NotNull(message = "用户ID不能为空")
    @Schema(description = "用户ID", required = true)
    private Long userId;

    @NotNull(message = "应用ID不能为空")
    @Schema(description = "应用ID", required = true)
    private Long appId;

    @NotBlank(message = "反馈标题不能为空")
    @Size(max = 255, message = "标题长度不能超过255字符")
    @Schema(description = "反馈标题", required = true)
    private String title;

    @NotBlank(message = "反馈内容不能为空")
    @Schema(description = "反馈内容", required = true)
    private String content;

    @Schema(description = "反馈图片列表（最多9张）")
    private List<MultipartFile> images;
}

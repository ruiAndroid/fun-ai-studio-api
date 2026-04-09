package fun.ai.studio.entity.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "管理员回复反馈请求")
public class FeedbackReplyRequest {

    @NotNull(message = "反馈ID不能为空")
    @Schema(description = "反馈ID", required = true)
    private Long feedbackId;

    @NotBlank(message = "回复内容不能为空")
    @Schema(description = "回复内容", required = true)
    private String reply;

    @NotNull(message = "处理状态不能为空")
    @Schema(description = "处理状态：1-处理中，2-已处理", required = true)
    private Integer status;
}

package fun.ai.studio.entity.response;

import fun.ai.studio.entity.FunAiApp;
import fun.ai.studio.entity.FunAiUser;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "反馈详情响应")
public class FeedbackResponse {

    @Schema(description = "反馈ID")
    private Long id;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "用户信息")
    private FunAiUser user;

    @Schema(description = "应用ID")
    private Long appId;

    @Schema(description = "应用信息")
    private FunAiApp app;

    @Schema(description = "反馈标题")
    private String title;

    @Schema(description = "反馈内容")
    private String content;

    @Schema(description = "图片URL列表")
    private List<String> images;

    @Schema(description = "处理状态：0-待处理，1-处理中，2-已处理")
    private Integer status;

    @Schema(description = "状态描述")
    private String statusDesc;

    @Schema(description = "管理员回复内容")
    private String reply;

    @Schema(description = "管理员回复时间")
    private LocalDateTime replyTime;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}

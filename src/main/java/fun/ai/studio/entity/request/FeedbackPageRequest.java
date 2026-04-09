package fun.ai.studio.entity.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "反馈分页查询请求")
public class FeedbackPageRequest {

    @Schema(description = "页码（从1开始）", defaultValue = "1")
    private Integer pageNum = 1;

    @Schema(description = "每页数量", defaultValue = "10")
    private Integer pageSize = 10;

    @Schema(description = "处理状态筛选（可选）")
    private Integer status;

    @Schema(description = "关键词搜索（搜索标题和内容）")
    private String keyword;
}

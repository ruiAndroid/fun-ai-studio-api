package fun.ai.studio.controller;

import fun.ai.studio.common.Result;
import fun.ai.studio.entity.request.FeedbackCreateRequest;
import fun.ai.studio.entity.response.FeedbackPageResponse;
import fun.ai.studio.entity.response.FeedbackResponse;
import fun.ai.studio.service.FeedbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/fun-ai/feedback")
@Tag(name = "用户反馈管理", description = "用户反馈的提交和查看接口")
public class FeedbackController {

    private static final Logger logger = LoggerFactory.getLogger(FeedbackController.class);

    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    /**
     * 用户提交反馈
     */
    @PostMapping("/create")
    @Operation(summary = "提交反馈", description = "用户提交反馈，可上传最多9张图片")
    public Result<FeedbackResponse> createFeedback(@Valid FeedbackCreateRequest request) {
        try {
            FeedbackResponse response = feedbackService.createFeedback(request);
            return Result.success("反馈提交成功", response);
        } catch (IllegalArgumentException e) {
            logger.warn("提交反馈失败: {}", e.getMessage());
            return Result.error(e.getMessage());
        } catch (Exception e) {
            logger.error("提交反馈异常", e);
            return Result.error("提交反馈失败: " + e.getMessage());
        }
    }

    /**
     * 用户查看自己的反馈列表
     */
    @GetMapping("/my-list")
    @Operation(summary = "我的反馈列表", description = "获取当前用户的反馈列表（分页）")
    public Result<FeedbackPageResponse> getMyFeedbacks(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "10") Integer pageSize) {
        try {
            FeedbackPageResponse response = feedbackService.getMyFeedbacks(userId, pageNum, pageSize);
            return Result.success(response);
        } catch (Exception e) {
            logger.error("获取反馈列表失败", e);
            return Result.error("获取反馈列表失败: " + e.getMessage());
        }
    }
}

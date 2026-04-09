package fun.ai.studio.controller.admin;

import fun.ai.studio.common.Result;
import fun.ai.studio.entity.FunAiUser;
import fun.ai.studio.entity.request.FeedbackPageRequest;
import fun.ai.studio.entity.request.FeedbackReplyRequest;
import fun.ai.studio.entity.response.FeedbackPageResponse;
import fun.ai.studio.entity.response.FeedbackResponse;
import fun.ai.studio.service.FeedbackService;
import fun.ai.studio.service.FunAiUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

/**
 * 管理员反馈管理接口
 * <p>
 * 提供管理员查看和回复用户反馈的功能。
 * 鉴权方式：JWT + userType=1 校验
 * </p>
 */
@RestController
@RequestMapping("/api/fun-ai/admin/feedback")
@Tag(name = "管理员功能", description = "管理员工作空间文件管理和用户反馈管理（需管理员权限）")
public class AdminFeedbackController {

    private static final Logger log = LoggerFactory.getLogger(AdminFeedbackController.class);

    private final FeedbackService feedbackService;
    private final FunAiUserService funAiUserService;

    public AdminFeedbackController(FeedbackService feedbackService, FunAiUserService funAiUserService) {
        this.feedbackService = feedbackService;
        this.funAiUserService = funAiUserService;
    }

    /**
     * 管理员查看所有反馈列表（分页）
     */
    @GetMapping("/list")
    @Operation(summary = "反馈列表", description = "管理员分页查询所有用户反馈")
    public void list(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "10") Integer pageSize,
            @Parameter(description = "处理状态筛选（可选）") @RequestParam(required = false) Integer status,
            @Parameter(description = "关键词搜索") @RequestParam(required = false) String keyword,
            HttpServletResponse response
    ) throws Exception {
        FunAiUser currentUser = getCurrentUser();
        if (!isAdmin(currentUser)) {
            writeError(response, HttpStatus.FORBIDDEN, 403, "需要管理员权限");
            return;
        }

        FeedbackPageRequest request = new FeedbackPageRequest();
        request.setPageNum(pageNum);
        request.setPageSize(pageSize);
        request.setStatus(status);
        request.setKeyword(keyword);

        try {
            FeedbackPageResponse pageResponse = feedbackService.getAllFeedbacks(request);
            writeSuccess(response, pageResponse);
        } catch (Exception e) {
            log.error("获取反馈列表失败", e);
            writeError(response, HttpStatus.INTERNAL_SERVER_ERROR, 500, "获取反馈列表失败: " + e.getMessage());
        }
    }

    /**
     * 管理员查看反馈详情
     */
    @GetMapping("/detail")
    @Operation(summary = "反馈详情", description = "管理员查看反馈详情")
    public void detail(
            @Parameter(description = "反馈ID", required = true) @RequestParam Long feedbackId,
            HttpServletResponse response
    ) throws Exception {
        FunAiUser currentUser = getCurrentUser();
        if (!isAdmin(currentUser)) {
            writeError(response, HttpStatus.FORBIDDEN, 403, "需要管理员权限");
            return;
        }

        try {
            FeedbackResponse feedbackResponse = feedbackService.getFeedbackDetailForAdmin(feedbackId);
            writeSuccess(response, feedbackResponse);
        } catch (IllegalArgumentException e) {
            writeError(response, HttpStatus.NOT_FOUND, 404, e.getMessage());
        } catch (Exception e) {
            log.error("获取反馈详情失败", e);
            writeError(response, HttpStatus.INTERNAL_SERVER_ERROR, 500, "获取反馈详情失败: " + e.getMessage());
        }
    }

    /**
     * 管理员回复反馈
     */
    @PostMapping(value = "/reply", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "回复反馈", description = "管理员回复用户反馈")
    public void reply(
            @RequestBody FeedbackReplyRequest request,
            HttpServletResponse response
    ) throws Exception {
        FunAiUser currentUser = getCurrentUser();
        if (!isAdmin(currentUser)) {
            writeError(response, HttpStatus.FORBIDDEN, 403, "需要管理员权限");
            return;
        }

        try {
            FeedbackResponse feedbackResponse = feedbackService.replyFeedback(request);
            writeSuccess(response, "回复成功", feedbackResponse);
        } catch (IllegalArgumentException e) {
            writeError(response, HttpStatus.BAD_REQUEST, 400, e.getMessage());
        } catch (Exception e) {
            log.error("回复反馈失败", e);
            writeError(response, HttpStatus.INTERNAL_SERVER_ERROR, 500, "回复反馈失败: " + e.getMessage());
        }
    }

    private boolean isAdmin(FunAiUser user) {
        return user != null && user.getUserType() != null && user.getUserType() == 1;
    }

    private FunAiUser getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        Object principal = auth.getPrincipal();
        String username = null;
        if (principal instanceof UserDetails ud) {
            username = ud.getUsername();
        } else if (principal instanceof String s) {
            username = s;
        }
        if (username == null || username.isBlank()) {
            return null;
        }
        return funAiUserService.findByUsername(username);
    }

    private void writeSuccess(HttpServletResponse response, Object data) throws Exception {
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        Result<?> result = Result.success(data);
        response.getWriter().write(toJson(result));
    }

    private void writeSuccess(HttpServletResponse response, String message, Object data) throws Exception {
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        Result<?> result = Result.success(message, data);
        response.getWriter().write(toJson(result));
    }

    private void writeError(HttpServletResponse response, HttpStatus status, int code, String message) throws Exception {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        Result<?> error = Result.error(code, message);
        response.getWriter().write(toJson(error));
    }

    private String toJson(Result<?> r) {
        int c = r == null || r.getCode() == null ? 500 : r.getCode();
        String m = r == null || r.getMessage() == null ? "" : r.getMessage();
        Object d = r == null ? null : r.getData();
        return "{\"code\":" + c + ",\"message\":\"" + escapeJson(m) + "\",\"data\":" + (d == null ? "null" : toJson(d)) + "}";
    }

    private String toJson(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof String) return "\"" + escapeJson((String) obj) + "\"";
        if (obj instanceof Number) return obj.toString();
        if (obj instanceof Boolean) return obj.toString();
        // 简单处理，其他对象直接 toString
        return "\"" + escapeJson(obj.toString()) + "\"";
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(ch);
            }
        }
        return sb.toString();
    }
}

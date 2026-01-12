package fun.ai.studio.controller.user;

import fun.ai.studio.common.Result;
import fun.ai.studio.entity.FunAiUser;
import fun.ai.studio.service.FunAiUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前登录用户信息（用于前端自动填充 userId 等）。
 */
@RestController
@RequestMapping("/api/fun-ai/user")
@Tag(name = "用户信息")
public class FunAiUserController {
    private final FunAiUserService funAiUserService;

    public FunAiUserController(FunAiUserService funAiUserService) {
        this.funAiUserService = funAiUserService;
    }

    @GetMapping("/me")
    @Operation(summary = "获取当前登录用户信息", description = "从 JWT 中解析用户名，再查询用户表返回 userId/userName 等（password 字段已 @JsonIgnore）。")
    public Result<FunAiUser> me() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) {
                return Result.error(401, "请先登录");
            }
            Object principal = auth.getPrincipal();
            String username = null;
            if (principal instanceof UserDetails ud) {
                username = ud.getUsername();
            } else if (principal instanceof String s) {
                username = s;
            }
            if (username == null || username.isBlank()) {
                return Result.error(401, "请先登录");
            }
            FunAiUser user = funAiUserService.findByUsername(username);
            if (user == null) {
                return Result.error(404, "用户不存在");
            }
            return Result.success(user);
        } catch (Exception e) {
            return Result.error("获取用户信息失败: " + e.getMessage());
        }
    }
}



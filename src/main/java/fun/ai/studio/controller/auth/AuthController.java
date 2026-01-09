package fun.ai.studio.controller.auth;

import fun.ai.studio.common.Const;
import fun.ai.studio.common.Result;
import fun.ai.studio.entity.FunAiUser;
import fun.ai.studio.entity.request.RegisterRequest;
import fun.ai.studio.entity.request.LoginRequest;
import fun.ai.studio.service.FunAiUserService;
import fun.ai.studio.utils.JwtUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@Tag(name = "用户鉴权相关接口")
@RestController
@RequestMapping("/api/fun-ai/auth")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    @Autowired
    private FunAiUserService funAiUserService;
    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @PostMapping("/login")
    @Operation(summary = "风行AI平台用户登录", description = "风行AI用户登录并获取JWT Token")
    public Result<FunAiUser> funAiLogin(@Valid @RequestBody LoginRequest loginRequest, HttpServletResponse response) {
        try {
            // 先根据用户名查找用户，检查用户状态
            FunAiUser user = funAiUserService.findByUsername(loginRequest.getUserName());
            if (user == null) {
                logger.warn("Login failed for fun ai user: {}, reason: 用户不存在", loginRequest.getUserName());
                return Result.error("用户名或密码错误");
            }
            // 注意：不要直接打印 user 对象（可能包含 password hash）
            logger.info("funAiLogin userId={}, userName={}", user.getId(), user.getUserName());
            
            // 直接验证密码，而不是使用AuthenticationManager
            boolean passwordMatch = passwordEncoder.matches(loginRequest.getPassword(), user.getPassword());
            if (!passwordMatch) {
                logger.warn("Login failed for fun ai user: {}, reason: 密码错误", loginRequest.getUserName());
                return Result.error("用户名或密码错误");
            }

            // 生成JWT Token
            String token = jwtUtil.generateToken(loginRequest.getUserName());
            logger.info("Generated JWT Token for fun ai user {}: length={}", loginRequest.getUserName(), token.length());

            // 设置Authorization响应头
            response.setHeader("Authorization", "Bearer " + token);
            response.setHeader("Access-Control-Expose-Headers", "Authorization");

            // 更新上次登录时间
            user.setLastLoginTime(LocalDateTime.now());
            funAiUserService.updateById(user);

            logger.info("Login successful for fun ai user: {}", loginRequest.getUserName());
            return Result.success(user);
        } catch (Exception e) {
            logger.error("Login failed for fun ai user: {}, reason: {}", loginRequest.getUserName(), e.getMessage());
            return Result.error("登录失败，请稍后重试");
        }
    }
    @PostMapping("/register")
    @Operation(summary = "风行ai平台用户注册", description = "风行ai平台注册新用户")
    public Result<FunAiUser> funAiRegister(@Valid @RequestBody RegisterRequest registerRequest) {
        try {
            // 检查用户是否已存在
            if (funAiUserService.findByUsername(registerRequest.getUserName()) != null) {
                return Result.error("用户名已存在");
            }

            // 创建用户对象
            FunAiUser user = new FunAiUser();
            user.setUserName(registerRequest.getUserName());
            user.setPassword(registerRequest.getPassword());
            user.setPhone(registerRequest.getPhone());
            user.setEmail(registerRequest.getEmail());
            user.setAvatar(Const.USER_DEFAULT_AVATAR);
            user.setAppCount(0);


            // 注册用户
            FunAiUser registeredUser = funAiUserService.register(user);
            return Result.success("注册成功", registeredUser);
        } catch (Exception e) {
            return Result.error("注册失败: " + e.getMessage());
        }
    }


}
package fun.ai.studio.service.impl;

import fun.ai.studio.alert.MailAlertService;
import fun.ai.studio.entity.FunAiUser;
import fun.ai.studio.entity.VerificationCode;
import fun.ai.studio.mapper.VerificationCodeMapper;
import fun.ai.studio.service.FunAiUserService;
import fun.ai.studio.service.VerificationCodeService;
import fun.ai.studio.utils.EmailUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Random;

@Service
public class VerificationCodeServiceImpl implements VerificationCodeService {

    private static final Logger logger = LoggerFactory.getLogger(VerificationCodeServiceImpl.class);

    private static final int CODE_LENGTH = 6;
    private static final int VALID_MINUTES = 10;
    private static final int RATE_LIMIT_MINUTES = 5;
    private static final int MAX_ERROR_COUNT = 3;

    @Autowired
    private VerificationCodeMapper verificationCodeMapper;
    @Autowired
    private FunAiUserService funAiUserService;
    @Autowired
    private MailAlertService mailAlertService;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Override
    public String sendCombinedCode(String username, String email) {
        // 1. 根据用户名查找用户
        FunAiUser user = funAiUserService.findByUsername(username);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }

        // 2. 检查邮箱是否已被其他用户绑定
        FunAiUser existing = funAiUserService.findByEmail(email);
        if (existing != null && !existing.getId().equals(user.getId())) {
            throw new IllegalArgumentException("该邮箱已被其他用户绑定");
        }

        // 3. 检查发送频率
        checkRateLimit(email, VerificationCode.TYPE_PASSWORD_RESET);

        // 4. 生成验证码
        String code = generateCode();
        LocalDateTime expiredTime = LocalDateTime.now().plusMinutes(VALID_MINUTES);

        // 5. 保存验证码（存用户提交的email，用于后续校验）
        VerificationCode verificationCode = new VerificationCode();
        verificationCode.setUserId(user.getId());
        verificationCode.setEmail(email);
        verificationCode.setCode(code);
        verificationCode.setType(VerificationCode.TYPE_PASSWORD_RESET);
        verificationCode.setExpiredTime(expiredTime);
        verificationCode.setUsed(false);
        verificationCode.setErrorCount(0);
        verificationCodeMapper.insert(verificationCode);

        // 6. 发送邮件
        String subject = "密码重置验证码";
        String body = buildEmailBody(user.getUserName(), code, VALID_MINUTES);
        mailAlertService.sendTo(email, subject, body);

        logger.info("sendCombinedCode: userId={}, emailMasked={}", user.getId(), EmailUtils.maskEmail(email));

        return EmailUtils.maskEmail(email);
    }

    @Override
    public void combinedResetPassword(String username, String email, String code, String newPassword) {
        // 1. 根据用户名查找用户
        FunAiUser user = funAiUserService.findByUsername(username);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }

        // 2. 如果用户当前邮箱与提交邮箱不一致，先绑定邮箱
        if (!email.equalsIgnoreCase(user.getEmail())) {
            // 检查邮箱是否已被他人使用
            FunAiUser existing = funAiUserService.findByEmail(email);
            if (existing != null && !existing.getId().equals(user.getId())) {
                throw new IllegalArgumentException("该邮箱已被其他用户绑定");
            }
            // 绑定邮箱
            user.setEmail(email);
            funAiUserService.updateById(user);
            logger.info("combinedResetPassword: bound email for userId={}, emailMasked={}", user.getId(), EmailUtils.maskEmail(email));
        }

        // 3. 用【用户提交的email】查询验证码
        VerificationCode verificationCode = verificationCodeMapper.findValidCode(
                user.getId(), email, VerificationCode.TYPE_PASSWORD_RESET);
        if (verificationCode == null) {
            throw new IllegalArgumentException("验证码不存在或已过期");
        }

        // 4. 校验验证码
        verifyCode(verificationCode, code);

        // 5. 标记验证码已使用
        verificationCodeMapper.markAsUsed(verificationCode.getId());

        // 6. 重置密码
        user.setPassword(passwordEncoder.encode(newPassword));
        funAiUserService.updateById(user);

        logger.info("combinedResetPassword: success for userId={}", user.getId());
    }

    @Override
    public String sendRegisterCode(String username, String email) {
        // 1. 检查用户名是否已存在
        if (funAiUserService.findByUsername(username) != null) {
            throw new IllegalArgumentException("用户名已存在");
        }

        // 2. 检查邮箱是否已被其他用户绑定
        FunAiUser existing = funAiUserService.findByEmail(email);
        if (existing != null) {
            throw new IllegalArgumentException("该邮箱已被其他用户绑定");
        }

        // 3. 检查发送频率
        checkRateLimit(email, VerificationCode.TYPE_REGISTER);

        // 4. 生成验证码
        String code = generateCode();
        LocalDateTime expiredTime = LocalDateTime.now().plusMinutes(VALID_MINUTES);

        // 5. 保存验证码（注册时用户不存在，userId为null）
        VerificationCode verificationCode = new VerificationCode();
        verificationCode.setUserId(null);
        verificationCode.setEmail(email);
        verificationCode.setCode(code);
        verificationCode.setType(VerificationCode.TYPE_REGISTER);
        verificationCode.setExpiredTime(expiredTime);
        verificationCode.setUsed(false);
        verificationCode.setErrorCount(0);
        verificationCodeMapper.insert(verificationCode);

        // 6. 发送邮件
        String subject = "注册验证码";
        String body = buildRegisterEmailBody(username, code, VALID_MINUTES);
        mailAlertService.sendTo(email, subject, body);

        logger.info("sendRegisterCode: username={}, emailMasked={}", username, EmailUtils.maskEmail(email));

        return EmailUtils.maskEmail(email);
    }

    @Override
    public void verifyRegisterCode(String username, String email, String code) {
        // 1. 用邮箱+type查找有效验证码
        VerificationCode verificationCode = verificationCodeMapper.findValidCodeByEmail(email, VerificationCode.TYPE_REGISTER);
        if (verificationCode == null) {
            throw new IllegalArgumentException("验证码不存在或已过期");
        }

        // 2. 校验验证码
        verifyCode(verificationCode, code);

        // 3. 标记验证码已使用
        verificationCodeMapper.markAsUsed(verificationCode.getId());

        logger.info("verifyRegisterCode: success for username={}", username);
    }

    @Override
    public String sendLoginCode(String email) {
        // 1. 检查邮箱是否已绑定用户
        FunAiUser user = funAiUserService.findByEmail(email);
        if (user == null) {
            throw new IllegalArgumentException("该邮箱未绑定用户");
        }

        // 2. 检查发送频率
        checkRateLimit(email, VerificationCode.TYPE_LOGIN);

        // 3. 生成验证码
        String code = generateCode();
        LocalDateTime expiredTime = LocalDateTime.now().plusMinutes(VALID_MINUTES);

        // 4. 保存验证码
        VerificationCode verificationCode = new VerificationCode();
        verificationCode.setUserId(user.getId());
        verificationCode.setEmail(email);
        verificationCode.setCode(code);
        verificationCode.setType(VerificationCode.TYPE_LOGIN);
        verificationCode.setExpiredTime(expiredTime);
        verificationCode.setUsed(false);
        verificationCode.setErrorCount(0);
        verificationCodeMapper.insert(verificationCode);

        // 5. 发送邮件
        String subject = "登录验证码";
        String body = buildLoginEmailBody(user.getUserName(), code, VALID_MINUTES);
        mailAlertService.sendTo(email, subject, body);

        logger.info("sendLoginCode: userId={}, emailMasked={}", user.getId(), EmailUtils.maskEmail(email));

        return EmailUtils.maskEmail(email);
    }

    @Override
    public FunAiUser verifyLoginCode(String email, String code) {
        VerificationCode verificationCode = verificationCodeMapper.findValidCodeByEmail(email, VerificationCode.TYPE_LOGIN);
        if (verificationCode == null) {
            throw new IllegalArgumentException("验证码不存在或已过期");
        }

        verifyCode(verificationCode, code);
        verificationCodeMapper.markAsUsed(verificationCode.getId());

        // 使用验证码中保存的userId直接查询，避免重复按邮箱查询
        FunAiUser user = funAiUserService.getById(verificationCode.getUserId());
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }

        logger.info("verifyLoginCode: success for userId={}", user.getId());
        return user;
    }

    /**
     * 构建注册验证码邮件正文
     */
    private String buildRegisterEmailBody(String userName, String code, int validMinutes) {
        return "【Fun AI Studio】注册验证码\n\n" +
               "尊敬的用户 " + userName + " 您好！\n\n" +
               "您正在申请注册，请在" + validMinutes + "分钟内完成验证。\n\n" +
               "您的验证码是：" + code + "\n\n" +
               "如果这不是您的操作，请忽略此邮件。\n\n" +
               "--\n" +
               "Fun AI Studio";
    }

    /**
     * 构建登录验证码邮件正文
     */
    private String buildLoginEmailBody(String userName, String code, int validMinutes) {
        return "【Fun AI Studio】登录验证码\n\n" +
               "尊敬的用户 " + userName + " 您好！\n\n" +
               "您正在申请登录，请在" + validMinutes + "分钟内完成验证。\n\n" +
               "您的验证码是：" + code + "\n\n" +
               "如果这不是您的操作，请忽略此邮件。\n\n" +
               "--\n" +
               "Fun AI Studio";
    }

    /**
     * 检查发送频率限制
     */
    private void checkRateLimit(String email, Integer type) {
        VerificationCode lastCode = verificationCodeMapper.findLatestByEmail(email, type);
        if (lastCode != null) {
            LocalDateTime rateLimitExpired = lastCode.getCreateTime().plusMinutes(RATE_LIMIT_MINUTES);
            if (rateLimitExpired.isAfter(LocalDateTime.now())) {
                long minutes = java.time.Duration.between(LocalDateTime.now(), rateLimitExpired).toMinutes();
                throw new IllegalArgumentException("请勿频繁发送验证码，请" + (minutes + 1) + "分钟后再试");
            }
        }
    }

    /**
     * 查找有效验证码
     */
    private VerificationCode findValidCode(Long userId, String email, Integer type) {
        VerificationCode verificationCode = verificationCodeMapper.findValidCode(userId, email, type);
        if (verificationCode == null) {
            throw new IllegalArgumentException("验证码不存在或已过期");
        }
        return verificationCode;
    }

    /**
     * 校验验证码
     */
    private void verifyCode(VerificationCode verificationCode, String code) {
        // 检查是否已使用
        if (verificationCode.getUsed()) {
            throw new IllegalArgumentException("验证码已使用，请重新获取");
        }

        // 检查是否过期
        if (verificationCode.getExpiredTime().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("验证码已过期，请重新获取");
        }

        // 检查错误次数
        if (verificationCode.getErrorCount() >= MAX_ERROR_COUNT) {
            verificationCodeMapper.markAsUsed(verificationCode.getId());
            throw new IllegalArgumentException("验证码错误次数过多，请重新获取");
        }

        // 校验验证码
        if (!verificationCode.getCode().equals(code)) {
            verificationCodeMapper.incrementErrorCount(verificationCode.getId());
            int remaining = MAX_ERROR_COUNT - verificationCode.getErrorCount() - 1;
            if (remaining > 0) {
                throw new IllegalArgumentException("验证码错误，还剩" + remaining + "次机会");
            } else {
                verificationCodeMapper.markAsUsed(verificationCode.getId());
                throw new IllegalArgumentException("验证码错误次数过多，请重新获取");
            }
        }
    }

    /**
     * 生成6位数字验证码
     */
    private String generateCode() {
        Random random = new Random();
        int code = 100000 + random.nextInt(900000);
        return String.valueOf(code);
    }

    /**
     * 构建邮件正文
     */
    private String buildEmailBody(String userName, String code, int validMinutes) {
        return "【Fun AI Studio】密码重置验证码\n\n" +
               "尊敬的用户 " + userName + " 您好！\n\n" +
               "您正在申请密码重置，请在" + validMinutes + "分钟内完成验证。\n\n" +
               "您的验证码是：" + code + "\n\n" +
               "如果这不是您的操作，请忽略此邮件。\n\n" +
               "--\n" +
               "Fun AI Studio";
    }
}

package fun.ai.studio.service;

import fun.ai.studio.entity.FunAiUser;

/**
 * 验证码服务接口
 */
public interface VerificationCodeService {

    /**
     * 发送合并重置验证码（支持先绑定邮箱）
     * @param username 用户名
     * @param email 邮箱地址
     * @return 脱敏后的邮箱地址
     */
    String sendCombinedCode(String username, String email);

    /**
     * 合并重置密码（支持先绑定邮箱再重置）
     * @param username 用户名
     * @param email 邮箱地址
     * @param code 验证码
     * @param newPassword 新密码
     */
    void combinedResetPassword(String username, String email, String code, String newPassword);

    /**
     * 发送注册验证码
     * @param username 用户名
     * @param email 邮箱地址
     * @return 脱敏后的邮箱地址
     */
    String sendRegisterCode(String username, String email);

    /**
     * 验证注册验证码
     * @param username 用户名
     * @param email 邮箱地址
     * @param code 验证码
     */
    void verifyRegisterCode(String username, String email, String code);

    /**
     * 发送登录验证码
     * @param email 邮箱地址
     * @return 脱敏后的邮箱地址
     */
    String sendLoginCode(String email);

    /**
     * 验证登录验证码并返回用户
     * @param email 邮箱地址
     * @param code 验证码
     * @return 用户对象
     */
    FunAiUser verifyLoginCode(String email, String code);
}

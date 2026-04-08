package fun.ai.studio.service;


import com.baomidou.mybatisplus.extension.service.IService;
import fun.ai.studio.entity.FunAiUser;

/**
 * 用户服务接口
 */
public interface FunAiUserService extends IService<FunAiUser> {
    /**
     * 根据用户名查找用户
     * @param username 用户名
     * @return 用户实体
     */
    FunAiUser findByUsername(String username);

    /**
     * 注册用户
     * @param user 用户实体
     * @return 注册后的用户实体
     */
    FunAiUser register(FunAiUser user);

    /**
     * 注册用户（消耗邀请码，一次性）
     *
     * @param user 用户实体
     * @param inviteCode 邀请码
     * @return 注册后的用户实体
     * @throws IllegalArgumentException 邀请码无效/已使用等
     */
    FunAiUser registerWithInviteCode(FunAiUser user, String inviteCode) throws IllegalArgumentException;
    
    /**
     * 根据用户名获取用户ID
     * @param username 用户名
     * @return 用户ID
     */
    Long getUserIdByUsername(String username);

    /**
     * 根据用户名或邮箱查找用户
     * @param identifier 用户名或邮箱
     * @return 用户实体
     */
    FunAiUser findByUsernameOrEmail(String identifier);

    /**
     * 根据邮箱查找用户
     * @param email 邮箱
     * @return 用户实体
     */
    FunAiUser findByEmail(String email);


}
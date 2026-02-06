package fun.ai.studio.service;

import com.baomidou.mybatisplus.extension.service.IService;
import fun.ai.studio.entity.FunAiInviteCode;

/**
 * 邀请码服务（一次性消耗）
 */
public interface FunAiInviteCodeService extends IService<FunAiInviteCode> {

    /**
     * 判断邀请码是否可用（存在且未使用）
     */
    boolean isAvailable(String code);

    /**
     * 消耗邀请码（原子：仅当 status=0 时更新为已使用）。
     *
     * @throws IllegalArgumentException 邀请码不存在/已使用/参数非法
     */
    void consume(String code, Long usedByUserId) throws IllegalArgumentException;
}


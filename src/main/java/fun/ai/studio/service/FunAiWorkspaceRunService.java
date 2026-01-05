package fun.ai.studio.service;

import com.baomidou.mybatisplus.extension.service.IService;
import fun.ai.studio.entity.FunAiWorkspaceRun;

public interface FunAiWorkspaceRunService extends IService<FunAiWorkspaceRun> {

    FunAiWorkspaceRun getByUserId(Long userId);

    /**
     * 单机版：以 userId 为唯一键做 upsert。
     */
    void upsertByUserId(FunAiWorkspaceRun record);

    /**
     * 更新 lastActiveAt（用于心跳/活跃记录）
     */
    void touch(Long userId, Long activeAtMs);
}



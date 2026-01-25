package fun.ai.studio.service;


import com.baomidou.mybatisplus.extension.service.IService;
import fun.ai.studio.entity.FunAiApp;
import java.util.List;

/**
 * AI应用服务接口
 */
public interface FunAiAppService extends IService<FunAiApp> {
    /**
     * 根据用户ID查询应用列表
     * @param userId 用户ID
     * @return 应用列表
     */
    List<FunAiApp> getAppsByUserId(Long userId);


    /**
     * 根据应用ID和用户ID查询应用
     * @param appId 应用ID
     * @param userId 用户ID
     * @return 应用信息
     */
    FunAiApp getAppByIdAndUserId(Long appId, Long userId);

    /**
     * 创建应用
     * @param app 应用信息
     * @return 创建后的应用信息
     */
    FunAiApp createApp(FunAiApp app);

    /**
     * 更新应用
     * @param app 应用信息
     * @param userId 用户ID
     * @return 更新后的应用信息
     */
    FunAiApp updateApp(FunAiApp app, Long userId);

    /**
     * 删除应用
     * @param appId 应用ID
     * @param userId 用户ID
     * @return 是否删除成功
     */
    boolean deleteApp(Long appId, Long userId);

    /**
     * 创建应用（包含完整的业务逻辑：校验、创建文件夹、更新用户计数等）
     * @param userId 用户ID
     * @return 创建后的应用信息
     * @throws IllegalArgumentException 当用户不存在或应用数量已达上限时抛出
     */
    FunAiApp createAppWithValidation(Long userId) throws IllegalArgumentException;

    /**
     * 修改应用基础信息（appName/appDescription/appType）
     * - appName：同一用户下不可重名（排除当前 appId）
     * @param userId 用户ID
     * @param appId 应用ID
     * @param appName 应用名称
     * @param appDescription 应用描述
     * @param appType 应用类型
     * @return 更新后的应用信息
     * @throws IllegalArgumentException 当应用不存在、无权限或 appName 重名/非法时抛出
     */
    FunAiApp updateBasicInfo(Long userId, Long appId, String appName, String appDescription, String appType)
            throws IllegalArgumentException;

    /**
     * 统计用户“运行中占用槽位”的项目数量（用于限制同时在线/部署中的项目数）。
     * 说明：目前按 app_status in (DEPLOYING, READY) 统计。
     */
    long countRunningSlotsByUserId(Long userId);

    /**
     * 将应用标记为部署中（DEPLOYING），并清空 last_deploy_error。
     * 仅允许操作自己名下的应用。
     */
    boolean markDeploying(Long userId, Long appId);

    /**
     * 下线应用：将应用从“运行中槽位”释放（通常 READY/DEPLOYING -> UPLOADED），并清空 last_deploy_error。
     * 仅允许操作自己名下的应用。
     */
    boolean markStopped(Long userId, Long appId);
}

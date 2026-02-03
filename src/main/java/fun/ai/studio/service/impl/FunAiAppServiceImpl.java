package fun.ai.studio.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import fun.ai.studio.entity.FunAiApp;
import fun.ai.studio.entity.FunAiUser;
import fun.ai.studio.enums.FunAiAppStatus;
import fun.ai.studio.gitea.GiteaRepoAutomationService;
import fun.ai.studio.mapper.FunAiAppMapper;
import fun.ai.studio.service.FunAiAppService;
import fun.ai.studio.service.FunAiConversationService;
import fun.ai.studio.service.FunAiUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;

/**
 * AI应用服务实现类
 */
@Service
public class FunAiAppServiceImpl extends ServiceImpl<FunAiAppMapper, FunAiApp> implements FunAiAppService {

    private static final Logger logger = LoggerFactory.getLogger(FunAiAppServiceImpl.class);

    @Autowired
    private FunAiUserService funAiUserService;

    @Autowired(required = false)
    private GiteaRepoAutomationService giteaRepoAutomationService;

    @Autowired(required = false)
    private FunAiConversationService conversationService;

    /**
     * 单用户最多可创建应用数量（默认 20）。
     * 使用 @Value 避免部署时漏同步“新增 Java 文件”导致编译失败。
     */
    @Value("${funai.app.limits.max-apps-per-user:20}")
    private int maxAppsPerUser;

    @Override
    public List<FunAiApp> getAppsByUserId(Long userId) {
        QueryWrapper<FunAiApp> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", userId)
                .orderByDesc("create_time");
        return baseMapper.selectList(queryWrapper);
    }


    @Override
    public FunAiApp getAppByIdAndUserId(Long appId, Long userId) {
        QueryWrapper<FunAiApp> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("id", appId)
                .eq("user_id", userId);
        return baseMapper.selectOne(queryWrapper);
    }

    @Override
    public FunAiApp createApp(FunAiApp app) {
        // 生成应用密钥
        if (!StringUtils.hasText(app.getAppKey())) {
            app.setAppKey(generateAppKey());
        }
        if (!StringUtils.hasText(app.getAppSecret())) {
            app.setAppSecret(generateAppSecret());
        }
        // 设置默认状态为：空壳/草稿（仅创建记录）
        if (app.getAppStatus() == null) {
            app.setAppStatus(FunAiAppStatus.CREATED.code());
        }
        // 保存应用
        save(app);
        return app;
    }

    @Override
    public FunAiApp updateApp(FunAiApp app, Long userId) {
        // 验证应用是否属于该用户
        FunAiApp existingApp = getAppByIdAndUserId(app.getId(), userId);
        if (existingApp == null) {
            throw new RuntimeException("应用不存在或无权限操作");
        }
        // 更新应用信息
        app.setUserId(userId); // 确保用户ID不变
        updateById(app);
        return app;
    }

    @Override
    public FunAiApp updateBasicInfo(Long userId, Long appId, String appName, String appDescription, String appType)
            throws IllegalArgumentException {
        if (userId == null || appId == null) {
            throw new IllegalArgumentException("userId/appId 不能为空");
        }

        // 1) 校验应用归属
        FunAiApp existingApp = getAppByIdAndUserId(appId, userId);
        if (existingApp == null) {
            throw new IllegalArgumentException("应用不存在或无权限操作");
        }

        // 部署中不允许修改基础信息（避免目录/部署过程冲突）
        if (existingApp.getAppStatus() != null && existingApp.getAppStatus() == FunAiAppStatus.DEPLOYING.code()) {
            throw new IllegalArgumentException("应用部署中，暂不允许修改");
        }

        // 2) appName 同名校验（仅当传入且非空时才校验/更新），并同步重命名本地目录
        if (StringUtils.hasText(appName)) {
            String trimmedName = appName.trim();
            QueryWrapper<FunAiApp> nameCheck = new QueryWrapper<>();
            nameCheck.eq("user_id", userId)
                    .eq("app_name", trimmedName)
                    .ne("id", appId)
                    .last("limit 1");
            FunAiApp duplicate = baseMapper.selectOne(nameCheck);
            if (duplicate != null) {
                throw new IllegalArgumentException("应用名称已存在，请换一个名称");
            }
            // 目录结构已改为按 appId 命名，appName 变化不再影响磁盘路径
            existingApp.setAppName(trimmedName);
        }

        // 3) 其他字段更新（允许置空：如果你不想允许置空，可以改成 hasText 才更新）
        if (appDescription != null) {
            existingApp.setAppDescription(appDescription);
        }
        if (appType != null) {
            existingApp.setAppType(appType);
        }

        updateById(existingApp);
        return existingApp;
    }

    @Override
    public long countRunningSlotsByUserId(Long userId) {
        if (userId == null) return 0;
        // 运行中槽位：DEPLOYING + READY（避免“正在部署的第4个”成功后超限）
        return this.lambdaQuery()
                .eq(FunAiApp::getUserId, userId)
                .in(FunAiApp::getAppStatus, FunAiAppStatus.DEPLOYING.code(), FunAiAppStatus.READY.code())
                .count();
    }

    @Override
    public boolean markDeploying(Long userId, Long appId) {
        if (userId == null || appId == null) return false;
        return this.lambdaUpdate()
                .eq(FunAiApp::getId, appId)
                .eq(FunAiApp::getUserId, userId)
                .set(FunAiApp::getAppStatus, FunAiAppStatus.DEPLOYING.code())
                .set(FunAiApp::getLastDeployError, null)
                .update();
    }

    @Override
    public boolean markStopped(Long userId, Long appId) {
        if (userId == null || appId == null) return false;
        // 说明：当前状态机里没有单独的 STOPPED/OFFLINE，先回退到 UPLOADED（可再次部署；也能释放“运行中槽位”）。
        return this.lambdaUpdate()
                .eq(FunAiApp::getId, appId)
                .eq(FunAiApp::getUserId, userId)
                .set(FunAiApp::getAppStatus, FunAiAppStatus.UPLOADED.code())
                .set(FunAiApp::getLastDeployError, null)
                .update();
    }

    @Override
    public boolean markReady(Long userId, Long appId) {
        if (userId == null || appId == null) return false;
        // 只对 DEPLOYING 的应用做落库更新，避免覆盖其他人工/异常兜底状态
        return this.lambdaUpdate()
                .eq(FunAiApp::getId, appId)
                .eq(FunAiApp::getUserId, userId)
                .eq(FunAiApp::getAppStatus, FunAiAppStatus.DEPLOYING.code())
                .set(FunAiApp::getAppStatus, FunAiAppStatus.READY.code())
                .set(FunAiApp::getLastDeployError, null)
                .update();
    }

    @Override
    public boolean markFailed(Long userId, Long appId, String errorMessage) {
        if (userId == null || appId == null) return false;
        String msg = (errorMessage == null || errorMessage.trim().isEmpty())
                ? "deploy failed"
                : errorMessage.trim();
        // 只对 DEPLOYING 的应用做落库更新，避免覆盖其他人工/异常兜底状态
        return this.lambdaUpdate()
                .eq(FunAiApp::getId, appId)
                .eq(FunAiApp::getUserId, userId)
                .eq(FunAiApp::getAppStatus, FunAiAppStatus.DEPLOYING.code())
                .set(FunAiApp::getAppStatus, FunAiAppStatus.FAILED.code())
                .set(FunAiApp::getLastDeployError, msg)
                .update();
    }

    // 旧链路（zip 上传 + 解压 + npm build + /fun-ai-app 静态站点部署）已移除：
    // - uploadAppFile / getLatestUploadedZipPath / deployApp
    // deploy 相关工具方法也一并移除（避免误用与依赖旧目录结构）

    @Override
    public boolean deleteApp(Long appId, Long userId) {
        // 验证应用是否属于该用户
        FunAiApp existingApp = getAppByIdAndUserId(appId, userId);
        if (existingApp == null) {
            logger.warn("删除应用失败: 应用不存在或无权限操作, userId={}, appId={}", userId, appId);
            throw new IllegalArgumentException("应用不存在或无权限操作");
        }
        
        // DISABLED 不允许删除（管理员禁用的应用需要先解禁）
        // 注意：DEPLOYING 状态现在允许删除，因为：
        // 1. Controller 层会先调用 deployClient.stopApp() 下线容器
        // 2. 再调用 deployClient.purgeApp() 清理 Deploy 侧数据
        // 3. 如果真的在部署中，stop 会中断容器；如果是"部署失败但状态没更新"的情况，也能正常删除
        Integer st = existingApp.getAppStatus();
        if (st != null && st == FunAiAppStatus.DISABLED.code()) {
            logger.warn("尝试删除禁用状态的应用: appId={}, userId={}, appStatus={}",
                    appId, userId, st);
            throw new IllegalArgumentException("应用已禁用，暂不允许删除（请联系管理员）");
        }
        
        // 删除数据库记录
        boolean deleted = removeById(appId);
        
        // 如果删除成功，更新用户的 app_count 并清理会话
        if (deleted) {
            try {
                updateUserAppCountAfterDelete(userId);
            } catch (Exception e) {
                logger.error("更新用户应用数量失败: userId={}, error={}", userId, e.getMessage(), e);
                // 更新失败不影响删除结果，记录日志即可
            }
            
            // 清理该应用的所有会话
            if (conversationService != null) {
                try {
                    conversationService.deleteConversationsByApp(userId, appId);
                    logger.info("已清理应用会话: userId={}, appId={}", userId, appId);
                } catch (Exception e) {
                    logger.error("清理应用会话失败: userId={}, appId={}, error={}", 
                        userId, appId, e.getMessage(), e);
                    // 清理失败不影响删除结果，记录日志即可
                }
            }
        }
        
        return deleted;
    }

    /**
     * 生成应用密钥
     * @return 应用密钥
     */
    private String generateAppKey() {
        return "AIK_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    /**
     * 生成应用密钥
     * @return 应用密钥
     */
    private String generateAppSecret() {
        return "AIS_" + UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    public FunAiApp createAppWithValidation(Long userId) throws IllegalArgumentException {
        // 0. 校验用户应用数量是否已达上限
        FunAiUser user = funAiUserService.getById(userId);
        if (user == null) {
            logger.error("用户不存在: userId={}", userId);
            throw new IllegalArgumentException("用户不存在");
        }
        
        // 检查 app_count，如果为 null 则视为 0
        Integer appCount = user.getAppCount();
        if (appCount == null) {
            appCount = 0;
        }

        int maxApps = (maxAppsPerUser > 0 ? maxAppsPerUser : 20);
        if (appCount >= maxApps) {
            logger.warn("用户应用数量已达上限: userId={}, appCount={}, maxApps={}", userId, appCount, maxApps);
            throw new IllegalArgumentException("已达项目数量上限（max=" + maxApps + "）");
        }
        
        // 1. 查询用户实际的应用列表（用于生成应用名称）
        List<FunAiApp> existingApps = getAppsByUserId(userId);
        
        // 2. 生成应用名称：找到下一个可用的"未命名应用X"
        String appName = generateNextAppName(existingApps);
        
        // 3. 创建FunAiApp对象，设置默认值（仅创建 DB 记录；代码目录由 workspace 链路在 open-editor/ensure-dir 时创建）
        FunAiApp app = new FunAiApp();
        app.setUserId(userId);
        app.setAppName(appName);
        app.setAppDescription("这是一个默认应用");
        app.setAppType("default");
        app.setAppStatus(FunAiAppStatus.CREATED.code()); // 默认空壳

        // 4. 保存到数据库（会自动生成id）
        FunAiApp createdApp = createApp(app);

        // 4.1 best-effort：创建 Git 仓库并授权 runner 只读（不阻塞 app 创建）
        try {
            if (giteaRepoAutomationService != null) {
                giteaRepoAutomationService.ensureRepoAndGrantRunner(userId, createdApp.getId());
            }
        } catch (Exception e) {
            logger.warn("gitea automation failed: userId={}, appId={}, err={}", userId, createdApp.getId(), e.getMessage());
        }
        
        // 5. 更新用户的 app_count
        try {
            Integer newAppCount = appCount + 1;
            user.setAppCount(newAppCount);
            funAiUserService.updateById(user);
            logger.info("更新用户应用数量: userId={}, newAppCount={}", userId, newAppCount);
        } catch (Exception updateException) {
            // 如果更新失败，记录日志但不影响创建结果
            logger.error("更新用户应用数量失败: userId={}, error={}", 
                userId, updateException.getMessage(), updateException);
        }
        
        return createdApp;
    }
    /**
     * 生成下一个可用的应用名称
     * @param existingApps 用户现有的应用列表
     * @return 新的应用名称，格式为"未命名应用X"
     */
    private String generateNextAppName(List<FunAiApp> existingApps) {
        if (existingApps == null || existingApps.isEmpty()) {
            return "未命名应用1";
        }
        
        // 收集所有已使用的数字
        Set<Integer> usedNumbers = new HashSet<>();
        for (FunAiApp app : existingApps) {
            String name = app.getAppName();
            if (name != null && name.startsWith("未命名应用")) {
                try {
                    String numberStr = name.substring("未命名应用".length());
                    int number = Integer.parseInt(numberStr);
                    usedNumbers.add(number);
                } catch (NumberFormatException e) {
                    // 如果不是纯数字，忽略
                }
            }
        }
        
        // 找到第一个未使用的数字
        int nextNumber = 1;
        while (usedNumbers.contains(nextNumber)) {
            nextNumber++;
        }
        
        return "未命名应用" + nextNumber;
    }


    /**
     * 删除应用后更新用户的 app_count
     * @param userId 用户ID
     */
    private void updateUserAppCountAfterDelete(Long userId) {
        FunAiUser user = funAiUserService.getById(userId);
        if (user == null) {
            logger.warn("用户不存在，跳过更新应用数量: userId={}", userId);
            return;
        }
        
        Integer appCount = user.getAppCount();
        if (appCount == null || appCount <= 0) {
            appCount = 0;
        } else {
            appCount = appCount - 1;
        }
        
        user.setAppCount(appCount);
        funAiUserService.updateById(user);
        logger.info("更新用户应用数量: userId={}, newAppCount={}", userId, appCount);
    }
}

package fun.ai.studio.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import fun.ai.studio.entity.FunAiUser;
import fun.ai.studio.mapper.FunAiUserMapper;
import fun.ai.studio.service.FunAiInviteCodeService;
import fun.ai.studio.service.FunAiUserService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Service
public class FunAiUserServiceImpl extends ServiceImpl<FunAiUserMapper, FunAiUser> implements FunAiUserService {

    private final PasswordEncoder passwordEncoder;
    private final FunAiInviteCodeService inviteCodeService;

    public FunAiUserServiceImpl(PasswordEncoder passwordEncoder,
                                FunAiInviteCodeService inviteCodeService) {
        this.passwordEncoder = passwordEncoder;
        this.inviteCodeService = inviteCodeService;
    }

    @Override
    public FunAiUser findByUsername(String username) {
        QueryWrapper<FunAiUser> queryWrapper = new QueryWrapper<>();
        // 加上 limit 1：避免极端情况下（脏数据/重复用户名）selectOne 扫描过多并抛异常
        queryWrapper.eq("user_name", username).last("limit 1"); // 注意这里使用的是user_name而不是username
        return getBaseMapper().selectOne(queryWrapper);
    }


    @Override
    public FunAiUser register(FunAiUser user) {
        // 检查用户名是否已存在
        if (findByUsername(user.getUserName()) != null) {
            throw new RuntimeException("用户名已存在");
        }
        // 密码加密
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());

        // 使用MyBatis Plus的save方法确保ID自动生成
        boolean saved = save(user);
        if (!saved) {
            throw new RuntimeException("用户注册失败");
        }
        return user;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FunAiUser registerWithInviteCode(FunAiUser user, String inviteCode) throws IllegalArgumentException {
        if (user == null) {
            throw new IllegalArgumentException("用户信息不能为空");
        }
        if (!StringUtils.hasText(inviteCode)) {
            throw new IllegalArgumentException("邀请码不能为空");
        }
        if (inviteCodeService == null) {
            throw new IllegalStateException("邀请码服务不可用");
        }

        // 先创建用户（拿到 userId），再原子消耗邀请码；任何一步失败都会回滚
        FunAiUser created = register(user);
        Long userId = created == null ? null : created.getId();
        if (userId == null) {
            throw new IllegalStateException("用户注册失败（未生成 userId）");
        }

        try {
            inviteCodeService.consume(inviteCode, userId);
        } catch (IllegalArgumentException e) {
            // 邀请码无效/已使用：回滚
            throw e;
        } catch (Exception e) {
            // 例如表不存在/SQL 错误
            throw new IllegalArgumentException("邀请码系统异常，请联系管理员：" + e.getMessage());
        }
        return created;
    }

    @Override
    public Long getUserIdByUsername(String username) {
        FunAiUser user = findByUsername(username);
        return user != null ? user.getId() : null;
    }

}
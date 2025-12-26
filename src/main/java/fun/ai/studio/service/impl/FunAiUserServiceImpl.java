package fun.ai.studio.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import fun.ai.studio.entity.FunAiUser;
import fun.ai.studio.mapper.FunAiUserMapper;
import fun.ai.studio.service.FunAiUserService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class FunAiUserServiceImpl extends ServiceImpl<FunAiUserMapper, FunAiUser> implements FunAiUserService {

    private final PasswordEncoder passwordEncoder;

    public FunAiUserServiceImpl(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
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
    public Long getUserIdByUsername(String username) {
        FunAiUser user = findByUsername(username);
        return user != null ? user.getId() : null;
    }

}
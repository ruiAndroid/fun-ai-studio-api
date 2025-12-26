package fun.ai.studio.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import fun.ai.studio.entity.FunAiUser;
import fun.ai.studio.mapper.FunAiUserMapper;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;


@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private final FunAiUserMapper funAiUserMapper;
    public UserDetailsServiceImpl(FunAiUserMapper funAiUserMapper) {
        this.funAiUserMapper = funAiUserMapper;
    }




    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        QueryWrapper<FunAiUser> queryWrapper = new QueryWrapper<>();
        // limit 1：避免重复用户名导致 selectOne 抛异常，同时减少扫描
        queryWrapper.eq("user_name", username).last("limit 1");
        FunAiUser user = funAiUserMapper.selectOne(queryWrapper);

        if (user == null) {
            throw new UsernameNotFoundException("用户不存在");
        }
        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getUserName())
                .password(user.getPassword())
                .accountExpired(false)
                .accountLocked(false)
                .credentialsExpired(false)
                .disabled(false)
                .build();
    }
}
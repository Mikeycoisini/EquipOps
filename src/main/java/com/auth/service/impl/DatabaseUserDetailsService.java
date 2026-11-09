package com.auth.service.impl;

import com.auth.model.LoginUser;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.equipops.domain.entity.UserEntity;
import com.workorder.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** 根据账号加载认证主体；密码校验由 AuthenticationManager 处理。 */
@Service
@RequiredArgsConstructor
public class DatabaseUserDetailsService implements UserDetailsService {
    /** 复用已有用户 Mapper，账号唯一约束保证最多查到一个用户。 */
    private final UserMapper userMapper;

    /**
     * Spring Security 在认证过程中调用此方法。
     * 这里只负责“找到账号资料”，密码是否匹配由 DaoAuthenticationProvider 判断。
     */
    @Override
    public UserDetails loadUserByUsername(String username) {
        // LambdaQueryWrapper 用实体 getter 指定列，避免手写账号列名字符串。
        UserEntity user = userMapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getAccount, username));
        // 不向客户端区分“账号不存在”和“密码错误”，统一由认证异常处理器响应。
        if (user == null) {
            throw new UsernameNotFoundException("账号或密码有误");
        }
        // 转成框架认识的 UserDetails；密码哈希仅用于本次认证，不作为接口响应。
        return new LoginUser(user.getId(), user.getAccount(), user.getPasswordHash(),
                user.getDisplayName(), user.getEnabled());
    }
}

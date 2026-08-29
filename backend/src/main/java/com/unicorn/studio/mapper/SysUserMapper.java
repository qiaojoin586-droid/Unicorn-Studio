package com.unicorn.studio.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unicorn.studio.model.SysUser;

import java.util.Optional;

public interface SysUserMapper extends BaseMapper<SysUser> {

    default Optional<SysUser> findByUsername(String username) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username)));
    }

    default Optional<SysUser> findByRefreshToken(String refreshToken) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getRefreshToken, refreshToken)));
    }

    default boolean existsByUsername(String username) {
        return selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username)) > 0;
    }

    /** 登录失败次数原子自增（避免并发失败时读-改-写丢计数） */
    default void incrementFailedAttempts(Long id) {
        update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, id)
                .setSql("failed_login_attempts = COALESCE(failed_login_attempts, 0) + 1"));
    }
}
